import { Globe2, Loader2, Pencil, Power, Trash2 } from 'lucide-react'
import type { ReactNode } from 'react'

import type { Monitor, MonitorStats } from '../api/types'
import { formatInterval, hostnameFromUrl } from '../utils/format'
import { StatusBadge } from './StatusBadge'

interface MonitorListProps {
  monitors: Monitor[]
  selectedId: string | null
  selectedStats: MonitorStats | null
  busyId?: string | null
  onSelect: (id: string) => void
  onEdit: (monitor: Monitor) => void
  onToggleEnabled: (monitor: Monitor) => void
  onDelete: (monitor: Monitor) => void
}

function healthTone(monitor: Monitor, stats: MonitorStats | null, selected: boolean) {
  if (!monitor.enabled) {
    return { tone: 'neutral' as const, label: 'Paused' }
  }
  if (!selected || !stats) {
    return { tone: 'neutral' as const, label: 'Active' }
  }
  if (stats.totalChecks === 0) {
    return { tone: 'warn' as const, label: 'Waiting' }
  }
  if (stats.lastCheckSuccess === false) {
    return { tone: 'bad' as const, label: 'Down' }
  }
  if (stats.lastCheckSuccess === true) {
    return { tone: 'ok' as const, label: 'Healthy' }
  }
  return { tone: 'neutral' as const, label: 'Unknown' }
}

export function MonitorList({
  monitors,
  selectedId,
  selectedStats,
  busyId = null,
  onSelect,
  onEdit,
  onToggleEnabled,
  onDelete,
}: MonitorListProps) {
  if (monitors.length === 0) {
    return null
  }

  return (
    <section aria-labelledby="monitors-heading" className="space-y-3">
      <div className="flex items-end justify-between gap-3">
        <div>
          <h2 id="monitors-heading" className="font-display text-xl font-bold text-snow">
            Your websites
          </h2>
          <p className="text-sm text-mist">Choose a monitor to view health, latency, and recent checks.</p>
        </div>
        <span className="font-mono text-xs text-mist">{monitors.length} total</span>
      </div>

      <ul className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
        {monitors.map((monitor) => {
          const selected = monitor.id === selectedId
          const health = healthTone(monitor, selectedStats, selected)
          const busy = busyId === monitor.id
          return (
            <li key={monitor.id}>
              <div
                className={`rounded-2xl border px-4 py-3 transition ${
                  selected
                    ? 'border-pulse/50 bg-panel shadow-[0_0_0_1px_rgba(46,196,182,0.15)]'
                    : 'border-line/80 bg-panel/60 hover:border-mist/30 hover:bg-panel'
                }`}
              >
                <button
                  type="button"
                  onClick={() => onSelect(monitor.id)}
                  className="w-full text-left"
                  disabled={busy}
                >
                  <div className="mb-2 flex items-start justify-between gap-2">
                    <div className="flex min-w-0 items-center gap-2">
                      <Globe2 className={`size-4 shrink-0 ${selected ? 'text-pulse' : 'text-mist'}`} />
                      <span className="truncate font-medium text-snow">{monitor.name}</span>
                    </div>
                    <StatusBadge tone={health.tone} label={health.label} />
                  </div>
                  <p className="truncate font-mono text-xs text-mist">{hostnameFromUrl(monitor.url)}</p>
                  <p className="mt-2 font-mono text-[11px] text-mist/80">
                    every {formatInterval(monitor.intervalSeconds)}
                  </p>
                </button>

                <div className="mt-3 flex items-center gap-1 border-t border-line/60 pt-3">
                  <ActionButton
                    label="Edit"
                    disabled={busy}
                    onClick={() => onEdit(monitor)}
                    icon={<Pencil className="size-3.5" />}
                  />
                  <ActionButton
                    label={monitor.enabled ? 'Pause' : 'Resume'}
                    disabled={busy}
                    onClick={() => onToggleEnabled(monitor)}
                    icon={
                      busy ? (
                        <Loader2 className="size-3.5 animate-spin" />
                      ) : (
                        <Power className="size-3.5" />
                      )
                    }
                  />
                  <ActionButton
                    label="Delete"
                    danger
                    disabled={busy}
                    onClick={() => onDelete(monitor)}
                    icon={<Trash2 className="size-3.5" />}
                  />
                </div>
              </div>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function ActionButton({
  label,
  icon,
  onClick,
  disabled,
  danger = false,
}: {
  label: string
  icon: ReactNode
  onClick: () => void
  disabled?: boolean
  danger?: boolean
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className={`inline-flex flex-1 items-center justify-center gap-1.5 rounded-lg px-2 py-1.5 text-xs font-medium transition disabled:opacity-50 ${
        danger
          ? 'text-bad hover:bg-bad/10'
          : 'text-mist hover:bg-panel-2 hover:text-snow'
      }`}
    >
      {icon}
      <span className="hidden sm:inline">{label}</span>
    </button>
  )
}
