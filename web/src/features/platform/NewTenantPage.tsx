import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useState, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import { listPlatformTenantsQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { createPlatformTenant } from '@/api/generated/sdk.gen'
import type { Problem } from '@/api/http'
import { FormActions, FormGrid } from '@/components/page/FormPage'
import { PageHeader } from '@/components/page/PageHeader'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { pm } from './platform-messages'

interface Form {
  name: string
  slug: string
  locale: string
  timeZone: string
  adminName: string
  adminEmail: string
}

const LOCALES = ['en', 'ar'] as const

/** Each language in its own words, as language pickers show them. */
function nativeName(code: string): string {
  return new Intl.DisplayNames([code], { type: 'language' }).of(code) ?? code
}

const SLUG = /^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$/

/** The browser's zone as a default; the server checks the IANA id. */
function localZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
}

/** `/platform/tenants/new` (design E07 §3): create an organisation and invite its first administrator. */
export function NewTenantPage() {
  const intl = useIntl()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const [form, setForm] = useState<Form>({
    name: '',
    slug: '',
    locale: 'en',
    timeZone: localZone(),
    adminName: '',
    adminEmail: '',
  })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const set = (key: keyof Form) => (e: { target: { value: string } }) => {
    setForm((f) => ({ ...f, [key]: e.target.value }))
  }
  const slugFormatError = form.slug !== '' && !SLUG.test(form.slug) ? intl.formatMessage(pm.slugHint) : undefined

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    setBusy(true)
    setErrors({})
    try {
      const { data } = await withStepUp(() => createPlatformTenant({ body: form, throwOnError: true }))
      await queryClient.invalidateQueries({ queryKey: listPlatformTenantsQueryKey() })
      toast.show(intl.formatMessage(pm.created))
      await navigate({ to: '/platform/tenants/$tenantId', params: { tenantId: data.id } })
    } catch (err) {
      const problems = (err as Problem).errors ?? []
      setErrors(Object.fromEntries(problems.map((p) => [p.field, p.message])))
      toast.show(intl.formatMessage(pm.invalidForm), 'error')
    } finally {
      setBusy(false)
    }
  }

  const complete = [form.name, form.slug, form.adminName, form.adminEmail, form.timeZone].every((v) => v.trim() !== '')
  return (
    <section aria-labelledby="new-tenant-title" className="flex flex-col gap-4">
      <PageHeader
        title={<span id="new-tenant-title">{intl.formatMessage(pm.newTitle)}</span>}
        description={intl.formatMessage(pm.newLead)}
      />
      <form noValidate className="flex max-w-2xl flex-col gap-4" onSubmit={(e) => void submit(e)}>
        <FormGrid>
          <Field label={intl.formatMessage(pm.name)} required error={errors.name}>
            {(p) => <Input {...p} value={form.name} onChange={set('name')} autoComplete="organization" />}
          </Field>
          <Field
            label={intl.formatMessage(pm.slug)}
            required
            hint={intl.formatMessage(pm.slugHint)}
            error={errors.slug ?? slugFormatError}
          >
            {(p) => (
              <Input
                {...p}
                value={form.slug}
                onChange={(e) => {
                  setForm((f) => ({ ...f, slug: e.target.value.toLowerCase() }))
                }}
                autoComplete="off"
                spellCheck={false}
              />
            )}
          </Field>
          <Field label={intl.formatMessage(pm.locale)} required error={errors.locale}>
            {(p) => (
              <Select {...p} value={form.locale} onChange={set('locale')}>
                {LOCALES.map((code) => (
                  <option key={code} value={code}>
                    {nativeName(code)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label={intl.formatMessage(pm.timeZone)} required error={errors.timeZone}>
            {(p) => <Input {...p} value={form.timeZone} onChange={set('timeZone')} autoComplete="off" />}
          </Field>
          <Field label={intl.formatMessage(pm.adminName)} required error={errors.adminName}>
            {(p) => <Input {...p} value={form.adminName} onChange={set('adminName')} autoComplete="off" />}
          </Field>
          <Field label={intl.formatMessage(pm.adminEmail)} required error={errors.adminEmail}>
            {(p) => (
              <Input {...p} type="email" value={form.adminEmail} onChange={set('adminEmail')} autoComplete="off" />
            )}
          </Field>
        </FormGrid>
        <FormActions>
          <Button variant="outline" asChild>
            <Link to="/platform">{intl.formatMessage(pm.cancel)}</Link>
          </Button>
          <Button type="submit" disabled={busy || !complete || slugFormatError !== undefined}>
            {intl.formatMessage(pm.create)}
          </Button>
        </FormActions>
      </form>
    </section>
  )
}
