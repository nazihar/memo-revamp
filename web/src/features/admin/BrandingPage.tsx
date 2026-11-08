import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getAdminBrandingOptions,
  getAdminBrandingQueryKey,
  getBrandingQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { updateBranding } from '@/api/generated/sdk.gen'
import type { Branding } from '@/api/generated/types.gen'
import { problemType, PROBLEM, type Problem } from '@/api/http'
import { FileUpload } from '@/components/page/FileUpload'
import { FormActions, FormGrid } from '@/components/page/FormPage'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { BRAND_CHECKS, contrast, isColour } from '@/lib/contrast'
import { am, amv } from './admin-messages'

/** The default colours, as examples of the format. */
const LIGHT_EXAMPLE = '#1f4e8c'
const DARK_EXAMPLE = '#8db2ea'

/** `/admin/branding` (design E07 §6, §7): logo upload and the two theme colours with live contrast. */
export function BrandingPage() {
  const intl = useIntl()
  const branding = useQuery(getAdminBrandingOptions())
  return (
    <section aria-labelledby="branding-title" className="flex flex-col gap-4">
      <PageHeader
        title={<span id="branding-title">{intl.formatMessage(am.brandingTitle)}</span>}
        description={intl.formatMessage(am.brandingLead)}
      />
      {branding.isPending ? (
        <LoadingState rows={3} />
      ) : branding.isError ? (
        <ErrorState onRetry={() => void branding.refetch()} />
      ) : (
        <BrandingForm key={branding.data.version} view={branding.data} />
      )}
    </section>
  )
}

function ContrastReport({ colour, theme }: { colour: string; theme: 'light' | 'dark' }) {
  const intl = useIntl()
  if (!isColour(colour)) return null
  return (
    <ul className="flex flex-col gap-1 text-xs">
      {BRAND_CHECKS[theme].map(({ against, minimum }) => {
        const ratio = contrast(colour, against)
        const ok = ratio >= minimum
        return (
          <li key={against} className="flex items-center gap-2">
            <Badge tone={ok ? 'ok' : 'danger'}>{intl.formatMessage(ok ? am.contrastOk : am.contrastLow)}</Badge>
            {intl.formatMessage(amv.contrast, {
              ratio: intl.formatNumber(ratio, { maximumFractionDigits: 2 }),
              minimum: intl.formatNumber(minimum, { maximumFractionDigits: 1 }),
            })}
          </li>
        )
      })}
    </ul>
  )
}

function BrandingForm({ view }: { view: Branding }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [light, setLight] = useState(view.colourLight ?? '')
  const [dark, setDark] = useState(view.colourDark ?? '')
  const [logo, setLogo] = useState(view.logoFileId ?? undefined)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  const save = async (logoFileId: string | undefined = logo) => {
    setBusy(true)
    setErrors({})
    try {
      await updateBranding({
        headers: { 'If-Match': `"${String(view.version)}"` },
        body: { colourLight: light.trim() || undefined, colourDark: dark.trim() || undefined, logoFileId },
        throwOnError: true,
      })
      toast.show(intl.formatMessage(am.saved))
    } catch (err) {
      setErrors(Object.fromEntries(((err as Problem).errors ?? []).map((p) => [p.field, p.message])))
      toast.show(intl.formatMessage(problemType(err) === PROBLEM.conflict ? am.stale : am.invalid), 'error')
    } finally {
      setBusy(false)
      await queryClient.invalidateQueries({ queryKey: getAdminBrandingQueryKey() })
      await queryClient.invalidateQueries({ queryKey: getBrandingQueryKey() })
    }
  }

  return (
    <form
      noValidate
      className="flex max-w-2xl flex-col gap-6"
      onSubmit={(e) => {
        e.preventDefault()
        void save()
      }}
    >
      <div className="flex flex-col gap-2">
        <FileUpload
          label={intl.formatMessage(am.logo)}
          hint={intl.formatMessage(am.logoHint)}
          purpose="tenant.logo"
          owner="tenant"
          accept="image/png,image/jpeg,image/webp"
          maxBytes={512 * 1024}
          onUploaded={({ fileId }) => {
            setLogo(fileId)
            void save(fileId)
          }}
        />
        {errors.logoFileId ? (
          <p role="alert" className="text-sm text-destructive">
            {errors.logoFileId}
          </p>
        ) : null}
        {logo ? (
          <Button variant="outline" className="self-start" disabled={busy} onClick={() => void save(undefined)}>
            {intl.formatMessage(am.logoRemove)}
          </Button>
        ) : (
          <p className="text-sm text-muted-foreground">{intl.formatMessage(am.logoNone)}</p>
        )}
      </div>
      <FormGrid>
        <div className="flex flex-col gap-2">
          <Field
            label={intl.formatMessage(am.colourLight)}
            hint={intl.formatMessage(am.colourHint)}
            error={errors.colourLight}
          >
            {(p) => (
              <Input
                {...p}
                value={light}
                placeholder={LIGHT_EXAMPLE}
                spellCheck={false}
                onChange={(e) => {
                  setLight(e.target.value)
                }}
              />
            )}
          </Field>
          <ContrastReport colour={light.trim()} theme="light" />
        </div>
        <div className="flex flex-col gap-2">
          <Field
            label={intl.formatMessage(am.colourDark)}
            hint={intl.formatMessage(am.colourHint)}
            error={errors.colourDark}
          >
            {(p) => (
              <Input
                {...p}
                value={dark}
                placeholder={DARK_EXAMPLE}
                spellCheck={false}
                onChange={(e) => {
                  setDark(e.target.value)
                }}
              />
            )}
          </Field>
          <ContrastReport colour={dark.trim()} theme="dark" />
        </div>
      </FormGrid>
      <FormActions>
        <Button type="submit" disabled={busy}>
          {intl.formatMessage(am.save)}
        </Button>
      </FormActions>
    </form>
  )
}
