import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useEffect, useRef, useState, type SyntheticEvent } from 'react'
import { defineMessages, useIntl, type NoMessageValues } from 'react-intl'
import { acceptInvitation, lookUpInvitation } from '@/api/generated/sdk.gen'
import { isProblem, problemType, PROBLEM } from '@/api/http'
import { AuthHeading, FormAlert } from './AuthLayout'
import { takeLinkToken } from './link-token'
import { passwordProblemMessage } from './password-messages'
import { pathForState, refreshSession } from './session'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'

const messages = defineMessages<{
  title: { organisation: string }
  lead: { name: string; email: string }
  loading: NoMessageValues
  invalidTitle: NoMessageValues
  invalidLead: NoMessageValues
  password: NoMessageValues
  passwordHint: NoMessageValues
  accept: NoMessageValues
  tooMany: NoMessageValues
  signIn: NoMessageValues
}>({
  title: { id: 'invitation.title', defaultMessage: 'Join {organisation}' },
  lead: { id: 'invitation.lead', defaultMessage: 'You were invited as {name} ({email}). Choose a password to finish.' },
  loading: { id: 'invitation.loading', defaultMessage: 'Checking your invitation…' },
  invalidTitle: { id: 'invitation.invalid.title', defaultMessage: 'This invitation is no longer valid' },
  invalidLead: {
    id: 'invitation.invalid.lead',
    defaultMessage: 'It was already used or has expired. Ask your administrator to send a new one.',
  },
  password: { id: 'invitation.password', defaultMessage: 'Password' },
  passwordHint: {
    id: 'invitation.password.hint',
    defaultMessage: 'At least 12 characters. A long phrase is easier to remember than a complex word.',
  },
  accept: { id: 'invitation.accept', defaultMessage: 'Set password and continue' },
  tooMany: { id: 'invitation.tooMany', defaultMessage: 'Too many attempts. Wait a while and try again.' },
  signIn: { id: 'invitation.signIn', defaultMessage: 'Go to sign in' },
})

/** Invitation acceptance (design E03 §3): the invitee sets a password and is signed in; MFA enrolment may follow. */
export function InvitationPage() {
  const intl = useIntl()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [token] = useState(takeLinkToken)
  const [password, setPassword] = useState('')
  const [fieldError, setFieldError] = useState<string>()
  const [alert, setAlert] = useState<string>()
  const [expired, setExpired] = useState(false)
  const lookup = useMutation({
    mutationFn: (value: string) => lookUpInvitation({ body: { token: value }, throwOnError: true }).then((r) => r.data),
  })
  const lookUpNow = lookup.mutate
  const looked = useRef(false)

  useEffect(() => {
    if (looked.current || !token) return
    looked.current = true
    lookUpNow(token)
  }, [token, lookUpNow])

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    if (!token) return
    setFieldError(undefined)
    setAlert(undefined)
    try {
      const { data } = await acceptInvitation({ body: { token, password }, throwOnError: true })
      await refreshSession(queryClient, data)
      await navigate({ to: pathForState(data.state) ?? '/login' })
    } catch (err) {
      const fieldProblem = isProblem(err) ? err.errors?.find((f) => f.field === 'password') : undefined
      if (fieldProblem) setFieldError(passwordProblemMessage(intl, fieldProblem.code))
      else if (problemType(err) === PROBLEM.rateLimited) setAlert(intl.formatMessage(messages.tooMany))
      else setExpired(true)
    }
  }

  // Too many lookups is not an invalid link: say so, or people ask for new invitations that would not help.
  if (lookup.isError && problemType(lookup.error) === PROBLEM.rateLimited) {
    return <FormAlert>{intl.formatMessage(messages.tooMany)}</FormAlert>
  }
  if (!token || expired || lookup.isError) {
    return (
      <>
        <AuthHeading
          title={intl.formatMessage(messages.invalidTitle)}
          lead={intl.formatMessage(messages.invalidLead)}
        />
        <Button asChild variant="outline" className="w-full">
          <Link to="/login">{intl.formatMessage(messages.signIn)}</Link>
        </Button>
      </>
    )
  }
  if (!lookup.data) {
    return (
      <p role="status" className="text-sm text-muted-foreground">
        {intl.formatMessage(messages.loading)}
      </p>
    )
  }
  const invitation = lookup.data
  return (
    <>
      <AuthHeading
        title={intl.formatMessage(messages.title, { organisation: invitation.organisation })}
        lead={intl.formatMessage(messages.lead, { name: invitation.displayName, email: invitation.email })}
      />
      <FormAlert>{alert}</FormAlert>
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
        <Field
          label={intl.formatMessage(messages.password)}
          hint={intl.formatMessage(messages.passwordHint)}
          required
          error={fieldError}
        >
          {(p) => (
            <Input
              {...p}
              name="password"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value)
              }}
            />
          )}
        </Field>
        <Button type="submit" disabled={password === ''}>
          {intl.formatMessage(messages.accept)}
        </Button>
      </form>
    </>
  )
}
