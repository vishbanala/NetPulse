import { ListChecks } from 'lucide-react'

import type { MonitoringCheck } from '../api/types'
import { formatMs, formatTimestamp } from '../utils/format'
import { EmptyState } from './EmptyState'
import { StatusBadge } from './StatusBadge'

interface ChecksTableProps {
  checks: MonitoringCheck[]
}

export function ChecksTable({ checks }: ChecksTableProps) {
  return (
    <section
      aria-labelledby="checks-heading"
      className="rounded-2xl border border-line/80 bg-panel/70 backdrop-blur-sm"
    >
      <div className="border-b border-line/80 px-4 py-4 md:px-5">
        <h2 id="checks-heading" className="font-display text-xl font-bold text-snow">
          Recent checks
        </h2>
        <p className="text-sm text-mist">Latest results for this website, newest first.</p>
      </div>

      {checks.length === 0 ? (
        <EmptyState
          icon={ListChecks}
          title="No checks yet"
          description="Results will show up here after NetPulse runs its first check."
        />
      ) : (
        <div className="overflow-x-auto">
          <table className="min-w-full text-left text-sm">
            <thead className="bg-panel-2/80 font-mono text-[11px] uppercase tracking-wider text-mist">
              <tr>
                <th className="px-4 py-3 font-medium md:px-5">Time</th>
                <th className="px-4 py-3 font-medium">Result</th>
                <th className="px-4 py-3 font-medium">HTTP</th>
                <th className="px-4 py-3 font-medium">Latency</th>
              </tr>
            </thead>
            <tbody>
              {checks.map((check) => (
                <tr key={check.id} className="border-t border-line/60">
                  <td className="px-4 py-3 font-mono text-xs text-snow/90 md:px-5">
                    {formatTimestamp(check.checkedAt)}
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge
                      tone={check.success ? 'ok' : 'bad'}
                      label={check.success ? 'Up' : 'Down'}
                    />
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-mist">
                    {check.httpStatus ?? '—'}
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-mist">
                    {formatMs(check.responseTimeMs)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
