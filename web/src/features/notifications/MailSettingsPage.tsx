import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getMailLogInfiniteOptions,
  getMailSettingsOptions,
  getMailSettingsQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { removeMailSettings, setMailSettings, testMailSettings } from '@/api/generated/sdk.gen'
import type { MailLogEntry, MailSettingsView } from '@/api/generated/types.gen'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { FormActions, FormGrid } from '@/components/page/FormPage'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { nm, nmv, statusLabel, templateLabel } from './notification-messages'

const LOG_PAGE = { query: { limit: 50 } }

interface Form {
  host: string
  port: string
  tlsMode: string
  username: string
  password: string
  fromAddress: string
  fromName: string
}

function formOf(view: MailSettingsView | undefined): Form {
  return {
    host: view?.host ?? '',
    port: String(view?.port ?? 587),
    tlsMode: view?.tlsMode ?? 'STARTTLS',
    username: view?.username ?? '',
    password: '',
    fromAddress: view?.fromAddress ?? '',
    fromName: view?.fromName ?? '',
  }
}

/** The server's validation message, or a generic one (Problem Details `detail`). */
function problemDetail(error: unknown): string {
  const detail = (error as { detail?: unknown } | undefined)?.detail
  return typeof detail === 'string' ? detail : ''
}

/** `/admin/email` (design E06 §8): the organisation's mail relay, a test email and the delivery log. */
export function MailSettingsPage() {
  const intl = useIntl()
  const settings = useQuery(getMailSettingsOptions())
  const current = settings.data ?? undefined
  return (
    <section aria-labelledby="mail-title" className="flex flex-col gap-8">
      <PageHeader
        title={<span id="mail-title">{intl.formatMessage(nm.mailTitle)}</span>}
        description={intl.formatMessage(nm.mailLead)}
      />
      {settings.isPending ? (
        <LoadingState rows={3} />
      ) : settings.isError ? (
        <ErrorState onRetry={() => void settings.refetch()} />
      ) : (
        // A new version of the settings mounts a fresh form, so the fields always start from what is stored.
        <MailSettingsForm key={current?.version ?? 'platform'} current={current} />
      )}
      <MailLog />
    </section>
  )
}

function MailSettingsForm({ current }: { current: MailSettingsView | undefined }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const [form, setForm] = useState<Form>(() => formOf(current))
  const [busy, setBusy] = useState(false)
  const set = (key: keyof Form) => (e: { target: { value: string } }) => {
    setForm((f) => ({ ...f, [key]: e.target.value }))
  }
  const run = async (action: () => Promise<void>) => {
    setBusy(true)
    try {
      await action()
    } finally {
      setBusy(false)
    }
  }
  const save = () =>
    run(async () => {
      try {
        await withStepUp(() =>
          setMailSettings({
            headers: current ? { 'If-Match': `"${String(current.version)}"` } : undefined,
            body: {
              host: form.host.trim(),
              port: Number(form.port),
              tlsMode: form.tlsMode,
              username: form.username.trim() || undefined,
              password: form.password === '' ? undefined : form.password,
              fromAddress: form.fromAddress.trim(),
              fromName: form.fromName.trim() || undefined,
            },
            throwOnError: true,
          }),
        )
        await queryClient.invalidateQueries({ queryKey: getMailSettingsQueryKey() })
        toast.show(intl.formatMessage(nm.mailSaved))
      } catch (error) {
        toast.show(intl.formatMessage(nmv.mailInvalid, { detail: problemDetail(error) }), 'error')
      }
    })
  const remove = () =>
    run(async () => {
      await withStepUp(() => removeMailSettings({ throwOnError: true }))
      await queryClient.invalidateQueries({ queryKey: getMailSettingsQueryKey() })
      toast.show(intl.formatMessage(nm.mailRemoved))
    })
  const test = () =>
    run(async () => {
      const result = await withStepUp(() => testMailSettings({ throwOnError: true }))
      if (result.data.ok) toast.show(intl.formatMessage(nm.testOk))
      else
        toast.show(
          intl.formatMessage(nmv.testFailed, {
            detail: [result.data.smtpCode, result.data.errorClass].filter(Boolean).join(' '),
          }),
          'error',
        )
    })

  return (
    <form
      className="flex max-w-2xl flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault()
        void save()
      }}
    >
      {current ? null : <p className="text-sm text-muted-foreground">{intl.formatMessage(nm.mailPlatform)}</p>}
      <FormGrid>
        <Field label={intl.formatMessage(nm.mailHost)} required>
          {(p) => <Input {...p} value={form.host} onChange={set('host')} autoComplete="off" required />}
        </Field>
        <Field label={intl.formatMessage(nm.mailPort)} required>
          {(p) => <Input {...p} value={form.port} onChange={set('port')} inputMode="numeric" required />}
        </Field>
        <Field label={intl.formatMessage(nm.mailTls)} required>
          {(p) => (
            <Select {...p} value={form.tlsMode} onChange={set('tlsMode')}>
              <option value="STARTTLS">{intl.formatMessage(nm.mailTlsStarttls)}</option>
              <option value="TLS">{intl.formatMessage(nm.mailTlsTls)}</option>
              <option value="NONE">{intl.formatMessage(nm.mailTlsNone)}</option>
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(nm.mailFromAddress)} required>
          {(p) => <Input {...p} type="email" value={form.fromAddress} onChange={set('fromAddress')} required />}
        </Field>
        <Field label={intl.formatMessage(nm.mailFromName)} optionalLabel={intl.formatMessage(nm.optional)}>
          {(p) => <Input {...p} value={form.fromName} onChange={set('fromName')} />}
        </Field>
        <Field label={intl.formatMessage(nm.mailUsername)} optionalLabel={intl.formatMessage(nm.optional)}>
          {(p) => <Input {...p} value={form.username} onChange={set('username')} autoComplete="off" />}
        </Field>
        <Field
          label={intl.formatMessage(nm.mailPassword)}
          optionalLabel={intl.formatMessage(nm.optional)}
          hint={current?.passwordSet ? intl.formatMessage(nm.mailPasswordKeep) : undefined}
        >
          {(p) => (
            <Input
              {...p}
              type="password"
              value={form.password}
              onChange={set('password')}
              autoComplete="new-password"
            />
          )}
        </Field>
      </FormGrid>
      <FormActions>
        <Button type="submit" disabled={busy}>
          {intl.formatMessage(nm.save)}
        </Button>
        <Button type="button" variant="outline" disabled={busy} onClick={() => void test()}>
          {intl.formatMessage(nm.test)}
        </Button>
        {current ? (
          <Button type="button" variant="ghost" disabled={busy} onClick={() => void remove()}>
            {intl.formatMessage(nm.remove)}
          </Button>
        ) : null}
      </FormActions>
    </form>
  )
}

function MailLog() {
  const intl = useIntl()
  const log = useInfiniteQuery({
    ...getMailLogInfiniteOptions(LOG_PAGE),
    initialPageParam: LOG_PAGE,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const rows = log.data?.pages.flatMap((p) => p.entries) ?? []
  const columns: DataTableColumn<MailLogEntry>[] = [
    {
      id: 'when',
      header: intl.formatMessage(nm.colWhen),
      cell: ({ row }) => intl.formatDate(row.original.createdAt, { dateStyle: 'medium', timeStyle: 'short' }),
    },
    {
      id: 'template',
      header: intl.formatMessage(nm.colTemplate),
      cell: ({ row }) => templateLabel(intl, row.original.template),
    },
    { id: 'to', header: intl.formatMessage(nm.colTo), cell: ({ row }) => row.original.to },
    {
      id: 'status',
      header: intl.formatMessage(nm.colStatus),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-2">
          <Badge tone={row.original.status === 'SENT' ? 'ok' : row.original.status === 'FAILED' ? 'danger' : 'neutral'}>
            {statusLabel(intl, row.original.status)}
          </Badge>
          {row.original.smtpCode ? (
            <span className="text-xs text-muted-foreground">{row.original.smtpCode}</span>
          ) : null}
        </span>
      ),
    },
    { id: 'attempts', header: intl.formatMessage(nm.colAttempts), cell: ({ row }) => row.original.attempts },
  ]
  return (
    <section aria-labelledby="mail-log-title" className="flex flex-col gap-3">
      <h2 id="mail-log-title" className="text-lg font-semibold">
        {intl.formatMessage(nm.logTitle)}
      </h2>
      <DataTable
        caption={intl.formatMessage(nm.logCaption)}
        columns={columns}
        rows={rows}
        getRowId={(r) => r.id}
        state={log.isPending ? 'loading' : log.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void log.refetch() }}
        empty={intl.formatMessage(nm.logEmpty)}
      />
      {log.hasNextPage ? (
        <Button
          variant="outline"
          className="self-center"
          disabled={log.isFetchingNextPage}
          onClick={() => void log.fetchNextPage()}
        >
          {intl.formatMessage(nm.loadMore)}
        </Button>
      ) : null}
    </section>
  )
}
