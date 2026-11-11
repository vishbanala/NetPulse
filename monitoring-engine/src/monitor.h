#ifndef NETPULSE_MONITOR_H
#define NETPULSE_MONITOR_H

#include <stddef.h>

typedef enum {
    NP_STATUS_HEALTHY = 0,
    NP_STATUS_UNHEALTHY = 1,
    NP_STATUS_ERROR = 2,
    NP_STATUS_REJECTED = 3
} NpHealthStatus;

typedef struct {
    char url[2048];
    long http_status;          /* 0 if no HTTP response was received */
    double response_time_ms;
    NpHealthStatus health;
    char message[256];
} NpMonitorResult;

/*
 * Perform one HTTP(S) uptime check (HEAD, with bounded GET fallback).
 * Does not download full response bodies. timeout_ms covers connect + transfer.
 * Returns process-style exit code matching health classification.
 */
int np_monitor_check(const char *url, long timeout_ms, NpMonitorResult *out);

void np_monitor_print(const NpMonitorResult *result);

const char *np_health_name(NpHealthStatus status);

#endif /* NETPULSE_MONITOR_H */
