import { describe, expect, it } from 'vitest'

import { validateMonitorForm } from './validation'

describe('validateMonitorForm', () => {
  it('rejects intervals below five minutes', () => {
    const errors = validateMonitorForm({
      name: 'Example',
      url: 'https://example.com',
      intervalSeconds: '60',
      enabled: true,
    })
    expect(errors.intervalSeconds).toMatch(/300/)
  })

  it('accepts a five-minute interval', () => {
    const errors = validateMonitorForm({
      name: 'Example',
      url: 'https://example.com',
      intervalSeconds: '300',
      enabled: true,
    })
    expect(errors).toEqual({})
  })
})
