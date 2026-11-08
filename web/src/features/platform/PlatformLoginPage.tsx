import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useEffect, useRef, useState, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import {
  confirmPlatformMfaEnrolment,
  platformLogin,
  startPlatformMfaEnrolment,
  verifyPlatformMfa,
} from '@/api/generated/sdk.gen'
import type { MfaEnrolment, PlatformSession } from '@/api/generated/types.gen'
import { problemType, PROBLEM } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { AuthHeading, FormAlert } from '@/features/auth/AuthLayout'
import { pm } from './platform-messages'
import { refreshPlatformSession, usePlatformSession } from './platform-session'

/** Operator sign-in (ADR-0031): password, then TOTP; on the first sign-in, TOTP enrolment instead. */
export function PlatformLoginPage() {
  const session = usePlatformSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const state = session.data?.state

  useEffect(() => {
    if (state === 'AUTHENTICATED') void navigate({ to: '/platform' })
  }, [state, navigate])

  const proceed = (next: PlatformSession) => void refreshPlatformSession(queryClient, next)

  if (state === 'MFA_REQUIRED') return <CodeStep onDone={proceed} />
  if (state === 'MFA_ENROLMENT_REQUIRED') return <EnrolStep onDone={proceed} />
  return <PasswordStep onDone={proceed} />
}

function PasswordStep({ onDone }: { onDone: (next: PlatformSession) => void }) {
  const intl = useIntl()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      const { data } = await platformLogin({ body: { email: email.trim(), password }, throwOnError: true })
      onDone(data)
    } catch (err) {
      const type = problemType(err)
      setError(
        intl.formatMessage(
          type === PROBLEM.invalidCredentials ? pm.invalid : type === PROBLEM.rateLimited ? pm.tooMany : pm.failed,
        ),
      )
      setPassword('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <AuthHeading title={intl.formatMessage(pm.loginTitle)} lead={intl.formatMessage(pm.loginLead)} />
      <FormAlert>{error}</FormAlert>
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
        <Field label={intl.formatMessage(pm.email)} required>
          {(p) => (
            <Input
              {...p}
              name="email"
              type="email"
              autoComplete="username"
              value={email}
              onChange={(e) => {
                setEmail(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(pm.password)} required>
          {(p) => (
            <Input
              {...p}
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value)
              }}
            />
          )}
        </Field>
        <Button type="submit" disabled={busy || email.trim() === '' || password === ''}>
          {intl.formatMessage(pm.signIn)}
        </Button>
      </form>
    </>
  )
}

function CodeForm({ onSubmit }: { onSubmit: (code: string) => Promise<PlatformSession> }) {
  const intl = useIntl()
  const [code, setCode] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await onSubmit(code.trim())
    } catch (err) {
      setError(intl.formatMessage(problemType(err) === PROBLEM.rateLimited ? pm.tooMany : pm.codeInvalid))
      setCode('')
    } finally {
      setBusy(false)
    }
  }
  return (
    <>
      <FormAlert>{error}</FormAlert>
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
        <Field label={intl.formatMessage(pm.code)} required>
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
          {intl.formatMessage(pm.continue)}
        </Button>
      </form>
    </>
  )
}

function CodeStep({ onDone }: { onDone: (next: PlatformSession) => void }) {
  const intl = useIntl()
  return (
    <>
      <AuthHeading title={intl.formatMessage(pm.codeTitle)} lead={intl.formatMessage(pm.codeLead)} />
      <CodeForm
        onSubmit={async (code) => {
          const { data } = await verifyPlatformMfa({ body: { code }, throwOnError: true })
          onDone(data)
          return data
        }}
      />
    </>
  )
}

function EnrolStep({ onDone }: { onDone: (next: PlatformSession) => void }) {
  const intl = useIntl()
  const [enrolment, setEnrolment] = useState<MfaEnrolment>()
  const started = useRef(false)
  useEffect(() => {
    // Once per visit: a second start would replace the secret behind the QR code shown.
    if (started.current) return
    started.current = true
    void startPlatformMfaEnrolment({ throwOnError: true }).then(({ data }) => {
      setEnrolment(data)
    })
  }, [])
  return (
    <>
      <AuthHeading title={intl.formatMessage(pm.enrolTitle)} lead={intl.formatMessage(pm.enrolLead)} />
      {enrolment ? (
        <div className="mb-4 flex flex-col items-center gap-2">
          <img
            src={`data:image/svg+xml;base64,${btoa(enrolment.qrSvg)}`}
            alt={intl.formatMessage(pm.enrolQr)}
            className="size-48 rounded-md bg-white p-2"
          />
          <p className="flex gap-1.5 text-sm">
            <span>{intl.formatMessage(pm.enrolKey)}</span>
            <code className="font-mono" data-testid="totp-secret">
              {enrolment.secret.replace(/(.{4})/g, '$1 ').trim()}
            </code>
          </p>
        </div>
      ) : null}
      <CodeForm
        onSubmit={async (code) => {
          const { data } = await confirmPlatformMfaEnrolment({ body: { code }, throwOnError: true })
          onDone(data)
          return data
        }}
      />
    </>
  )
}
