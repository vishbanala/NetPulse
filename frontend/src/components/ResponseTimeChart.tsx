import { Activity } from 'lucide-react'
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

import type { MonitoringCheck } from '../api/types'
import {
  chartSpansMultipleDays,
  formatChartTick,
  formatMs,
  formatTimestamp,
} from '../utils/format'
import { EmptyState } from './EmptyState'

interface ResponseTimeChartProps {
  checks: MonitoringCheck[]
}

interface ChartPoint {
  checkedAt: string
  responseTimeMs: number
  success: boolean
}

export function ResponseTimeChart({ checks }: ResponseTimeChartProps) {
  const points: ChartPoint[] = [...checks]
    .filter((c) => c.responseTimeMs != null)
    .map((c) => ({
      checkedAt: c.checkedAt,
      responseTimeMs: c.responseTimeMs as number,
      success: c.success,
    }))
    .reverse()

  const includeDate = chartSpansMultipleDays(points.map((p) => p.checkedAt))

  return (
    <section
      aria-labelledby="latency-heading"
      className="rounded-2xl border border-line/80 bg-panel/70 p-4 backdrop-blur-sm md:p-5"
    >
      <div className="mb-4">
        <h2 id="latency-heading" className="font-display text-xl font-bold text-snow">
          Response time
        </h2>
        <p className="text-sm text-mist">How quickly this site has been responding lately.</p>
      </div>

      {points.length === 0 ? (
        <EmptyState
          icon={Activity}
          title="No response times yet"
          description="After the first successful checks run, you’ll see latency trends here."
        />
      ) : (
        <div className="h-64 w-full md:h-72">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={points} margin={{ top: 8, right: 12, left: 0, bottom: includeDate ? 8 : 0 }}>
              <CartesianGrid stroke="rgba(42,54,80,0.7)" strokeDasharray="3 3" vertical={false} />
              <XAxis
                dataKey="checkedAt"
                tickFormatter={(value: string) => formatChartTick(value, includeDate)}
                tick={{ fill: '#93a0bd', fontSize: 11, fontFamily: 'IBM Plex Mono' }}
                axisLine={{ stroke: '#2a3650' }}
                tickLine={false}
                minTickGap={includeDate ? 40 : 28}
                interval="preserveStartEnd"
              />
              <YAxis
                tickFormatter={(v: number) => `${Math.round(v)}`}
                width={48}
                tick={{ fill: '#93a0bd', fontSize: 11, fontFamily: 'IBM Plex Mono' }}
                axisLine={false}
                tickLine={false}
                unit="ms"
              />
              <Tooltip
                contentStyle={{
                  background: '#121a2c',
                  border: '1px solid #2a3650',
                  borderRadius: 12,
                  color: '#eef3ff',
                  fontFamily: 'IBM Plex Mono',
                  fontSize: 12,
                }}
                labelFormatter={(label) => formatTimestamp(String(label))}
                formatter={(value) => [formatMs(Number(value)), 'Response time']}
              />
              <Line
                type="monotone"
                dataKey="responseTimeMs"
                stroke="#2ec4b6"
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4, fill: '#2ec4b6' }}
                isAnimationActive={false}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  )
}
