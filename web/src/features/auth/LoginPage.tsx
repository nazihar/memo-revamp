import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useEffect, useState, type SyntheticEvent } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { login, logout, verifyMfa } from '@/api/generated/sdk.gen'
import type { Session } from '@/api/generated/types.gen'
import { problemType, PROBLEM } from '@/api/http'
import { AuthHeading, FormAlert } from './AuthLayout'
import { pathForState, refreshSession, useSession } from './session'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'

const messages = defineMessages({
  title: { id: 'login.title', defaultMessage: 'Sign in' },
  lead: { id: 'login.lead', defaultMessage: 'Use your work email and password.' },
  email: { id: 'login.email', defaultMessage: 'Email' },
  password: { id: 'login.password', defaultMessage: 'Password' },
  submit: { id: 'login.submit', defaultMessage: 'Sign in' },
  forgot: { id: 'login.forgot', defaultMessage: 'Forgot your password?' },
  invalid: {
    id: 'login.invalid',
    defaultMessage:
      'The email or password is incorrect, or the account is temporarily locked. Try again or reset your password.',
  },
  tooMany: { id: 'login.tooMany', defaultMessage: 'Too many attempts. Wait a few minutes and try again.' },
  failed: { id: 'login.failed', defaultMessage: 'Signing in did not work. Try again.' },
  codeTitle: { id: 'login.code.title', defaultMessage: 'Enter your code' },
  codeLead: {
    id: 'login.code.lead',
    defaultMessage: 'Open your authenticator app and enter the 6-digit code for memo.',
  },
  recoveryLead: {
    id: 'login.recovery.lead',
    defaultMessage: 'Enter one of the recovery codes you saved when you set up two-step sign-in. Each code works once.',
  },
  code: { id: 'login.code', defaultMessage: 'Code' },
  recoveryCode: { id: 'login.recoveryCode', defaultMessage: 'Recovery code' },
  verify: { id: 'login.verify', defaultMessage: 'Continue' },
  useRecovery: { id: 'login.useRecovery', defaultMessage: 'Use a recovery code instead' },
  useCode: { id: 'login.useCode', defaultMessage: 'Use the code from my app' },
  codeInvalid: {
    id: 'login.code.invalid',
    defaultMessage: 'That code is not valid. Check the time on your phone and try again.',
  },
  startOver: { id: 'login.startOver', defaultMessage: 'Sign in as someone else' },
})

/** Sign-in (E03 design §11): password, then a TOTP or recovery code when the session needs one. */
export function LoginPage({ next }: { next?: string }) {
  const session = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const state = session.data?.state

  const proceed = async (result: Session) => {
    await refreshSession(queryClient, result)
    const target = pathForState(result.state, next)
    if (target) await navigate({ to: target })
  }

  useEffect(() => {
    const target = state ? pathForState(state, next) : undefined
    if (target) void navigate({ to: target })
  }, [state, next, navigate])

  return state === 'MFA_REQUIRED' ? (
    <CodeStep onDone={(result) => void proceed(result)} />
  ) : (
    <PasswordStep onDone={(result) => void proceed(result)} />
  )
}

function PasswordStep({ onDone }: { onDone: (result: Session) => void }) {
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
      const { data } = await login({ body: { email: email.trim(), password }, throwOnError: true })
      onDone(data)
    } catch (err) {
      const type = problemType(err)
      setError(
        intl.formatMessage(
          type === PROBLEM.invalidCredentials
            ? messages.invalid
            : type === PROBLEM.rateLimited
              ? messages.tooMany
              : messages.failed,
        ),
      )
      setPassword('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <AuthHeading title={intl.formatMessage(messages.title)} lead={intl.formatMessage(messages.lead)} />
      <FormAlert>{error}</FormAlert>
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
        <Field label={intl.formatMessage(messages.email)} required>
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
        <Field label={intl.formatMessage(messages.password)} required>
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
          {intl.formatMessage(messages.submit)}
        </Button>
        <Link to="/reset-password" className="text-sm text-primary underline-offset-4 hover:underline">
          {intl.formatMessage(messages.forgot)}
        </Link>
      </form>
    </>
  )
}

function CodeStep({ onDone }: { onDone: (result: Session) => void }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const [recovery, setRecovery] = useState(false)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      const { data } = await verifyMfa({ body: { code: code.trim() }, throwOnError: true })
      onDone(data)
    } catch (err) {
      setError(intl.formatMessage(problemType(err) === PROBLEM.rateLimited ? messages.tooMany : messages.codeInvalid))
      setCode('')
    } finally {
      setBusy(false)
    }
  }

  const startOver = async () => {
    await logout({ throwOnError: false })
    await refreshSession(queryClient)
  }

  return (
    <>
      <AuthHeading
        title={intl.formatMessage(messages.codeTitle)}
        lead={intl.formatMessage(recovery ? messages.recoveryLead : messages.codeLead)}
      />
      <FormAlert>{error}</FormAlert>
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
        <Field label={intl.formatMessage(recovery ? messages.recoveryCode : messages.code)} required>
          {(p) => (
            <Input
              {...p}
              name="code"
              inputMode={recovery ? 'text' : 'numeric'}
              autoComplete="one-time-code"
              value={code}
              onChange={(e) => {
                setCode(e.target.value)
              }}
            />
          )}
        </Field>
        <Button type="submit" disabled={busy || code.trim() === ''}>
          {intl.formatMessage(messages.verify)}
        </Button>
        <div className="flex flex-wrap justify-between gap-2">
          <Button
            variant="link"
            onClick={() => {
              setRecovery(!recovery)
              setCode('')
              setError(undefined)
            }}
          >
            {intl.formatMessage(recovery ? messages.useCode : messages.useRecovery)}
          </Button>
          <Button variant="link" onClick={() => void startOver()}>
            {intl.formatMessage(messages.startOver)}
          </Button>
        </div>
      </form>
    </>
  )
}
