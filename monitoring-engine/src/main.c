#include "monitor.h"

#include <curl/curl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define DEFAULT_TIMEOUT_MS 5000L
#define DEFAULT_WORKERS 4
#define MAX_WORKERS 16
#define MAX_URLS 64

typedef struct {
    const char **urls;
    int url_count;
    int next_index;
    long timeout_ms;
    pthread_mutex_t lock;
    int worst_exit; /* highest severity exit code seen */
} WorkQueue;

static void usage(const char *argv0)
{
    fprintf(stderr,
            "Usage: %s [options] <url> [url...]\n"
            "\n"
            "Options:\n"
            "  -t <ms>     Request timeout in milliseconds (default: %ld)\n"
            "  -j <n>      Max worker threads for multiple URLs (default: %d, max: %d)\n"
            "  -h          Show this help\n"
            "\n"
            "Only allowlisted public test URLs are accepted in Step 3.\n",
            argv0, DEFAULT_TIMEOUT_MS, DEFAULT_WORKERS, MAX_WORKERS);
}

static int max_exit(int a, int b)
{
    return a > b ? a : b;
}

static void *worker_main(void *arg)
{
    WorkQueue *q = (WorkQueue *)arg;

    for (;;) {
        int index;
        NpMonitorResult result;
        int code;

        pthread_mutex_lock(&q->lock);
        if (q->next_index >= q->url_count) {
            pthread_mutex_unlock(&q->lock);
            break;
        }
        index = q->next_index++;
        pthread_mutex_unlock(&q->lock);

        code = np_monitor_check(q->urls[index], q->timeout_ms, &result);

        pthread_mutex_lock(&q->lock);
        np_monitor_print(&result);
        printf("---\n");
        q->worst_exit = max_exit(q->worst_exit, code);
        pthread_mutex_unlock(&q->lock);
    }

    return NULL;
}

int main(int argc, char **argv)
{
    long timeout_ms = DEFAULT_TIMEOUT_MS;
    int workers = DEFAULT_WORKERS;
    const char *urls[MAX_URLS];
    int url_count = 0;
    int i;
    int exit_code = 0;
    CURLcode global_rc;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-h") == 0 || strcmp(argv[i], "--help") == 0) {
            usage(argv[0]);
            return 0;
        }
        if (strcmp(argv[i], "-t") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "Missing value for -t\n");
                return (int)NP_STATUS_REJECTED;
            }
            timeout_ms = strtol(argv[++i], NULL, 10);
            if (timeout_ms <= 0) {
                fprintf(stderr, "Timeout must be positive\n");
                return (int)NP_STATUS_REJECTED;
            }
            continue;
        }
        if (strcmp(argv[i], "-j") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "Missing value for -j\n");
                return (int)NP_STATUS_REJECTED;
            }
            workers = (int)strtol(argv[++i], NULL, 10);
            if (workers < 1) {
                workers = 1;
            }
            if (workers > MAX_WORKERS) {
                workers = MAX_WORKERS;
            }
            continue;
        }
        if (argv[i][0] == '-') {
            fprintf(stderr, "Unknown option: %s\n", argv[i]);
            usage(argv[0]);
            return (int)NP_STATUS_REJECTED;
        }
        if (url_count >= MAX_URLS) {
            fprintf(stderr, "Too many URLs (max %d)\n", MAX_URLS);
            return (int)NP_STATUS_REJECTED;
        }
        urls[url_count++] = argv[i];
    }

    if (url_count == 0) {
        usage(argv[0]);
        return (int)NP_STATUS_REJECTED;
    }

    global_rc = curl_global_init(CURL_GLOBAL_DEFAULT);
    if (global_rc != CURLE_OK) {
        fprintf(stderr, "curl_global_init failed: %s\n",
                curl_easy_strerror(global_rc));
        return (int)NP_STATUS_ERROR;
    }

    if (url_count == 1) {
        NpMonitorResult result;
        exit_code = np_monitor_check(urls[0], timeout_ms, &result);
        np_monitor_print(&result);
    } else {
        WorkQueue queue;
        pthread_t threads[MAX_WORKERS];
        int nthreads = workers;
        int t;

        if (nthreads > url_count) {
            nthreads = url_count;
        }

        memset(&queue, 0, sizeof(queue));
        queue.urls = urls;
        queue.url_count = url_count;
        queue.next_index = 0;
        queue.timeout_ms = timeout_ms;
        queue.worst_exit = 0;
        pthread_mutex_init(&queue.lock, NULL);

        for (t = 0; t < nthreads; t++) {
            if (pthread_create(&threads[t], NULL, worker_main, &queue) != 0) {
                fprintf(stderr, "Failed to create worker thread %d\n", t);
                nthreads = t;
                break;
            }
        }

        for (t = 0; t < nthreads; t++) {
            pthread_join(threads[t], NULL);
        }

        pthread_mutex_destroy(&queue.lock);
        exit_code = queue.worst_exit;
    }

    curl_global_cleanup();
    return exit_code;
}
