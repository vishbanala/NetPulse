# Deploying NetPulse on Render

Exact settings for the **authenticated** stack. Do not put secret values in git.

## Architecture

| Preferred free layout | Role |
|----------------------|------|
| **Render Free Web** (Docker) | Spring Boot API + bundled React SPA (same origin) |
| **GitHub Actions** (cron) | One-shot Java enqueue + bounded C worker |
| **Demo static site** (existing) | `VITE_DEMO_MODE=true` sample UI — leave unchanged |

External: Supabase PostgreSQL, AWS SQS (`us-east-2`).

For the free layout, keep **`NETPULSE_SCHEDULER_ENABLED=false`** on Render and use Actions for scheduling/checks. See [FREE_DEPLOY_GITHUB_ACTIONS.md](FREE_DEPLOY_GITHUB_ACTIONS.md).

| Optional paid layout | Role |
|----------------------|------|
| **Web** (Docker) | API + SPA; may set `NETPULSE_SCHEDULER_ENABLED=true` |
| **Worker** (Docker background) | Long-running C SQS consumer |

Never enable the continuous Java scheduler on Render **and** the Actions workflow at the same time (duplicate enqueue).

---

## 1) Web service (API + SPA)

| Setting | Value |
|---------|--------|
| Service type | **Web Service** |
| Language / runtime | **Docker** |
| Dockerfile path | `backend/Dockerfile` |
| Docker build context directory | **`.`** (repository root) |
| Health check path | `/health` |
| Auto-deploy | optional |

### Local image build (verify)

```bash
docker build -f backend/Dockerfile -t netpulse-web .
```

### Safe production defaults (already in the image)

These are set in `backend/Dockerfile` and should remain unless you have a deliberate reason to change them:

| Variable | Safe default |
|----------|----------------|
| `SERVER_ADDRESS` | `0.0.0.0` |
| `NETPULSE_SECURITY_MODE` | `AUTHENTICATED` |
| `NETPULSE_SESSION_COOKIE_SECURE` | `true` |
| `NETPULSE_SESSION_COOKIE_SAME_SITE` | `Lax` |
| `NETPULSE_SCHEDULER_ENABLED` | `false` (**required** for free + GitHub Actions) |
| `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS` | `never` |
| `VITE_DEMO_MODE` (build-time) | `false` (baked into SPA) |

Render injects `PORT` automatically.

For free-tier monitoring, leave the scheduler off here and configure [GitHub Actions](FREE_DEPLOY_GITHUB_ACTIONS.md). Only set `NETPULSE_SCHEDULER_ENABLED=true` on a always-on web instance if Actions is disabled.

### Required environment variable **names** (Web)

Set in the Render dashboard (values never in git):

- `NETPULSE_DB_URL`
- `NETPULSE_DB_USER`
- `NETPULSE_DB_PASSWORD`
- `NETPULSE_ADMIN_EMAIL` (preferred bootstrap admin; `NETPULSE_ADMIN_USERNAME` accepted as legacy alias)
- `NETPULSE_ADMIN_PASSWORD_HASH` (BCrypt hash only)
- `AWS_REGION`
- `NETPULSE_SQS_QUEUE_URL`
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_SESSION_TOKEN` (if using temporary credentials)

Optional (paid always-on scheduler only — not for free + Actions):

- `NETPULSE_SCHEDULER_ENABLED=true`

Optional:

- `NETPULSE_SCHEDULER_FIXED_DELAY_MS`
- `NETPULSE_SCHEDULER_BATCH_SIZE`
- `NETPULSE_SCHEDULER_PUBLISH_LEASE_SECONDS`
- `NETPULSE_SESSION_TIMEOUT`
- `NETPULSE_LOGIN_MAX_ATTEMPTS`
- `NETPULSE_LOGIN_WINDOW_SECONDS`
- `NETPULSE_CORS_ALLOWED_ORIGINS` (omit for same-origin SPA)
- `NETPULSE_MAX_ENABLED_MONITORS_GLOBAL` (optional site-wide enabled-monitor ceiling; default 40)
- `NETPULSE_SMTP_*` + `NETPULSE_PUBLIC_BASE_URL` (optional; enables forgot/reset password only)

Missing DB/admin credentials in `AUTHENTICATED` / `HEALTH_ONLY` modes cause **startup failure** (fail closed).

### Same-origin confirmation

The web image builds the React app into `classpath:/static`. Browsers load the SPA and call relative `/api/...` on the **same host** → HttpOnly session cookie + CSRF with `SameSite=Lax`.

---

## 2) Worker service (C engine)

| Setting | Value |
|---------|--------|
| Service type | **Background Worker** (or Docker service with no public port) |
| Language / runtime | **Docker** |
| Dockerfile path | `monitoring-engine/Dockerfile` |
| Docker build context directory | **`.`** (repository root) |
| Health check | none (no HTTP listener) |

### Local image build (verify)

```bash
docker build -f monitoring-engine/Dockerfile -t netpulse-worker .
```

### Required environment variable **names** (Worker)

- `NETPULSE_DB_URL`
- `NETPULSE_DB_USER`
- `NETPULSE_DB_PASSWORD`
- `AWS_REGION`
- `NETPULSE_SQS_QUEUE_URL`
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_SESSION_TOKEN` (if temporary)

Optional:

- `NETPULSE_HTTP_TIMEOUT_MS`
- `NETPULSE_SQS_WAIT_SECONDS`
- `NETPULSE_SQS_VISIBILITY_SECONDS`
- `NETPULSE_PG_CONNINFO`

The worker **exits non-zero** if SQS/region/DB/AWS credentials are missing.

Do **not** set `NETPULSE_SCHEDULER_ENABLED` on the worker (ignored; scheduler is Java-only).

---

## 3) Demo static site

Leave the existing Render static site as-is with build-time `VITE_DEMO_MODE=true`. It must not receive production DB/AWS/admin secrets.

---

## Health checks

Use **`GET /health`** on the web service. Response body is only `{"status":"UP"}` (no secrets).

---

## Why not static-site `/api` rewrite → separate API?

Not used for production auth. Cookie/CSRF sessions need same-origin (or fragile cross-site `SameSite=None`). See architecture notes above; production uses the bundled SPA on the Java web service.
