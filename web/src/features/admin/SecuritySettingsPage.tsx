import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { getSecuritySettingsOptions, getSecuritySettingsQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { updateSecuritySettings } from '@/api/generated/sdk.gen'
import type { SecuritySettings, SecuritySettingsView } from '@/api/generated/types.gen'
import { problemType, PROBLEM, type Problem } from '@/api/http'
import { FormActions, FormGrid } from '@/components/page/FormPage'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { am } from './admin-messages'

type NumberField = Exclude<keyof SecuritySettings, 'mfaRequired'>

const NUMBER_FIELDS: { field: NumberField; label: keyof typeof am }[] = [
  { field: 'idleMinutes', label: 'idleMinutes' },
  { field: 'absoluteHours', label: 'absoluteHours' },
  { field: 'stepUpMinutes', label: 'stepUpMinutes' },
  { field: 'passwordMinLength', label: 'passwordMinLength' },
  { field: 'throttleAfter', label: 'throttleAfter' },
  { field: 'lockAfter', label: 'lockAfter' },
  { field: 'lockMinutes', label: 'lockMinutes' },
]

/** `/admin/security` (design E07 §7): sign-in and lockout rules within the ranges the server allows. */
export function SecuritySettingsPage() {
  const intl = useIntl()
  const settings = useQuery(getSecuritySettingsOptions())
  return (
    <section aria-labelledby="security-title" className="flex flex-col gap-4">
      <PageHeader
        title={<span id="security-title">{intl.formatMessage(am.securityTitle)}</span>}
        description={intl.formatMessage(am.securityLead)}
      />
      {settings.isPending ? (
        <LoadingState rows={4} />
      ) : settings.isError ? (
        <ErrorState onRetry={() => void settings.refetch()} />
      ) : (
        <SecurityForm key={settings.data.version} view={settings.data} />
      )}
    </section>
  )
}

function SecurityForm({ view }: { view: SecuritySettingsView }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const withStepUp = useWithStepUp()
  const [mfa, setMfa] = useState(view.settings.mfaRequired)
  const [numbers, setNumbers] = useState(() =>
    Object.fromEntries(NUMBER_FIELDS.map(({ field }) => [field, String(view.settings[field])])),
  )
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const limit = (field: string) => view.limits.find((l) => l.field === field)

  const save = async () => {
    setBusy(true)
    setErrors({})
    try {
      await withStepUp(() =>
        updateSecuritySettings({
          headers: { 'If-Match': `"${String(view.version)}"` },
          body: {
            mfaRequired: mfa,
            ...(Object.fromEntries(NUMBER_FIELDS.map(({ field }) => [field, Number(numbers[field])])) as Record<
              NumberField,
              number
            >),
          },
          throwOnError: true,
        }),
      )
      toast.show(intl.formatMessage(am.saved))
      await queryClient.invalidateQueries({ queryKey: getSecuritySettingsQueryKey() })
    } catch (err) {
      if (problemType(err) === PROBLEM.conflict) {
        toast.show(intl.formatMessage(am.stale), 'error')
        await queryClient.invalidateQueries({ queryKey: getSecuritySettingsQueryKey() })
      } else {
        setErrors(Object.fromEntries(((err as Problem).errors ?? []).map((p) => [p.field, p.message])))
        toast.show(intl.formatMessage(am.invalid), 'error')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <form
      noValidate
      className="flex max-w-2xl flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault()
        void save()
      }}
    >
      <Field label={intl.formatMessage(am.mfa)} required error={errors.mfaRequired}>
        {(p) => (
          <Select
            {...p}
            value={mfa}
            onChange={(e) => {
              setMfa(e.target.value)
            }}
          >
            <option value="all">{intl.formatMessage(am.mfaAll)}</option>
            <option value="admins">{intl.formatMessage(am.mfaAdmins)}</option>
          </Select>
        )}
      </Field>
      <FormGrid>
        {NUMBER_FIELDS.map(({ field, label }) => {
          const l = limit(field)
          return (
            <Field
              key={field}
              label={intl.formatMessage(am[label])}
              required
              hint={l ? `${intl.formatNumber(l.min)}–${intl.formatNumber(l.max)}` : undefined}
              error={errors[field]}
            >
              {(p) => (
                <Input
                  {...p}
                  type="number"
                  inputMode="numeric"
                  min={l?.min}
                  max={l?.max}
                  value={numbers[field]}
                  onChange={(e) => {
                    setNumbers((n) => ({ ...n, [field]: e.target.value }))
                  }}
                />
              )}
            </Field>
          )
        })}
      </FormGrid>
      <FormActions>
        <Button type="submit" disabled={busy}>
          {intl.formatMessage(am.save)}
        </Button>
      </FormActions>
    </form>
  )
}
