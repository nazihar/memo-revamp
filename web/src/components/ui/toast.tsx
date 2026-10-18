import { CircleCheck, CircleX } from 'lucide-react'
import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { ToastContext } from './toast-context'

interface ToastItem {
  id: number
  message: string
  tone: 'success' | 'error'
}

const DISPLAY_MS = 6_000

/**
 * Short confirmations ("Saved"). A polite live region we own: no injected styles (ADR-0021), announced by screen
 * readers, and never the only place an error is reported (forms show errors on their fields).
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const show = useCallback((message: string, tone: ToastItem['tone'] = 'success') => {
    const id = Date.now() + Math.random()
    setItems((current) => [...current, { id, message, tone }])
    window.setTimeout(() => {
      setItems((current) => current.filter((t) => t.id !== id))
    }, DISPLAY_MS)
  }, [])
  const api = useMemo(() => ({ show }), [show])
  return (
    <ToastContext.Provider value={api}>
      {children}
      <div
        role="status"
        aria-live="polite"
        className="pointer-events-none fixed inset-x-0 bottom-4 z-50 flex flex-col items-center gap-2 px-4"
      >
        {items.map((t) => {
          const Icon = t.tone === 'success' ? CircleCheck : CircleX
          return (
            <div
              key={t.id}
              className={cn(
                'pointer-events-auto flex items-center gap-2 rounded-md border bg-card px-4 py-3 text-sm shadow-lg',
                t.tone === 'success' ? 'text-status-ok-fg' : 'text-status-danger-fg',
              )}
            >
              <Icon aria-hidden="true" className="size-4 shrink-0" />
              <span className="text-foreground">{t.message}</span>
            </div>
          )
        })}
      </div>
    </ToastContext.Provider>
  )
}
