import { AlertCircle, CheckCircle2, X } from 'lucide-react'

export type ToastTone = 'success' | 'error'

export interface ToastMessage {
  id: string
  tone: ToastTone
  title: string
  detail?: string
}

interface ToastStackProps {
  toasts: ToastMessage[]
  onDismiss: (id: string) => void
}

export function ToastStack({ toasts, onDismiss }: ToastStackProps) {
  if (toasts.length === 0) {
    return null
  }

  return (
    <div
      className="pointer-events-none fixed right-4 top-4 z-50 flex w-[min(100%-2rem,22rem)] flex-col gap-2"
      aria-live="polite"
    >
      {toasts.map((toast) => {
        const ok = toast.tone === 'success'
        return (
          <div
            key={toast.id}
            className={`pointer-events-auto flex gap-3 rounded-2xl border px-4 py-3 shadow-lg backdrop-blur-md ${
              ok
                ? 'border-ok/30 bg-panel/95 text-snow'
                : 'border-bad/35 bg-panel/95 text-snow'
            }`}
            role="status"
          >
            {ok ? (
              <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-ok" />
            ) : (
              <AlertCircle className="mt-0.5 size-4 shrink-0 text-bad" />
            )}
            <div className="min-w-0 flex-1">
              <p className="text-sm font-semibold">{toast.title}</p>
              {toast.detail ? <p className="mt-0.5 text-xs text-mist">{toast.detail}</p> : null}
            </div>
            <button
              type="button"
              onClick={() => onDismiss(toast.id)}
              className="rounded-md p-1 text-mist transition hover:bg-panel-2 hover:text-snow"
              aria-label="Dismiss notification"
            >
              <X className="size-4" />
            </button>
          </div>
        )
      })}
    </div>
  )
}
