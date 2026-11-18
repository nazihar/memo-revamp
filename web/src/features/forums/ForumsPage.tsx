import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Plus } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { listForumsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { ForumResponse } from '@/api/generated/types.gen'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { PERMISSION, holds, useMyAccess } from '@/features/access/access'
import { useTerms } from '@/features/terms/terms'
import { NewForumDialog } from './ForumDialogs'
import { fm, fmt, fmv } from './messages'

/**
 * `/committees` (flow K1, ADR-0056): the committees the user may see, with their type, chair today, members in office
 * and terms that need attention. Administrators (`forums.manage`) see every committee and start new ones.
 */
export function ForumsPage() {
  const intl = useIntl()
  const terms = useTerms()
  const forums = useQuery(listForumsOptions())
  const access = useMyAccess()
  const [adding, setAdding] = useState(false)

  const columns: DataTableColumn<ForumResponse>[] = [
    {
      id: 'name',
      header: intl.formatMessage(fm.colName),
      cell: ({ row }) => (
        <Link
          to="/committees/$forumId"
          params={{ forumId: row.original.id }}
          className="inline-flex min-h-10 items-center font-medium underline-offset-4 hover:underline"
        >
          {row.original.name}
        </Link>
      ),
    },
    { id: 'code', header: intl.formatMessage(fm.colCode), cell: ({ row }) => <code>{row.original.code}</code> },
    { id: 'type', header: intl.formatMessage(fm.colType), cell: ({ row }) => row.original.typeName },
    {
      id: 'chair',
      header: intl.formatMessage(fm.colChair),
      cell: ({ row }) => row.original.chair?.name ?? intl.formatMessage(fm.none),
    },
    {
      id: 'members',
      header: intl.formatMessage(fm.colMembers),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-2">
          <span>{intl.formatNumber(row.original.memberCount)}</span>
          {row.original.termWarnings > 0 ? (
            <Badge tone="warn">{intl.formatMessage(fmv.warnings, { count: row.original.termWarnings })}</Badge>
          ) : null}
        </span>
      ),
    },
    {
      id: 'status',
      header: intl.formatMessage(fm.colStatus),
      cell: ({ row }) => (
        <Badge tone={row.original.status === 'ACTIVE' ? 'ok' : 'neutral'}>
          {intl.formatMessage(fm[row.original.status])}
        </Badge>
      ),
    },
  ]

  return (
    <section aria-labelledby="forums-title">
      <PageHeader
        title={<span id="forums-title">{intl.formatMessage(fmt.title, terms)}</span>}
        description={intl.formatMessage(fmt.lead, terms)}
        actions={
          holds(access.data, PERMISSION.forumsManage) ? (
            <Button
              onClick={() => {
                setAdding(true)
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(fmt.add, terms)}
            </Button>
          ) : undefined
        }
      />
      <DataTable
        caption={intl.formatMessage(fmt.caption, terms)}
        columns={columns}
        rows={forums.data ?? []}
        getRowId={(row) => row.id}
        state={forums.isPending ? 'loading' : forums.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void forums.refetch() }}
        empty={<EmptyState title={intl.formatMessage(fmt.empty, terms)} />}
      />
      {adding ? (
        <NewForumDialog
          onClose={() => {
            setAdding(false)
          }}
        />
      ) : null}
    </section>
  )
}
