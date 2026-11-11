import { useCallback, useEffect, useRef, useState } from 'react'

import {
  fetchMonitorChecks,
  fetchMonitorIncidents,
  fetchMonitors,
  fetchMonitorStats,
} from '../api/monitors'
import { ApiError } from '../api/types'
import type { Incident, Monitor, MonitoringCheck, MonitorStats } from '../api/types'

export interface MonitorDashboardData {
  monitors: Monitor[]
  selectedId: string | null
  stats: MonitorStats | null
  checks: MonitoringCheck[]
  incidents: Incident[]
}

interface DashboardState {
  data: MonitorDashboardData | null
  loading: boolean
  error: string | null
}

export function useMonitorDashboard() {
  const [state, setState] = useState<DashboardState>({
    data: null,
    loading: true,
    error: null,
  })
  const selectedIdRef = useRef<string | null>(null)

  const load = useCallback(async (preferredId?: string | null) => {
    setState((prev) => ({ ...prev, loading: true, error: null }))
    try {
      const monitors = await fetchMonitors()
      if (monitors.length === 0) {
        selectedIdRef.current = null
        setState({
          loading: false,
          error: null,
          data: {
            monitors: [],
            selectedId: null,
            stats: null,
            checks: [],
            incidents: [],
          },
        })
        return
      }

      const current = selectedIdRef.current
      const nextId =
        (preferredId && monitors.some((m) => m.id === preferredId) ? preferredId : null) ??
        (current && monitors.some((m) => m.id === current) ? current : null) ??
        monitors[0].id

      selectedIdRef.current = nextId

      const [stats, checks, incidents] = await Promise.all([
        fetchMonitorStats(nextId),
        fetchMonitorChecks(nextId, 50),
        fetchMonitorIncidents(nextId),
      ])

      setState({
        loading: false,
        error: null,
        data: {
          monitors,
          selectedId: nextId,
          stats,
          checks,
          incidents,
        },
      })
    } catch (err) {
      const message =
        err instanceof ApiError
          ? err.message
          : err instanceof Error
            ? err.message
            : 'Failed to load dashboard'
      setState({ data: null, loading: false, error: message })
    }
  }, [])

  useEffect(() => {
    void load(null)
  }, [load])

  const selectMonitor = useCallback(
    (id: string) => {
      if (id === selectedIdRef.current) {
        return
      }
      void load(id)
    },
    [load],
  )

  const refresh = useCallback(async (preferredId?: string | null) => {
    await load(preferredId === undefined ? selectedIdRef.current : preferredId)
  }, [load])

  return {
    ...state,
    selectedId: state.data?.selectedId ?? null,
    selectMonitor,
    refresh,
  }
}
