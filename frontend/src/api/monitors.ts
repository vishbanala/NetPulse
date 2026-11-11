import { apiDelete, apiGet, apiPost, apiPut } from './client'
import type {
  Incident,
  Monitor,
  MonitoringCheck,
  MonitorStats,
  MonitorWriteRequest,
} from './types'

export function fetchMonitors(): Promise<Monitor[]> {
  return apiGet<Monitor[]>('/api/monitors')
}

export function fetchMonitorStats(monitorId: string): Promise<MonitorStats> {
  return apiGet<MonitorStats>(`/api/monitors/${monitorId}/stats`)
}

export function fetchMonitorChecks(monitorId: string, limit = 50): Promise<MonitoringCheck[]> {
  const q = new URLSearchParams({ limit: String(limit) })
  return apiGet<MonitoringCheck[]>(`/api/monitors/${monitorId}/checks?${q}`)
}

export function fetchMonitorIncidents(monitorId: string): Promise<Incident[]> {
  return apiGet<Incident[]>(`/api/monitors/${monitorId}/incidents`)
}

export function createMonitor(payload: MonitorWriteRequest): Promise<Monitor> {
  return apiPost<Monitor>('/api/monitors', payload)
}

export function updateMonitor(monitorId: string, payload: MonitorWriteRequest): Promise<Monitor> {
  return apiPut<Monitor>(`/api/monitors/${monitorId}`, payload)
}

export function deleteMonitor(monitorId: string): Promise<void> {
  return apiDelete(`/api/monitors/${monitorId}`)
}
