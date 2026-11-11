import type { LucideIcon } from 'lucide-react'

interface EmptyStateProps {
  icon: LucideIcon
  title: string
  description: string
}

export function EmptyState({ icon: Icon, title, description }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 px-6 py-14 text-center">
      <div className="flex size-12 items-center justify-center rounded-2xl bg-panel-2 ring-1 ring-line">
        <Icon className="size-5 text-mist" strokeWidth={1.75} />
      </div>
      <div>
        <p className="font-display text-lg font-semibold text-snow">{title}</p>
        <p className="mt-1 max-w-sm text-sm text-mist">{description}</p>
      </div>
    </div>
  )
}
