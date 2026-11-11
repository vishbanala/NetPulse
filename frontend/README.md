# frontend

React + TypeScript dashboard for NetPulse monitors, latency, and incidents.

## Stack

- React 19 + TypeScript + Vite
- Tailwind CSS v4
- Lucide React, Recharts

## Develop (live API)

Requires the Spring Boot API on `http://127.0.0.1:8080`. Vite proxies `/api` to that origin.

```powershell
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

## Demo mode (public portfolio / Render)

Set `VITE_DEMO_MODE=true` at **build** time. The UI uses labeled sample data and browser `localStorage` for Add / Edit / Pause / Delete — it never calls the backend, Supabase, or AWS.

```powershell
$env:VITE_DEMO_MODE="true"
npm run build
```

## Authenticated production UI

When `VITE_DEMO_MODE` is not `true`, the app:

1. Restores the session via `GET /api/auth/me` (cookie)
2. Shows a login screen when unauthenticated
3. Sends `credentials: 'include'` and `X-XSRF-TOKEN` on mutating requests
4. Uses **relative** `/api/...` paths (same-origin)

Recommended hosting: serve this build from Spring Boot (see `docs/DEPLOY_RENDER.md`). Do not rely on Render static-site rewrites as a cookie proxy.

## Build

```powershell
npm run build
```

## Data sources

The UI calls the live API only (no mock data): monitors CRUD, stats, checks, and incidents.
