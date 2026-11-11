#include "monitor.h"
#include "url_guard.h"

#include <curl/curl.h>
#include <stdio.h>
#include <string.h>

/* Discard body; enforce a small download cap via CURLOPT_MAXFILESIZE_LARGE. */
static size_t discard_write(char *ptr, size_t size, size_t nmemb, void *userdata)
{
    (void)ptr;
    (void)userdata;
    return size * nmemb;
}

const char *np_health_name(NpHealthStatus status)
{
    switch (status) {
    case NP_STATUS_HEALTHY:
        return "HEALTHY";
    case NP_STATUS_UNHEALTHY:
        return "UNHEALTHY";
    case NP_STATUS_ERROR:
        return "ERROR";
    case NP_STATUS_REJECTED:
        return "REJECTED";
    default:
        return "UNKNOWN";
    }
}

void np_monitor_print(const NpMonitorResult *result)
{
    if (result == NULL) {
        return;
    }

    printf("Website: %s\n", result->url);
    if (result->http_status > 0) {
        printf("HTTP Status: %ld\n", result->http_status);
    } else {
        printf("HTTP Status: (none)\n");
    }
    printf("Response Time: %.0f ms\n", result->response_time_ms);
    printf("Status: %s\n", np_health_name(result->health));
    if (result->message[0] != '\0') {
        printf("Detail: %s\n", result->message);
    }
}

int np_monitor_check(const char *url, long timeout_ms, NpMonitorResult *out)
{
    CURL *curl = NULL;
    CURLcode rc;
    long http_status = 0;
    double total_time = 0.0;
    char guard_err[256];

    if (out == NULL) {
        return (int)NP_STATUS_ERROR;
    }

    memset(out, 0, sizeof(*out));
    if (url != NULL) {
        snprintf(out->url, sizeof(out->url), "%s", url);
    }

    if (url == NULL || url[0] == '\0') {
        out->health = NP_STATUS_REJECTED;
        snprintf(out->message, sizeof(out->message), "Missing URL");
        return (int)out->health;
    }

    if (timeout_ms <= 0) {
        timeout_ms = 5000;
    }

    guard_err[0] = '\0';
    if (np_url_is_allowed(url, guard_err, sizeof(guard_err)) != 0) {
        out->health = NP_STATUS_REJECTED;
        snprintf(out->message, sizeof(out->message), "%s",
                 guard_err[0] ? guard_err : "URL rejected");
        return (int)out->health;
    }

    curl = curl_easy_init();
    if (curl == NULL) {
        out->health = NP_STATUS_ERROR;
        snprintf(out->message, sizeof(out->message), "curl_easy_init failed");
        return (int)out->health;
    }

    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L);
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L); /* no redirects */
    curl_easy_setopt(curl, CURLOPT_PROTOCOLS_STR, "http,https");
    curl_easy_setopt(curl, CURLOPT_REDIR_PROTOCOLS_STR, "");
    curl_easy_setopt(curl, CURLOPT_USERAGENT, "NetPulseMonitor/0.1");
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, discard_write);
    curl_easy_setopt(curl, CURLOPT_MAXFILESIZE_LARGE, (curl_off_t)65536);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT_MS, timeout_ms);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT_MS, timeout_ms);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
#if LIBCURL_VERSION_NUM >= 0x074700
    /* Prefer fresh DNS where supported (mitigates some rebinding windows). */
    curl_easy_setopt(curl, CURLOPT_DNS_CACHE_TIMEOUT, 0L);
#endif

    rc = curl_easy_perform(curl);

    curl_easy_getinfo(curl, CURLINFO_TOTAL_TIME, &total_time);
    out->response_time_ms = total_time * 1000.0;

    if (rc == CURLE_OK) {
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &http_status);
        out->http_status = http_status;

        if (http_status >= 200 && http_status < 300) {
            out->health = NP_STATUS_HEALTHY;
            snprintf(out->message, sizeof(out->message), "HTTP success");
        } else {
            out->health = NP_STATUS_UNHEALTHY;
            snprintf(out->message, sizeof(out->message),
                     "Non-success HTTP status");
        }
    } else if (rc == CURLE_OPERATION_TIMEDOUT) {
        out->health = NP_STATUS_ERROR;
        snprintf(out->message, sizeof(out->message), "Request timed out");
    } else if (rc == CURLE_COULDNT_RESOLVE_HOST) {
        out->health = NP_STATUS_ERROR;
        snprintf(out->message, sizeof(out->message), "DNS resolution failed");
    } else if (rc == CURLE_COULDNT_CONNECT) {
        out->health = NP_STATUS_ERROR;
        snprintf(out->message, sizeof(out->message), "Connection failed");
    } else {
        out->health = NP_STATUS_ERROR;
        snprintf(out->message, sizeof(out->message), "libcurl: %s",
                 curl_easy_strerror(rc));
    }

    curl_easy_cleanup(curl);
    return (int)out->health;
}
