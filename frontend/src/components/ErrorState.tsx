import { AlertTriangle, RefreshCw } from 'lucide-react'

interface ErrorStateProps {
  message: string
  onRetry?: () => void
}

export function ErrorState({ message, onRetry }: ErrorStateProps) {
  return (
    <div className="mx-auto flex min-h-[50vh] max-w-lg flex-col items-center justify-center gap-4 px-6 text-center">
      <div className="flex size-12 items-center justify-center rounded-2xl bg-bad/10 ring-1 ring-bad/30">
        <AlertTriangle className="size-5 text-bad" strokeWidth={1.75} />
      </div>
      <div>
        <p className="font-display text-xl font-semibold text-snow">Could not load dashboard</p>
        <p className="mt-2 text-sm leading-relaxed text-mist">{message}</p>
      </div>
      {onRetry ? (
        <button
          type="button"
          onClick={onRetry}
          className="inline-flex items-center gap-2 rounded-lg bg-pulse px-4 py-2 text-sm font-semibold text-ink transition hover:bg-pulse/90"
        >
          <RefreshCw className="size-4" />
          Retry
        </button>
      ) : null}
    </div>
  )
}
