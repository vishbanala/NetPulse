# frontend

React + TypeScript dashboard for NetPulse monitors, latency, and incidents.

## Stack

- React 19 + TypeScript + Vite
- Tailwind CSS v4
- Lucide React, Recharts

## Develop

Requires the Spring Boot API on `http://127.0.0.1:8080`. Vite proxies `/api` to that origin.

```powershell
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

## Build

```powershell
npm run build
```

## Data sources

The UI calls the live API only (no mock data): monitors CRUD, stats, checks, and incidents.
