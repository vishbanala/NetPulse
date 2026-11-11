import { isDemoMode } from '../demo/mode'
import {
  demoCreateMonitor,
  demoDeleteMonitor,
  demoFetchMonitorChecks,
  demoFetchMonitorIncidents,
  demoFetchMonitors,
  demoFetchMonitorStats,
  demoUpdateMonitor,
} from '../demo/demoStore'
import { apiDelete, apiGet, apiPost, apiPut } from './client'
import type {
  Incident,
  Monitor,
  MonitoringCheck,
  MonitorStats,
  MonitorWriteRequest,
} from './types'

export function fetchMonitors(): Promise<Monitor[]> {
  if (isDemoMode()) {
    return demoFetchMonitors()
  }
  return apiGet<Monitor[]>('/api/monitors')
}

export function fetchMonitorStats(monitorId: string): Promise<MonitorStats> {
  if (isDemoMode()) {
    return demoFetchMonitorStats(monitorId)
  }
  return apiGet<MonitorStats>(`/api/monitors/${monitorId}/stats`)
}

export function fetchMonitorChecks(monitorId: string, limit = 50): Promise<MonitoringCheck[]> {
  if (isDemoMode()) {
    return demoFetchMonitorChecks(monitorId, limit)
  }
  const q = new URLSearchParams({ limit: String(limit) })
  return apiGet<MonitoringCheck[]>(`/api/monitors/${monitorId}/checks?${q}`)
}

export function fetchMonitorIncidents(monitorId: string): Promise<Incident[]> {
  if (isDemoMode()) {
    return demoFetchMonitorIncidents(monitorId)
  }
  return apiGet<Incident[]>(`/api/monitors/${monitorId}/incidents`)
}

export function createMonitor(payload: MonitorWriteRequest): Promise<Monitor> {
  if (isDemoMode()) {
    return demoCreateMonitor(payload)
  }
  return apiPost<Monitor>('/api/monitors', payload)
}

export function updateMonitor(monitorId: string, payload: MonitorWriteRequest): Promise<Monitor> {
  if (isDemoMode()) {
    return demoUpdateMonitor(monitorId, payload)
  }
  return apiPut<Monitor>(`/api/monitors/${monitorId}`, payload)
}

export function deleteMonitor(monitorId: string): Promise<void> {
  if (isDemoMode()) {
    return demoDeleteMonitor(monitorId)
  }
  return apiDelete(`/api/monitors/${monitorId}`)
}
