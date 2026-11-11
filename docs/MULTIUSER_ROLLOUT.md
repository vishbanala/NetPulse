# Multi-user rollout (portfolio)

Public register/login with per-user dashboards on `feature/public-multiuser`.
**Do not apply migrations or change production until you explicitly approve deploy.**

## Scope

- Anyone can register and immediately create monitors (no email verification gate)
- Email is an account identifier only (not proof of ownership)
- Optional SMTP enables forgot/reset password; without SMTP the UI hides that flow
- Max **3 enabled monitors per user**, min interval **300s**, plus a site-wide enabled-monitor cap (`NETPULSE_MAX_ENABLED_MONITORS_GLOBAL`, default 40)
- Ownership isolation on all monitor/check/stats/incident APIs
- SSRF protections remain in Java + C (public IPs, pin-IP, no redirects)

## Migrations (manual, after backup)

**Single-pass** (preferred on Windows):

```powershell
cd backend\db\scripts
# NETPULSE_ADMIN_EMAIL + NETPULSE_ADMIN_PASSWORD_HASH and PG* must already be set
.\Apply-MultiuserMigrations.ps1
```

This binds the real admin into the session via `set_config` (no default password in SQL),
applies `007` (creates users, owns legacy monitors), then `008` (min interval 300s).

See `docs/PRODUCTION_UPGRADE_MULTIUSER.md` for backup/restore commands and Render+Actions cutover.

Rollback (destroys ownership mapping / users): `008_rollback.sql` then `007_rollback.sql`.
Prefer restoring the pre-upgrade dump if real users already registered.

## Environment

| Variable | Purpose |
|----------|---------|
| `NETPULSE_SECURITY_MODE=AUTHENTICATED` | Public multi-user auth |
| `NETPULSE_ADMIN_EMAIL` | Bootstrap admin email |
| `NETPULSE_ADMIN_PASSWORD_HASH` | BCrypt hash for that admin |
| `NETPULSE_MAX_ENABLED_MONITORS_GLOBAL` | Site-wide enabled monitor ceiling (default 40) |
| `NETPULSE_PUBLIC_BASE_URL` | Only needed for password-reset links |
| `NETPULSE_SMTP_*` | Optional; enables forgot/reset password |
| `NETPULSE_CORS_*` / session cookie vars | As today for HTTPS SPA |

## Suggested deploy order (when you approve)

1. Backup Postgres  
2. Insert bootstrap admin if needed → apply `007` → apply `008`  
3. Set env (admin email/hash, optional SMTP, global cap)  
4. Deploy API/SPA  
5. Smoke: register → login → create monitor → confirm isolation → logout; confirm demo site unchanged  
6. Confirm GitHub Actions schedule-once + worker still drain jobs  

## Security notes (portfolio-appropriate)

- Parameterized JDBC, React XSS defaults, CSRF + HttpOnly sessions  
- SSRF: Java validator + C pin-IP; redirects disabled  
- IDOR: ownership checks return 404  
- Free-tier Actions caps (~50 msgs / 240s) remain the fleet ceiling  
