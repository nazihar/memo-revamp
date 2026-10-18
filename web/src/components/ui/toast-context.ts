import { createContext, useContext } from 'react'

export interface ToastApi {
  show: (message: string, tone?: 'success' | 'error') => void
}

export const ToastContext = createContext<ToastApi | null>(null)

export function useToast(): ToastApi {
  const api = useContext(ToastContext)
  if (!api) throw new Error('useToast must be used inside <ToastProvider>')
  return api
}
