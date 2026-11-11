import { useCallback, useState } from 'react'

import type { ToastMessage, ToastTone } from '../components/Toast'

export function useToasts() {
  const [toasts, setToasts] = useState<ToastMessage[]>([])

  const dismiss = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }, [])

  const push = useCallback(
    (tone: ToastTone, title: string, detail?: string) => {
      const id = crypto.randomUUID()
      setToasts((prev) => [...prev, { id, tone, title, detail }])
      window.setTimeout(() => {
        setToasts((prev) => prev.filter((t) => t.id !== id))
      }, 4500)
    },
    [],
  )

  return { toasts, push, dismiss }
}
