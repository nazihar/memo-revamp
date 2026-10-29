import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useMemo, useRef, useState, type SyntheticEvent, type ReactNode } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { getSessionQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { stepUp } from '@/api/generated/sdk.gen'
import { isRateLimited } from './session'
import { useMe } from './session'
import { StepUpContext, type StepUpApi } from './step-up-context'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'

const messages = defineMessages({
  title: { id: 'stepUp.title', defaultMessage: 'Confirm it is you' },
  descriptionCode: {
    id: 'stepUp.description.code',
    defaultMessage: 'Enter the 6-digit code from your authenticator app to continue.',
  },
  descriptionPassword: { id: 'stepUp.description.password', defaultMessage: 'Enter your password to continue.' },
  code: { id: 'stepUp.code', defaultMessage: 'Code' },
  password: { id: 'stepUp.password', defaultMessage: 'Password' },
  confirm: { id: 'stepUp.confirm', defaultMessage: 'Confirm' },
  cancel: { id: 'stepUp.cancel', defaultMessage: 'Cancel' },
  wrong: { id: 'stepUp.wrong', defaultMessage: 'That did not match. Try again.' },
  tooMany: { id: 'stepUp.tooMany', defaultMessage: 'Too many attempts. Wait a minute and try again.' },
})

/** Provides [useWithStepUp] and renders the confirmation dialog (ADR-0024). */
export function StepUpProvider({ children }: { children: ReactNode }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [value, setValue] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const resolver = useRef<((ok: boolean) => void) | null>(null)
  const me = useMe(open)
  const withCode = me.data?.mfaEnrolled ?? true

  const finish = useCallback((ok: boolean) => {
    resolver.current?.(ok)
    resolver.current = null
    setOpen(false)
    setValue('')
    setError(undefined)
  }, [])

  const api = useMemo<StepUpApi>(
    () => ({
      request: () =>
        new Promise<boolean>((resolve) => {
          resolver.current = resolve
          setOpen(true)
        }),
    }),
    [],
  )

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      await stepUp({ body: withCode ? { code: value.trim() } : { password: value }, throwOnError: true })
      await queryClient.invalidateQueries({ queryKey: getSessionQueryKey() })
      finish(true)
    } catch (err) {
      setError(intl.formatMessage(isRateLimited(err) ? messages.tooMany : messages.wrong))
    } finally {
      setBusy(false)
    }
  }

  return (
    <StepUpContext.Provider value={api}>
      {children}
      <Dialog
        open={open}
        onOpenChange={(next) => {
          if (!next && resolver.current) finish(false)
        }}
        title={intl.formatMessage(messages.title)}
        description={intl.formatMessage(withCode ? messages.descriptionCode : messages.descriptionPassword)}
      >
        {/* Only while open: a closed dialog keeps no second "Code" field in the page. */}
        {open ? (
          <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
            <Field label={intl.formatMessage(withCode ? messages.code : messages.password)} required error={error}>
              {(p) => (
                <Input
                  {...p}
                  name="step-up"
                  type={withCode ? 'text' : 'password'}
                  inputMode={withCode ? 'numeric' : undefined}
                  autoComplete={withCode ? 'one-time-code' : 'current-password'}
                  value={value}
                  onChange={(e) => {
                    setValue(e.target.value)
                  }}
                />
              )}
            </Field>
            <div className="flex justify-end gap-2">
              <Button
                variant="outline"
                onClick={() => {
                  finish(false)
                }}
              >
                {intl.formatMessage(messages.cancel)}
              </Button>
              <Button type="submit" disabled={busy || value.trim() === ''}>
                {intl.formatMessage(messages.confirm)}
              </Button>
            </div>
          </form>
        ) : null}
      </Dialog>
    </StepUpContext.Provider>
  )
}
