import { useInfiniteQuery } from '@tanstack/react-query'
import { useDeferredValue, useState } from 'react'
import { useIntl } from 'react-intl'
import { queryPlatformAuditInfiniteOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { PlatformAuditEvent } from '@/api/generated/types.gen'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { pm } from './platform-messages'

/** `/platform/audit` (ADR-0031): the platform chain, newest first, filtered by action prefix. */
export function PlatformAuditPage() {
  const intl = useIntl()
  const [action, setAction] = useState('')
  const filter = useDeferredValue(action.trim())
  const page = { query: { limit: 50, ...(filter ? { action: filter } : {}) } }
  const audit = useInfiniteQuery({
    ...queryPlatformAuditInfiniteOptions(page),
    initialPageParam: page,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const rows = audit.data?.pages.flatMap((p) => p.items) ?? []
  const columns: DataTableColumn<PlatformAuditEvent>[] = [
    {
      id: 'time',
      header: intl.formatMessage(pm.colTime),
      cell: ({ row }) => intl.formatDate(row.original.occurredAt, { dateStyle: 'medium', timeStyle: 'medium' }),
    },
    { id: 'action', header: intl.formatMessage(pm.colAction), cell: ({ row }) => <code>{row.original.action}</code> },
    {
      id: 'actor',
      header: intl.formatMessage(pm.colActor),
      cell: ({ row }) => [row.original.actorType, row.original.actorId].filter(Boolean).join(' '),
    },
    {
      id: 'object',
      header: intl.formatMessage(pm.colObject),
      cell: ({ row }) => [row.original.objectType, row.original.objectId].filter(Boolean).join(' '),
    },
  ]
  return (
    <section aria-labelledby="platform-audit-title" className="flex flex-col gap-4">
      <PageHeader
        title={<span id="platform-audit-title">{intl.formatMessage(pm.auditTitle)}</span>}
        description={intl.formatMessage(pm.auditLead)}
      />
      <div className="max-w-sm">
        <Field label={intl.formatMessage(pm.auditFilter)}>
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
      </div>
      <DataTable
        caption={intl.formatMessage(pm.auditCaption)}
        columns={columns}
        rows={rows}
        getRowId={(r) => String(r.seq)}
        state={audit.isPending ? 'loading' : audit.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void audit.refetch() }}
      />
      {audit.hasNextPage ? (
        <Button
          variant="outline"
          className="self-center"
          disabled={audit.isFetchingNextPage}
          onClick={() => void audit.fetchNextPage()}
        >
          {intl.formatMessage(pm.loadMore)}
        </Button>
      ) : null}
    </section>
  )
}
