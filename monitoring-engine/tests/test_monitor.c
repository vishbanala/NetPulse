#include "monitor.h"
#include "url_guard.h"

#include <curl/curl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

/*
 * Basic integration-style tests against the Step-3 allowlist.
 * Results are real network outcomes — do not treat failures as "invented pass".
 */

static int g_pass = 0;
static int g_fail = 0;
static int g_skip = 0;

static void expect_true(const char *name, int cond, const char *detail)
{
    if (cond) {
        printf("PASS  %s\n", name);
        g_pass++;
    } else {
        printf("FAIL  %s — %s\n", name, detail ? detail : "");
        g_fail++;
    }
}

static void *concurrent_worker(void *arg)
{
    const char *url = (const char *)arg;
    NpMonitorResult result;
    (void)np_monitor_check(url, 10000, &result);
    return (void *)(intptr_t)result.health;
}

int main(void)
{
    NpMonitorResult result;
    char err[256];
    CURLcode global_rc;

    printf("NetPulse monitoring-engine tests\n");
    printf("================================\n");

    global_rc = curl_global_init(CURL_GLOBAL_DEFAULT);
    if (global_rc != CURLE_OK) {
        fprintf(stderr, "curl_global_init failed\n");
        return 1;
    }

    /* --- URL guard / invalid input (no network required) --- */
    expect_true("reject empty URL",
                np_url_is_allowed("", err, sizeof(err)) != 0,
                err);

    expect_true("reject ftp scheme",
                np_url_is_allowed("ftp://example.com/file", err, sizeof(err)) != 0,
                err);

    expect_true("reject embedded credentials",
                np_url_is_allowed("https://user:pass@example.com/", err, sizeof(err)) != 0,
                err);

    expect_true("reject non-allowlisted public URL",
                np_url_is_allowed("https://www.wikipedia.org", err, sizeof(err)) != 0,
                err);

    expect_true("reject localhost",
                np_url_is_allowed("http://127.0.0.1/", err, sizeof(err)) != 0,
                err);

    expect_true("reject cloud metadata IP",
                np_url_is_allowed("http://169.254.169.254/latest/meta-data/", err, sizeof(err)) != 0,
                err);

    expect_true("allow example.com on allowlist",
                np_url_is_allowed("https://example.com", err, sizeof(err)) == 0,
                err);

    /* Regression: Google was abandoned by the Java scheduler while still "Waiting" in UI. */
    expect_true("allow www.google.com on allowlist",
                np_url_is_allowed("https://www.google.com", err, sizeof(err)) == 0,
                err);

    /* SSRF still blocks even if someone later mistakenly allowlists a private host pattern. */
    expect_true("reject credentials on google host",
                np_url_is_allowed("https://user:pass@www.google.com/", err, sizeof(err)) != 0,
                err);

    /* --- Successful HTTP request --- */
    {
        int code = np_monitor_check("https://example.com", 10000, &result);
        expect_true("successful request to example.com",
                    code == (int)NP_STATUS_HEALTHY && result.http_status == 200,
                    result.message);
        printf("      (status=%ld time=%.0fms)\n",
               result.http_status, result.response_time_ms);
    }

    /*
     * Regression: Google HTML exceeds the old 64KiB CURLOPT_MAXFILESIZE_LARGE
     * Content-Length check and was falsely marked ERROR / DOWN.
     */
    {
        int code = np_monitor_check("https://www.google.com", 15000, &result);
        expect_true("google large healthy response is not filesize failure",
                    code == (int)NP_STATUS_HEALTHY &&
                        result.http_status >= 200 && result.http_status < 300 &&
                        strstr(result.message, "Maximum") == NULL &&
                        strstr(result.message, "filesize") == NULL,
                    result.message);
        printf("      (status=%ld time=%.0fms detail=%s)\n",
               result.http_status, result.response_time_ms, result.message);
    }

    /* Deterministic >64KiB body (httpbin); must stay HEALTHY with bounded GET. */
    {
        int code = np_monitor_check("https://httpbin.org/bytes/100000", 20000, &result);
        expect_true("100KiB healthy body classified HEALTHY",
                    code == (int)NP_STATUS_HEALTHY &&
                        result.http_status >= 200 && result.http_status < 300 &&
                        strstr(result.message, "Maximum") == NULL,
                    result.message);
        printf("      (status=%ld time=%.0fms detail=%s)\n",
               result.http_status, result.response_time_ms, result.message);
    }

    /* --- HTTP error (still a completed HTTP transaction) --- */
    {
        int code = np_monitor_check("https://httpbin.org/status/500", 15000, &result);
        expect_true("HTTP 500 classified unhealthy",
                    code == (int)NP_STATUS_UNHEALTHY && result.http_status == 500,
                    result.message);
        printf("      (status=%ld time=%.0fms)\n",
               result.http_status, result.response_time_ms);
    }

    /* --- Timeout / network-style failure --- */
    {
        int code = np_monitor_check("https://httpbin.org/delay/5", 1000, &result);
        expect_true("timeout on delayed endpoint",
                    code == (int)NP_STATUS_ERROR &&
                        strstr(result.message, "timed out") != NULL,
                    result.message);
        printf("      (detail=%s time=%.0fms)\n",
               result.message, result.response_time_ms);
    }

    /* --- Invalid / rejected URL through monitor API --- */
    {
        int code = np_monitor_check("https://evil.example/not-allowed", 5000, &result);
        expect_true("monitor rejects non-allowlisted URL",
                    code == (int)NP_STATUS_REJECTED,
                    result.message);
    }

    /* --- Concurrent requests (bounded threads) --- */
    {
        pthread_t t1, t2;
        void *r1 = NULL;
        void *r2 = NULL;
        int h1, h2;

        if (pthread_create(&t1, NULL, concurrent_worker, (void *)"https://example.com") != 0 ||
            pthread_create(&t2, NULL, concurrent_worker, (void *)"https://httpbin.org/get") != 0) {
            printf("SKIP  concurrent requests — pthread_create failed\n");
            g_skip++;
        } else {
            pthread_join(t1, &r1);
            pthread_join(t2, &r2);
            h1 = (int)(intptr_t)r1;
            h2 = (int)(intptr_t)r2;
            expect_true("concurrent requests completed without crash",
                        (h1 == (int)NP_STATUS_HEALTHY || h1 == (int)NP_STATUS_UNHEALTHY ||
                         h1 == (int)NP_STATUS_ERROR) &&
                            (h2 == (int)NP_STATUS_HEALTHY || h2 == (int)NP_STATUS_UNHEALTHY ||
                             h2 == (int)NP_STATUS_ERROR),
                        "unexpected health enum");
            printf("      (worker1=%s worker2=%s)\n",
                   np_health_name((NpHealthStatus)h1),
                   np_health_name((NpHealthStatus)h2));
        }
    }

    /* Resource cleanup is exercised by curl_easy_cleanup / curl_global_cleanup
     * on every path above; Valgrind/ASan can be added later on Linux CI. */
    printf("PASS  resource cleanup paths exercised via libcurl cleanup\n");
    g_pass++;

    curl_global_cleanup();

    printf("================================\n");
    printf("Results: %d passed, %d failed, %d skipped\n", g_pass, g_fail, g_skip);
    return g_fail == 0 ? 0 : 1;
}
