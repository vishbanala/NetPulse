#ifndef NETPULSE_JOB_JSON_H
#define NETPULSE_JOB_JSON_H

#include <stddef.h>

typedef struct {
    char job_id[64];
    char monitor_id[64];
    char url[2048];
    char scheduled_at[64];
} NpMonitoringJob;

/*
 * Parse Java publisher JSON:
 * {"jobId":"...","monitorId":"...","url":"...","scheduledAt":"..."}
 * Returns 0 on success.
 */
int np_job_parse(const char *json, NpMonitoringJob *out, char *err, size_t err_len);

#endif
