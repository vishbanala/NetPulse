import { ShieldAlert } from 'lucide-react'

import type { Incident } from '../api/types'
import { formatTimestamp } from '../utils/format'
import { EmptyState } from './EmptyState'
import { StatusBadge } from './StatusBadge'

interface IncidentsSectionProps {
  incidents: Incident[]
}

export function IncidentsSection({ incidents }: IncidentsSectionProps) {
  return (
    <section
      aria-labelledby="incidents-heading"
      className="rounded-2xl border border-line/80 bg-panel/70 backdrop-blur-sm"
    >
      <div className="border-b border-line/80 px-4 py-4 md:px-5">
        <h2 id="incidents-heading" className="font-display text-xl font-bold text-snow">
          Incidents
        </h2>
        <p className="text-sm text-mist">Outages detected for this monitor.</p>
      </div>

      {incidents.length === 0 ? (
        <EmptyState
          icon={ShieldAlert}
          title="No incidents"
          description="Everything looks calm — there are no open or past incidents for this site."
        />
      ) : (
        <ul className="divide-y divide-line/60">
          {incidents.map((incident) => {
            const open = incident.status === 'open' || incident.resolvedAt == null
            return (
              <li
                key={incident.id}
                className="flex flex-col gap-2 px-4 py-4 md:flex-row md:items-center md:justify-between md:px-5"
              >
                <div className="min-w-0">
                  <div className="mb-1 flex items-center gap-2">
                    <StatusBadge tone={open ? 'bad' : 'ok'} label={open ? 'Open' : 'Resolved'} />
                    <span className="truncate text-sm text-snow">
                      {incident.reason?.trim() || 'Unspecified reason'}
                    </span>
                  </div>
                  <p className="font-mono text-[11px] text-mist">
                    Started {formatTimestamp(incident.startedAt)}
                    {incident.resolvedAt
                      ? ` · Ended ${formatTimestamp(incident.resolvedAt)}`
                      : ' · Ongoing'}
                  </p>
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
