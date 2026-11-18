import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useIntl, type IntlShape } from 'react-intl'
import { listForumMembersOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { ForumResponse, MemberResponse } from '@/api/generated/types.gen'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { Badge, type Tone } from '@/components/ui/badge'
import type { MemberAction } from './ForumPage'
import { NameCell, RowMenu } from './MemberRow'
import { fm, fmv } from './messages'
import { formatDay } from './shared'

const tones: Record<MemberResponse['state'], Tone> = {
  UPCOMING: 'neutral',
  IN_OFFICE: 'ok',
  TERM_ENDED: 'warn',
  ENDED: 'neutral',
}

/** Until when a row ran: its end, its planned term end, or no end date. */
function until(intl: IntlShape, m: MemberResponse): string {
  if (m.endedOn) return formatDay(intl, m.endedOn)
  if (m.termEndsOn) return intl.formatMessage(fmv.planned, { date: formatDay(intl, m.termEndsOn) })
  return intl.formatMessage(fm.openEnded)
}

/**
 * History (flow K2): every term of office, newest first. A renewal or a role change is a new row and an ended row stays,
 * so the record of who sat when, in which role and on whose authority is never lost. Rows that have not ended keep
 * their menu here too, which is where a term that ended without renewal is renewed or ended.
 */
export function HistoryTab({
  forum,
  onAction,
}: {
  forum: ForumResponse
  onAction: (action: MemberAction, member: MemberResponse) => void
}) {
  const intl = useIntl()
  const rows = useQuery(listForumMembersOptions({ path: { forumId: forum.id }, query: { history: true } }))

  // Memoised: a cell function is a component to the table, so new functions would remount the row menus.
  const columns = useMemo(
    (): DataTableColumn<MemberResponse>[] => [
      { id: 'name', header: intl.formatMessage(fm.colName), cell: ({ row }) => <NameCell member={row.original} /> },
      {
        id: 'role',
        header: intl.formatMessage(fm.colRole),
        cell: ({ row }) => intl.formatMessage(fm[row.original.role]),
      },
      {
        id: 'term',
        header: intl.formatMessage(fm.colTerm),
        cell: ({ row }) => intl.formatMessage(fmv.termOpen, { n: row.original.termNumber }),
      },
      { id: 'from', header: intl.formatMessage(fm.colFrom), cell: ({ row }) => formatDay(intl, row.original.startsOn) },
      { id: 'to', header: intl.formatMessage(fm.colTo), cell: ({ row }) => until(intl, row.original) },
      {
        id: 'state',
        header: intl.formatMessage(fm.colState),
        cell: ({ row }) => {
          const m = row.original
          const state = intl.formatMessage(fm[m.state])
          const reason = m.endReason ? intl.formatMessage(fm[m.endReason]) : undefined
          return (
            <span className="flex flex-col gap-1">
              <Badge tone={tones[m.state]}>
                {reason ? intl.formatMessage(fmv.endedBecause, { state, reason }) : state}
              </Badge>
              {m.endNote ? <span className="text-xs text-muted-foreground">{m.endNote}</span> : null}
            </span>
          )
        },
      },
      {
        id: 'authority',
        header: intl.formatMessage(fm.colAuthority),
        cell: ({ row }) => row.original.authority ?? '—',
      },
      ...(forum.canManageMembers
        ? [
            {
              id: 'actions',
              header: () => <span className="sr-only">{intl.formatMessage(fm.colActions)}</span>,
              cell: ({ row }) => <RowMenu forum={forum} member={row.original} onAction={onAction} />,
            } satisfies DataTableColumn<MemberResponse>,
          ]
        : []),
    ],
    [intl, forum, onAction],
  )

  return (
    <div className="flex flex-col gap-3">
      <p className="max-w-3xl text-sm text-muted-foreground">{intl.formatMessage(fm.historyLead)}</p>
      <DataTable
        caption={intl.formatMessage(fmv.historyCaption, { name: forum.name })}
        columns={columns}
        rows={rows.data ?? []}
        getRowId={(row) => row.id}
        state={rows.isPending ? 'loading' : rows.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void rows.refetch() }}
      />
    </div>
  )
}
