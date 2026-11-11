import { useCallback, useEffect, useState } from 'react'

import {
  fetchAuthFeatures,
  fetchCurrentUser,
  forgotPassword as apiForgotPassword,
  login as apiLogin,
  logout as apiLogout,
  register as apiRegister,
  type AuthFeatures,
  type AuthUser,
} from '../api/auth'
import { ApiError } from '../api/types'
import { isDemoMode } from '../demo/mode'

interface AuthState {
  user: AuthUser | null
  loading: boolean
  error: string | null
  features: AuthFeatures
}

const defaultFeatures: AuthFeatures = {
  passwordResetEnabled: false,
  registrationEnabled: true,
}

export function useAuth() {
  const demoMode = isDemoMode()
  const [state, setState] = useState<AuthState>({
    user: demoMode ? { email: 'demo@localhost', username: 'demo' } : null,
    loading: !demoMode,
    error: null,
    features: defaultFeatures,
  })

  const restore = useCallback(async () => {
    if (demoMode) {
      setState({
        user: { email: 'demo@localhost', username: 'demo' },
        loading: false,
        error: null,
        features: defaultFeatures,
      })
      return
    }
    setState((prev) => ({ ...prev, loading: true, error: null }))
    try {
      const [user, features] = await Promise.all([fetchCurrentUser(), fetchAuthFeatures()])
      setState({ user, loading: false, error: null, features })
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Failed to restore session'
      setState({ user: null, loading: false, error: message, features: defaultFeatures })
    }
  }, [demoMode])

  useEffect(() => {
    void restore()
  }, [restore])

  const login = useCallback(async (email: string, password: string) => {
    setState((prev) => ({ ...prev, error: null }))
    const user = await apiLogin(email, password)
    setState((prev) => ({ ...prev, user, loading: false, error: null }))
    return user
  }, [])

  const register = useCallback(async (email: string, password: string) => {
    setState((prev) => ({ ...prev, error: null }))
    return apiRegister(email, password)
  }, [])

  const forgotPassword = useCallback(async (email: string) => {
    await apiForgotPassword(email)
  }, [])

  const logout = useCallback(async () => {
    await apiLogout()
    setState((prev) => ({ ...prev, user: null, loading: false, error: null }))
  }, [])

  return {
    demoMode,
    user: state.user,
    loading: state.loading,
    error: state.error,
    features: state.features,
    isAuthenticated: state.user != null,
    login,
    register,
    forgotPassword,
    logout,
    restore,
  }
}
