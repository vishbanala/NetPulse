interface StatusBadgeProps {
  tone: 'ok' | 'bad' | 'warn' | 'neutral'
  label: string
}

const TONE_CLASS: Record<StatusBadgeProps['tone'], string> = {
  ok: 'bg-ok/15 text-ok ring-ok/25',
  bad: 'bg-bad/15 text-bad ring-bad/25',
  warn: 'bg-warn/15 text-warn ring-warn/25',
  neutral: 'bg-mist/10 text-mist ring-mist/20',
}

export function StatusBadge({ tone, label }: StatusBadgeProps) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 font-mono text-[11px] font-medium uppercase tracking-wide ring-1 ${TONE_CLASS[tone]}`}
    >
      <span className="size-1.5 rounded-full bg-current" aria-hidden />
      {label}
    </span>
  )
}
