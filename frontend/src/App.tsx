import { Plus, RadioTower } from 'lucide-react'
import { useState } from 'react'

import { ApiError } from './api/types'
import type { Monitor, MonitorWriteRequest } from './api/types'
import { createMonitor, deleteMonitor, updateMonitor } from './api/monitors'
import { ChecksTable } from './components/ChecksTable'
import { ConfirmDialog } from './components/ConfirmDialog'
import { DemoModeBanner } from './components/DemoModeBanner'
import { EmptyState } from './components/EmptyState'
import { ErrorState } from './components/ErrorState'
import { IncidentsSection } from './components/IncidentsSection'
import { LoadingState } from './components/LoadingState'
import { MonitorFormModal } from './components/MonitorFormModal'
import { MonitorList } from './components/MonitorList'
import { ResponseTimeChart } from './components/ResponseTimeChart'
import { Sidebar } from './components/Sidebar'
import { SummaryCards } from './components/SummaryCards'
import { ToastStack } from './components/Toast'
import { isDemoMode } from './demo/mode'
import { useMonitorDashboard } from './hooks/useMonitorDashboard'
import { useToasts } from './hooks/useToasts'
import { formatTimestamp } from './utils/format'

type FormMode = 'create' | 'edit'

export default function App() {
  const demoMode = isDemoMode()
  const { data, loading, error, selectMonitor, refresh } = useMonitorDashboard()
  const { toasts, push, dismiss } = useToasts()

  const [formOpen, setFormOpen] = useState(false)
  const [formMode, setFormMode] = useState<FormMode>('create')
  const [editing, setEditing] = useState<Monitor | null>(null)
  const [formSubmitting, setFormSubmitting] = useState(false)
  const [formApiError, setFormApiError] = useState<string | null>(null)

  const [deleteTarget, setDeleteTarget] = useState<Monitor | null>(null)
  const [deleteSubmitting, setDeleteSubmitting] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)

  const selectedMonitor =
    data?.monitors.find((m) => m.id === data.selectedId) ?? null

  const openCreate = () => {
    setFormMode('create')
    setEditing(null)
    setFormApiError(null)
    setFormOpen(true)
  }

  const openEdit = (monitor: Monitor) => {
    setFormMode('edit')
    setEditing(monitor)
    setFormApiError(null)
    setFormOpen(true)
  }

  const closeForm = () => {
    if (formSubmitting) {
      return
    }
    setFormOpen(false)
    setFormApiError(null)
  }

  const handleFormSubmit = async (payload: MonitorWriteRequest) => {
    setFormSubmitting(true)
    setFormApiError(null)
    try {
      if (formMode === 'create') {
        const created = await createMonitor(payload)
        setFormOpen(false)
        push('success', 'Monitor created', created.name)
        await refresh(created.id)
      } else if (editing) {
        const updated = await updateMonitor(editing.id, payload)
        setFormOpen(false)
        push('success', 'Monitor updated', updated.name)
        await refresh(updated.id)
      }
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Something went wrong'
      setFormApiError(message)
      push('error', formMode === 'create' ? 'Could not create monitor' : 'Could not update monitor', message)
    } finally {
      setFormSubmitting(false)
    }
  }

  const handleToggleEnabled = async (monitor: Monitor) => {
    setBusyId(monitor.id)
    try {
      const updated = await updateMonitor(monitor.id, {
        name: monitor.name,
        url: monitor.url,
        intervalSeconds: monitor.intervalSeconds,
        enabled: !monitor.enabled,
      })
      push(
        'success',
        updated.enabled ? 'Monitoring resumed' : 'Monitoring paused',
        updated.name,
      )
      await refresh(monitor.id)
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Something went wrong'
      push('error', 'Could not update status', message)
    } finally {
      setBusyId(null)
    }
  }

  const handleConfirmDelete = async () => {
    if (!deleteTarget) {
      return
    }
    const target = deleteTarget
    setDeleteSubmitting(true)
    try {
      await deleteMonitor(target.id)
      setDeleteTarget(null)
      push('success', 'Monitor deleted', target.name)
      const keepId =
        data?.selectedId === target.id ? null : (data?.selectedId ?? null)
      await refresh(keepId)
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Something went wrong'
      push('error', 'Could not delete monitor', message)
    } finally {
      setDeleteSubmitting(false)
    }
  }

  return (
    <div className="min-h-screen md:flex md:flex-col">
      <ToastStack toasts={toasts} onDismiss={dismiss} />
      {demoMode ? <DemoModeBanner /> : null}

      <div className="flex min-h-0 flex-1 flex-col md:flex-row">
      <Sidebar
        onRefresh={() => void refresh()}
        onAddMonitor={openCreate}
        refreshing={loading && data != null}
        demoMode={demoMode}
      />

      <main id="overview" className="flex-1 px-4 py-6 md:px-8 md:py-8">
        {loading && data == null ? <LoadingState /> : null}

        {!loading && error ? (
          <ErrorState message={error} onRetry={() => void refresh()} />
        ) : null}

        {data && !error ? (
          data.monitors.length === 0 ? (
            <div className="mx-auto flex max-w-lg flex-col items-center gap-4 py-16">
              <EmptyState
                icon={RadioTower}
                title="No monitors yet"
                description="Add your first website to start tracking uptime and response time."
              />
              <button
                type="button"
                onClick={openCreate}
                className="inline-flex items-center gap-2 rounded-xl bg-pulse px-4 py-2.5 text-sm font-semibold text-ink transition hover:bg-pulse/90"
              >
                <Plus className="size-4" />
                Add monitor
              </button>
            </div>
          ) : (
            <div className="mx-auto flex max-w-6xl flex-col gap-6">
              <header className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="space-y-1">
                  <p className="font-mono text-[11px] uppercase tracking-[0.2em] text-pulse">
                    Selected monitor
                  </p>
                  <h2 className="font-display text-2xl font-bold text-snow md:text-3xl">
                    {selectedMonitor?.name ?? 'Monitor'}
                  </h2>
                  <p className="truncate text-sm text-mist">{selectedMonitor?.url}</p>
                  {data.stats?.lastCheckedAt ? (
                    <p className="font-mono text-xs text-mist/80">
                      Last check {formatTimestamp(data.stats.lastCheckedAt)}
                      {demoMode ? ' · sample' : ''}
                    </p>
                  ) : null}
                </div>
                {selectedMonitor ? (
                  <div className="flex flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={() => openEdit(selectedMonitor)}
                      className="rounded-xl border border-line px-3 py-2 text-sm font-medium text-snow transition hover:bg-panel"
                    >
                      Edit
                    </button>
                    <button
                      type="button"
                      onClick={() => void handleToggleEnabled(selectedMonitor)}
                      disabled={busyId === selectedMonitor.id}
                      className="rounded-xl border border-line px-3 py-2 text-sm font-medium text-snow transition hover:bg-panel disabled:opacity-60"
                    >
                      {selectedMonitor.enabled ? 'Pause' : 'Resume'}
                    </button>
                  </div>
                ) : null}
              </header>

              <MonitorList
                monitors={data.monitors}
                selectedId={data.selectedId}
                selectedStats={data.stats}
                busyId={busyId}
                onSelect={selectMonitor}
                onEdit={openEdit}
                onToggleEnabled={(m) => void handleToggleEnabled(m)}
                onDelete={setDeleteTarget}
              />

              <SummaryCards stats={data.stats} />

              <ResponseTimeChart checks={data.checks} />

              <div className="grid gap-6 xl:grid-cols-5">
                <div className="xl:col-span-3">
                  <ChecksTable checks={data.checks} />
                </div>
                <div className="xl:col-span-2">
                  <IncidentsSection incidents={data.incidents} />
                </div>
              </div>
            </div>
          )
        ) : null}
      </main>
      </div>

      <MonitorFormModal
        open={formOpen}
        mode={formMode}
        initial={editing}
        submitting={formSubmitting}
        apiError={formApiError}
        onClose={closeForm}
        onSubmit={(payload) => void handleFormSubmit(payload)}
      />

      <ConfirmDialog
        open={deleteTarget != null}
        title="Delete monitor?"
        description={
          deleteTarget
            ? `“${deleteTarget.name}” and its check history will be removed. This cannot be undone.`
            : ''
        }
        confirmLabel="Delete monitor"
        loading={deleteSubmitting}
        onCancel={() => {
          if (!deleteSubmitting) {
            setDeleteTarget(null)
          }
        }}
        onConfirm={() => void handleConfirmDelete()}
      />
    </div>
  )
}
