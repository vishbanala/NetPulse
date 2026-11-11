import { Loader2, X } from 'lucide-react'
import { useEffect, useId, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'

import type { Monitor } from '../api/types'
import {
  hasFormErrors,
  toMonitorWriteRequest,
  validateMonitorForm,
  type MonitorFormErrors,
  type MonitorFormValues,
} from '../utils/validation'

interface MonitorFormModalProps {
  open: boolean
  mode: 'create' | 'edit'
  initial?: Monitor | null
  submitting?: boolean
  apiError?: string | null
  onClose: () => void
  onSubmit: (values: ReturnType<typeof toMonitorWriteRequest>) => void
}

function fromMonitor(monitor: Monitor | null | undefined): MonitorFormValues {
  if (!monitor) {
    return {
      name: '',
      url: 'https://',
      intervalSeconds: '60',
      enabled: true,
    }
  }
  return {
    name: monitor.name,
    url: monitor.url,
    intervalSeconds: String(monitor.intervalSeconds),
    enabled: monitor.enabled,
  }
}

export function MonitorFormModal({
  open,
  mode,
  initial,
  submitting = false,
  apiError = null,
  onClose,
  onSubmit,
}: MonitorFormModalProps) {
  const titleId = useId()
  const [values, setValues] = useState<MonitorFormValues>(fromMonitor(initial))
  const [errors, setErrors] = useState<MonitorFormErrors>({})

  useEffect(() => {
    if (open) {
      setValues(fromMonitor(initial))
      setErrors({})
    }
  }, [open, initial])

  useEffect(() => {
    if (!open) {
      return
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !submitting) {
        onClose()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, submitting, onClose])

  if (!open) {
    return null
  }

  const title = mode === 'create' ? 'Add monitor' : 'Edit monitor'

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    const nextErrors = validateMonitorForm(values)
    setErrors(nextErrors)
    if (hasFormErrors(nextErrors)) {
      return
    }
    onSubmit(toMonitorWriteRequest(values))
  }

  return (
    <div className="fixed inset-0 z-40 flex items-end justify-center p-0 sm:items-center sm:p-4">
      <button
        type="button"
        className="absolute inset-0 bg-ink/70 backdrop-blur-sm"
        aria-label="Close dialog"
        disabled={submitting}
        onClick={onClose}
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="relative max-h-[92vh] w-full overflow-y-auto rounded-t-3xl border border-line bg-panel shadow-2xl sm:max-w-lg sm:rounded-3xl"
      >
        <div className="flex items-start justify-between gap-3 border-b border-line px-5 py-4">
          <div>
            <h2 id={titleId} className="font-display text-2xl font-bold text-snow">
              {title}
            </h2>
            <p className="mt-1 text-sm text-mist">
              We’ll check this website on the interval you choose.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="rounded-lg p-2 text-mist transition hover:bg-panel-2 hover:text-snow disabled:opacity-60"
            aria-label="Close"
          >
            <X className="size-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4 px-5 py-5" noValidate>
          <Field
            label="Monitor name"
            error={errors.name}
            htmlFor="monitor-name"
          >
            <input
              id="monitor-name"
              value={values.name}
              onChange={(e) => setValues((v) => ({ ...v, name: e.target.value }))}
              placeholder="Homepage"
              maxLength={200}
              className={inputClass(Boolean(errors.name))}
              autoFocus
            />
          </Field>

          <Field label="Website URL" error={errors.url} htmlFor="monitor-url">
            <input
              id="monitor-url"
              value={values.url}
              onChange={(e) => setValues((v) => ({ ...v, url: e.target.value }))}
              placeholder="https://example.com"
              maxLength={2048}
              inputMode="url"
              className={inputClass(Boolean(errors.url))}
            />
          </Field>

          <Field
            label="Check interval (seconds)"
            error={errors.intervalSeconds}
            htmlFor="monitor-interval"
            hint="Between 10 and 86400 seconds"
          >
            <input
              id="monitor-interval"
              value={values.intervalSeconds}
              onChange={(e) => setValues((v) => ({ ...v, intervalSeconds: e.target.value }))}
              inputMode="numeric"
              className={inputClass(Boolean(errors.intervalSeconds))}
            />
          </Field>

          <label className="flex items-center gap-3 rounded-xl border border-line bg-panel-2/60 px-3 py-3">
            <input
              type="checkbox"
              checked={values.enabled}
              onChange={(e) => setValues((v) => ({ ...v, enabled: e.target.checked }))}
              className="size-4 accent-pulse"
            />
            <span className="text-sm text-snow">
              Enable monitoring
              <span className="mt-0.5 block text-xs text-mist">
                Disabled monitors stay in your list but are not checked.
              </span>
            </span>
          </label>

          {apiError ? (
            <p className="rounded-xl border border-bad/30 bg-bad/10 px-3 py-2 text-sm text-bad">
              {apiError}
            </p>
          ) : null}

          <div className="flex flex-col-reverse gap-2 pt-1 sm:flex-row sm:justify-end">
            <button
              type="button"
              disabled={submitting}
              onClick={onClose}
              className="rounded-xl border border-line px-4 py-2.5 text-sm font-medium text-snow transition hover:bg-panel-2 disabled:opacity-60"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="inline-flex items-center justify-center gap-2 rounded-xl bg-pulse px-4 py-2.5 text-sm font-semibold text-ink transition hover:bg-pulse/90 disabled:opacity-60"
            >
              {submitting ? <Loader2 className="size-4 animate-spin" /> : null}
              {mode === 'create' ? 'Create monitor' : 'Save changes'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

function Field({
  label,
  htmlFor,
  error,
  hint,
  children,
}: {
  label: string
  htmlFor: string
  error?: string
  hint?: string
  children: ReactNode
}) {
  return (
    <div>
      <label htmlFor={htmlFor} className="mb-1.5 block text-sm font-medium text-snow">
        {label}
      </label>
      {children}
      {hint && !error ? <p className="mt-1 text-xs text-mist">{hint}</p> : null}
      {error ? <p className="mt-1 text-xs text-bad">{error}</p> : null}
    </div>
  )
}

function inputClass(invalid: boolean): string {
  return `w-full rounded-xl border bg-ink-2 px-3 py-2.5 text-sm text-snow outline-none transition placeholder:text-mist/50 focus:ring-2 ${
    invalid
      ? 'border-bad/50 focus:ring-bad/30'
      : 'border-line focus:border-pulse/50 focus:ring-pulse/20'
  }`
}
