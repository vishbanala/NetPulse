import { Clock3, Gauge, ShieldCheck } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

import type { MonitorStats } from '../api/types'
import { formatCount, formatMs, formatUptime } from '../utils/format'

interface SummaryCardsProps {
  stats: MonitorStats | null
}

interface CardSpec {
  label: string
  value: string
  hint: string
  icon: LucideIcon
}

export function SummaryCards({ stats }: SummaryCardsProps) {
  const cards: CardSpec[] = [
    {
      label: 'Uptime',
      value: formatUptime(stats?.uptimePercentage),
      hint:
        stats && stats.totalChecks > 0
          ? `${formatCount(stats.successfulChecks)} of ${formatCount(stats.totalChecks)} checks OK`
          : 'No checks recorded yet',
      icon: ShieldCheck,
    },
    {
      label: 'Avg response',
      value: formatMs(stats?.averageResponseTimeMs),
      hint:
        stats?.lastHttpStatus != null
          ? `Latest HTTP ${stats.lastHttpStatus}`
          : 'Waiting for the first sample',
      icon: Gauge,
    },
    {
      label: 'Total checks',
      value: formatCount(stats?.totalChecks ?? 0),
      hint:
        stats != null
          ? `${formatCount(stats.failedChecks)} failed`
          : 'Select a monitor to load stats',
      icon: Clock3,
    },
  ]

  return (
    <section aria-label="Summary metrics" className="grid gap-3 sm:grid-cols-3">
      {cards.map((card) => {
        const Icon = card.icon
        return (
          <article
            key={card.label}
            className="rounded-2xl border border-line/80 bg-panel/70 px-4 py-4 backdrop-blur-sm"
          >
            <div className="mb-3 flex items-center justify-between">
              <p className="text-xs font-medium uppercase tracking-[0.14em] text-mist">{card.label}</p>
              <Icon className="size-4 text-pulse" strokeWidth={1.75} />
            </div>
            <p className="font-mono text-3xl font-semibold tracking-tight text-snow">{card.value}</p>
            <p className="mt-1 text-sm text-mist">{card.hint}</p>
          </article>
        )
      })}
    </section>
  )
}
