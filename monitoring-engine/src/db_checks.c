#include "db_checks.h"

#include <libpq-fe.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct NpDb {
    PGconn *conn;
};

static void set_err(char *err, size_t err_len, const char *msg)
{
    if (err && err_len) {
        snprintf(err, err_len, "%s", msg);
    }
}

/*
 * Parse jdbc:postgresql://host[:port]/dbname[?sslmode=...]
 * Login role always comes from NETPULSE_DB_USER (not from the path segment
 * "postgres", which is the database name).
 */
static int parse_jdbc_url(const char *jdbc,
                          char *host, size_t host_len,
                          char *port, size_t port_len,
                          char *dbname, size_t dbname_len,
                          char *sslmode, size_t sslmode_len)
{
    const char *p;
    const char *slash;
    const char *q;
    const char *colon;

    if (!jdbc) {
        return -1;
    }
    p = strstr(jdbc, "://");
    if (!p) {
        return -1;
    }
    p += 3;

    /* Reject URLs with embedded userinfo; credentials come from env only. */
    {
        const char *at = strchr(p, '@');
        const char *first_slash = strchr(p, '/');
        if (at != NULL && (first_slash == NULL || at < first_slash)) {
            return -1;
        }
    }

    slash = strchr(p, '/');
    q = strchr(p, '?');
    if (!slash) {
        return -1;
    }

    {
        size_t hostport_len = (size_t)(slash - p);
        char hostport[300];
        if (hostport_len == 0 || hostport_len >= sizeof(hostport)) {
            return -1;
        }
        memcpy(hostport, p, hostport_len);
        hostport[hostport_len] = '\0';
        colon = strrchr(hostport, ':');
        if (colon) {
            size_t hlen = (size_t)(colon - hostport);
            if (hlen == 0 || hlen >= host_len) {
                return -1;
            }
            memcpy(host, hostport, hlen);
            host[hlen] = '\0';
            snprintf(port, port_len, "%s", colon + 1);
        } else {
            snprintf(host, host_len, "%s", hostport);
            snprintf(port, port_len, "%s", "5432");
        }
    }

    {
        const char *db_start = slash + 1;
        const char *db_end = (q && q > slash) ? q : (db_start + strlen(db_start));
        size_t n = (size_t)(db_end - db_start);
        if (n == 0 || n >= dbname_len) {
            return -1;
        }
        memcpy(dbname, db_start, n);
        dbname[n] = '\0';
    }

    snprintf(sslmode, sslmode_len, "%s", "require");
    if (q) {
        const char *sm = strstr(q, "sslmode=");
        if (sm) {
            sm += 8;
            snprintf(sslmode, sslmode_len, "%.*s", (int)(sslmode_len > 1 ? sslmode_len - 1 : 0), sm);
            {
                char *amp = strchr(sslmode, '&');
                if (amp) {
                    *amp = '\0';
                }
            }
        }
    }

    return 0;
}

NpDb *np_db_connect(char *err, size_t err_len)
{
    NpDb *db;
    const char *direct = getenv("NETPULSE_PG_CONNINFO");
    const char *jdbc = getenv("NETPULSE_DB_URL");
    const char *user = getenv("NETPULSE_DB_USER");
    const char *pass = getenv("NETPULSE_DB_PASSWORD");
    char host[300];
    char port[16];
    char dbname[128];
    char sslmode[32];

    db = (NpDb *)calloc(1, sizeof(*db));
    if (!db) {
        set_err(err, err_len, "oom");
        return NULL;
    }

    if (direct && direct[0]) {
        db->conn = PQconnectdb(direct);
    } else {
        const char *keywords[8];
        const char *values[8];

        if (!jdbc || !jdbc[0] || !user || !user[0] || !pass) {
            set_err(err, err_len,
                    "Set NETPULSE_PG_CONNINFO or NETPULSE_DB_URL/NETPULSE_DB_USER/NETPULSE_DB_PASSWORD");
            free(db);
            return NULL;
        }
        if (parse_jdbc_url(jdbc, host, sizeof(host), port, sizeof(port),
                           dbname, sizeof(dbname), sslmode, sizeof(sslmode)) != 0) {
            set_err(err, err_len, "NETPULSE_DB_URL must look like jdbc:postgresql://host:port/dbname?sslmode=require");
            free(db);
            return NULL;
        }

        /*
         * Use PQconnectdbParams so dotted pooler usernames such as
         * postgres.<project-ref> are never glued into a fragile keyword string.
         * The path "/postgres" is the database name only — not the login role.
         */
        keywords[0] = "host";
        values[0] = host;
        keywords[1] = "port";
        values[1] = port;
        keywords[2] = "dbname";
        values[2] = dbname;
        keywords[3] = "user";
        values[3] = user; /* NETPULSE_DB_USER — must remain postgres.<ref> for pooler */
        keywords[4] = "password";
        values[4] = pass;
        keywords[5] = "sslmode";
        values[5] = sslmode;
        keywords[6] = "application_name";
        values[6] = "netpulse-worker";
        keywords[7] = NULL;
        values[7] = NULL;

        fprintf(stderr,
                "DB connect: host=%s port=%s dbname=%s user=%s sslmode=%s\n",
                host, port, dbname, user, sslmode);

        if (strstr(host, "pooler.supabase") != NULL && strcmp(user, "postgres") == 0) {
            set_err(err, err_len,
                    "NETPULSE_DB_USER is 'postgres' but host is a Supabase pooler; "
                    "use the pooler username postgres.<project-ref>");
            free(db);
            return NULL;
        }

        db->conn = PQconnectdbParams(keywords, values, 0);
    }

    if (PQstatus(db->conn) != CONNECTION_OK) {
        snprintf(err, err_len, "DB connect failed (user=%s): %s",
                 user && user[0] ? user : "(conninfo)",
                 PQerrorMessage(db->conn));
        PQfinish(db->conn);
        free(db);
        return NULL;
    }
    return db;
}

void np_db_close(NpDb *db)
{
    if (!db) {
        return;
    }
    if (db->conn) {
        PQfinish(db->conn);
    }
    free(db);
}

int np_db_insert_check(NpDb *db,
                       const char *job_id,
                       const char *monitor_id,
                       const NpMonitorResult *result,
                       const char *scheduled_at_iso,
                       char *err,
                       size_t err_len)
{
    /*
     * Single-statement call into the Phase 11 SQL function so check insert +
     * incident open/resolve commit atomically with the surrounding transaction.
     */
    const char *sql =
        "SELECT netpulse_record_monitoring_check("
        "$1::uuid, $2::uuid, $3::int, $4::float8, $5::bool, $6::timestamptz)";
    const char *paramValues[6];
    char status_buf[32];
    char latency_buf[64];
    char success_buf[8];
    PGresult *begin_res;
    PGresult *res;
    PGresult *end_res;
    ExecStatusType st;
    const char *outcome;
    int success = (result->health == NP_STATUS_HEALTHY) ? 1 : 0;
    int rc = -1;

    if (!db || !db->conn || !job_id || !monitor_id || !result) {
        set_err(err, err_len, "null args");
        return -1;
    }

    if (result->http_status > 0) {
        snprintf(status_buf, sizeof(status_buf), "%ld", result->http_status);
        paramValues[2] = status_buf;
    } else {
        paramValues[2] = NULL;
    }
    snprintf(latency_buf, sizeof(latency_buf), "%.3f", result->response_time_ms);
    snprintf(success_buf, sizeof(success_buf), "%s", success ? "true" : "false");

    paramValues[0] = job_id;
    paramValues[1] = monitor_id;
    paramValues[3] = latency_buf;
    paramValues[4] = success_buf;
    if (scheduled_at_iso && scheduled_at_iso[0]) {
        paramValues[5] = scheduled_at_iso;
    } else {
        paramValues[5] = NULL; /* function defaults to NOW() */
    }

    begin_res = PQexec(db->conn, "BEGIN");
    if (PQresultStatus(begin_res) != PGRES_COMMAND_OK) {
        snprintf(err, err_len, "BEGIN failed: %s", PQerrorMessage(db->conn));
        PQclear(begin_res);
        return -1;
    }
    PQclear(begin_res);

    res = PQexecParams(db->conn, sql, 6, NULL, paramValues, NULL, NULL, 0);
    st = PQresultStatus(res);
    if (st != PGRES_TUPLES_OK || PQntuples(res) != 1) {
        snprintf(err, err_len, "record check failed: %s", PQerrorMessage(db->conn));
        PQclear(res);
        end_res = PQexec(db->conn, "ROLLBACK");
        PQclear(end_res);
        return -1;
    }

    outcome = PQgetvalue(res, 0, 0);
    if (outcome && strcmp(outcome, "duplicate") == 0) {
        rc = 0;
    } else if (outcome &&
               (strcmp(outcome, "recorded") == 0 ||
                strcmp(outcome, "opened") == 0 ||
                strcmp(outcome, "resolved") == 0)) {
        rc = 1;
        if (strcmp(outcome, "opened") == 0 || strcmp(outcome, "resolved") == 0) {
            fprintf(stderr, "Incident state change monitorId=%s outcome=%s\n",
                    monitor_id, outcome);
        }
    } else {
        snprintf(err, err_len, "unexpected record outcome: %s",
                 outcome ? outcome : "(null)");
        PQclear(res);
        end_res = PQexec(db->conn, "ROLLBACK");
        PQclear(end_res);
        return -1;
    }
    PQclear(res);

    end_res = PQexec(db->conn, "COMMIT");
    if (PQresultStatus(end_res) != PGRES_COMMAND_OK) {
        snprintf(err, err_len, "COMMIT failed: %s", PQerrorMessage(db->conn));
        PQclear(end_res);
        end_res = PQexec(db->conn, "ROLLBACK");
        PQclear(end_res);
        return -1;
    }
    PQclear(end_res);
    return rc;
}
