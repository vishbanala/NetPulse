#include "aws_creds.h"
#include "aws_sigv4.h"
#include "db_checks.h"
#include "job_json.h"
#include "monitor.h"
#include "sqs_client.h"
#include "url_guard.h"

#include <curl/curl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef _WIN32
#include <windows.h>
static void sleep_seconds(unsigned int seconds)
{
    Sleep(seconds * 1000u);
}
#else
#include <unistd.h>
static void sleep_seconds(unsigned int seconds)
{
    sleep(seconds);
}
#endif

static volatile sig_atomic_t g_stop = 0;

static void on_signal(int sig)
{
    (void)sig;
    g_stop = 1;
}

static long env_long(const char *name, long fallback)
{
    const char *v = getenv(name);
    if (!v || !v[0]) {
        return fallback;
    }
    return strtol(v, NULL, 10);
}

static void usage(const char *argv0)
{
    fprintf(stderr,
            "Usage: %s\n"
            "\n"
            "Environment:\n"
            "  NETPULSE_SQS_QUEUE_URL   required\n"
            "  AWS_REGION              required (e.g. us-east-2)\n"
            "  AWS_PROFILE             optional (local shared credentials)\n"
            "  AWS_ACCESS_KEY_ID/...   optional (or export-credentials from aws login)\n"
            "  NETPULSE_DB_URL/USER/PASSWORD or NETPULSE_PG_CONNINFO\n"
            "  NETPULSE_HTTP_TIMEOUT_MS   default 10000\n"
            "  NETPULSE_SQS_WAIT_SECONDS  default 20 (long poll)\n"
            "  NETPULSE_SQS_VISIBILITY_SECONDS default 90\n"
            "\n"
            "Bounded run (GitHub Actions / one-shot). 0 = unlimited (daemon mode):\n"
            "  NETPULSE_WORKER_MAX_MESSAGES         stop after N successful jobs\n"
            "  NETPULSE_WORKER_MAX_EMPTY_RECEIVES   stop after N consecutive empty polls\n"
            "  NETPULSE_WORKER_MAX_RUNTIME_SECONDS  stop after wall-clock seconds\n"
            "\n"
            "Localhost-only tooling. Do not expose without authn/authz.\n",
            argv0);
}

int main(int argc, char **argv)
{
    const char *queue_url;
    const char *region;
    char err[512];
    NpAwsCredentials creds;
    NpSqsClient *sqs = NULL;
    NpDb *db = NULL;
    long http_timeout_ms;
    int wait_seconds;
    int visibility_seconds;
    long max_messages;
    long max_empty_receives;
    long max_runtime_seconds;
    int processed = 0;
    int empty_streak = 0;
    time_t started_at;
    unsigned int auth_backoff_sec = 1;

    if (argc > 1 && (strcmp(argv[1], "-h") == 0 || strcmp(argv[1], "--help") == 0)) {
        usage(argv[0]);
        return 0;
    }

    queue_url = getenv("NETPULSE_SQS_QUEUE_URL");
    region = getenv("AWS_REGION");
    if (!queue_url || !queue_url[0] || !region || !region[0]) {
        fprintf(stderr, "NETPULSE_SQS_QUEUE_URL and AWS_REGION are required\n");
        usage(argv[0]);
        return 2;
    }

    http_timeout_ms = env_long("NETPULSE_HTTP_TIMEOUT_MS", 10000);
    wait_seconds = (int)env_long("NETPULSE_SQS_WAIT_SECONDS", 20);
    visibility_seconds = (int)env_long("NETPULSE_SQS_VISIBILITY_SECONDS", 90);
    max_messages = env_long("NETPULSE_WORKER_MAX_MESSAGES", 0);
    max_empty_receives = env_long("NETPULSE_WORKER_MAX_EMPTY_RECEIVES", 0);
    max_runtime_seconds = env_long("NETPULSE_WORKER_MAX_RUNTIME_SECONDS", 0);
    started_at = time(NULL);

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);

    if (np_aws_sigv4_self_test(err, sizeof(err)) != 0) {
        fprintf(stderr, "SigV4 self-test failed: %s\n", err);
        return 1;
    }

    if (curl_global_init(CURL_GLOBAL_DEFAULT) != 0) {
        fprintf(stderr, "curl_global_init failed\n");
        return 1;
    }

    if (np_aws_load_credentials(&creds, err, sizeof(err)) != 0) {
        fprintf(stderr, "AWS credentials: %s\n", err);
        curl_global_cleanup();
        return 2;
    }

    sqs = np_sqs_client_create(queue_url, region, &creds, err, sizeof(err));
    if (!sqs) {
        fprintf(stderr, "SQS client: %s\n", err);
        curl_global_cleanup();
        return 2;
    }

    db = np_db_connect(err, sizeof(err));
    if (!db) {
        fprintf(stderr, "Database: %s\n", err);
        np_sqs_client_destroy(sqs);
        curl_global_cleanup();
        return 2;
    }

    fprintf(stdout,
            "netpulse-worker started region=%s wait=%ds visibility=%ds "
            "max_messages=%ld max_empty=%ld max_runtime_s=%ld\n",
            region, wait_seconds, visibility_seconds,
            max_messages, max_empty_receives, max_runtime_seconds);
    fflush(stdout);

    while (!g_stop) {
        NpSqsMessage msg;
        NpMonitoringJob job;
        NpMonitorResult result;
        int rr;
        int insert_rc;
        char guard_err[256];

        if (max_runtime_seconds > 0
                && (long)(time(NULL) - started_at) >= max_runtime_seconds) {
            fprintf(stdout, "Max runtime reached (%ld s); stopping after %d jobs\n",
                    max_runtime_seconds, processed);
            fflush(stdout);
            break;
        }

        rr = np_sqs_receive(sqs, wait_seconds, visibility_seconds, &msg, err, sizeof(err));
        if (g_stop) {
            break;
        }
        if (rr < 0) {
            int is_sig = (strstr(err, "403") != NULL) ||
                         (strstr(err, "InvalidSignature") != NULL) ||
                         (strstr(err, "SignatureDoesNotMatch") != NULL);
            fprintf(stderr, "SQS receive error: %s\n", err);
            if (is_sig) {
                fprintf(stderr,
                        "Auth/signature failure — backing off %u seconds to avoid spamming AWS\n",
                        auth_backoff_sec);
                sleep_seconds(auth_backoff_sec);
                if (auth_backoff_sec < 60u) {
                    auth_backoff_sec *= 2u;
                    if (auth_backoff_sec > 60u) {
                        auth_backoff_sec = 60u;
                    }
                }
            } else {
                auth_backoff_sec = 1;
                sleep_seconds(1);
            }
            continue;
        }
        auth_backoff_sec = 1;
        if (rr == 0) {
            empty_streak++;
            if (max_empty_receives > 0 && empty_streak >= max_empty_receives) {
                fprintf(stdout,
                        "Idle exit after %d empty receive(s); processed=%d\n",
                        empty_streak, processed);
                fflush(stdout);
                break;
            }
            continue; /* empty long-poll */
        }
        empty_streak = 0;

        fprintf(stdout, "Received SQS messageId=%s\n", msg.message_id);
        fflush(stdout);

        if (np_job_parse(msg.body, &job, err, sizeof(err)) != 0) {
            fprintf(stderr, "Invalid job JSON (leaving message for retry/DLQ policy): %s\n", err);
            /* Do not delete — poison handling/DLQ is a later step. */
            continue;
        }

        if (np_url_is_allowed(job.url, guard_err, sizeof(guard_err)) != 0) {
            fprintf(stderr, "Rejected URL for jobId=%s: %s\n", job.job_id, guard_err);
            /* Persist a failed check then delete so invalid URL policy drift does not loop forever. */
            memset(&result, 0, sizeof(result));
            snprintf(result.url, sizeof(result.url), "%s", job.url);
            result.health = NP_STATUS_REJECTED;
            snprintf(result.message, sizeof(result.message), "%s", guard_err);
            insert_rc = np_db_insert_check(db, job.job_id, job.monitor_id, &result,
                                           job.scheduled_at, err, sizeof(err));
            if (insert_rc < 0) {
                fprintf(stderr, "DB write failed (message kept): %s\n", err);
                continue;
            }
            if (np_sqs_delete(sqs, msg.receipt_handle, err, sizeof(err)) != 0) {
                fprintf(stderr, "SQS delete failed after reject record: %s\n", err);
            }
            continue;
        }

        (void)np_monitor_check(job.url, http_timeout_ms, &result);
        np_monitor_print(&result);

        insert_rc = np_db_insert_check(db, job.job_id, job.monitor_id, &result,
                                       job.scheduled_at, err, sizeof(err));
        if (insert_rc < 0) {
            fprintf(stderr, "DB write failed for jobId=%s (message NOT deleted): %s\n",
                    job.job_id, err);
            continue;
        }
        if (insert_rc == 0) {
            fprintf(stdout, "Duplicate jobId=%s already in DB — deleting SQS message\n", job.job_id);
        }

        if (np_sqs_delete(sqs, msg.receipt_handle, err, sizeof(err)) != 0) {
            fprintf(stderr, "SQS delete failed for jobId=%s: %s\n", job.job_id, err);
            continue;
        }

        processed++;
        fprintf(stdout, "Completed jobId=%s (processed=%d)\n", job.job_id, processed);
        fflush(stdout);

        if (max_messages > 0 && processed >= max_messages) {
            fprintf(stdout, "Max messages reached (%ld); stopping\n", max_messages);
            fflush(stdout);
            break;
        }
    }

    fprintf(stdout, "Shutting down gracefully after %d jobs\n", processed);
    np_db_close(db);
    np_sqs_client_destroy(sqs);
    curl_global_cleanup();
    return 0;
}
