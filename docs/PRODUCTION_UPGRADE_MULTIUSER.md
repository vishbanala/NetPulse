# Production upgrade to multi-user (`3672077` lineage)

Upgrade the **existing** production URL to public multi-user NetPulse.  
Do not merge, deploy, or migrate until you explicitly approve.

## What changed for a clean 007

`Apply-MultiuserMigrations.ps1` applies **007 + 008 in one PostgreSQL transaction**:

1. **Preflight** validates admin email + BCrypt hash in PowerShell (no DB DDL yet).
2. Bind admin from env via `set_config`, then re-validate in SQL (still before DDL).
3. Migration `007` validates session admin again, then creates `users` / token tables and `monitors.user_id`.
4. If monitors exist and `users` is empty → insert that admin → own all monitors.
5. Enforce `NOT NULL` + FK.
6. Apply `008` (interval floor 300s) in the **same** transaction, then `COMMIT`.

Any failure aborts the transaction — no leftover `users` table or nullable `user_id`.  
The script exits nonzero on failure and never prints credentials.

Helper (Windows): `backend/db/scripts/Apply-MultiuserMigrations.ps1`  
Atomicity harness (isolated Docker clones of a dump): `backend/db/tests/Test-MultiuserMigrationAtomicity.ps1`

## Scheduler / worker compatibility

- Java `MonitorSchedulingService` / `MonitorScheduleRepository` select monitor id/url/schedule columns only — **ignore `user_id`**, still claim all enabled monitors.
- New code validates URLs with `PublicUrlValidator` (not allowlist).
- C worker never reads `user_id`; still pin-IP + no redirects.
- **Render and GitHub Actions must run the same multi-user commit** so URL policy matches. Actions checks out the **default branch** on cron; deploy API from this feature commit without updating what Actions builds leaves policy skew.

## Windows: backup + isolated restore test

Use Supabase **direct** DB host (port 5432), not the pooler, for `pg_dump` when possible. Load secrets into the shell from your password manager — **do not echo them**.

```powershell
# --- set connection from secret store (examples of NAMES only) ---
# $env:PGHOST = "<supabase-db-host>"
# $env:PGPORT = "5432"
# $env:PGUSER = "<db-user>"
# $env:PGDATABASE = "postgres"
# $env:PGPASSWORD = "<db-password>"

$Stamp = Get-Date -Format "yyyyMMddTHHmmssZ"
$Out = Join-Path $env:USERPROFILE "netpulse-pre-multiuser-$Stamp.dump"

# Create custom-format backup
pg_dump --format=custom --no-owner --no-acl --file=$Out

# Verify
Get-Item $Out | Format-List FullName, Length, LastWriteTime
pg_restore --list $Out | Select-Object -First 20
pg_restore --list $Out | Select-String -Pattern "TABLE DATA.*(monitors|monitoring_checks|incidents)"

# Isolated restore test via Docker (does not touch production)
docker rm -f netpulse-restore-test 2>$null
docker run -d --name netpulse-restore-test `
  -e POSTGRES_PASSWORD=localtest `
  -e POSTGRES_USER=netpulse `
  -e POSTGRES_DB=netpulse `
  -p 55433:5432 postgres:16

# Wait until ready
for ($i = 0; $i -lt 30; $i++) {
  docker exec netpulse-restore-test pg_isready -U netpulse -d netpulse | Out-Null
  if ($LASTEXITCODE -eq 0) { break }
  Start-Sleep -Seconds 1
}

docker cp $Out netpulse-restore-test:/backup.dump
docker exec -e PGPASSWORD=localtest netpulse-restore-test `
  pg_restore --dbname=netpulse --no-owner --no-acl --exit-on-error /backup.dump

docker exec -e PGPASSWORD=localtest netpulse-restore-test `
  psql -U netpulse -d netpulse -c "SELECT COUNT(*) AS monitors FROM monitors; SELECT COUNT(*) AS checks FROM monitoring_checks; SELECT COUNT(*) AS incidents FROM incidents;"

# Cleanup scratch only
docker rm -f netpulse-restore-test
```

If `pg_dump` / `pg_restore` are not on PATH, use the client from the image:

```powershell
docker run --rm -e PGPASSWORD=$env:PGPASSWORD postgres:16 `
  pg_dump -h $env:PGHOST -p $env:PGPORT -U $env:PGUSER -d $env:PGDATABASE `
  --format=custom --no-owner --no-acl -f /tmp/backup.dump
# (mount a host volume to copy the file out)
```

## Apply 007+008 (after backup verified)

```powershell
cd backend\db\scripts
# $env:NETPULSE_ADMIN_EMAIL / NETPULSE_ADMIN_PASSWORD_HASH already set (same as Render)
# $env:PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE set to production only when approved
.\Apply-MultiuserMigrations.ps1
```

Or manually in one `psql` session: run `007_bind_admin_session.sql` with `-v admin_email=... -v admin_hash=...`, then `007_…sql`, then `008_…sql`.

## Deployment sequence (compatible Render + Actions)

1. **Backup + restore test** (above).  
2. **Migrate DB** with `Apply-MultiuserMigrations.ps1` (admin email/hash = Render’s existing admin secrets).  
3. **Cut over both runners to the same commit** in one window:
   - Merge or otherwise make default-branch Actions build this multi-user commit (**when you approve**), **and**
   - Deploy Render Web from that same commit.  
4. Keep `NETPULSE_SCHEDULER_ENABLED=false` on Render.  
5. Smoke: admin login → legacy monitors visible → register new user → isolation → Actions still writes checks.  
6. Leave demo static site unchanged.

Avoid: migrating DB and deploying new API while Actions still runs **allowlist-era** `main` for a long period.

## Rollback note

Prefer restore from the pre-upgrade dump. `007_rollback.sql` drops `users` and destroys registered accounts.
