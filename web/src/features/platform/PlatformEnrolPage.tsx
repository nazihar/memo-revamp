import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useState, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import { acceptPlatformEnrolment, lookUpPlatformEnrolment } from '@/api/generated/sdk.gen'
import type { PlatformEnrolment } from '@/api/generated/types.gen'
import { problemType, PROBLEM } from '@/api/http'
import { LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { AuthHeading, FormAlert } from '@/features/auth/AuthLayout'
import { takeLinkToken } from '@/features/auth/link-token'
import { pm, pmv } from './platform-messages'
import { refreshPlatformSession } from './platform-session'

/** The one-time link from the `operator-invite` command (ADR-0031): choose a password, then set up TOTP. */
export function PlatformEnrolPage() {
  const intl = useIntl()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [token] = useState(takeLinkToken)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const lookup = useQuery({
    queryKey: ['platform-enrolment', token],
    queryFn: async () =>
      (await lookUpPlatformEnrolment({ body: { token: token ?? '' }, throwOnError: true })).data as PlatformEnrolment,
    enabled: token !== undefined,
    retry: false,
  })

  if (token === undefined || lookup.isError) {
    return <AuthHeading title={intl.formatMessage(pm.linkTitle)} lead={intl.formatMessage(pm.linkInvalid)} />
  }
  if (lookup.isPending) return <LoadingState rows={2} />

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      const { data } = await acceptPlatformEnrolment({ body: { token, password }, throwOnError: true })
      await refreshPlatformSession(queryClient, data)
      await navigate({ to: '/platform/login' })
    } catch (err) {
      const type = problemType(err)
      const tokenProblem = (err as { errors?: { field: string }[] }).errors?.some((p) => p.field === 'token')
      setError(
        intl.formatMessage(type === PROBLEM.rateLimited ? pm.tooMany : tokenProblem ? pm.linkInvalid : pm.linkWeak),
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <AuthHeading
        title={intl.formatMessage(pm.linkTitle)}
        lead={intl.formatMessage(pmv.linkLead, { name: lookup.data.displayName, email: lookup.data.email })}
      />
      <FormAlert>{error}</FormAlert>
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
        <Field label={intl.formatMessage(pm.linkPassword)} required>
          {(p) => (
            <Input
              {...p}
              name="new-password"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value)
              }}
            />
          )}
        </Field>
        <Button type="submit" disabled={busy || password.length < 15}>
          {intl.formatMessage(pm.linkSubmit)}
        </Button>
      </form>
    </>
  )
}
