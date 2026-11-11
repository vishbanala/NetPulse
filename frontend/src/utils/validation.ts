import type { MonitorWriteRequest } from '../api/types'

export interface MonitorFormValues {
  name: string
  url: string
  intervalSeconds: string
  enabled: boolean
}

export interface MonitorFormErrors {
  name?: string
  url?: string
  intervalSeconds?: string
}

/** Mirrors backend CreateMonitorRequest rules. */
export function validateMonitorForm(values: MonitorFormValues): MonitorFormErrors {
  const errors: MonitorFormErrors = {}
  const name = values.name.trim()
  const url = values.url.trim()

  if (!name) {
    errors.name = 'Name is required'
  } else if (name.length > 200) {
    errors.name = 'Name must be 200 characters or fewer'
  }

  if (!url) {
    errors.url = 'URL is required'
  } else if (url.length > 2048) {
    errors.url = 'URL must be 2048 characters or fewer'
  } else {
    const lower = url.toLowerCase()
    if (!lower.startsWith('http://') && !lower.startsWith('https://')) {
      errors.url = 'URL must start with http:// or https://'
    } else {
      try {
        void new URL(url)
      } catch {
        errors.url = 'Enter a valid website URL'
      }
    }
  }

  const interval = Number(values.intervalSeconds)
  if (!values.intervalSeconds.trim() || Number.isNaN(interval)) {
    errors.intervalSeconds = 'Interval is required'
  } else if (!Number.isInteger(interval)) {
    errors.intervalSeconds = 'Interval must be a whole number of seconds'
  } else if (interval < 10 || interval > 86400) {
    errors.intervalSeconds = 'Interval must be between 10 and 86400 seconds'
  }

  return errors
}

export function toMonitorWriteRequest(values: MonitorFormValues): MonitorWriteRequest {
  return {
    name: values.name.trim(),
    url: values.url.trim(),
    intervalSeconds: Number(values.intervalSeconds),
    enabled: values.enabled,
  }
}

export function hasFormErrors(errors: MonitorFormErrors): boolean {
  return Boolean(errors.name || errors.url || errors.intervalSeconds)
}
