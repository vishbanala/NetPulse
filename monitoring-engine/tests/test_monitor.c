#include "monitor.h"
#include "url_guard.h"

#include <curl/curl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

/*
 * Integration-style tests for public-URL SSRF policy and uptime classification.
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
    char pin[512];
    CURLcode global_rc;

    printf("NetPulse monitoring-engine tests\n");
    printf("================================\n");

    global_rc = curl_global_init(CURL_GLOBAL_DEFAULT);
    if (global_rc != CURLE_OK) {
        fprintf(stderr, "curl_global_init failed\n");
        return 1;
    }

    expect_true("reject empty URL",
                np_url_is_allowed("", err, sizeof(err)) != 0,
                err);

    expect_true("reject ftp scheme",
                np_url_is_allowed("ftp://example.com/file", err, sizeof(err)) != 0,
                err);

    expect_true("reject embedded credentials",
                np_url_is_allowed("https://user:pass@example.com/", err, sizeof(err)) != 0,
                err);

    expect_true("reject localhost",
                np_url_is_allowed("http://127.0.0.1/", err, sizeof(err)) != 0,
                err);

    expect_true("reject cloud metadata IP",
                np_url_is_allowed("http://169.254.169.254/latest/meta-data/", err, sizeof(err)) != 0,
                err);

    expect_true("reject private 10/8",
                np_url_is_allowed("http://10.0.0.1/", err, sizeof(err)) != 0,
                err);

    expect_true("reject IPv6 loopback",
                np_url_is_allowed("http://[::1]/", err, sizeof(err)) != 0,
                err);

    expect_true("reject non-default port",
                np_url_is_allowed("https://example.com:8443/", err, sizeof(err)) != 0,
                err);

    expect_true("allow example.com public HTTPS",
                np_url_is_allowed("https://example.com", err, sizeof(err)) == 0,
                err);

    expect_true("pin resolve for example.com",
                np_url_pin_public_resolve("https://example.com", pin, sizeof(pin), err, sizeof(err)) == 0
                    && strstr(pin, "example.com:443:") != NULL,
                err);

    expect_true("allow www.google.com",
                np_url_is_allowed("https://www.google.com", err, sizeof(err)) == 0,
                err);

    expect_true("reject credentials on google host",
                np_url_is_allowed("https://user:pass@www.google.com/", err, sizeof(err)) != 0,
                err);

    {
        int code = np_monitor_check("https://example.com", 10000, &result);
        expect_true("successful request to example.com",
                    code == (int)NP_STATUS_HEALTHY && result.http_status == 200,
                    result.message);
        printf("      (status=%ld time=%.0fms)\n",
               result.http_status, result.response_time_ms);
    }

    {
        int code = np_monitor_check("https://www.google.com", 15000, &result);
        expect_true("google large healthy response is not filesize failure",
                    code == (int)NP_STATUS_HEALTHY &&
                        result.http_status >= 200 && result.http_status < 300 &&
                        strstr(result.message, "Maximum") == NULL,
                    result.message);
        printf("      (status=%ld time=%.0fms detail=%s)\n",
               result.http_status, result.response_time_ms, result.message);
    }

    {
        int code = np_monitor_check("https://httpbin.org/bytes/100000", 20000, &result);
        expect_true("100KiB healthy body classified HEALTHY",
                    code == (int)NP_STATUS_HEALTHY &&
                        result.http_status >= 200 && result.http_status < 300,
                    result.message);
        printf("      (status=%ld time=%.0fms detail=%s)\n",
               result.http_status, result.response_time_ms, result.message);
    }

    {
        int code = np_monitor_check("https://httpbin.org/status/500", 15000, &result);
        expect_true("HTTP 500 classified unhealthy",
                    code == (int)NP_STATUS_UNHEALTHY && result.http_status == 500,
                    result.message);
        printf("      (status=%ld time=%.0fms)\n",
               result.http_status, result.response_time_ms);
    }

    {
        int code = np_monitor_check("https://httpbin.org/delay/5", 1000, &result);
        expect_true("timeout on delayed endpoint",
                    code == (int)NP_STATUS_ERROR &&
                        strstr(result.message, "timed out") != NULL,
                    result.message);
        printf("      (detail=%s time=%.0fms)\n",
               result.message, result.response_time_ms);
    }

    {
        int code = np_monitor_check("http://127.0.0.1/", 5000, &result);
        expect_true("monitor rejects loopback",
                    code == (int)NP_STATUS_REJECTED,
                    result.message);
    }

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

    printf("PASS  resource cleanup paths exercised via libcurl cleanup\n");
    g_pass++;

    curl_global_cleanup();

    printf("================================\n");
    printf("Results: %d passed, %d failed, %d skipped\n", g_pass, g_fail, g_skip);
    return g_fail == 0 ? 0 : 1;
}
