# backend

Spring Boot REST API and opt-in monitoring scheduler for NetPulse.

## Security

**Unauthenticated + localhost only** (`server.address=127.0.0.1`).  
Do not expose this API publicly until authentication and rate limiting exist.

## Stack

- Spring Web / Actuator / JDBC / Validation
- PostgreSQL (Supabase)
- AWS SDK for Java v2 — SQS module only

## Environment

See `.env.example`. Never commit real passwords or access keys.

| Variable | Purpose |
|----------|---------|
| `NETPULSE_DB_URL` | Supabase JDBC URL |
| `NETPULSE_DB_USER` | DB username |
| `NETPULSE_DB_PASSWORD` | DB password |
| `AWS_REGION` | e.g. `us-east-2` |
| `NETPULSE_SQS_QUEUE_URL` | SQS queue URL |
| `NETPULSE_SCHEDULER_ENABLED` | `true` to enable automatic scheduling (default `false`) |
| `NETPULSE_SECURITY_MODE` | `UNAUTHENTICATED_LOCALHOST_ONLY`, `HEALTH_ONLY`, or `AUTHENTICATED` |
| `NETPULSE_ADMIN_EMAIL` | Bootstrap admin email (`AUTHENTICATED`) |
| `NETPULSE_ADMIN_USERNAME` | Legacy alias for admin email |
| `NETPULSE_ADMIN_PASSWORD_HASH` | BCrypt password hash (`AUTHENTICATED`) |
| `NETPULSE_PUBLIC_BASE_URL` | Base URL for optional password-reset links |
| `NETPULSE_SMTP_*` | Optional SMTP (forgot/reset password only) |
| `NETPULSE_MAX_ENABLED_MONITORS_GLOBAL` | Site-wide enabled monitor ceiling (default 40) |
| `NETPULSE_CORS_ALLOWED_ORIGINS` | Allowed browser origin(s) (`AUTHENTICATED`) |
| `NETPULSE_SESSION_COOKIE_SAME_SITE` | `Lax` or `None` |
| `NETPULSE_SESSION_COOKIE_SECURE` | `true` on HTTPS |
| `PORT` / `SERVER_PORT` | Listen port (`PORT` wins when set, e.g. Render) |
| `SERVER_ADDRESS` | Bind address (local default `127.0.0.1`) |

Render / auth notes: `docs/DEPLOY_RENDER.md`. Dockerfiles: `backend/Dockerfile`, `monitoring-engine/Dockerfile`.

If your AWS profile uses `aws login` (browser session) instead of static keys, export temporary credentials into the process environment before `mvn spring-boot:run` (the Java SDK cannot read that cache directly).

## Migrations

Apply in order via the Supabase SQL Editor:

| File | Purpose |
|------|---------|
| `db/migrations/001_init.sql` | Core tables |
| `db/migrations/002_monitoring_checks_job_id.sql` | Idempotent `job_id` |
| `db/migrations/003_monitor_schedules.sql` | Durable schedule leases |
| `db/migrations/004_incident_detection.sql` | Incident function + one-open index |
| `db/migrations/005_alert_outbox.sql` | Historical (superseded by 006) |
| `db/migrations/006_remove_alert_outbox.sql` | Restores Phase 11 incident function |
| `db/migrations/007_users_and_monitor_ownership.sql` | Users + `monitors.user_id` ownership |
| `db/migrations/008_monitor_limits.sql` | Min interval 300s |

Rollout notes: `docs/MULTIUSER_ROLLOUT.md`. Optional SQL scenarios: `db/tests/004_incident_detection_scenarios.sql`.

## Run

```powershell
cd backend
# Ensure NETPULSE_DB_* and AWS env vars are set in this shell
mvn spring-boot:run
```

```powershell
mvn test
```

## Scheduler

Disabled by default. When enabled (`NETPULSE_SCHEDULER_ENABLED=true`):

1. Ensures a `monitor_schedules` row for each enabled monitor
2. Claims due rows with `FOR UPDATE SKIP LOCKED`
3. Publishes `MonitoringJob` JSON to SQS
4. Retains `pending_job_id` on publish failure for the same job retry
