# Step 6 — Minimal IAM additions for the C worker

## Current publisher permissions (already granted)

Your `netpulse-developer` policy can:

- `sqs:SendMessage`
- `sqs:GetQueueUrl`
- `sqs:GetQueueAttributes`

on `netpulse-monitoring-jobs`.

## Required additional permissions for the worker

The C worker must also:

| Action | Why |
|--------|-----|
| `sqs:ReceiveMessage` | Long-poll monitoring jobs |
| `sqs:DeleteMessage` | Remove message only after durable DB insert |
| `sqs:ChangeMessageVisibility` | Optional but recommended for long checks |

## Exact minimal policy statement to add

Replace `ACCOUNT_ID` with your account ID. Keep the resource scoped to this one queue.

```json
{
  "Sid": "NetPulseWorkerConsume",
  "Effect": "Allow",
  "Action": [
    "sqs:ReceiveMessage",
    "sqs:DeleteMessage",
    "sqs:ChangeMessageVisibility"
  ],
  "Resource": "arn:aws:sqs:us-east-2:ACCOUNT_ID:netpulse-monitoring-jobs"
}
```

If this is a separate IAM user/role for the worker later (recommended for ECS), grant only the three consume actions above plus `sqs:GetQueueAttributes` for health checks. Do **not** grant `SendMessage` to a production worker role unless needed.

## Queue attribute recommendation (existing queue — not a new resource)

Current `VisibilityTimeout` observed: **30 seconds**.  
HTTP checks may approach that limit. In the SQS console (or CLI), set:

- **Visibility timeout:** `90`–`120` seconds
- **Receive message wait time:** `20` seconds (long polling)

Example CLI (only after you approve):

```powershell
aws sqs set-queue-attributes `
  --queue-url $env:NETPULSE_SQS_QUEUE_URL `
  --attributes VisibilityTimeout=90,ReceiveMessageWaitTimeSeconds=20 `
  --region us-east-2 `
  --profile netpulse
```

`SetQueueAttributes` also needs IAM permission if done via this IAM user:

```json
"sqs:SetQueueAttributes"
```

You can instead change these values in the AWS Console without adding that API permission.

## STOP

Do **not** run the C worker against real SQS until:

1. The Receive/Delete(/ChangeVisibility) permissions above are attached.
2. Migration `002_monitoring_checks_job_id.sql` is applied in Supabase.
3. `libpq` is installed for the C build.
