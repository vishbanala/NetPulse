#ifndef NETPULSE_DB_CHECKS_H
#define NETPULSE_DB_CHECKS_H

#include "monitor.h"

#include <stddef.h>

typedef struct NpDb NpDb;

/*
 * Connect using:
 * - NETPULSE_PG_CONNINFO, or
 * - NETPULSE_DB_URL (JDBC) + NETPULSE_DB_USER + NETPULSE_DB_PASSWORD
 */
NpDb *np_db_connect(char *err, size_t err_len);
void np_db_close(NpDb *db);

/*
 * Idempotent insert via netpulse_record_monitoring_check (Phase 11).
 * Also opens/resolves incidents transactionally.
 *
 * scheduled_at_iso may be NULL/empty → DB uses NOW().
 * Prefer the job's scheduledAt so out-of-order SQS deliveries order correctly.
 *
 * Returns:
 *   1 = inserted (and incident state updated if needed)
 *   0 = duplicate job_id (already recorded) — still success for delete-message
 *  -1 = failure (do NOT delete SQS message)
 */
int np_db_insert_check(NpDb *db,
                       const char *job_id,
                       const char *monitor_id,
                       const NpMonitorResult *result,
                       const char *scheduled_at_iso,
                       char *err,
                       size_t err_len);

#endif
