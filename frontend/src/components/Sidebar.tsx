import { Activity, LayoutDashboard, Plus, RefreshCw } from 'lucide-react'

interface SidebarProps {
  onRefresh: () => void
  onAddMonitor: () => void
  refreshing?: boolean
}

export function Sidebar({ onRefresh, onAddMonitor, refreshing = false }: SidebarProps) {
  return (
    <aside className="flex w-full flex-col border-b border-line/80 bg-ink/40 px-5 py-5 backdrop-blur-md md:w-64 md:border-b-0 md:border-r md:px-6 md:py-8">
      <div className="mb-8">
        <div className="mb-3 flex items-center gap-2 text-pulse">
          <Activity className="size-5" strokeWidth={2.25} />
          <span className="font-mono text-[11px] font-semibold uppercase tracking-[0.22em]">
            Status at a glance
          </span>
        </div>
        <h1 className="font-display text-3xl font-extrabold tracking-tight text-snow md:text-4xl">
          NetPulse
        </h1>
        <p className="mt-2 text-sm leading-relaxed text-mist">
          Track uptime, response time, and outages for the sites you care about.
        </p>
      </div>

      <nav className="flex flex-1 flex-col gap-1" aria-label="Primary">
        <a
          href="#overview"
          className="flex items-center gap-3 rounded-xl bg-panel px-3 py-2.5 text-sm font-medium text-snow ring-1 ring-line"
        >
          <LayoutDashboard className="size-4 text-pulse" />
          Overview
        </a>
      </nav>

      <div className="mt-6 flex flex-col gap-2">
        <button
          type="button"
          onClick={onAddMonitor}
          className="inline-flex items-center justify-center gap-2 rounded-xl bg-pulse px-3 py-2.5 text-sm font-semibold text-ink transition hover:bg-pulse/90"
        >
          <Plus className="size-4" />
          Add monitor
        </button>
        <button
          type="button"
          onClick={onRefresh}
          disabled={refreshing}
          className="inline-flex items-center justify-center gap-2 rounded-xl border border-line bg-panel-2 px-3 py-2.5 text-sm font-medium text-snow transition hover:border-pulse/40 hover:text-pulse disabled:opacity-60"
        >
          <RefreshCw className={`size-4 ${refreshing ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>
    </aside>
  )
}
