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

1. If production already has monitors and **no** `users` rows yet, insert the real admin first:

```sql
INSERT INTO users (email, password_hash, role, email_verified_at)
VALUES ('YOUR_ADMIN_EMAIL', 'YOUR_BCRYPT_HASH_FROM_ENV', 'ADMIN', NOW());
```

Use the same values as `NETPULSE_ADMIN_EMAIL` / `NETPULSE_ADMIN_PASSWORD_HASH`.  
Migration **007 never inserts a default password**.

2. Apply `backend/db/migrations/007_users_and_monitor_ownership.sql`  
   (assigns existing monitors to the earliest ADMIN, else earliest user)
3. Apply `backend/db/migrations/008_monitor_limits.sql`

Rollback (destroys ownership mapping): `008_rollback.sql` then `007_rollback.sql`.

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
