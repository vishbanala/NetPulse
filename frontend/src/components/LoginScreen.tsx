import { Activity, LogIn } from 'lucide-react'
import { useState } from 'react'
import type { FormEvent } from 'react'

import { ApiError } from '../api/types'

interface LoginScreenProps {
  onLogin: (username: string, password: string) => Promise<void>
  bootstrapError?: string | null
}

export function LoginScreen({ onLogin, bootstrapError }: LoginScreenProps) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await onLogin(username.trim(), password)
    } catch (err) {
      const message =
        err instanceof ApiError
          ? err.message
          : err instanceof Error
            ? err.message
            : 'Login failed'
      setError(message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4 py-10">
      <div className="w-full max-w-md rounded-2xl border border-line bg-panel/80 p-6 shadow-[0_20px_60px_rgba(0,0,0,0.35)] backdrop-blur md:p-8">
        <div className="mb-6 flex items-center gap-2 text-pulse">
          <Activity className="size-5" strokeWidth={2.25} />
          <span className="font-mono text-[11px] font-semibold uppercase tracking-[0.22em]">
            NetPulse admin
          </span>
        </div>
        <h1 className="font-display text-3xl font-extrabold tracking-tight text-snow">Sign in</h1>
        <p className="mt-2 text-sm leading-relaxed text-mist">
          Authenticated session uses HttpOnly cookies and CSRF protection. Credentials are never
          stored in localStorage.
        </p>

        <form className="mt-6 space-y-4" onSubmit={(e) => void handleSubmit(e)}>
          <label className="block space-y-1.5">
            <span className="text-xs font-medium uppercase tracking-wide text-mist">Username</span>
            <input
              name="username"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              className="w-full rounded-xl border border-line bg-ink-2 px-3 py-2.5 text-sm text-snow outline-none ring-pulse/40 focus:ring-2"
            />
          </label>
          <label className="block space-y-1.5">
            <span className="text-xs font-medium uppercase tracking-wide text-mist">Password</span>
            <input
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              className="w-full rounded-xl border border-line bg-ink-2 px-3 py-2.5 text-sm text-snow outline-none ring-pulse/40 focus:ring-2"
            />
          </label>

          {bootstrapError ? (
            <p className="rounded-lg border border-warn/30 bg-warn/10 px-3 py-2 text-sm text-warn">
              {bootstrapError}
            </p>
          ) : null}
          {error ? (
            <p className="rounded-lg border border-bad/30 bg-bad/10 px-3 py-2 text-sm text-bad" role="alert">
              {error}
            </p>
          ) : null}

          <button
            type="submit"
            disabled={submitting}
            className="inline-flex w-full items-center justify-center gap-2 rounded-xl bg-pulse px-3 py-2.5 text-sm font-semibold text-ink transition hover:bg-pulse/90 disabled:opacity-60"
          >
            <LogIn className="size-4" />
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </div>
    </div>
  )
}
