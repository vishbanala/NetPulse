#include "aws_creds.h"

#include <curl/curl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#include <pwd.h>
#endif

static void set_err(char *err, size_t err_len, const char *msg)
{
    if (err && err_len) {
        snprintf(err, err_len, "%s", msg);
    }
}

static void trim(char *s)
{
    char *e;
    while (*s == ' ' || *s == '\t' || *s == '\r' || *s == '\n') {
        memmove(s, s + 1, strlen(s));
    }
    e = s + strlen(s);
    while (e > s && (e[-1] == ' ' || e[-1] == '\t' || e[-1] == '\r' || e[-1] == '\n')) {
        *--e = '\0';
    }
}

static int load_from_env(NpAwsCredentials *out)
{
    const char *ak = getenv("AWS_ACCESS_KEY_ID");
    const char *sk = getenv("AWS_SECRET_ACCESS_KEY");
    const char *token = getenv("AWS_SESSION_TOKEN");
    if (ak == NULL || sk == NULL || ak[0] == '\0' || sk[0] == '\0') {
        return -1;
    }
    memset(out, 0, sizeof(*out));
    snprintf(out->access_key_id, sizeof(out->access_key_id), "%s", ak);
    snprintf(out->secret_access_key, sizeof(out->secret_access_key), "%s", sk);
    if (token && token[0]) {
        snprintf(out->session_token, sizeof(out->session_token), "%s", token);
    }
    /* Trim CR/LF/spaces — common with exported temporary credentials on Windows. */
    trim(out->access_key_id);
    trim(out->secret_access_key);
    trim(out->session_token);
    if (out->access_key_id[0] == '\0' || out->secret_access_key[0] == '\0') {
        return -1;
    }
    fprintf(stderr,
            "AWS creds: source=env akid_prefix=%.4s akid_len=%zu secret_len=%zu "
            "session_token_len=%zu\n",
            out->access_key_id,
            strlen(out->access_key_id),
            strlen(out->secret_access_key),
            strlen(out->session_token));
    return 0;
}

static int home_dir(char *out, size_t out_len)
{
#ifdef _WIN32
    const char *p = getenv("USERPROFILE");
    if (p == NULL || p[0] == '\0') {
        return -1;
    }
    snprintf(out, out_len, "%s", p);
    return 0;
#else
    const char *p = getenv("HOME");
    if (p && p[0]) {
        snprintf(out, out_len, "%s", p);
        return 0;
    }
    {
        struct passwd *pw = getpwuid(getuid());
        if (pw && pw->pw_dir) {
            snprintf(out, out_len, "%s", pw->pw_dir);
            return 0;
        }
    }
    return -1;
#endif
}

static int load_from_shared_file(NpAwsCredentials *out)
{
    char path[512];
    char home[400];
    const char *profile = getenv("AWS_PROFILE");
    char wanted[128];
    FILE *fp;
    char line[8192];
    int in_section = 0;
    char ak[128] = {0};
    char sk[128] = {0};
    char token[4096] = {0};

    if (home_dir(home, sizeof(home)) != 0) {
        return -1;
    }
    snprintf(path, sizeof(path), "%s/.aws/credentials", home);
    if (profile == NULL || profile[0] == '\0') {
        profile = "default";
    }
    snprintf(wanted, sizeof(wanted), "[%s]", profile);

    fp = fopen(path, "r");
    if (fp == NULL) {
        return -1;
    }

    while (fgets(line, sizeof(line), fp) != NULL) {
        trim(line);
        if (line[0] == '\0' || line[0] == '#' || line[0] == ';') {
            continue;
        }
        if (line[0] == '[') {
            in_section = (strcmp(line, wanted) == 0);
            continue;
        }
        if (!in_section) {
            continue;
        }
        if (strncmp(line, "aws_access_key_id", 17) == 0) {
            char *eq = strchr(line, '=');
            if (eq) {
                snprintf(ak, sizeof(ak), "%s", eq + 1);
                trim(ak);
            }
        } else if (strncmp(line, "aws_secret_access_key", 21) == 0) {
            char *eq = strchr(line, '=');
            if (eq) {
                snprintf(sk, sizeof(sk), "%s", eq + 1);
                trim(sk);
            }
        } else if (strncmp(line, "aws_session_token", 17) == 0) {
            char *eq = strchr(line, '=');
            if (eq) {
                snprintf(token, sizeof(token), "%s", eq + 1);
                trim(token);
            }
        }
    }
    fclose(fp);

    if (ak[0] == '\0' || sk[0] == '\0') {
        return -1;
    }
    memset(out, 0, sizeof(*out));
    snprintf(out->access_key_id, sizeof(out->access_key_id), "%s", ak);
    snprintf(out->secret_access_key, sizeof(out->secret_access_key), "%s", sk);
    if (token[0]) {
        snprintf(out->session_token, sizeof(out->session_token), "%s", token);
    }
    return 0;
}

struct CurlBuf {
    char *data;
    size_t len;
};

static size_t write_cb(char *ptr, size_t size, size_t nmemb, void *userdata)
{
    struct CurlBuf *b = (struct CurlBuf *)userdata;
    size_t n = size * nmemb;
    char *p = (char *)realloc(b->data, b->len + n + 1);
    if (p == NULL) {
        return 0;
    }
    b->data = p;
    memcpy(b->data + b->len, ptr, n);
    b->len += n;
    b->data[b->len] = '\0';
    return n;
}

static int json_string_field(const char *json, const char *key, char *out, size_t out_len)
{
    char pattern[128];
    const char *p;
    size_t n = 0;
    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    p = strstr(json, pattern);
    if (!p) {
        return -1;
    }
    p = strchr(p + strlen(pattern), '"');
    if (!p) {
        return -1;
    }
    p++;
    while (*p && *p != '"' && n + 1 < out_len) {
        if (*p == '\\' && p[1]) {
            p++;
        }
        out[n++] = *p++;
    }
    out[n] = '\0';
    return n > 0 ? 0 : -1;
}

/* ECS task role / container credentials (used later on Fargate). */
static int load_from_ecs(NpAwsCredentials *out)
{
    const char *rel = getenv("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI");
    const char *full = getenv("AWS_CONTAINER_CREDENTIALS_FULL_URI");
    char url[1024];
    CURL *curl;
    CURLcode rc;
    struct CurlBuf buf = {0};
    long http = 0;

    if (full && full[0]) {
        snprintf(url, sizeof(url), "%s", full);
    } else if (rel && rel[0]) {
        snprintf(url, sizeof(url), "http://169.254.170.2%s", rel);
    } else {
        return -1;
    }

    curl = curl_easy_init();
    if (!curl) {
        return -1;
    }
    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, write_cb);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &buf);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 2L);
    rc = curl_easy_perform(curl);
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &http);
    curl_easy_cleanup(curl);
    if (rc != CURLE_OK || http != 200 || buf.data == NULL) {
        free(buf.data);
        return -1;
    }

    memset(out, 0, sizeof(*out));
    if (json_string_field(buf.data, "AccessKeyId", out->access_key_id, sizeof(out->access_key_id)) != 0 ||
        json_string_field(buf.data, "SecretAccessKey", out->secret_access_key, sizeof(out->secret_access_key)) != 0) {
        free(buf.data);
        return -1;
    }
    (void)json_string_field(buf.data, "Token", out->session_token, sizeof(out->session_token));
    free(buf.data);
    return 0;
}

int np_aws_load_credentials(NpAwsCredentials *out, char *err, size_t err_len)
{
    if (out == NULL) {
        set_err(err, err_len, "null credentials out");
        return -1;
    }
    if (load_from_env(out) == 0) {
        return 0;
    }
    if (load_from_shared_file(out) == 0) {
        return 0;
    }
    if (load_from_ecs(out) == 0) {
        return 0;
    }
    set_err(err, err_len,
            "No AWS credentials found (env, ~/.aws/credentials, or ECS task role). "
            "For aws login profiles, export temporary credentials into the environment first.");
    return -1;
}
