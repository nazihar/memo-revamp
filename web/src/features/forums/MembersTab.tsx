import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { useIntl } from 'react-intl'
import { listForumMembersOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { ForumPolicyResponse, ForumResponse, MemberResponse } from '@/api/generated/types.gen'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { EmptyState } from '@/components/page/States'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { quorumSentence } from '@/features/forum-types/quorum-words'
import { ruleOf } from '@/features/forum-types/settings'
import type { MemberAction } from './ForumPage'
import { NameCell, RowMenu, TermCell } from './MemberRow'
import { fm, fmv } from './messages'
import { attributeLabels, attributeNames, formatDay, today } from './shared'

/**
 * Members on a date (flow K2): the members in office that day, the quorum that day ("Quorum today: 3 of 5 voting
 * members") with the rule in words, and a row menu for those who manage members.
 */
export function MembersTab({
  forum,
  policy,
  on,
  onDate,
  onAction,
}: {
  forum: ForumResponse
  policy?: ForumPolicyResponse
  on: string
  onDate: (day: string) => void
  onAction: (action: MemberAction, member: MemberResponse) => void
}) {
  const intl = useIntl()
  const members = useQuery({
    ...listForumMembersOptions({ path: { forumId: forum.id }, query: { on } }),
    enabled: Boolean(on),
  })
  const date = on ? formatDay(intl, on) : ''
  const labels = attributeNames(policy)

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
        id: 'votes',
        header: intl.formatMessage(fm.colVotes),
        cell: ({ row }) => intl.formatMessage(row.original.voting ? fm.yes : fm.no),
      },
      {
        id: 'attributes',
        header: intl.formatMessage(fm.colAttributes),
        cell: ({ row }) => intl.formatList(attributeLabels(row.original, policy)) || '—',
      },
      {
        id: 'alternate',
        header: intl.formatMessage(fm.colAlternate),
        cell: ({ row }) => row.original.alternate?.name ?? '—',
      },
      { id: 'term', header: intl.formatMessage(fm.colTerm), cell: ({ row }) => <TermCell member={row.original} /> },
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
    [intl, forum, policy, onAction],
  )

  const who = policy?.quorum.basis === 'SEATS' ? 'seats' : policy?.quorum.counts === 'ALL' ? 'all' : 'voting'
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <Field label={intl.formatMessage(fm.membersOn)} className="w-52">
          {(p) => (
            <Input
              {...p}
              type="date"
              value={on}
              onChange={(e) => {
                onDate(e.target.value)
              }}
            />
          )}
        </Field>
        {policy && on ? (
          <div className="flex max-w-xl flex-col gap-1 pb-1 text-sm sm:items-end sm:text-end">
            <p aria-live="polite" className="font-semibold">
              {on === today()
                ? intl.formatMessage(fmv.quorumToday, {
                    needed: policy.quorumNeeded,
                    basis: policy.quorumBasis,
                    who,
                  })
                : intl.formatMessage(fmv.quorumOn, {
                    needed: policy.quorumNeeded,
                    basis: policy.quorumBasis,
                    who,
                    date,
                  })}
            </p>
            <p className="text-muted-foreground">{quorumSentence(intl, ruleOf(policy.quorum), labels)}</p>
          </div>
        ) : null}
      </div>
      <DataTable
        caption={intl.formatMessage(fmv.membersCaption, { date })}
        columns={columns}
        rows={members.data ?? []}
        getRowId={(row) => row.id}
        state={!on ? 'ready' : members.isPending ? 'loading' : members.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void members.refetch() }}
        empty={on ? <EmptyState title={intl.formatMessage(fmv.noMembersOn, { date })} /> : null}
      />
    </div>
  )
}
