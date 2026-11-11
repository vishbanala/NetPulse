import type {
  Incident,
  Monitor,
  MonitoringCheck,
  MonitorStats,
  MonitorWriteRequest,
} from '../api/types'
import { ApiError } from '../api/types'

const STORAGE_KEY = 'netpulse.demo.v1'

interface DemoState {
  monitors: Monitor[]
  checksByMonitor: Record<string, MonitoringCheck[]>
  incidentsByMonitor: Record<string, Incident[]>
}

function isoMinutesAgo(minutes: number): string {
  return new Date(Date.now() - minutes * 60_000).toISOString()
}

function uuid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `demo-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function buildChecks(
  monitorId: string,
  pattern: Array<{ minutesAgo: number; success: boolean; status: number; ms: number }>,
): MonitoringCheck[] {
  return pattern.map((p) => ({
    id: uuid(),
    monitorId,
    httpStatus: p.status,
    responseTimeMs: p.ms,
    success: p.success,
    checkedAt: isoMinutesAgo(p.minutesAgo),
  }))
}

function seedState(): DemoState {
  const docsId = '11111111-1111-4111-8111-111111111111'
  const apiId = '22222222-2222-4222-8222-222222222222'
  const statusId = '33333333-3333-4333-8333-333333333333'

  const docsChecks = buildChecks(docsId, [
    { minutesAgo: 90, success: true, status: 200, ms: 118 },
    { minutesAgo: 80, success: true, status: 200, ms: 132 },
    { minutesAgo: 70, success: true, status: 200, ms: 141 },
    { minutesAgo: 60, success: false, status: 503, ms: 2100 },
    { minutesAgo: 50, success: false, status: 503, ms: 1980 },
    { minutesAgo: 40, success: true, status: 200, ms: 156 },
    { minutesAgo: 30, success: true, status: 200, ms: 149 },
    { minutesAgo: 20, success: true, status: 200, ms: 128 },
    { minutesAgo: 10, success: true, status: 200, ms: 121 },
    { minutesAgo: 2, success: true, status: 200, ms: 115 },
  ])

  const apiChecks = buildChecks(apiId, [
    { minutesAgo: 75, success: true, status: 200, ms: 89 },
    { minutesAgo: 60, success: true, status: 200, ms: 94 },
    { minutesAgo: 45, success: true, status: 200, ms: 102 },
    { minutesAgo: 30, success: true, status: 200, ms: 97 },
    { minutesAgo: 15, success: true, status: 200, ms: 91 },
    { minutesAgo: 5, success: true, status: 200, ms: 88 },
  ])

  const statusChecks = buildChecks(statusId, [
    { minutesAgo: 100, success: true, status: 200, ms: 210 },
    { minutesAgo: 80, success: true, status: 200, ms: 198 },
    { minutesAgo: 60, success: true, status: 200, ms: 205 },
    { minutesAgo: 40, success: true, status: 200, ms: 220 },
    { minutesAgo: 20, success: true, status: 200, ms: 187 },
    { minutesAgo: 4, success: true, status: 200, ms: 193 },
  ])

  return {
    monitors: [
      {
        id: docsId,
        name: 'Docs site (sample)',
        url: 'https://example.com',
        intervalSeconds: 300,
        enabled: true,
        createdAt: isoMinutesAgo(60 * 24 * 14),
      },
      {
        id: apiId,
        name: 'Public API (sample)',
        url: 'https://httpbin.org/get',
        intervalSeconds: 300,
        enabled: true,
        createdAt: isoMinutesAgo(60 * 24 * 10),
      },
      {
        id: statusId,
        name: 'Status page (sample)',
        url: 'https://httpbin.org/status/200',
        intervalSeconds: 600,
        enabled: false,
        createdAt: isoMinutesAgo(60 * 24 * 7),
      },
    ],
    checksByMonitor: {
      [docsId]: docsChecks,
      [apiId]: apiChecks,
      [statusId]: statusChecks,
    },
    incidentsByMonitor: {
      [docsId]: [
        {
          id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
          monitorId: docsId,
          startedAt: isoMinutesAgo(60),
          resolvedAt: isoMinutesAgo(40),
          reason: 'Two consecutive failed checks',
          status: 'resolved',
        },
      ],
      [apiId]: [],
      [statusId]: [],
    },
  }
}

function readState(): DemoState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      const seeded = seedState()
      writeState(seeded)
      return seeded
    }
    const parsed = JSON.parse(raw) as DemoState
    if (!parsed.monitors || !parsed.checksByMonitor || !parsed.incidentsByMonitor) {
      const seeded = seedState()
      writeState(seeded)
      return seeded
    }
    return parsed
  } catch {
    const seeded = seedState()
    writeState(seeded)
    return seeded
  }
}

function writeState(state: DemoState): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
}

function delay<T>(value: T, ms = 180): Promise<T> {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(value), ms)
  })
}

function computeStats(monitorId: string, checks: MonitoringCheck[]): MonitorStats {
  const total = checks.length
  const successful = checks.filter((c) => c.success).length
  const failed = total - successful
  const withLatency = checks.filter((c) => c.responseTimeMs != null)
  const avg =
    withLatency.length === 0
      ? null
      : withLatency.reduce((sum, c) => sum + (c.responseTimeMs ?? 0), 0) / withLatency.length

  const latest = [...checks].sort(
    (a, b) => new Date(b.checkedAt).getTime() - new Date(a.checkedAt).getTime(),
  )[0]

  return {
    monitorId,
    totalChecks: total,
    successfulChecks: successful,
    failedChecks: failed,
    uptimePercentage: total === 0 ? null : (successful / total) * 100,
    averageResponseTimeMs: avg,
    lastCheckedAt: latest?.checkedAt ?? null,
    lastCheckSuccess: latest?.success ?? null,
    lastHttpStatus: latest?.httpStatus ?? null,
  }
}

function syntheticChecksForNewMonitor(monitorId: string): MonitoringCheck[] {
  return buildChecks(monitorId, [
    { minutesAgo: 25, success: true, status: 200, ms: 140 },
    { minutesAgo: 20, success: true, status: 200, ms: 132 },
    { minutesAgo: 15, success: true, status: 200, ms: 128 },
    { minutesAgo: 10, success: true, status: 200, ms: 135 },
    { minutesAgo: 5, success: true, status: 200, ms: 122 },
    { minutesAgo: 1, success: true, status: 200, ms: 119 },
  ])
}

export async function demoFetchMonitors(): Promise<Monitor[]> {
  const state = readState()
  return delay([...state.monitors].sort((a, b) => a.name.localeCompare(b.name)))
}

export async function demoFetchMonitorStats(monitorId: string): Promise<MonitorStats> {
  const state = readState()
  if (!state.monitors.some((m) => m.id === monitorId)) {
    throw new ApiError(404, 'Monitor not found')
  }
  const checks = state.checksByMonitor[monitorId] ?? []
  return delay(computeStats(monitorId, checks))
}

export async function demoFetchMonitorChecks(
  monitorId: string,
  limit = 50,
): Promise<MonitoringCheck[]> {
  const state = readState()
  if (!state.monitors.some((m) => m.id === monitorId)) {
    throw new ApiError(404, 'Monitor not found')
  }
  const checks = [...(state.checksByMonitor[monitorId] ?? [])].sort(
    (a, b) => new Date(b.checkedAt).getTime() - new Date(a.checkedAt).getTime(),
  )
  return delay(checks.slice(0, limit))
}

export async function demoFetchMonitorIncidents(monitorId: string): Promise<Incident[]> {
  const state = readState()
  if (!state.monitors.some((m) => m.id === monitorId)) {
    throw new ApiError(404, 'Monitor not found')
  }
  const incidents = [...(state.incidentsByMonitor[monitorId] ?? [])].sort(
    (a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime(),
  )
  return delay(incidents)
}

export async function demoCreateMonitor(payload: MonitorWriteRequest): Promise<Monitor> {
  const state = readState()
  const monitor: Monitor = {
    id: uuid(),
    name: payload.name.trim(),
    url: payload.url.trim(),
    intervalSeconds: payload.intervalSeconds,
    enabled: payload.enabled ?? true,
    createdAt: new Date().toISOString(),
  }
  state.monitors.push(monitor)
  state.checksByMonitor[monitor.id] = syntheticChecksForNewMonitor(monitor.id)
  state.incidentsByMonitor[monitor.id] = []
  writeState(state)
  return delay(monitor)
}

export async function demoUpdateMonitor(
  monitorId: string,
  payload: MonitorWriteRequest,
): Promise<Monitor> {
  const state = readState()
  const idx = state.monitors.findIndex((m) => m.id === monitorId)
  if (idx < 0) {
    throw new ApiError(404, 'Monitor not found')
  }
  const updated: Monitor = {
    ...state.monitors[idx],
    name: payload.name.trim(),
    url: payload.url.trim(),
    intervalSeconds: payload.intervalSeconds,
    enabled: payload.enabled ?? state.monitors[idx].enabled,
  }
  state.monitors[idx] = updated
  writeState(state)
  return delay(updated)
}

export async function demoDeleteMonitor(monitorId: string): Promise<void> {
  const state = readState()
  if (!state.monitors.some((m) => m.id === monitorId)) {
    throw new ApiError(404, 'Monitor not found')
  }
  state.monitors = state.monitors.filter((m) => m.id !== monitorId)
  delete state.checksByMonitor[monitorId]
  delete state.incidentsByMonitor[monitorId]
  writeState(state)
  await delay(undefined)
}
