import { useInfiniteQuery } from '@tanstack/react-query'
import { useDeferredValue, useState } from 'react'
import { useIntl } from 'react-intl'
import { queryAuditInfiniteOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { AuditEvent } from '@/api/generated/types.gen'
import { csrfFetch, isProblem, problemType, PROBLEM } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { FormGrid } from '@/components/page/FormPage'
import { PageHeader } from '@/components/page/PageHeader'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { am } from './admin-messages'

const DAY = 24 * 60 * 60 * 1000

/** `yyyy-mm-dd` of [date] in local time, for date inputs. */
function day(date: Date): string {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 10)
}

/** Start of the local day [value] as an ISO instant; the end date includes its whole day. */
function instant(value: string, endOfDay = false): string | undefined {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined
  const start = new Date(`${value}T00:00:00`)
  return new Date(start.getTime() + (endOfDay ? DAY : 0)).toISOString()
}

/** `/admin/audit` (ADR-0033): filter, read newest first, and download the verifiable export. */
export function AuditPage() {
  const intl = useIntl()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const [from, setFrom] = useState(() => day(new Date(Date.now() - 7 * DAY)))
  const [to, setTo] = useState(() => day(new Date()))
  const [action, setAction] = useState('')
  const [objectType, setObjectType] = useState('')
  const filters = useDeferredValue({ from, to, action: action.trim(), objectType: objectType.trim() })
  const query = {
    query: {
      limit: 50,
      from: instant(filters.from),
      to: instant(filters.to, true),
      ...(filters.action ? { action: filters.action } : {}),
      ...(filters.objectType ? { objectType: filters.objectType } : {}),
    },
  }
  const audit = useInfiniteQuery({
    ...queryAuditInfiniteOptions(query),
    initialPageParam: query,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const rows = audit.data?.pages.flatMap((p) => p.items) ?? []

  const actor = (e: AuditEvent) => {
    switch (e.actorType) {
      case 'USER':
        return e.actorName ?? e.actorId ?? ''
      case 'PLATFORM_OPERATOR':
        return intl.formatMessage(am.platform)
      case 'ANONYMOUS':
        return intl.formatMessage(am.anonymous)
      default:
        return `${intl.formatMessage(am.system)} (${e.actorId ?? ''})`
    }
  }
  const columns: DataTableColumn<AuditEvent>[] = [
    {
      id: 'time',
      header: intl.formatMessage(am.colTime),
      cell: ({ row }) => intl.formatDate(row.original.occurredAt, { dateStyle: 'medium', timeStyle: 'medium' }),
    },
    { id: 'actor', header: intl.formatMessage(am.colActor), cell: ({ row }) => actor(row.original) },
    { id: 'action', header: intl.formatMessage(am.colAction), cell: ({ row }) => <code>{row.original.action}</code> },
    {
      id: 'object',
      header: intl.formatMessage(am.colObject),
      cell: ({ row }) => [row.original.objectType, row.original.objectId].filter(Boolean).join(' '),
    },
    {
      id: 'change',
      header: intl.formatMessage(am.colChange),
      cell: ({ row }) =>
        row.original.before || row.original.after ? (
          <details>
            <summary className="cursor-pointer text-primary">{intl.formatMessage(am.details)}</summary>
            <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 text-xs">
              {row.original.before ? (
                <>
                  <dt className="text-muted-foreground">{intl.formatMessage(am.before)}</dt>
                  <dd className="break-all font-mono">{JSON.stringify(row.original.before)}</dd>
                </>
              ) : null}
              {row.original.after ? (
                <>
                  <dt className="text-muted-foreground">{intl.formatMessage(am.after)}</dt>
                  <dd className="break-all font-mono">{JSON.stringify(row.original.after)}</dd>
                </>
              ) : null}
            </dl>
          </details>
        ) : null,
    },
  ]

  const download = async () => {
    try {
      const blob = await withStepUp(async () => {
        const response = await csrfFetch('/api/v1/admin/audit/export', {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            from: instant(from),
            to: instant(to, true),
            action: action.trim() || undefined,
            objectType: objectType.trim() || undefined,
          }),
        })
        if (!response.ok) throw (await response.json().catch(() => ({ status: response.status }))) as unknown
        return response.blob()
      })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `audit-${from}-${to}.zip`
      link.click()
      URL.revokeObjectURL(url)
      toast.show(intl.formatMessage(am.exported))
    } catch (err) {
      toast.show(
        intl.formatMessage(
          problemType(err) === PROBLEM.rateLimited
            ? am.tooMany
            : isProblem(err) && err.status === 400
              ? am.invalid
              : am.failed,
        ),
        'error',
      )
    }
  }

  return (
    <section aria-labelledby="audit-title" className="flex flex-col gap-4">
      <PageHeader
        title={<span id="audit-title">{intl.formatMessage(am.auditTitle)}</span>}
        description={intl.formatMessage(am.auditLead)}
        actions={<Button onClick={() => void download()}>{intl.formatMessage(am.export)}</Button>}
      />
      <p className="text-sm text-muted-foreground">{intl.formatMessage(am.exportHint)}</p>
      <FormGrid>
        <Field label={intl.formatMessage(am.from)}>
          {(p) => (
            <Input
              {...p}
              type="date"
              value={from}
              onChange={(e) => {
                setFrom(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(am.to)}>
          {(p) => (
            <Input
              {...p}
              type="date"
              value={to}
              onChange={(e) => {
                setTo(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(am.action)}>
          {(p) => (
            <Input
              {...p}
              value={action}
              onChange={(e) => {
                setAction(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(am.objectType)}>
          {(p) => (
            <Input
              {...p}
              value={objectType}
              onChange={(e) => {
                setObjectType(e.target.value)
              }}
            />
          )}
        </Field>
      </FormGrid>
      <DataTable
        caption={intl.formatMessage(am.auditCaption)}
        columns={columns}
        rows={rows}
        getRowId={(r) => String(r.seq)}
        state={audit.isPending ? 'loading' : audit.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void audit.refetch() }}
        empty={intl.formatMessage(am.empty)}
      />
      {audit.hasNextPage ? (
        <Button
          variant="outline"
          className="self-center"
          disabled={audit.isFetchingNextPage}
          onClick={() => void audit.fetchNextPage()}
        >
          {intl.formatMessage(am.loadMore)}
        </Button>
      ) : null}
    </section>
  )
}
