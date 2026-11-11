import { apiGet, apiPost, ensureCsrfToken } from './client'

export interface AuthUser {
  id?: string
  email: string
  username: string
  role?: string
}

export interface AuthFeatures {
  passwordResetEnabled: boolean
  registrationEnabled: boolean
}

interface AuthMeResponse {
  authenticated: boolean
  id?: string
  email?: string
  username?: string
  role?: string
}

interface RegisterResponse {
  registered: boolean
  message: string
}

function toAuthUser(body: AuthMeResponse): AuthUser | null {
  if (!body.authenticated) {
    return null
  }
  const email = body.email || body.username
  if (!email) {
    return null
  }
  return {
    id: body.id,
    email,
    username: body.username || email,
    role: body.role,
  }
}

export async function fetchCsrf(): Promise<void> {
  await ensureCsrfToken()
}

export async function fetchAuthFeatures(): Promise<AuthFeatures> {
  try {
    return await apiGet<AuthFeatures>('/api/auth/features')
  } catch {
    return { passwordResetEnabled: false, registrationEnabled: true }
  }
}

export async function fetchCurrentUser(): Promise<AuthUser | null> {
  try {
    const me = await apiGet<AuthMeResponse>('/api/auth/me')
    return toAuthUser(me)
  } catch (err) {
    if (err && typeof err === 'object' && 'status' in err && (err as { status: number }).status === 401) {
      return null
    }
    throw err
  }
}

export async function login(email: string, password: string): Promise<AuthUser> {
  await ensureCsrfToken()
  const result = await apiPost<AuthMeResponse>('/api/auth/login', { email, password })
  const user = toAuthUser(result)
  if (!user) {
    throw new Error('Login succeeded but session payload was incomplete')
  }
  return user
}

export async function register(email: string, password: string): Promise<RegisterResponse> {
  await ensureCsrfToken()
  return apiPost<RegisterResponse>('/api/auth/register', { email, password })
}

export async function logout(): Promise<void> {
  await ensureCsrfToken()
  await apiPost<void>('/api/auth/logout')
}

export async function forgotPassword(email: string): Promise<void> {
  await ensureCsrfToken()
  await apiPost<void>('/api/auth/forgot-password', { email })
}
