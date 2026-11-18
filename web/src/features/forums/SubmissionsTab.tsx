import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { ChevronDown } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useIntl } from 'react-intl'
import { listForumSubmissionsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { ForumPolicyResponse, ForumResponse } from '@/api/generated/types.gen'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { EmptyState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { useTerms } from '@/features/terms/terms'
import { cn } from '@/lib/utils'
import { openPapersOptions, paperName, paperTone, type Submission, type SubmissionStatus } from './papers'
import { pm } from './papers-messages'
import { sm, smt, smv } from './submission-messages'
import { actionsFor, ageInDays, triages, waitingTooLong, type TriageAction } from './submissions'
import { TriageDialog } from './TriageDialogs'

type Filter = 'waiting' | 'accepted' | 'deferred' | 'closed'
const FILTERS: Filter[] = ['waiting', 'accepted', 'deferred', 'closed']
const OF_FILTER: Record<Exclude<Filter, 'closed'>, SubmissionStatus> = {
  waiting: 'QUEUED',
  accepted: 'ACCEPTED',
  deferred: 'DEFERRED',
}
const CLOSED: SubmissionStatus[] = ['DECIDED', 'RETURNED', 'DECLINED', 'WITHDRAWN', 'REFERRED']

/**
 * The Submissions tab (flow M1, design E02 §10): the committee's papers, oldest first, by Waiting, Accepted, Deferred
 * and Closed, with purpose, memo (or "Restricted"), sender, age, flags and status. Secretaries and administrators get a
 * Triage menu per open paper; the chair reads.
 */
export function SubmissionsTab({ forum, policy }: { forum: ForumResponse; policy?: ForumPolicyResponse }) {
  const intl = useIntl()
  const [filter, setFilter] = useState<Filter>('waiting')
  const [open, setOpen] = useState<{ action: TriageAction; submission: Submission }>()
  const openPapers = useQuery(openPapersOptions(forum.id))
  const closed = useQuery({
    ...listForumSubmissionsOptions({ path: { forumId: forum.id }, query: { status: CLOSED } }),
    enabled: filter === 'closed',
  })
  const canTriage = triages(forum)
  const query = filter === 'closed' ? closed : openPapers
  const rows =
    filter === 'closed' ? (closed.data ?? []) : (openPapers.data ?? []).filter((s) => s.status === OF_FILTER[filter])
  const count = (f: Filter) =>
    f === 'closed' ? undefined : (openPapers.data ?? []).filter((s) => s.status === OF_FILTER[f]).length
  const labels: Record<Filter, string> = {
    waiting: intl.formatMessage(sm.waiting),
    accepted: intl.formatMessage(sm.accepted),
    deferred: intl.formatMessage(sm.deferred),
    closed: intl.formatMessage(sm.closed),
  }
  const empties: Record<Filter, string> = {
    waiting: intl.formatMessage(sm.emptyWaiting),
    accepted: intl.formatMessage(sm.emptyAccepted),
    deferred: intl.formatMessage(sm.emptyDeferred),
    closed: intl.formatMessage(sm.emptyClosed),
  }

  // Memoised: a cell function is a component to the table, so new functions would remount the row menus.
  const columns = useMemo(
    (): DataTableColumn<Submission>[] => [
      {
        id: 'purpose',
        header: intl.formatMessage(sm.colPurpose),
        cell: ({ row }) => intl.formatMessage(pm[row.original.purpose]),
      },
      { id: 'memo', header: intl.formatMessage(sm.colMemo), cell: ({ row }) => <MemoCell submission={row.original} /> },
      { id: 'from', header: intl.formatMessage(sm.colFrom), cell: ({ row }) => row.original.submittedBy.name },
      {
        id: 'waiting',
        header: intl.formatMessage(sm.colWaiting),
        cell: ({ row }) => (
          <time
            dateTime={row.original.submittedAt}
            title={intl.formatDate(row.original.submittedAt, { dateStyle: 'medium' })}
          >
            {intl.formatMessage(smv.age, { days: ageInDays(row.original.submittedAt) })}
          </time>
        ),
      },
      { id: 'flags', header: intl.formatMessage(sm.colFlags), cell: ({ row }) => <Flags submission={row.original} /> },
      {
        id: 'status',
        header: intl.formatMessage(sm.colStatus),
        cell: ({ row }) => (
          <Badge tone={paperTone[row.original.status]}>{intl.formatMessage(pm[row.original.status])}</Badge>
        ),
      },
      {
        id: 'actions',
        header: () => <span className="sr-only">{intl.formatMessage(sm.colActions)}</span>,
        cell: ({ row }) => (
          <RowActions
            submission={row.original}
            canTriage={canTriage}
            onAction={(action) => {
              setOpen({ action, submission: row.original })
            }}
          />
        ),
      },
    ],
    [intl, canTriage],
  )

  return (
    <div className="flex flex-col gap-4">
      <div role="group" aria-label={intl.formatMessage(sm.show)} className="flex flex-wrap gap-2">
        {FILTERS.map((f) => {
          const n = count(f)
          return (
            <Button
              key={f}
              variant={filter === f ? 'default' : 'outline'}
              aria-pressed={filter === f}
              className="min-h-11"
              onClick={() => {
                setFilter(f)
              }}
            >
              {n ? intl.formatMessage(smv.filterCount, { label: labels[f], count: n }) : labels[f]}
            </Button>
          )
        })}
      </div>
      <DataTable
        caption={intl.formatMessage(smv.caption, { name: forum.name })}
        columns={columns}
        rows={rows}
        getRowId={(row) => row.id}
        state={query.isPending ? 'loading' : query.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void query.refetch() }}
        empty={<EmptyState title={empties[filter]} />}
      />
      {open ? (
        <TriageDialog
          action={open.action}
          submission={open.submission}
          policy={policy?.submissionPolicy}
          onClose={() => {
            setOpen(undefined)
          }}
        />
      ) : null}
    </div>
  )
}

/** The memo's number and subject, linked to the memo; "Restricted" when the reader is not cleared for it. */
function MemoCell({ submission }: { submission: Submission }) {
  const intl = useIntl()
  const memo = submission.memo
  if (!memo) {
    return (
      <span className="flex flex-col">
        <span className="font-medium">{intl.formatMessage(pm.restricted)}</span>
        <span className="text-xs text-muted-foreground">{intl.formatMessage(pm.restrictedHint)}</span>
      </span>
    )
  }
  return (
    <Link to="/memos/$memoId" params={{ memoId: memo.id }} className="flex flex-col underline-offset-4 hover:underline">
      {memo.reference ? (
        <span dir="ltr" className="w-fit font-medium">
          {memo.reference}
        </span>
      ) : null}
      <span dir="auto">{memo.subject}</span>
    </Link>
  )
}

/** "Waiting too long" (the secretaries were reminded), "For information only" (the memo did not wait), "Sent again". */
function Flags({ submission }: { submission: Submission }) {
  const intl = useIntl()
  const flags = [
    waitingTooLong(submission) ? (
      <Badge key="long" tone="warn">
        <span
          title={intl.formatMessage(smv.remindedAt, {
            date: intl.formatDate(submission.remindedAt ?? undefined, { dateStyle: 'medium' }),
          })}
        >
          {intl.formatMessage(sm.waitingLong)}
        </span>
      </Badge>
    ) : null,
    submission.waits ? null : (
      <Badge key="info" tone="neutral">
        {intl.formatMessage(sm.forInformation)}
      </Badge>
    ),
    submission.previousId ? (
      <Badge key="again" tone="neutral">
        {intl.formatMessage(sm.resubmitted)}
      </Badge>
    ) : null,
  ].filter(Boolean)
  return flags.length ? <span className="flex flex-wrap gap-1">{flags}</span> : null
}

/** The Triage menu (flow M1) for those who triage; otherwise a link to the paper. */
function RowActions({
  submission,
  canTriage,
  onAction,
}: {
  submission: Submission
  canTriage: boolean
  onAction: (action: TriageAction) => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const actions = canTriage ? actionsFor(submission) : []
  const open = (
    <Link
      to="/committees/$forumId/submissions/$submissionId"
      params={{ forumId: submission.forumId, submissionId: submission.id }}
    >
      {intl.formatMessage(sm.openPaper)}
    </Link>
  )
  if (actions.length === 0) {
    return (
      <Button variant="outline" className="min-h-11" asChild>
        {open}
      </Button>
    )
  }
  const labels: Record<TriageAction, string> = {
    accept: intl.formatMessage(sm.accept),
    defer: intl.formatMessage(sm.defer),
    return: intl.formatMessage(sm.returnIt),
    decline: intl.formatMessage(sm.decline),
    refer: intl.formatMessage(smt.refer, terms),
    cover: intl.formatMessage(sm.coverSheet),
  }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="outline"
          className="min-h-11"
          aria-label={intl.formatMessage(smv.triageFor, { memo: paperName(intl, submission) })}
        >
          {intl.formatMessage(sm.triage)}
          <ChevronDown aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {actions.map((action) => (
          <DropdownMenuItem
            key={action}
            className={cn('min-h-11', action === 'decline' && 'text-destructive')}
            onSelect={() => {
              onAction(action)
            }}
          >
            {labels[action]}
          </DropdownMenuItem>
        ))}
        <DropdownMenuItem asChild className="min-h-11">
          {open}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
