import { Activity, LogIn, UserPlus } from 'lucide-react'
import { useState } from 'react'
import type { FormEvent } from 'react'

import { ApiError } from '../api/types'

type AuthTab = 'login' | 'register'

interface LoginScreenProps {
  onLogin: (email: string, password: string) => Promise<void>
  onRegister: (email: string, password: string) => Promise<{ message: string }>
  onForgotPassword?: (email: string) => Promise<void>
  passwordResetEnabled?: boolean
  bootstrapError?: string | null
}

export function LoginScreen({
  onLogin,
  onRegister,
  onForgotPassword,
  passwordResetEnabled = false,
  bootstrapError,
}: LoginScreenProps) {
  const [tab, setTab] = useState<AuthTab>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    setInfo(null)
    try {
      if (tab === 'login') {
        await onLogin(email.trim(), password)
      } else {
        const result = await onRegister(email.trim(), password)
        setInfo(result.message)
        setTab('login')
        setPassword('')
      }
    } catch (err) {
      const message =
        err instanceof ApiError
          ? err.message
          : err instanceof Error
            ? err.message
            : tab === 'login'
              ? 'Login failed'
              : 'Registration failed'
      setError(message)
    } finally {
      setSubmitting(false)
    }
  }

  const handleForgot = async () => {
    if (!onForgotPassword || !passwordResetEnabled) {
      return
    }
    const trimmed = email.trim()
    if (!trimmed) {
      setError('Enter your email above, then request a reset link.')
      return
    }
    setSubmitting(true)
    setError(null)
    setInfo(null)
    try {
      await onForgotPassword(trimmed)
      setInfo('If that email exists, a reset link will be sent when mail is configured.')
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Could not start password reset'
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
            NetPulse
          </span>
        </div>
        <h1 className="font-display text-3xl font-extrabold tracking-tight text-snow">
          {tab === 'login' ? 'Sign in' : 'Create account'}
        </h1>
        <p className="mt-2 text-sm leading-relaxed text-mist">
          Register with email and password, then monitor public websites from your own dashboard.
        </p>

        <div className="mt-5 grid grid-cols-2 gap-2 rounded-xl border border-line bg-ink-2 p-1">
          <button
            type="button"
            onClick={() => {
              setTab('login')
              setError(null)
            }}
            className={`rounded-lg px-3 py-2 text-sm font-medium transition ${
              tab === 'login' ? 'bg-panel text-snow' : 'text-mist hover:text-snow'
            }`}
          >
            Sign in
          </button>
          <button
            type="button"
            onClick={() => {
              setTab('register')
              setError(null)
            }}
            className={`rounded-lg px-3 py-2 text-sm font-medium transition ${
              tab === 'register' ? 'bg-panel text-snow' : 'text-mist hover:text-snow'
            }`}
          >
            Register
          </button>
        </div>

        <form className="mt-6 space-y-4" onSubmit={(e) => void handleSubmit(e)}>
          <label className="block space-y-1.5">
            <span className="text-xs font-medium uppercase tracking-wide text-mist">Email</span>
            <input
              name="email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              className="w-full rounded-xl border border-line bg-ink-2 px-3 py-2.5 text-sm text-snow outline-none ring-pulse/40 focus:ring-2"
            />
          </label>
          <label className="block space-y-1.5">
            <span className="text-xs font-medium uppercase tracking-wide text-mist">Password</span>
            <input
              name="password"
              type="password"
              autoComplete={tab === 'login' ? 'current-password' : 'new-password'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={tab === 'register' ? 10 : undefined}
              className="w-full rounded-xl border border-line bg-ink-2 px-3 py-2.5 text-sm text-snow outline-none ring-pulse/40 focus:ring-2"
            />
            {tab === 'register' ? (
              <span className="block text-xs text-mist">At least 10 characters.</span>
            ) : null}
          </label>

          {bootstrapError ? (
            <p className="rounded-lg border border-warn/30 bg-warn/10 px-3 py-2 text-sm text-warn">
              {bootstrapError}
            </p>
          ) : null}
          {info ? (
            <p className="rounded-lg border border-good/30 bg-good/10 px-3 py-2 text-sm text-good">
              {info}
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
            {tab === 'login' ? <LogIn className="size-4" /> : <UserPlus className="size-4" />}
            {submitting
              ? tab === 'login'
                ? 'Signing in…'
                : 'Creating account…'
              : tab === 'login'
                ? 'Sign in'
                : 'Create account'}
          </button>
        </form>

        {tab === 'login' && passwordResetEnabled && onForgotPassword ? (
          <button
            type="button"
            disabled={submitting}
            onClick={() => void handleForgot()}
            className="mt-4 w-full text-center text-sm text-mist underline underline-offset-2 hover:text-snow disabled:opacity-60"
          >
            Forgot password?
          </button>
        ) : null}
      </div>
    </div>
  )
}
