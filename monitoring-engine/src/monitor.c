#include "monitor.h"
#include "url_guard.h"

#include <curl/curl.h>
#include <stdio.h>
#include <string.h>

/*
 * Uptime checks must not download entire bodies. CURLOPT_MAXFILESIZE_LARGE is
 * unsuitable alone: libcurl aborts on Content-Length (CURLE_FILESIZE_EXCEEDED)
 * even when the write callback discards bytes — which marked Google DOWN.
 *
 * Strategy:
 *  1) Prefer HEAD (no body).
 *  2) If the server rejects HEAD, fall back to GET with a discard callback that
 *     stops after NP_BODY_READ_CAP bytes (intentional short read).
 *  3) Keep timeouts, no-follow redirects, and protocol restrictions.
 */

/* Soft cap on GET body bytes actually read (then abort transfer). */
#define NP_BODY_READ_CAP ((size_t)65536)

struct NpBodyCap {
    size_t received;
    int truncated; /* set when we intentionally stop reading */
};

static size_t discard_capped(char *ptr, size_t size, size_t nmemb, void *userdata)
{
    struct NpBodyCap *cap = (struct NpBodyCap *)userdata;
    size_t n = size * nmemb;
    size_t room;

    (void)ptr;
    if (cap == NULL) {
        return n;
    }
    if (cap->received >= NP_BODY_READ_CAP) {
        cap->truncated = 1;
        return 0; /* abort further download */
    }
    room = NP_BODY_READ_CAP - cap->received;
    if (n > room) {
        /* Accept only what fits the cap, then next invoke returns 0. */
        cap->received = NP_BODY_READ_CAP;
        cap->truncated = 1;
        return room;
    }
    cap->received += n;
    return n;
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

static void apply_common_opts(CURL *curl, const char *url, long timeout_ms)
{
    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L); /* no redirects */
    curl_easy_setopt(curl, CURLOPT_PROTOCOLS_STR, "http,https");
    curl_easy_setopt(curl, CURLOPT_REDIR_PROTOCOLS_STR, "");
    curl_easy_setopt(curl, CURLOPT_USERAGENT, "NetPulseMonitor/0.1");
    curl_easy_setopt(curl, CURLOPT_TIMEOUT_MS, timeout_ms);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT_MS, timeout_ms);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
#if LIBCURL_VERSION_NUM >= 0x074700
    curl_easy_setopt(curl, CURLOPT_DNS_CACHE_TIMEOUT, 0L);
#endif
}

static int head_rejected_by_server(long http_status)
{
    return http_status == 405 || /* Method Not Allowed */
           http_status == 501 || /* Not Implemented */
           http_status == 403;   /* some CDNs disallow HEAD */
}

static void classify_http_status(NpMonitorResult *out, long http_status, const char *via)
{
    out->http_status = http_status;
    if (http_status >= 200 && http_status < 300) {
        out->health = NP_STATUS_HEALTHY;
        snprintf(out->message, sizeof(out->message), "HTTP success (%s)", via);
    } else {
        out->health = NP_STATUS_UNHEALTHY;
        snprintf(out->message, sizeof(out->message),
                 "Non-success HTTP status (%s)", via);
    }
}

static void classify_curl_error(NpMonitorResult *out, CURLcode rc)
{
    if (rc == CURLE_OPERATION_TIMEDOUT) {
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
}

static int perform_head(CURL *curl, const char *url, long timeout_ms,
                        NpMonitorResult *out, double *elapsed_s, int *try_get)
{
    CURLcode rc;
    long http_status = 0;

    *try_get = 0;
    apply_common_opts(curl, url, timeout_ms);
    curl_easy_setopt(curl, CURLOPT_NOBODY, 1L);
    curl_easy_setopt(curl, CURLOPT_HTTPGET, 0L);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, NULL);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, NULL);

    rc = curl_easy_perform(curl);
    curl_easy_getinfo(curl, CURLINFO_TOTAL_TIME, elapsed_s);

    if (rc != CURLE_OK) {
        classify_curl_error(out, rc);
        return (int)out->health;
    }

    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &http_status);
    if (head_rejected_by_server(http_status)) {
        *try_get = 1;
        return 0;
    }

    classify_http_status(out, http_status, "HEAD");
    return (int)out->health;
}

static int perform_get_bounded(CURL *curl, const char *url, long timeout_ms,
                               NpMonitorResult *out, double *elapsed_s)
{
    CURLcode rc;
    long http_status = 0;
    struct NpBodyCap cap;

    memset(&cap, 0, sizeof(cap));
    apply_common_opts(curl, url, timeout_ms);
    curl_easy_setopt(curl, CURLOPT_NOBODY, 0L);
    curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, discard_capped);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &cap);
    /*
     * Do not set CURLOPT_MAXFILESIZE_LARGE: it fails on Content-Length before
     * any bytes are discarded. Cap is enforced in discard_capped instead.
     */

    rc = curl_easy_perform(curl);
    curl_easy_getinfo(curl, CURLINFO_TOTAL_TIME, elapsed_s);
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &http_status);

    if (rc == CURLE_OK) {
        classify_http_status(out, http_status, "GET");
        return (int)out->health;
    }

    /* Intentional short read after headers: availability already known. */
    if (rc == CURLE_WRITE_ERROR && cap.truncated && http_status > 0) {
        classify_http_status(out, http_status, "GET truncated");
        return (int)out->health;
    }

    classify_curl_error(out, rc);
    return (int)out->health;
}

int np_monitor_check(const char *url, long timeout_ms, NpMonitorResult *out)
{
    CURL *curl = NULL;
    char guard_err[256];
    double elapsed = 0.0;
    int try_get = 0;
    int code;

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

    code = perform_head(curl, url, timeout_ms, out, &elapsed, &try_get);
    out->response_time_ms = elapsed * 1000.0;

    if (try_get) {
        /* Reset easy handle options between HEAD and GET. */
        curl_easy_reset(curl);
        code = perform_get_bounded(curl, url, timeout_ms, out, &elapsed);
        out->response_time_ms = elapsed * 1000.0;
    }

    curl_easy_cleanup(curl);
    return code;
}
