import { afterEach, describe, expect, it, vi } from 'vitest'

describe('isDemoMode', () => {
  afterEach(() => {
    vi.resetModules()
    vi.unstubAllEnvs()
  })

  it('is true only when VITE_DEMO_MODE is exactly true', async () => {
    vi.stubEnv('VITE_DEMO_MODE', 'true')
    const { isDemoMode } = await import('./mode')
    expect(isDemoMode()).toBe(true)
  })

  it('is false for other values', async () => {
    vi.stubEnv('VITE_DEMO_MODE', 'false')
    const { isDemoMode } = await import('./mode')
    expect(isDemoMode()).toBe(false)
  })
})
