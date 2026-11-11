# Production upgrade to multi-user (`5926c1c` lineage)

Upgrade the **existing** Render production URL to public multi-user NetPulse.  
**Do not merge, deploy, migrate, change Render, or trigger Actions until you explicitly approve.**

Validated cutover tip: `feature/public-multiuser` @ `5926c1ce29621b87846a99b7e28035966338bba8`  
Current `main`: `1fc11a6554ae103c53c625343596b733bf3c7400` (allowlist-era; **3 commits behind** feature)

---

## What changed for a clean 007/008

`Apply-MultiuserMigrations.ps1` applies **007 + 008 in one PostgreSQL transaction**:

1. **Preflight** validates admin email + BCrypt hash in PowerShell (no DB DDL yet).
2. Bind admin from env via `set_config`, then re-validate in SQL (still before DDL).
3. Migration `007` validates session admin again, then creates `users` / token tables and `monitors.user_id`.
4. If monitors exist and `users` is empty → insert that admin → own all monitors.
5. Enforce `NOT NULL` + FK.
6. Apply `008` (interval floor 300s) in the **same** transaction, then `COMMIT`.

Any failure aborts the transaction — no leftover `users` table or nullable `user_id`.  
The script exits nonzero on failure and never prints credentials.

Helpers:

- `backend/db/scripts/Apply-MultiuserMigrations.ps1`
- `backend/db/tests/Test-MultiuserMigrationAtomicity.ps1` (isolated Docker clones)

---

## Compatibility facts (cutover design)

| Runner | Checkout / deploy source | Schema needs after 007 |
|--------|--------------------------|-------------------------|
| GitHub Actions `netpulse-monitor.yml` | `actions/checkout@v4` with **no `ref:`** → **default branch (`main`)** | Schedule SQL ignores `user_id` (OK). C worker ignores `user_id` (OK). |
| Render Web (Docker `backend/Dockerfile`) | Auto-deploy from connected branch (usually `main`) or manual deploy | Multi-user jar **requires** `users` + `monitors.user_id` |
| Allowlist-era `main` API | — | `INSERT INTO monitors (...)` **omits `user_id`** → **fails** once `user_id` is `NOT NULL` |

**Must cut over Render and Actions to the same multi-user commit** in one window so URL policy matches (`PublicUrlValidator` vs allowlist).

Actions concurrency: group `netpulse-monitor`, `cancel-in-progress: false` (in-flight drain is **not** cancelled). Job timeout: 10 minutes. Triggers: `schedule: */5 * * * *` and `workflow_dispatch`.

---

## Environment checklist (presence only — never print values)

### Local operator shell (preflight for migration apply)

| Name | Purpose |
|------|---------|
| `NETPULSE_ADMIN_EMAIL` | Same bootstrap admin as Render |
| `NETPULSE_ADMIN_PASSWORD_HASH` | Same BCrypt hash as Render (`$2a$`/`$2b$`/`$2y$`, 60 chars) |
| `PGHOST` / `PGPORT` / `PGUSER` / `PGPASSWORD` / `PGDATABASE` | Prefer Supabase **direct** host `:5432` for dump/migrate |

### Render Web dashboard (required names)

- `NETPULSE_DB_URL`, `NETPULSE_DB_USER`, `NETPULSE_DB_PASSWORD`
- `NETPULSE_ADMIN_EMAIL`, `NETPULSE_ADMIN_PASSWORD_HASH`
- `AWS_REGION`, `NETPULSE_SQS_QUEUE_URL`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
- `AWS_SESSION_TOKEN` (only if using temporary creds)
- Confirm `NETPULSE_SECURITY_MODE=AUTHENTICATED` (Dockerfile default)
- Confirm `NETPULSE_SCHEDULER_ENABLED=false` (required with Actions)
- Optional: `NETPULSE_MAX_ENABLED_MONITORS_GLOBAL`, `NETPULSE_SMTP_*`, `NETPULSE_PUBLIC_BASE_URL`

### GitHub Actions secrets (required names)

- `NETPULSE_DB_URL`, `NETPULSE_DB_USER`, `NETPULSE_DB_PASSWORD`
- `AWS_REGION`, `NETPULSE_SQS_QUEUE_URL`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
- `AWS_SESSION_TOKEN` (optional)
- Actions does **not** need `NETPULSE_ADMIN_*` (`HEALTH_ONLY` one-shot)

Presence check pattern (operator machine — prints PRESENT/MISSING only):

```powershell
foreach ($n in @(
  'NETPULSE_ADMIN_EMAIL','NETPULSE_ADMIN_PASSWORD_HASH',
  'PGHOST','PGPORT','PGUSER','PGPASSWORD','PGDATABASE'
)) {
  $v = [Environment]::GetEnvironmentVariable($n)
  Write-Host ("{0}={1}" -f $n, $(if ([string]::IsNullOrWhiteSpace($v)) { 'MISSING' } else { 'PRESENT' }))
}
```

Confirm Render + Actions secret **names** in their dashboards the same way (eye-check only).

---

## Auto-deploy risk (merge into `main`)

If Render **auto-deploy is ON** for `main`:

| Order | Result |
|-------|--------|
| Merge feature → `main` **before** DB migrate | New jar starts, queries `user_id` / `users` → **API broken** until migrate |
| Migrate DB **while** old `main` jar still serving | Reads/schedule/worker mostly OK; **monitor create** on old jar fails (`user_id` NOT NULL) |
| Merge **while** Actions still enabled and migrate mid-cron | Possible lock waits / failed enqueue during DDL window |

**Safe pattern:** suspend Actions + suspend Render → backup verified → migrate → merge/deploy **same commit** → resume Actions → resume Render.

If auto-deploy cannot be trusted during the window: turn **Auto-Deploy off** on the Render Web service before merging, then manually deploy `5926c1c` (or `main` after merge), then turn Auto-Deploy back on.

---

## Numbered production cutover runbook

### 0) Preconditions (no production writes)

1. Confirm tip commit: `5926c1ce29621b87846a99b7e28035966338bba8` on `feature/public-multiuser`.
2. Dashboard eye-check: Render env names + Actions secret names (table above).
3. Confirm Render Web: Docker context `.`, Dockerfile `backend/Dockerfile`, health `/health`, scheduler **false**.
4. Confirm you can suspend the Render Web service and disable the Actions workflow from the UI (or authenticated `gh`).

### 1) Fresh production backup + isolated restore verification

Load DB connection into the shell from your password manager (**do not echo**). Prefer direct host port **5432**.

```powershell
$Stamp = Get-Date -Format "yyyyMMddTHHmmssZ"
$Out = Join-Path $env:USERPROFILE "netpulse-pre-multiuser-$Stamp.dump"

pg_dump --format=custom --no-owner --no-acl --file=$Out

Get-Item $Out | Format-List FullName, Length, LastWriteTime
pg_restore --list $Out | Select-Object -First 20
pg_restore --list $Out | Select-String -Pattern "TABLE DATA.*(monitors|monitoring_checks|incidents|monitor_schedules)"
```

Isolated restore (does **not** touch production):

```powershell
docker rm -f netpulse-cutover-restore 2>$null
docker run -d --name netpulse-cutover-restore `
  -e POSTGRES_PASSWORD=localtest `
  -e POSTGRES_USER=postgres `
  -e POSTGRES_DB=postgres `
  -p 55446:5432 postgres:17

for ($i = 0; $i -lt 60; $i++) {
  docker exec netpulse-cutover-restore pg_isready -U postgres -d postgres | Out-Null
  if ($LASTEXITCODE -eq 0) { break }
  Start-Sleep -Seconds 1
}

docker cp $Out netpulse-cutover-restore:/backup.dump
docker exec -e PGPASSWORD=localtest netpulse-cutover-restore `
  pg_restore -U postgres --dbname=postgres --no-owner --no-acl --exit-on-error /backup.dump

docker exec -e PGPASSWORD=localtest netpulse-cutover-restore `
  psql -U postgres -d postgres -c @"
SELECT 'monitors' AS e, COUNT(*) FROM monitors
UNION ALL SELECT 'monitoring_checks', COUNT(*) FROM monitoring_checks
UNION ALL SELECT 'incidents', COUNT(*) FROM incidents
UNION ALL SELECT 'monitor_schedules', COUNT(*) FROM monitor_schedules;
"@
```

**Gate:** counts match production expectation (previously **3 / 30 / 1 / 3**). Keep `$Out` safe; do not delete.

Optional: dry-run migrate on this clone with disposable admin only (never production admin in logs).

### 2) Freeze writers (monitoring + API)

**GitHub Actions**

1. Open **Actions → NetPulse monitor → `...` → Disable workflow**  
   (or `gh workflow disable "NetPulse monitor"` when authenticated).
2. Open **Actions** runs: if one is **in progress**, **wait for it to finish** (do not cancel — drain must complete cleanly; up to ~10 minutes).
3. Confirm no new `schedule` / `workflow_dispatch` runs start.
4. Do **not** click “Run workflow” during the window.

**Render Web**

1. Dashboard → Web service → **Suspend** (stops the old jar from opening DB connections).
2. Confirm the public URL no longer serves the API (expected downtime).
3. If a separate Render **Worker** exists for the C engine, suspend it too (free layout usually has **no** always-on worker — Actions owns drain).

**SQS:** leave the queue alone. After cutover, the new Actions worker drains any leftover messages (worker does not use `user_id`).

### 3) Apply migrations 007 + 008 (production DB)

Same admin email/hash as Render. Direct DB host preferred.

```powershell
cd backend\db\scripts
# NETPULSE_ADMIN_EMAIL / NETPULSE_ADMIN_PASSWORD_HASH already set (same as Render)
# PGHOST/PGPORT/PGUSER/PGPASSWORD/PGDATABASE → production (approved window only)
.\Apply-MultiuserMigrations.ps1
```

Expect exit **0** and message: migrations applied successfully.

Verify (read-only checks; do not print emails/hashes):

```powershell
psql -v ON_ERROR_STOP=1 -c @"
SELECT COUNT(*) AS monitors FROM monitors;
SELECT COUNT(*) AS checks FROM monitoring_checks;
SELECT COUNT(*) AS incidents FROM incidents;
SELECT COUNT(*) AS schedules FROM monitor_schedules;
SELECT COUNT(*) AS users FROM users;
SELECT COUNT(*) AS null_owners FROM monitors WHERE user_id IS NULL;
SELECT COUNT(*) AS below_300 FROM monitors WHERE interval_seconds < 300;
SELECT EXISTS (
  SELECT 1 FROM pg_constraint WHERE conname = 'monitors_user_id_fkey'
) AS has_fk;
"@
```

**Gate:** monitors/checks/incidents/schedules unchanged; `users >= 1`; `null_owners = 0`; `below_300 = 0`; `has_fk = t`.

### 4) Cut over code to the **same** commit

Goal: Render jar and Actions checkout both run **`5926c1c`** (or `main` after it contains that commit).

Recommended sequence:

1. On Render Web: confirm **Auto-Deploy** behavior. If ON, either keep service suspended until merge finishes deploying, or temporarily disable Auto-Deploy and **Manual Deploy** the multi-user commit.
2. Merge `feature/public-multiuser` → `main` (PR or local merge + push) **only in this window**.
3. Wait until Render deploy of that commit is **Live** (or finish manual deploy).
4. Confirm deploy commit matches: Render deploy details show `5926c1c` (or the merge commit that contains it).
5. Re-enable Auto-Deploy if you disabled it.

Verify Actions will build multi-user code:

```powershell
git fetch origin
git rev-parse origin/main
# Must be 5926c1c or a merge commit that contains it
git merge-base --is-ancestor 5926c1ce29621b87846a99b7e28035966338bba8 origin/main
if ($LASTEXITCODE -eq 0) { "main contains 5926c1c" } else { "BLOCKER: main missing multi-user commit" }
```

### 5) Resume services

1. **Resume** Render Web → wait for healthy.
2. `GET https://<production-host>/health` → `{"status":"UP"}` only.
3. **Enable** GitHub Actions workflow `NetPulse monitor`.
4. Optionally run one **workflow_dispatch** after enable to prove enqueue+drain (not required if cron is soon).
5. Keep `NETPULSE_SCHEDULER_ENABLED=false` on Render.

### 6) Smoke checks

1. Admin login (existing credentials) → all **3** legacy monitors visible.
2. Register a new user → create monitor → cannot see admin monitors.
3. Confirm intervals on legacy monitors are **≥ 300**.
4. After an Actions run: new `monitoring_checks` rows appear.
5. Demo static site unchanged.

---

## Recovery (do **not** use `007_rollback.sql` on production)

`007_rollback.sql` drops `users` and ownership — destructive for any registered accounts.

**Preferred recovery:** restore the pre-upgrade custom dump taken in step 1.

High-level only (practice on a scratch DB first; coordinate with Supabase if needed):

1. Keep Actions disabled and Render suspended.
2. Restore `$Out` into a **scratch** database and verify counts.
3. Restore into production only with a tested procedure (Supabase support / maintenance window as applicable).
4. Deploy the **previous** known-good Render commit (`1fc11a6` / prior `main`) so the jar matches the restored schema.
5. Re-enable Actions only after `main`/deploy and schema again match.
6. Retain the failed-upgrade dump and the pre-upgrade dump for forensics.

---

## Scheduler / worker notes after upgrade

- Java schedule-once still claims all enabled monitors (no `user_id` filter).
- New code uses `PublicUrlValidator` (not allowlist).
- C worker unchanged regarding ownership; still pin-IP + no redirects.
- Free tier: Render scheduler stays **off**; Actions owns enqueue + drain.

---

## First safe production action

**Take a fresh `pg_dump` of production and verify restore into a disposable Docker Postgres 17 container** (step 1).  
That action does not change schema, Render, or Actions. Everything else waits on your explicit approval after the backup gate passes.
