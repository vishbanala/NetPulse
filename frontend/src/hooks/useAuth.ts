import { useCallback, useEffect, useState } from 'react'

import { fetchCurrentUser, login as apiLogin, logout as apiLogout } from '../api/auth'
import type { AuthUser } from '../api/auth'
import { ApiError } from '../api/types'
import { isDemoMode } from '../demo/mode'

interface AuthState {
  user: AuthUser | null
  loading: boolean
  error: string | null
}

export function useAuth() {
  const demoMode = isDemoMode()
  const [state, setState] = useState<AuthState>({
    user: demoMode ? { username: 'demo' } : null,
    loading: !demoMode,
    error: null,
  })

  const restore = useCallback(async () => {
    if (demoMode) {
      setState({ user: { username: 'demo' }, loading: false, error: null })
      return
    }
    setState((prev) => ({ ...prev, loading: true, error: null }))
    try {
      const user = await fetchCurrentUser()
      setState({ user, loading: false, error: null })
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Failed to restore session'
      setState({ user: null, loading: false, error: message })
    }
  }, [demoMode])

  useEffect(() => {
    void restore()
  }, [restore])

  const login = useCallback(async (username: string, password: string) => {
    setState((prev) => ({ ...prev, error: null }))
    const user = await apiLogin(username, password)
    setState({ user, loading: false, error: null })
    return user
  }, [])

  const logout = useCallback(async () => {
    await apiLogout()
    setState({ user: null, loading: false, error: null })
  }, [])

  return {
    demoMode,
    user: state.user,
    loading: state.loading,
    error: state.error,
    isAuthenticated: state.user != null,
    login,
    logout,
    restore,
  }
}
