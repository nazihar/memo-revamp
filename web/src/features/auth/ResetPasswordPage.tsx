import { Link } from '@tanstack/react-router'
import { useState, type SyntheticEvent } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { completePasswordReset, requestPasswordReset } from '@/api/generated/sdk.gen'
import { isProblem, problemType, PROBLEM } from '@/api/http'
import { AuthHeading, FormAlert } from './AuthLayout'
import { takeLinkToken } from './link-token'
import { passwordProblemMessage } from './password-messages'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'

const messages = defineMessages({
  requestTitle: { id: 'reset.request.title', defaultMessage: 'Reset your password' },
  requestLead: {
    id: 'reset.request.lead',
    defaultMessage: 'Enter your work email. If it has an account, we will email you a link.',
  },
  email: { id: 'reset.email', defaultMessage: 'Email' },
  send: { id: 'reset.send', defaultMessage: 'Email me a link' },
  sentTitle: { id: 'reset.sent.title', defaultMessage: 'Check your email' },
  sentLead: {
    id: 'reset.sent.lead',
    defaultMessage:
      'If an account exists for that address, a link is on its way. It works once and expires in 30 minutes.',
  },
  tooMany: { id: 'reset.tooMany', defaultMessage: 'Too many requests. Wait a while and try again.' },
  failed: { id: 'reset.failed', defaultMessage: 'That did not work. Try again.' },
  newTitle: { id: 'reset.new.title', defaultMessage: 'Choose a new password' },
  newLead: {
    id: 'reset.new.lead',
    defaultMessage: 'Use at least 12 characters. A long phrase is easier to remember than a complex word.',
  },
  newPassword: { id: 'reset.newPassword', defaultMessage: 'New password' },
  save: { id: 'reset.save', defaultMessage: 'Set password' },
  doneTitle: { id: 'reset.done.title', defaultMessage: 'Password changed' },
  doneLead: {
    id: 'reset.done.lead',
    defaultMessage: 'You were signed out everywhere. Sign in with your new password.',
  },
  signIn: { id: 'reset.signIn', defaultMessage: 'Go to sign in' },
  invalidLink: { id: 'reset.invalidLink', defaultMessage: 'This link is no longer valid. Ask for a new one.' },
  back: { id: 'reset.back', defaultMessage: 'Back to sign in' },
})

/** Password reset (design E03 §3): ask for a link, or with a link's token choose a new password. */
export function ResetPasswordPage() {
  const [token] = useState(takeLinkToken)
  return token ? <NewPassword token={token} /> : <RequestLink />
}

function RequestLink({ notice }: { notice?: string }) {
  const intl = useIntl()
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await requestPasswordReset({ body: { email: email.trim() }, throwOnError: true })
      setSent(true)
    } catch (err) {
      setError(intl.formatMessage(problemType(err) === PROBLEM.rateLimited ? messages.tooMany : messages.failed))
    } finally {
      setBusy(false)
    }
  }

  if (sent) {
    return (
      <>
        <AuthHeading title={intl.formatMessage(messages.sentTitle)} lead={intl.formatMessage(messages.sentLead)} />
        <BackToSignIn />
      </>
    )
  }
  return (
    <>
      <AuthHeading title={intl.formatMessage(messages.requestTitle)} lead={intl.formatMessage(messages.requestLead)} />
      <FormAlert>{error ?? notice}</FormAlert>
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
        <Button type="submit" disabled={busy || email.trim() === ''}>
          {intl.formatMessage(messages.send)}
        </Button>
        <BackToSignIn />
      </form>
    </>
  )
}

function NewPassword({ token }: { token: string }) {
  const intl = useIntl()
  const [password, setPassword] = useState('')
  const [fieldError, setFieldError] = useState<string>()
  const [linkInvalid, setLinkInvalid] = useState(false)
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    setBusy(true)
    setFieldError(undefined)
    try {
      await completePasswordReset({ body: { token, newPassword: password }, throwOnError: true })
      setDone(true)
    } catch (err) {
      const fieldProblem = isProblem(err) ? err.errors?.find((f) => f.field === 'newPassword') : undefined
      if (fieldProblem) setFieldError(passwordProblemMessage(intl, fieldProblem.code))
      else setLinkInvalid(true)
    } finally {
      setBusy(false)
    }
  }

  if (linkInvalid) return <RequestLink notice={intl.formatMessage(messages.invalidLink)} />
  if (done) {
    return (
      <>
        <AuthHeading title={intl.formatMessage(messages.doneTitle)} lead={intl.formatMessage(messages.doneLead)} />
        <Button asChild className="w-full">
          <Link to="/login">{intl.formatMessage(messages.signIn)}</Link>
        </Button>
      </>
    )
  }
  return (
    <>
      <AuthHeading title={intl.formatMessage(messages.newTitle)} lead={intl.formatMessage(messages.newLead)} />
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
        <Field label={intl.formatMessage(messages.newPassword)} required error={fieldError}>
          {(p) => (
            <Input
              {...p}
              name="newPassword"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value)
              }}
            />
          )}
        </Field>
        <Button type="submit" disabled={busy || password === ''}>
          {intl.formatMessage(messages.save)}
        </Button>
      </form>
    </>
  )
}

function BackToSignIn() {
  const intl = useIntl()
  return (
    <Link to="/login" className="text-sm text-primary underline-offset-4 hover:underline">
      {intl.formatMessage(messages.back)}
    </Link>
  )
}
