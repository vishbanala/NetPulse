/** Reads the non-HttpOnly CSRF cookie set by Spring Security. */
export function readCsrfCookie(cookieHeader = typeof document !== 'undefined' ? document.cookie : ''): string | null {
  if (!cookieHeader) {
    return null
  }
  const parts = cookieHeader.split(';')
  for (const part of parts) {
    const trimmed = part.trim()
    if (trimmed.startsWith('XSRF-TOKEN=')) {
      return decodeURIComponent(trimmed.slice('XSRF-TOKEN='.length))
    }
  }
  return null
}

export function isStateChangingMethod(method: string): boolean {
  const m = method.toUpperCase()
  return m !== 'GET' && m !== 'HEAD' && m !== 'OPTIONS' && m !== 'TRACE'
}
