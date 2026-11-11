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

## Build

```powershell
npm run build
```

## Data sources

The UI calls the live API only (no mock data): monitors CRUD, stats, checks, and incidents.
