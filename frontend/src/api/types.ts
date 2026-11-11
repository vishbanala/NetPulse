/** Jackson JSON shapes from the Spring Boot monitors API. */

export interface Monitor {
  id: string
  name: string
  url: string
  intervalSeconds: number
  enabled: boolean
  createdAt: string
}

/** Matches CreateMonitorRequest on the backend. */
export interface MonitorWriteRequest {
  name: string
  url: string
  intervalSeconds: number
  enabled?: boolean
}

export interface MonitorStats {
  monitorId: string
  totalChecks: number
  successfulChecks: number
  failedChecks: number
  uptimePercentage: number | null
  averageResponseTimeMs: number | null
  lastCheckedAt: string | null
  lastCheckSuccess: boolean | null
  lastHttpStatus: number | null
}

export interface MonitoringCheck {
  id: string
  monitorId: string
  httpStatus: number | null
  responseTimeMs: number | null
  success: boolean
  checkedAt: string
}

export interface Incident {
  id: string
  monitorId: string
  startedAt: string
  resolvedAt: string | null
  reason: string | null
  status: 'open' | 'resolved' | string
}

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}
