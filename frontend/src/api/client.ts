import { isStateChangingMethod, readCsrfCookie } from './csrf'
import { ApiError } from './types'

interface ErrorBody {
  message?: string
}

interface CsrfResponse {
  headerName?: string
  token?: string
}

async function parseError(response: Response): Promise<string> {
  let message = `Request failed (${response.status})`
  try {
    const body = (await response.json()) as ErrorBody
    if (body.message) {
      message = body.message
    }
  } catch {
    // keep default message
  }
  return message
}

/**
 * Ensures a CSRF cookie exists for SPA clients (Spring CookieCsrfTokenRepository).
 * Uses relative `/api` paths so same-origin deployments work without hardcoded hosts.
 */
export async function ensureCsrfToken(): Promise<string | null> {
  let token = readCsrfCookie()
  if (token) {
    return token
  }

  const response = await fetch('/api/auth/csrf', {
    method: 'GET',
    credentials: 'include',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) {
    throw new ApiError(response.status, await parseError(response))
  }

  token = readCsrfCookie()
  if (token) {
    return token
  }

  // Fallback if the cookie is not visible yet but the body includes the token.
  try {
    const body = (await response.json()) as CsrfResponse
    return body.token ?? null
  } catch {
    return null
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase()
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
  }

  if (init?.headers) {
    const incoming = new Headers(init.headers)
    incoming.forEach((value, key) => {
      headers[key] = value
    })
  }

  if (isStateChangingMethod(method)) {
    const csrf = await ensureCsrfToken()
    if (csrf) {
      headers['X-XSRF-TOKEN'] = csrf
    }
  }

  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      method,
      credentials: 'include',
      headers,
    })
  } catch {
    throw new ApiError(
      0,
      'Cannot reach the NetPulse API. Is the backend running and reachable at /api?',
    )
  }

  if (!response.ok) {
    throw new ApiError(response.status, await parseError(response))
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}

export function apiGet<T>(path: string): Promise<T> {
  return request<T>(path)
}

export function apiPost<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

export function apiPut<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, { method: 'PUT', body: JSON.stringify(body) })
}

export function apiDelete(path: string): Promise<void> {
  return request<void>(path, { method: 'DELETE' })
}
