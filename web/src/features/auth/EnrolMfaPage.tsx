import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useEffect, useRef, useState, type SyntheticEvent } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { confirmMfaEnrolment, startMfaEnrolment } from '@/api/generated/sdk.gen'
import type { MfaEnrolment } from '@/api/generated/types.gen'
import { problemType, PROBLEM } from '@/api/http'
import { AuthHeading, FormAlert } from './AuthLayout'
import { RecoveryCodeList } from './RecoveryCodeList'
import { refreshSession } from './session'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Input } from '@/components/ui/form-controls'

const messages = defineMessages({
  title: { id: 'enrol.title', defaultMessage: 'Set up two-step sign-in' },
  lead: {
    id: 'enrol.lead',
    defaultMessage:
      'Scan the QR code with an authenticator app (for example Microsoft Authenticator, Google Authenticator or 1Password), then enter the code it shows.',
  },
  qrAlt: { id: 'enrol.qrAlt', defaultMessage: 'QR code for your authenticator app' },
  manual: { id: 'enrol.manual', defaultMessage: 'Cannot scan? Enter this key in the app instead:' },
  code: { id: 'enrol.code', defaultMessage: 'Code from the app' },
  confirm: { id: 'enrol.confirm', defaultMessage: 'Turn on two-step sign-in' },
  loading: { id: 'enrol.loading', defaultMessage: 'Preparing your setup key…' },
  failed: { id: 'enrol.failed', defaultMessage: 'Setup could not start. Sign in again and retry.' },
  codeInvalid: {
    id: 'enrol.code.invalid',
    defaultMessage: 'That code did not match. Wait for a new code and try again.',
  },
  tooMany: { id: 'enrol.tooMany', defaultMessage: 'Too many attempts. Wait a minute and try again.' },
  codesTitle: { id: 'enrol.codes.title', defaultMessage: 'Save your recovery codes' },
  codesLead: {
    id: 'enrol.codes.lead',
    defaultMessage:
      'If you lose your phone, each of these codes lets you sign in once. Store them somewhere safe, such as a password manager. They are shown only now.',
  },
  saved: { id: 'enrol.codes.saved', defaultMessage: 'I have saved my recovery codes' },
  continue: { id: 'enrol.continue', defaultMessage: 'Continue' },
})

/** TOTP enrolment (ADR-0024): QR code or manual key, a confirming code, then recovery codes shown once. */
export function EnrolMfaPage() {
  const intl = useIntl()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [codes, setCodes] = useState<string[]>()
  const [saved, setSaved] = useState(false)
  const start = useMutation({ mutationFn: () => startMfaEnrolment({ throwOnError: true }).then((r) => r.data) })
  const startNow = start.mutate
  // Once per visit: a second start (StrictMode re-runs effects) would replace the secret behind the QR code shown.
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return
    started.current = true
    startNow()
  }, [startNow])

  if (codes) {
    return (
      <>
        <AuthHeading title={intl.formatMessage(messages.codesTitle)} lead={intl.formatMessage(messages.codesLead)} />
        <RecoveryCodeList codes={codes} />
        <label className="mt-4 flex items-center gap-2 text-sm">
          <Checkbox
            checked={saved}
            onChange={(e) => {
              setSaved(e.target.checked)
            }}
          />
          {intl.formatMessage(messages.saved)}
        </label>
        <Button className="mt-4 w-full" disabled={!saved} onClick={() => void navigate({ to: '/' })}>
          {intl.formatMessage(messages.continue)}
        </Button>
      </>
    )
  }

  return (
    <>
      <AuthHeading title={intl.formatMessage(messages.title)} lead={intl.formatMessage(messages.lead)} />
      {start.isPending && (
        <p role="status" className="text-sm text-muted-foreground">
          {intl.formatMessage(messages.loading)}
        </p>
      )}
      {start.isError && <FormAlert>{intl.formatMessage(messages.failed)}</FormAlert>}
      {start.data && (
        <ConfirmStep
          enrolment={start.data}
          onConfirmed={(recoveryCodes, session) => {
            setCodes(recoveryCodes)
            void refreshSession(queryClient, session)
          }}
        />
      )}
    </>
  )
}

function ConfirmStep({
  enrolment,
  onConfirmed,
}: {
  enrolment: MfaEnrolment
  onConfirmed: (codes: string[], session: Parameters<typeof refreshSession>[1]) => void
}) {
  const intl = useIntl()
  const [code, setCode] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      const { data } = await confirmMfaEnrolment({ body: { code: code.trim() }, throwOnError: true })
      onConfirmed(data.recoveryCodes, data.session)
    } catch (err) {
      setError(intl.formatMessage(problemType(err) === PROBLEM.rateLimited ? messages.tooMany : messages.codeInvalid))
      setCode('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <img
        src={`data:image/svg+xml;base64,${btoa(enrolment.qrSvg)}`}
        alt={intl.formatMessage(messages.qrAlt)}
        width={200}
        height={200}
        className="mx-auto rounded-md border bg-white p-2"
      />
      <div className="text-sm">
        <p className="text-muted-foreground">{intl.formatMessage(messages.manual)}</p>
        <code dir="ltr" className="mt-1 block break-all rounded-md bg-muted px-3 py-2 font-mono text-sm tracking-wider">
          {enrolment.secret.replace(/(.{4})/g, '$1 ').trim()}
        </code>
      </div>
      <FormAlert>{error}</FormAlert>
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
        <Field label={intl.formatMessage(messages.code)} required>
          {(p) => (
            <Input
              {...p}
              name="code"
              inputMode="numeric"
              autoComplete="one-time-code"
              value={code}
              onChange={(e) => {
                setCode(e.target.value)
              }}
            />
          )}
        </Field>
        <Button type="submit" disabled={busy || code.trim() === ''}>
          {intl.formatMessage(messages.confirm)}
        </Button>
      </form>
    </div>
  )
}
