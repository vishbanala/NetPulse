import { describe, expect, it } from 'vitest'

import { isStateChangingMethod, readCsrfCookie } from './csrf'

describe('readCsrfCookie', () => {
  it('reads XSRF-TOKEN from a cookie header', () => {
    expect(readCsrfCookie('a=1; XSRF-TOKEN=abc%2Fdef; b=2')).toBe('abc/def')
  })

  it('returns null when missing', () => {
    expect(readCsrfCookie('session=xyz')).toBeNull()
  })
})

describe('isStateChangingMethod', () => {
  it('classifies methods for CSRF header attachment', () => {
    expect(isStateChangingMethod('GET')).toBe(false)
    expect(isStateChangingMethod('HEAD')).toBe(false)
    expect(isStateChangingMethod('POST')).toBe(true)
    expect(isStateChangingMethod('PUT')).toBe(true)
    expect(isStateChangingMethod('DELETE')).toBe(true)
  })
})
