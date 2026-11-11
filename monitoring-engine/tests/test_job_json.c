#include "job_json.h"

#include <stdio.h>
#include <string.h>

static int g_pass = 0;
static int g_fail = 0;

static void expect(const char *name, int cond)
{
    if (cond) {
        printf("PASS  %s\n", name);
        g_pass++;
    } else {
        printf("FAIL  %s\n", name);
        g_fail++;
    }
}

int main(void)
{
    NpMonitoringJob job;
    char err[128];
    const char *ok_json =
        "{\"jobId\":\"78261c2e-4f9a-4351-bbce-1314070bfb71\","
        "\"monitorId\":\"6df75d9e-8c50-46e5-b8e6-f912c1bf57d6\","
        "\"url\":\"https://example.com\","
        "\"scheduledAt\":\"2026-11-08T03:20:00.123Z\"}";

    expect("parse valid Java payload",
           np_job_parse(ok_json, &job, err, sizeof(err)) == 0 &&
               strcmp(job.url, "https://example.com") == 0);

    expect("reject missing fields",
           np_job_parse("{\"jobId\":\"78261c2e-4f9a-4351-bbce-1314070bfb71\"}", &job, err, sizeof(err)) != 0);

    expect("reject bad uuid",
           np_job_parse(
               "{\"jobId\":\"nope\",\"monitorId\":\"6df75d9e-8c50-46e5-b8e6-f912c1bf57d6\","
               "\"url\":\"https://example.com\",\"scheduledAt\":\"2026-11-08T03:20:00Z\"}",
               &job, err, sizeof(err)) != 0);

    expect("reject non-http url",
           np_job_parse(
               "{\"jobId\":\"78261c2e-4f9a-4351-bbce-1314070bfb71\","
               "\"monitorId\":\"6df75d9e-8c50-46e5-b8e6-f912c1bf57d6\","
               "\"url\":\"ftp://example.com\",\"scheduledAt\":\"2026-11-08T03:20:00Z\"}",
               &job, err, sizeof(err)) != 0);

    printf("Results: %d passed, %d failed\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
