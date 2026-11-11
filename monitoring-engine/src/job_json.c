#include "job_json.h"

#include <ctype.h>
#include <stdio.h>
#include <string.h>

static void set_err(char *err, size_t err_len, const char *msg)
{
    if (err && err_len > 0) {
        snprintf(err, err_len, "%s", msg);
    }
}

static const char *skip_ws(const char *p)
{
    while (*p && isspace((unsigned char)*p)) {
        p++;
    }
    return p;
}

static int parse_string(const char **pp, char *out, size_t out_len)
{
    const char *p = skip_ws(*pp);
    size_t n = 0;

    if (*p != '"') {
        return -1;
    }
    p++;
    while (*p && *p != '"') {
        if (*p == '\\' && p[1] != '\0') {
            p++;
            if (n + 1 >= out_len) {
                return -1;
            }
            out[n++] = *p++;
            continue;
        }
        if (n + 1 >= out_len) {
            return -1;
        }
        out[n++] = *p++;
    }
    if (*p != '"') {
        return -1;
    }
    out[n] = '\0';
    *pp = p + 1;
    return 0;
}

static int extract_field(const char *json, const char *key, char *out, size_t out_len)
{
    char pattern[128];
    const char *p;
    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    p = strstr(json, pattern);
    if (p == NULL) {
        return -1;
    }
    p += strlen(pattern);
    p = skip_ws(p);
    if (*p != ':') {
        return -1;
    }
    p++;
    return parse_string(&p, out, out_len);
}

static int looks_like_uuid(const char *s)
{
    /* Soft check: 8-4-4-4-12 hex with dashes */
    size_t i;
    size_t len = strlen(s);
    if (len != 36) {
        return 0;
    }
    for (i = 0; i < len; i++) {
        char c = s[i];
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (c != '-') {
                return 0;
            }
        } else if (!isxdigit((unsigned char)c)) {
            return 0;
        }
    }
    return 1;
}

int np_job_parse(const char *json, NpMonitoringJob *out, char *err, size_t err_len)
{
    if (json == NULL || out == NULL) {
        set_err(err, err_len, "null input");
        return -1;
    }
    memset(out, 0, sizeof(*out));

    if (extract_field(json, "jobId", out->job_id, sizeof(out->job_id)) != 0 ||
        extract_field(json, "monitorId", out->monitor_id, sizeof(out->monitor_id)) != 0 ||
        extract_field(json, "url", out->url, sizeof(out->url)) != 0 ||
        extract_field(json, "scheduledAt", out->scheduled_at, sizeof(out->scheduled_at)) != 0) {
        set_err(err, err_len, "missing required JSON fields");
        return -1;
    }

    if (!looks_like_uuid(out->job_id) || !looks_like_uuid(out->monitor_id)) {
        set_err(err, err_len, "jobId/monitorId must be UUIDs");
        return -1;
    }
    if (!(strncmp(out->url, "http://", 7) == 0 || strncmp(out->url, "https://", 8) == 0)) {
        set_err(err, err_len, "url must be http(s)");
        return -1;
    }
    return 0;
}
