import { apiGet, apiPost, ensureCsrfToken } from './client'

export interface AuthUser {
  username: string
}

interface AuthMeResponse {
  authenticated: boolean
  username: string
}

interface LoginResponse {
  authenticated: boolean
  username: string
}

export async function fetchCsrf(): Promise<void> {
  await ensureCsrfToken()
}

export async function fetchCurrentUser(): Promise<AuthUser | null> {
  try {
    const me = await apiGet<AuthMeResponse>('/api/auth/me')
    if (!me.authenticated || !me.username) {
      return null
    }
    return { username: me.username }
  } catch (err) {
    // 401 means no session — not a hard failure for bootstrap.
    if (err && typeof err === 'object' && 'status' in err && (err as { status: number }).status === 401) {
      return null
    }
    throw err
  }
}

export async function login(username: string, password: string): Promise<AuthUser> {
  await ensureCsrfToken()
  const result = await apiPost<LoginResponse>('/api/auth/login', { username, password })
  return { username: result.username }
}

export async function logout(): Promise<void> {
  await ensureCsrfToken()
  await apiPost<void>('/api/auth/logout')
}
