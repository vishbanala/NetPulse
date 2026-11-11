export function formatMs(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) {
    return '—'
  }
  if (value < 10) {
    return `${value.toFixed(1)} ms`
  }
  return `${Math.round(value)} ms`
}

export function formatUptime(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) {
    return '—'
  }
  return `${value.toFixed(2)}%`
}

export function formatCount(value: number | null | undefined): string {
  if (value == null) {
    return '—'
  }
  return new Intl.NumberFormat('en-US').format(value)
}

export function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) {
    return '—'
  }
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}

/** Compact axis tick: includes month/day when the series spans more than one calendar day. */
export function formatChartTick(iso: string, includeDate = false): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  if (includeDate) {
    return new Intl.DateTimeFormat('en-US', {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(date)
  }
  return new Intl.DateTimeFormat('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

export function chartSpansMultipleDays(timestamps: string[]): boolean {
  const days = new Set<string>()
  for (const iso of timestamps) {
    const d = new Date(iso)
    if (!Number.isNaN(d.getTime())) {
      days.add(`${d.getUTCFullYear()}-${d.getUTCMonth()}-${d.getUTCDate()}`)
    }
    if (days.size > 1) {
      return true
    }
  }
  return false
}

export function hostnameFromUrl(url: string): string {
  try {
    return new URL(url).hostname
  } catch {
    return url
  }
}

export function formatInterval(seconds: number): string {
  if (seconds < 60) {
    return `${seconds}s`
  }
  if (seconds % 3600 === 0) {
    return `${seconds / 3600}h`
  }
  if (seconds % 60 === 0) {
    return `${seconds / 60}m`
  }
  return `${seconds}s`
}
