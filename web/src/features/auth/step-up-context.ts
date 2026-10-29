import { createContext, useCallback, useContext } from 'react'
import { problemType, PROBLEM } from '@/api/http'

export interface StepUpApi {
  /** Shows the confirmation dialog; resolves true once the server accepted the code or password. */
  request: () => Promise<boolean>
}

export const StepUpContext = createContext<StepUpApi | null>(null)

/**
 * Wraps a sensitive call (E03 design §11): when the server answers 401 `step-up-required`, the user confirms with a
 * code (or password) and the call is retried once. Cancelling rethrows the original problem.
 */
export function useWithStepUp() {
  const api = useContext(StepUpContext)
  if (!api) throw new Error('useWithStepUp must be used inside <StepUpProvider>')
  return useCallback(
    async <T>(call: () => Promise<T>): Promise<T> => {
      try {
        return await call()
      } catch (error) {
        if (problemType(error) !== PROBLEM.stepUpRequired) throw error
        if (!(await api.request())) throw error
        return call()
      }
    },
    [api],
  )
}
