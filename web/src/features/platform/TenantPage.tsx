import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useState, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import {
  getPlatformTenantOptions,
  getPlatformTenantQueryKey,
  listPlatformTenantsQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import {
  invitePlatformTenantFirstAdmin,
  renamePlatformTenant,
  resumePlatformTenant,
  suspendPlatformTenant,
} from '@/api/generated/sdk.gen'
import type { PlatformTenant } from '@/api/generated/types.gen'
import { problemType, PROBLEM, type Problem } from '@/api/http'
import { FormActions } from '@/components/page/FormPage'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { AlertDialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { pm, statusMessage } from './platform-messages'

/** `/platform/tenants/$tenantId` (design E07 §7): status, name and the first administrator of one organisation. */
export function TenantPage({ tenantId }: { tenantId: string }) {
  const intl = useIntl()
  const tenant = useQuery(getPlatformTenantOptions({ path: { id: tenantId } }))
  return (
    <section aria-labelledby="tenant-title" className="flex flex-col gap-6">
      <Link to="/platform" className="text-sm text-primary underline-offset-4 hover:underline">
        {intl.formatMessage(pm.back)}
      </Link>
      {tenant.isPending ? (
        <LoadingState rows={3} />
      ) : tenant.isError ? (
        <ErrorState onRetry={() => void tenant.refetch()} />
      ) : (
        <TenantDetail key={tenant.data.version} tenant={tenant.data} />
      )}
    </section>
  )
}

function TenantDetail({ tenant }: { tenant: PlatformTenant }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const [name, setName] = useState(tenant.name)
  const [confirmSuspend, setConfirmSuspend] = useState(false)
  const [busy, setBusy] = useState(false)
  const ifMatch = { 'If-Match': `"${String(tenant.version)}"` }
  const path = { id: tenant.id }

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: getPlatformTenantQueryKey({ path }) })
    await queryClient.invalidateQueries({ queryKey: listPlatformTenantsQueryKey() })
  }
  const run = async (action: () => Promise<unknown>, done: string) => {
    setBusy(true)
    try {
      await withStepUp(action)
      toast.show(done)
    } catch (err) {
      toast.show(intl.formatMessage(problemType(err) === PROBLEM.conflict ? pm.stale : pm.failed), 'error')
    } finally {
      setBusy(false)
      await refresh()
    }
  }

  return (
    <>
      <PageHeader
        title={<span id="tenant-title">{tenant.name}</span>}
        status={
          <Badge tone={tenant.status === 'ACTIVE' ? 'ok' : 'warn'}>
            {intl.formatMessage(statusMessage(tenant.status))}
          </Badge>
        }
        description={<code>{tenant.slug}</code>}
        actions={
          tenant.status === 'ACTIVE' ? (
            <Button
              variant="destructive"
              disabled={busy}
              onClick={() => {
                setConfirmSuspend(true)
              }}
            >
              {intl.formatMessage(pm.suspend)}
            </Button>
          ) : (
            <Button
              disabled={busy}
              onClick={() =>
                void run(
                  () => resumePlatformTenant({ path, headers: ifMatch, throwOnError: true }),
                  intl.formatMessage(pm.resumed),
                )
              }
            >
              {intl.formatMessage(pm.resume)}
            </Button>
          )
        }
      />
      <dl className="grid max-w-2xl grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
        <dt className="text-muted-foreground">{intl.formatMessage(pm.createdAt)}</dt>
        <dd>{intl.formatDate(tenant.createdAt, { dateStyle: 'medium', timeStyle: 'short' })}</dd>
        {tenant.statusChangedAt ? (
          <>
            <dt className="text-muted-foreground">{intl.formatMessage(pm.statusChangedAt)}</dt>
            <dd>{intl.formatDate(tenant.statusChangedAt, { dateStyle: 'medium', timeStyle: 'short' })}</dd>
          </>
        ) : null}
        <dt className="text-muted-foreground">{intl.formatMessage(pm.admin)}</dt>
        <dd>{intl.formatMessage(tenant.hasAdmin ? pm.adminYes : pm.adminNo)}</dd>
      </dl>

      <form
        className="flex max-w-md flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault()
          void run(
            () => renamePlatformTenant({ path, headers: ifMatch, body: { name: name.trim() }, throwOnError: true }),
            intl.formatMessage(pm.renamed),
          )
        }}
      >
        <Field label={intl.formatMessage(pm.name)} required>
          {(p) => (
            <Input
              {...p}
              value={name}
              onChange={(e) => {
                setName(e.target.value)
              }}
            />
          )}
        </Field>
        <FormActions>
          <Button type="submit" variant="outline" disabled={busy || name.trim() === '' || name.trim() === tenant.name}>
            {intl.formatMessage(pm.rename)}
          </Button>
        </FormActions>
      </form>

      {tenant.hasAdmin ? null : <FirstAdminForm tenant={tenant} onDone={refresh} />}

      <AlertDialog
        open={confirmSuspend}
        onOpenChange={setConfirmSuspend}
        title={intl.formatMessage(pm.suspendTitle)}
        description={intl.formatMessage(pm.suspendEffects)}
        confirmLabel={intl.formatMessage(pm.suspend)}
        cancelLabel={intl.formatMessage(pm.cancel)}
        destructive
        onConfirm={() =>
          void run(
            () => suspendPlatformTenant({ path, headers: ifMatch, throwOnError: true }),
            intl.formatMessage(pm.suspended),
          )
        }
      />
    </>
  )
}

function FirstAdminForm({ tenant, onDone }: { tenant: PlatformTenant; onDone: () => Promise<void> }) {
  const intl = useIntl()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      await withStepUp(() =>
        invitePlatformTenantFirstAdmin({
          path: { id: tenant.id },
          body: { email: email.trim(), displayName: displayName.trim() },
          throwOnError: true,
        }),
      )
      toast.show(intl.formatMessage(pm.invited))
      setEmail('')
      setDisplayName('')
    } catch (err) {
      const status = (err as Problem).status
      toast.show(intl.formatMessage(status === 409 ? pm.hasAdmin : pm.invalidForm), 'error')
    } finally {
      setBusy(false)
      await onDone()
    }
  }
  return (
    <section aria-labelledby="invite-title" className="flex max-w-md flex-col gap-3">
      <h2 id="invite-title" className="text-lg font-semibold">
        {intl.formatMessage(pm.inviteTitle)}
      </h2>
      <p className="text-sm text-muted-foreground">{intl.formatMessage(pm.inviteLead)}</p>
      <form noValidate className="flex flex-col gap-3" onSubmit={(e) => void submit(e)}>
        <Field label={intl.formatMessage(pm.adminName)} required>
          {(p) => (
            <Input
              {...p}
              value={displayName}
              onChange={(e) => {
                setDisplayName(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(pm.adminEmail)} required>
          {(p) => (
            <Input
              {...p}
              type="email"
              value={email}
              onChange={(e) => {
                setEmail(e.target.value)
              }}
            />
          )}
        </Field>
        <FormActions>
          <Button type="submit" disabled={busy || email.trim() === '' || displayName.trim() === ''}>
            {intl.formatMessage(pm.invite)}
          </Button>
        </FormActions>
      </form>
    </section>
  )
}
