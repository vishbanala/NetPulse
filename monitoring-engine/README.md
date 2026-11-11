# monitoring-engine

C11 HTTP(S) probe and AWS SQS worker for NetPulse.

| Binary | Role |
|--------|------|
| `netpulse-monitor` | CLI probe |
| `netpulse-worker` | Poll SQS → HTTP check → PostgreSQL via `netpulse_record_monitoring_check` |

## Dependencies

- **gcc**, **make**, **libcurl**, **pthreads**
- Worker: **libpq**; Windows CNG (`bcrypt`) or Linux **OpenSSL** (`-lcrypto`) for SigV4

JSON parsing is a small custom parser for the Java job payload (no AWS C++ SDK).

Credentials (never logged as secrets):

1. `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`
2. `~/.aws/credentials` + `AWS_PROFILE`
3. ECS container credentials URI (when present)

## Build & test

```powershell
$env:Path = "C:\msys64\ucrt64\bin;C:\msys64\usr\bin;" + $env:Path
cd monitoring-engine
make test
make worker
```

## Run worker

Set the same `NETPULSE_DB_*`, `AWS_REGION`, and `NETPULSE_SQS_QUEUE_URL` variables as the backend, then:

```powershell
.\build\netpulse-worker.exe
```

## Safety

- URL allowlist / private-network guards (SSRF protection)
- Deletes SQS messages only after a durable DB insert (or idempotent duplicate)
- Local development process — not a public endpoint

IAM notes for the worker role: `docs/IAM_STEP6_WORKER_POLICY.md`.
