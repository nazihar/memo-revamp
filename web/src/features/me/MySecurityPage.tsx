import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useState, type SyntheticEvent, type ReactNode } from 'react'
import { defineMessages, useIntl, type NoMessageValues } from 'react-intl'
import { getMeQueryKey, listMySessionsOptions, listMySessionsQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { changeMyPassword, regenerateMyRecoveryCodes, revokeMySession } from '@/api/generated/sdk.gen'
import type { MySession } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { passwordProblemMessage } from '@/features/auth/password-messages'
import { RecoveryCodeList } from '@/features/auth/RecoveryCodeList'
import { useMe } from '@/features/auth/session'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { deviceLabel } from './device-label'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'

const m = defineMessages<{
  title: NoMessageValues
  lead: NoMessageValues
  password: NoMessageValues
  passwordLead: NoMessageValues
  current: NoMessageValues
  new: NoMessageValues
  newHint: NoMessageValues
  change: NoMessageValues
  changed: NoMessageValues
  failed: NoMessageValues
  mfa: NoMessageValues
  mfaOn: NoMessageValues
  mfaOff: NoMessageValues
  newCodes: NoMessageValues
  newDevice: NoMessageValues
  codesTitle: NoMessageValues
  codesLead: NoMessageValues
  done: NoMessageValues
  sessions: NoMessageValues
  sessionsLead: NoMessageValues
  device: NoMessageValues
  address: NoMessageValues
  signedIn: NoMessageValues
  lastActive: NoMessageValues
  actions: NoMessageValues
  thisDevice: NoMessageValues
  unknownDevice: NoMessageValues
  on: { browser: string; os: string }
  signOut: NoMessageValues
  signedOut: NoMessageValues
  notRecorded: NoMessageValues
}>({
  title: { id: 'security.title', defaultMessage: 'My security' },
  lead: { id: 'security.lead', defaultMessage: 'How you sign in, and where you are signed in.' },
  password: { id: 'security.password', defaultMessage: 'Password' },
  passwordLead: {
    id: 'security.password.lead',
    defaultMessage: 'Changing it signs you out on every other device.',
  },
  current: { id: 'security.password.current', defaultMessage: 'Current password' },
  new: { id: 'security.password.new', defaultMessage: 'New password' },
  newHint: {
    id: 'security.password.new.hint',
    defaultMessage: 'At least 12 characters. A long phrase is easier to remember than a complex word.',
  },
  change: { id: 'security.password.change', defaultMessage: 'Change password' },
  changed: { id: 'security.password.changed', defaultMessage: 'Password changed. Other devices were signed out.' },
  failed: { id: 'security.failed', defaultMessage: 'That did not work. Try again.' },
  mfa: { id: 'security.mfa', defaultMessage: 'Two-step sign-in' },
  mfaOn: {
    id: 'security.mfa.on',
    defaultMessage: 'On: you confirm each sign-in with a code from your authenticator app.',
  },
  mfaOff: { id: 'security.mfa.off', defaultMessage: 'Off: your organisation does not require it for your account.' },
  newCodes: { id: 'security.mfa.newCodes', defaultMessage: 'New recovery codes' },
  newDevice: { id: 'security.mfa.newDevice', defaultMessage: 'Set up a new authenticator' },
  codesTitle: { id: 'security.codes.title', defaultMessage: 'Your new recovery codes' },
  codesLead: {
    id: 'security.codes.lead',
    defaultMessage: 'Your previous codes no longer work. Store these somewhere safe; they are shown only now.',
  },
  done: { id: 'security.codes.done', defaultMessage: 'I have saved them' },
  sessions: { id: 'security.sessions', defaultMessage: 'Signed-in devices' },
  sessionsLead: { id: 'security.sessions.lead', defaultMessage: 'Sign out any device you do not recognise.' },
  device: { id: 'security.sessions.device', defaultMessage: 'Device' },
  address: { id: 'security.sessions.address', defaultMessage: 'Address' },
  signedIn: { id: 'security.sessions.signedIn', defaultMessage: 'Signed in' },
  lastActive: { id: 'security.sessions.lastActive', defaultMessage: 'Last active' },
  actions: { id: 'security.sessions.actions', defaultMessage: 'Actions' },
  thisDevice: { id: 'security.sessions.this', defaultMessage: 'This device' },
  unknownDevice: { id: 'security.sessions.unknown', defaultMessage: 'Unknown device' },
  on: { id: 'security.sessions.on', defaultMessage: '{browser} on {os}' },
  signOut: { id: 'security.sessions.signOut', defaultMessage: 'Sign out' },
  signedOut: { id: 'security.sessions.signedOut', defaultMessage: 'The device was signed out.' },
  notRecorded: { id: 'security.sessions.notRecorded', defaultMessage: 'Not recorded' },
})

/** `/me/security` (E03 design §11): password, two-step sign-in and signed-in devices. */
export function MySecurityPage() {
  const intl = useIntl()
  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <PageHeader title={intl.formatMessage(m.title)} description={intl.formatMessage(m.lead)} className="mb-0" />
      <PasswordSection />
      <MfaSection />
      <SessionsSection />
    </div>
  )
}

function Section({ id, title, lead, children }: { id: string; title: string; lead?: string; children: ReactNode }) {
  return (
    <section aria-labelledby={id} className="rounded-lg border bg-card p-5 text-card-foreground">
      <h2 id={id} className="text-lg font-semibold">
        {title}
      </h2>
      {lead ? <p className="mt-1 text-sm text-muted-foreground">{lead}</p> : null}
      <div className="mt-4">{children}</div>
    </section>
  )
}

function PasswordSection() {
  const intl = useIntl()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [errors, setErrors] = useState<{ currentPassword?: string; newPassword?: string }>({})
  const [busy, setBusy] = useState(false)

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    setBusy(true)
    setErrors({})
    try {
      await withStepUp(() =>
        changeMyPassword({ body: { currentPassword: current, newPassword: next }, throwOnError: true }),
      )
      setCurrent('')
      setNext('')
      toast.show(intl.formatMessage(m.changed))
    } catch (err) {
      const fieldErrors = isProblem(err) ? (err.errors ?? []) : []
      if (fieldErrors.length === 0) toast.show(intl.formatMessage(m.failed), 'error')
      setErrors(Object.fromEntries(fieldErrors.map((f) => [f.field, passwordProblemMessage(intl, f.code)])))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Section id="security-password" title={intl.formatMessage(m.password)} lead={intl.formatMessage(m.passwordLead)}>
      <form noValidate onSubmit={(e) => void submit(e)} className="grid gap-4 md:grid-cols-2">
        <Field label={intl.formatMessage(m.current)} required error={errors.currentPassword}>
          {(p) => (
            <Input
              {...p}
              name="currentPassword"
              type="password"
              autoComplete="current-password"
              value={current}
              onChange={(e) => {
                setCurrent(e.target.value)
              }}
            />
          )}
        </Field>
        <Field
          label={intl.formatMessage(m.new)}
          hint={intl.formatMessage(m.newHint)}
          required
          error={errors.newPassword}
        >
          {(p) => (
            <Input
              {...p}
              name="newPassword"
              type="password"
              autoComplete="new-password"
              value={next}
              onChange={(e) => {
                setNext(e.target.value)
              }}
            />
          )}
        </Field>
        <div className="md:col-span-2">
          <Button type="submit" disabled={busy || current === '' || next === ''}>
            {intl.formatMessage(m.change)}
          </Button>
        </div>
      </form>
    </Section>
  )
}

function MfaSection() {
  const intl = useIntl()
  const toast = useToast()
  const navigate = useNavigate()
  const withStepUp = useWithStepUp()
  const me = useMe()
  const [codes, setCodes] = useState<string[]>()
  const regenerate = useMutation({
    mutationFn: () =>
      withStepUp(() => regenerateMyRecoveryCodes({ throwOnError: true }).then((r) => r.data.recoveryCodes)),
    onSuccess: setCodes,
    onError: () => {
      toast.show(intl.formatMessage(m.failed), 'error')
    },
  })
  const enrolled = me.data?.mfaEnrolled
  return (
    <Section id="security-mfa" title={intl.formatMessage(m.mfa)}>
      {me.isPending ? (
        <LoadingState rows={1} />
      ) : (
        <>
          <p className="text-sm">{intl.formatMessage(enrolled ? m.mfaOn : m.mfaOff)}</p>
          {enrolled ? (
            <div className="mt-4 flex flex-wrap gap-2">
              <Button
                variant="outline"
                disabled={regenerate.isPending}
                onClick={() => {
                  regenerate.mutate()
                }}
              >
                {intl.formatMessage(m.newCodes)}
              </Button>
              <Button variant="outline" onClick={() => void navigate({ to: '/login/enrol-mfa' })}>
                {intl.formatMessage(m.newDevice)}
              </Button>
            </div>
          ) : null}
        </>
      )}
      <Dialog
        open={!!codes}
        onOpenChange={(open) => {
          if (!open) setCodes(undefined)
        }}
        title={intl.formatMessage(m.codesTitle)}
        description={intl.formatMessage(m.codesLead)}
        footer={
          <Button
            onClick={() => {
              setCodes(undefined)
            }}
          >
            {intl.formatMessage(m.done)}
          </Button>
        }
      >
        {codes ? <RecoveryCodeList codes={codes} /> : null}
      </Dialog>
    </Section>
  )
}

function SessionsSection() {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const sessions = useQuery(listMySessionsOptions())
  const revoke = useMutation({
    mutationFn: (id: string) => revokeMySession({ path: { id }, throwOnError: true }),
    onSuccess: async () => {
      toast.show(intl.formatMessage(m.signedOut))
      await queryClient.invalidateQueries({ queryKey: listMySessionsQueryKey() })
      await queryClient.invalidateQueries({ queryKey: getMeQueryKey() })
    },
    onError: () => {
      toast.show(intl.formatMessage(m.failed), 'error')
    },
  })
  const when = (value: string | undefined | null) =>
    value ? intl.formatDate(value, { dateStyle: 'medium', timeStyle: 'short' }) : '—'
  const device = (s: MySession) => {
    const d = deviceLabel(s.userAgent)
    return d.browser && d.os
      ? intl.formatMessage(m.on, { browser: d.browser, os: d.os })
      : (d.browser ?? d.os ?? intl.formatMessage(m.unknownDevice))
  }

  return (
    <Section id="security-sessions" title={intl.formatMessage(m.sessions)} lead={intl.formatMessage(m.sessionsLead)}>
      {sessions.isPending ? <LoadingState rows={2} /> : null}
      {sessions.isError ? <ErrorState onRetry={() => void sessions.refetch()} /> : null}
      {sessions.data ? (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-start text-muted-foreground">
              <tr className="border-b">
                <th scope="col" className="py-2 pe-3 text-start font-medium">
                  {intl.formatMessage(m.device)}
                </th>
                <th scope="col" className="py-2 pe-3 text-start font-medium">
                  {intl.formatMessage(m.address)}
                </th>
                <th scope="col" className="py-2 pe-3 text-start font-medium">
                  {intl.formatMessage(m.signedIn)}
                </th>
                <th scope="col" className="py-2 pe-3 text-start font-medium">
                  {intl.formatMessage(m.lastActive)}
                </th>
                <th scope="col" className="py-2 text-end font-medium">
                  <span className="sr-only">{intl.formatMessage(m.actions)}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {sessions.data.map((s) => (
                <tr key={s.id} className="border-b last:border-b-0">
                  <td className="py-2 pe-3">
                    <span className="flex flex-wrap items-center gap-2">
                      {device(s)}
                      {s.current ? <Badge tone="neutral">{intl.formatMessage(m.thisDevice)}</Badge> : null}
                    </span>
                  </td>
                  <td className="py-2 pe-3" dir="ltr">
                    {s.ipAddress ?? intl.formatMessage(m.notRecorded)}
                  </td>
                  <td className="py-2 pe-3">{when(s.signedInAt)}</td>
                  <td className="py-2 pe-3">{when(s.lastActiveAt)}</td>
                  <td className="py-2 text-end">
                    {s.current ? null : (
                      <Button
                        variant="outline"
                        disabled={revoke.isPending}
                        onClick={() => {
                          revoke.mutate(s.id)
                        }}
                      >
                        {intl.formatMessage(m.signOut)}
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </Section>
  )
}
