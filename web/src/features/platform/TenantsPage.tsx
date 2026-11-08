import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useDeferredValue, useState } from 'react'
import { useIntl } from 'react-intl'
import { listPlatformTenantsInfiniteOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { PlatformTenant } from '@/api/generated/types.gen'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { pm, statusMessage } from './platform-messages'

/** `/platform` (design E07 §7): every organisation's registry entry, by address, with search. */
export function TenantsPage() {
  const intl = useIntl()
  const [search, setSearch] = useState('')
  const q = useDeferredValue(search.trim())
  const page = { query: { limit: 50, ...(q ? { q } : {}) } }
  const tenants = useInfiniteQuery({
    ...listPlatformTenantsInfiniteOptions(page),
    initialPageParam: page,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const rows = tenants.data?.pages.flatMap((p) => p.items) ?? []
  const columns: DataTableColumn<PlatformTenant>[] = [
    {
      id: 'name',
      header: intl.formatMessage(pm.colName),
      cell: ({ row }) => (
        <Link
          to="/platform/tenants/$tenantId"
          params={{ tenantId: row.original.id }}
          className="font-medium text-primary underline-offset-4 hover:underline"
        >
          {row.original.name}
        </Link>
      ),
    },
    { id: 'slug', header: intl.formatMessage(pm.colSlug), cell: ({ row }) => <code>{row.original.slug}</code> },
    {
      id: 'status',
      header: intl.formatMessage(pm.colStatus),
      cell: ({ row }) => (
        <Badge tone={row.original.status === 'ACTIVE' ? 'ok' : 'warn'}>
          {intl.formatMessage(statusMessage(row.original.status))}
        </Badge>
      ),
    },
    {
      id: 'created',
      header: intl.formatMessage(pm.colCreated),
      cell: ({ row }) => intl.formatDate(row.original.createdAt, { dateStyle: 'medium' }),
    },
  ]
  return (
    <section aria-labelledby="tenants-title" className="flex flex-col gap-4">
      <PageHeader
        title={<span id="tenants-title">{intl.formatMessage(pm.tenantsTitle)}</span>}
        description={intl.formatMessage(pm.tenantsLead)}
        actions={
          <Button asChild>
            <Link to="/platform/tenants/new">{intl.formatMessage(pm.newTenant)}</Link>
          </Button>
        }
      />
      <div className="max-w-sm">
        <Field label={intl.formatMessage(pm.tenantsSearch)}>
          {(p) => (
            <Input
              {...p}
              type="search"
              value={search}
              onChange={(e) => {
                setSearch(e.target.value)
              }}
            />
          )}
        </Field>
      </div>
      <DataTable
        caption={intl.formatMessage(pm.tenantsCaption)}
        columns={columns}
        rows={rows}
        getRowId={(r) => r.id}
        state={tenants.isPending ? 'loading' : tenants.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void tenants.refetch() }}
        empty={intl.formatMessage(pm.tenantsEmpty)}
      />
      {tenants.hasNextPage ? (
        <Button
          variant="outline"
          className="self-center"
          disabled={tenants.isFetchingNextPage}
          onClick={() => void tenants.fetchNextPage()}
        >
          {intl.formatMessage(pm.loadMore)}
        </Button>
      ) : null}
    </section>
  )
}
