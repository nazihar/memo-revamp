import { useState } from 'react'
import { useIntl } from 'react-intl'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { staleRefusal } from '../problems'
import { officerFieldProblems, officerRefusal } from './problems'

/**
 * Sending one officer form: one idempotency key per dialog (a double click or a retry acts once), a field's problem to its field,
 * anything else in words above the buttons, and a lost race reported to [onStale] so the page reloads.
 */
export function useSend(onStale?: () => void, refuse?: (problem: unknown) => string | undefined) {
  const intl = useIntl()
  const terms = useTerms()
  const [key] = useState(() => newIdempotencyKey())
  const [busy, setBusy] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [refusal, setRefusal] = useState<string>()
  const send = async (
    shown: readonly string[],
    call: (headers: { 'Idempotency-Key': string }) => Promise<void>,
  ): Promise<boolean> => {
    setBusy(true)
    setRefusal(undefined)
    setErrors({})
    try {
      await call({ 'Idempotency-Key': key })
      return true
    } catch (problem) {
      const fields = Object.fromEntries(
        Object.entries(officerFieldProblems(intl, terms, problem)).filter(([field]) => shown.includes(field)),
      )
      setErrors(fields)
      if (!Object.keys(fields).length) setRefusal(refuse?.(problem) ?? officerRefusal(intl, terms, problem))
      if (staleRefusal(problem)) onStale?.()
      return false
    } finally {
      setBusy(false)
    }
  }
  return { intl, terms, busy, errors, setErrors, refusal, setRefusal, send }
}
