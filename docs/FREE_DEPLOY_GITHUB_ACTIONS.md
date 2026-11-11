# Free deployment: Render Web + GitHub Actions monitor

Completely free-oriented stack (subject to each vendor’s free-tier limits):

| Piece | Where it runs |
|-------|----------------|
| Authenticated dashboard (Spring Boot + React SPA) | **Render Free Web Service** |
| Demo static site | Existing Render static site (`VITE_DEMO_MODE=true`) — unchanged |
| Enqueue due monitors | **GitHub Actions** one-shot Java (`NETPULSE_SCHEDULE_ONCE=true`) |
| HTTP checks + incident writes | **GitHub Actions** bounded C worker |
| Database / queue | Existing **Supabase PostgreSQL** + **AWS SQS** |

The Render web service stays asleep between dashboard visits. Scheduling and checks do **not** depend on it being awake.

## Why not run the scheduler on Render Free?

Free web services sleep after idle time. A sleeping JVM cannot claim due monitors. GitHub Actions wakes on a cron (~every 5 minutes), runs one enqueue tick, then drains SQS with the real C worker.

Keep on Render:

```text
NETPULSE_SCHEDULER_ENABLED=false
```

(Do not set this to `true` on the free web service or you risk duplicate enqueue when the instance is warm.)

## Workflow

File: [`.github/workflows/netpulse-monitor.yml`](../.github/workflows/netpulse-monitor.yml)

| Trigger | Purpose |
|---------|---------|
| `schedule: */5 * * * *` | Approximate 5-minute cadence |
| `workflow_dispatch` | Manual test run from the Actions tab |

**Concurrency:** group `netpulse-monitor` with `cancel-in-progress: false` so only one monitor job runs at a time (avoids overlapping drains). If a run is still active, the next waits.

### What each run does

1. Build the backend jar (`mvn -DskipTests package`) — **no frontend rebuild**.
2. Build `monitoring-engine` `netpulse-worker` with system libcurl/libpq.
3. **Schedule once:** `java -jar …` with `SPRING_MAIN_WEB_APPLICATION_TYPE=none` and `NETPULSE_SCHEDULE_ONCE=true` → `MonitorSchedulingService.runOnce()` claims due rows (`FOR UPDATE SKIP LOCKED`), publishes SQS jobs, exits.
4. **Bounded worker:** same C engine as Docker, with:
   - `NETPULSE_WORKER_MAX_MESSAGES=50`
   - `NETPULSE_WORKER_MAX_EMPTY_RECEIVES=2`
   - `NETPULSE_WORKER_MAX_RUNTIME_SECONDS=240`
   - short SQS wait (`5s`) so idle exit is quick

Duplicate protection (unchanged product logic):

- DB claim + publish lease + stable `pending_job_id`
- Worker inserts keyed by `job_id` (duplicate SQS delivery → delete message)

## GitHub Actions secrets (names only)

Configure under **Settings → Secrets and variables → Actions**. Never commit values.

| Secret name | Required? | Used for |
|-------------|-----------|----------|
| `NETPULSE_DB_URL` | yes | JDBC URL (Supabase pooler recommended) |
| `NETPULSE_DB_USER` | yes | DB user |
| `NETPULSE_DB_PASSWORD` | yes | DB password |
| `AWS_REGION` | yes | e.g. `us-east-2` |
| `NETPULSE_SQS_QUEUE_URL` | yes | Monitoring jobs queue |
| `AWS_ACCESS_KEY_ID` | yes | AWS access key for SQS |
| `AWS_SECRET_ACCESS_KEY` | yes | AWS secret key |
| `AWS_SESSION_TOKEN` | optional | Temporary credentials only |

Workflow hardening: `permissions.contents: read` only; `persist-credentials: false` on checkout; AWS/DB secrets are injected only into the verify / enqueue / drain steps (not into build steps).

Render still needs its **own** dashboard secrets (`NETPULSE_ADMIN_*`, DB, etc.) for the authenticated SPA. Actions does **not** need the admin password hash (`HEALTH_ONLY` headless mode for the one-shot jar).

## Expected delays and limits

- **Cron drift:** GitHub does not guarantee exact `*/5` starts; runs can be delayed by minutes under load.
- **Serialized runs:** an overlapping schedule waits; checks can bunch after a slow run.
- **Actions minutes:** private repos consume the plan’s monthly Actions minutes; public repos are generally free for standard GitHub-hosted runners (confirm current GitHub billing docs).
- **Render Free:** cold starts when opening the dashboard; monitoring continues via Actions regardless.
- **SQS / Supabase:** stay within each free/low-tier quota.

## Render Free Web Service (dashboard only)

| Setting | Value |
|---------|--------|
| Type | Web Service (Docker) |
| Dockerfile | `backend/Dockerfile` |
| Context | `.` (repo root) |
| Health check | `/health` |
| `NETPULSE_SCHEDULER_ENABLED` | **`false`** |
| `NETPULSE_SECURITY_MODE` | `AUTHENTICATED` |
| Session cookies | `Secure=true`, `SameSite=Lax` |

Do **not** deploy the C worker as a Render Background Worker on the free plan; Actions replaces that role.

## Local smoke (optional)

```bash
# One-shot enqueue (headless)
export SPRING_MAIN_WEB_APPLICATION_TYPE=none
export NETPULSE_SCHEDULE_ONCE=true
export NETPULSE_SCHEDULER_ENABLED=false
export NETPULSE_SECURITY_MODE=HEALTH_ONLY
# plus DB + AWS + SQS env…
java -jar backend/target/netpulse-backend-*.jar

# Bounded worker
export NETPULSE_WORKER_MAX_EMPTY_RECEIVES=2
export NETPULSE_WORKER_MAX_MESSAGES=50
export NETPULSE_WORKER_MAX_RUNTIME_SECONDS=240
export NETPULSE_SQS_WAIT_SECONDS=5
./monitoring-engine/build/netpulse-worker
```
