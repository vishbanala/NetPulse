#include "sqs_client.h"
#include "aws_sigv4.h"

#include <curl/curl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct NpSqsClient {
    char queue_url[1024];
    char region[64];
    char host[256];
    NpAwsCredentials creds;
};

struct MemBuf {
    char *data;
    size_t len;
};

static void set_err(char *err, size_t err_len, const char *msg)
{
    if (err && err_len) {
        snprintf(err, err_len, "%s", msg);
    }
}

static size_t write_cb(char *ptr, size_t size, size_t nmemb, void *userdata)
{
    struct MemBuf *b = (struct MemBuf *)userdata;
    size_t n = size * nmemb;
    char *p = (char *)realloc(b->data, b->len + n + 1);
    if (!p) {
        return 0;
    }
    b->data = p;
    memcpy(b->data + b->len, ptr, n);
    b->len += n;
    b->data[b->len] = '\0';
    return n;
}

static int extract_host(const char *queue_url, char *host, size_t host_len)
{
    const char *p = strstr(queue_url, "://");
    const char *slash;
    size_t n;
    if (!p) {
        return -1;
    }
    p += 3;
    slash = strchr(p, '/');
    n = slash ? (size_t)(slash - p) : strlen(p);
    if (n + 1 > host_len || n == 0) {
        return -1;
    }
    memcpy(host, p, n);
    host[n] = '\0';
    return 0;
}

static int json_unescape_copy(const char *src, size_t len, char *out, size_t out_len)
{
    size_t i = 0;
    size_t o = 0;
    while (i < len && o + 1 < out_len) {
        if (src[i] == '\\' && i + 1 < len) {
            char c = src[i + 1];
            if (c == 'n') {
                out[o++] = '\n';
                i += 2;
                continue;
            }
            if (c == '"' || c == '\\' || c == '/') {
                out[o++] = c;
                i += 2;
                continue;
            }
        }
        out[o++] = src[i++];
    }
    out[o] = '\0';
    return 0;
}

static int extract_json_string(const char *json, const char *key, char *out, size_t out_len)
{
    char pattern[128];
    const char *p;
    const char *start;
    size_t len = 0;
    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    p = strstr(json, pattern);
    if (!p) {
        return -1;
    }
    p = strchr(p + strlen(pattern), '"');
    if (!p) {
        return -1;
    }
    start = ++p;
    while (*p && !(*p == '"' && p[-1] != '\\')) {
        p++;
        len++;
        if (len > 100000) {
            return -1;
        }
    }
    return json_unescape_copy(start, len, out, out_len);
}

static int sqs_post(NpSqsClient *client, const char *amz_target, const char *payload,
                    char **response_out, long *http_out, char *err, size_t err_len)
{
    CURL *curl;
    struct curl_slist *headers = NULL;
    struct MemBuf buf = {0};
    char authorization[2048];
    char amz_date[32];
    char payload_hash[65];
    char header_buf[8192];
    char endpoint[320];
    CURLcode rc;
    long http = 0;
    size_t payload_len;

    if (!payload) {
        payload = "";
    }
    payload_len = strlen(payload);

    if (np_aws_sigv4_sign(&client->creds, client->region, "sqs", "POST", client->host, "/",
                          payload, amz_target, authorization, sizeof(authorization),
                          amz_date, sizeof(amz_date), payload_hash, sizeof(payload_hash)) != 0) {
        set_err(err, err_len, "SigV4 signing failed");
        return -1;
    }

    curl = curl_easy_init();
    if (!curl) {
        set_err(err, err_len, "curl_easy_init failed");
        return -1;
    }

    /*
     * Headers must match the signed set used by botocore for SQS JSON:
     * content-type, host (via URL), x-amz-date, [x-amz-security-token],
     * x-amz-target, x-amzn-query-mode.
     * Do NOT send x-amz-content-sha256 for SQS JSON.
     * Do NOT set Host manually — let libcurl derive it from the URL so it
     * cannot diverge from the signed host.
     */
    headers = curl_slist_append(headers, "Expect:");
    headers = curl_slist_append(headers, "Content-Type: application/x-amz-json-1.0");
    snprintf(header_buf, sizeof(header_buf), "X-Amz-Date: %s", amz_date);
    headers = curl_slist_append(headers, header_buf);
    snprintf(header_buf, sizeof(header_buf), "X-Amz-Target: %s", amz_target);
    headers = curl_slist_append(headers, header_buf);
    headers = curl_slist_append(headers, "x-amzn-query-mode: true");
    if (client->creds.session_token[0]) {
        snprintf(header_buf, sizeof(header_buf), "X-Amz-Security-Token: %s",
                 client->creds.session_token);
        headers = curl_slist_append(headers, header_buf);
    }
    snprintf(header_buf, sizeof(header_buf), "Authorization: %s", authorization);
    headers = curl_slist_append(headers, header_buf);

    snprintf(endpoint, sizeof(endpoint), "https://%s/", client->host);

    fprintf(stderr,
            "SQS HTTP: endpoint=%s target=%s payload_len=%zu region=%s query_mode=true\n",
            endpoint, amz_target, payload_len, client->region);

    curl_easy_setopt(curl, CURLOPT_URL, endpoint);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POST, 1L);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, payload);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, (long)payload_len);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, write_cb);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &buf);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 35L);
    curl_easy_setopt(curl, CURLOPT_HTTP_VERSION, CURL_HTTP_VERSION_1_1);

    rc = curl_easy_perform(curl);
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &http);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    if (http_out) {
        *http_out = http;
    }
    if (rc != CURLE_OK) {
        set_err(err, err_len, curl_easy_strerror(rc));
        free(buf.data);
        return -1;
    }
    if (http < 200 || http >= 300) {
        snprintf(err, err_len, "SQS HTTP %ld: %.200s", http, buf.data ? buf.data : "");
        free(buf.data);
        return -1;
    }
    *response_out = buf.data ? buf.data : strdup("{}");
    return 0;
}

NpSqsClient *np_sqs_client_create(const char *queue_url, const char *region,
                                  const NpAwsCredentials *creds, char *err, size_t err_len)
{
    NpSqsClient *c;
    if (!queue_url || !region || !creds) {
        set_err(err, err_len, "missing sqs client args");
        return NULL;
    }
    c = (NpSqsClient *)calloc(1, sizeof(*c));
    if (!c) {
        set_err(err, err_len, "oom");
        return NULL;
    }
    snprintf(c->queue_url, sizeof(c->queue_url), "%s", queue_url);
    snprintf(c->region, sizeof(c->region), "%s", region);
    c->creds = *creds;
    if (extract_host(queue_url, c->host, sizeof(c->host)) != 0) {
        set_err(err, err_len, "invalid queue URL host");
        free(c);
        return NULL;
    }
    return c;
}

void np_sqs_client_destroy(NpSqsClient *client)
{
    free(client);
}

int np_sqs_receive(NpSqsClient *client, int wait_seconds, int visibility_timeout_sec,
                   NpSqsMessage *out, char *err, size_t err_len)
{
    char payload[1536];
    char *response = NULL;
    long http = 0;

    if (!client || !out) {
        set_err(err, err_len, "null args");
        return -1;
    }
    memset(out, 0, sizeof(*out));

    snprintf(payload, sizeof(payload),
             "{\"QueueUrl\":\"%s\",\"MaxNumberOfMessages\":1,\"WaitTimeSeconds\":%d,"
             "\"VisibilityTimeout\":%d}",
             client->queue_url, wait_seconds, visibility_timeout_sec);

    if (sqs_post(client, "AmazonSQS.ReceiveMessage", payload, &response, &http, err, err_len) != 0) {
        return -1;
    }

    if (strstr(response, "\"Messages\"") == NULL ||
        strstr(response, "\"ReceiptHandle\"") == NULL) {
        free(response);
        return 0;
    }

    if (extract_json_string(response, "Body", out->body, sizeof(out->body)) != 0 ||
        extract_json_string(response, "ReceiptHandle", out->receipt_handle,
                            sizeof(out->receipt_handle)) != 0 ||
        extract_json_string(response, "MessageId", out->message_id, sizeof(out->message_id)) != 0) {
        set_err(err, err_len, "failed to parse SQS ReceiveMessage response");
        free(response);
        return -1;
    }

    free(response);
    return 1;
}

int np_sqs_delete(NpSqsClient *client, const char *receipt_handle, char *err, size_t err_len)
{
    char payload[8192];
    char *response = NULL;
    long http = 0;
    int rc;

    if (!client || !receipt_handle) {
        set_err(err, err_len, "null args");
        return -1;
    }

    {
        char escaped[4096];
        size_t i = 0;
        size_t o = 0;
        while (receipt_handle[i] && o + 2 < sizeof(escaped)) {
            char c = receipt_handle[i++];
            if (c == '"' || c == '\\') {
                escaped[o++] = '\\';
            }
            escaped[o++] = c;
        }
        escaped[o] = '\0';
        snprintf(payload, sizeof(payload),
                 "{\"QueueUrl\":\"%s\",\"ReceiptHandle\":\"%s\"}",
                 client->queue_url, escaped);
    }

    rc = sqs_post(client, "AmazonSQS.DeleteMessage", payload, &response, &http, err, err_len);
    free(response);
    return rc;
}
