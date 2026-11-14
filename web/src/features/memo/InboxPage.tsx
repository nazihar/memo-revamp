import { useInfiniteQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { FilePlus2, Lock } from 'lucide-react'
import { useIntl } from 'react-intl'
import { listMyMemosInfiniteOptions, listMyTasksInfiniteOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { BoxItem, InboxItem } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { dueLabel, isOverdue } from './due'
import { referenceOf } from './memo-fields'
import { mm, mmv, statusLabels } from './memo-messages'
import { statusTone } from './memo-status'

export type InboxTab = 'tasks' | 'drafts' | 'sent' | 'closed'

const TABS: InboxTab[] = ['tasks', 'drafts', 'sent', 'closed']
const tabLabel = { tasks: mm.tabTasks, drafts: mm.tabDrafts, sent: mm.tabSent, closed: mm.tabClosed }
const emptyLabel = { tasks: mm.emptyTasks, drafts: mm.emptyDrafts, sent: mm.emptySent, closed: mm.emptyClosed }

const ROW =
  'flex min-h-14 w-full flex-col gap-1 px-4 py-3 text-start hover:bg-accent focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring md:flex-row md:items-center md:gap-4'

/** `/inbox` (design E01 §13, ux-review §2): my tasks, drafts, sent and closed memos; rows become two-line cards on phones. */
export function InboxPage({ tab }: { tab: InboxTab }) {
  const intl = useIntl()
  const navigate = useNavigate()
  return (
    <section aria-labelledby="inbox-title">
      <PageHeader
        title={<span id="inbox-title">{intl.formatMessage(mm.inboxTitle)}</span>}
        description={intl.formatMessage(mm.inboxLead)}
        actions={
          <Button asChild>
            <Link to="/memos/new">
              <FilePlus2 aria-hidden="true" />
              {intl.formatMessage(mm.newMemo)}
            </Link>
          </Button>
        }
      />
      <div role="tablist" aria-label={intl.formatMessage(mm.inboxTitle)} className="mb-4 flex flex-wrap gap-1 border-b">
        {TABS.map((t) => (
          <button
            key={t}
            type="button"
            role="tab"
            id={`inbox-tab-${t}`}
            aria-selected={t === tab}
            aria-controls="inbox-panel"
            tabIndex={t === tab ? 0 : -1}
            onClick={() => void navigate({ to: '/inbox', search: { tab: t } })}
            className={cn(
              '-mb-px min-h-11 border-b-2 px-3 text-sm',
              t === tab
                ? 'border-primary font-semibold'
                : 'border-transparent text-muted-foreground hover:text-foreground',
            )}
          >
            {intl.formatMessage(tabLabel[t])}
          </button>
        ))}
      </div>
      <div role="tabpanel" id="inbox-panel" aria-labelledby={`inbox-tab-${tab}`}>
        {tab === 'tasks' ? <TaskList /> : <BoxList box={tab} />}
      </div>
    </section>
  )
}

function TaskList() {
  const intl = useIntl()
  const page = { query: { limit: 25 } }
  const list = useInfiniteQuery({
    ...listMyTasksInfiniteOptions(page),
    initialPageParam: page,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const items = list.data?.pages.flatMap((p) => p.items) ?? []
  return (
    <Listing
      pending={list.isPending}
      error={list.isError}
      onRetry={() => void list.refetch()}
      empty={items.length === 0 ? intl.formatMessage(emptyLabel.tasks) : undefined}
      more={list.hasNextPage ? () => void list.fetchNextPage() : undefined}
      loadingMore={list.isFetchingNextPage}
    >
      {items.map((item) => (
        <li key={item.taskId}>
          <TaskRow item={item} />
        </li>
      ))}
    </Listing>
  )
}

function TaskRow({ item }: { item: InboxItem }) {
  const intl = useIntl()
  const waiting = intl.formatMessage(mmv.assignedAt, {
    time: intl.formatDate(item.assignedAt, { dateStyle: 'medium', timeStyle: 'short' }),
  })
  if (item.memo.restricted) {
    return (
      <div className={cn(ROW, 'hover:bg-transparent')}>
        <span className="flex items-center gap-2 font-medium">
          <Lock aria-hidden="true" className="size-4" />
          {intl.formatMessage(mm.restricted)}
        </span>
        <span className="text-xs text-muted-foreground">{intl.formatMessage(mm.restrictedHint)}</span>
      </div>
    )
  }
  return (
    <Link to="/memos/$memoId" params={{ memoId: item.memo.id }} className={ROW}>
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate font-medium">{item.memo.subject}</span>
        <span className="text-xs text-muted-foreground">
          {intl.formatMessage(mmv.referenceRound, {
            reference: referenceOf(intl, item.memo.reference),
            round: item.round,
          })}
        </span>
      </span>
      <span className="flex items-center gap-2 text-xs text-muted-foreground">
        {item.stepName ? <span>{item.stepName}</span> : null}
        {item.kind === 'OPINION' ? <Badge tone="neutral">{intl.formatMessage(mm.kindOpinion)}</Badge> : null}
        {item.kind === 'CLARIFY' ? <Badge tone="neutral">{intl.formatMessage(mm.kindClarify)}</Badge> : null}
        {item.blocked ? <Badge tone="warn">{intl.formatMessage(mm.blocked)}</Badge> : null}
        {item.dueAt ? (
          <Badge tone={isOverdue(item.dueAt) ? 'danger' : 'neutral'}>{dueLabel(intl, item.dueAt)}</Badge>
        ) : null}
        {item.actingFor ? (
          <Badge tone="neutral">{intl.formatMessage(mmv.actingFor, { name: item.actingFor.name })}</Badge>
        ) : null}
        {item.claimedBy ? (
          <Badge tone="neutral">{intl.formatMessage(mmv.claimedBy, { name: item.claimedBy.name })}</Badge>
        ) : item.shared ? (
          <Badge tone="neutral">{intl.formatMessage(mm.sharedBadge)}</Badge>
        ) : null}
        <time dateTime={item.assignedAt}>{waiting}</time>
      </span>
    </Link>
  )
}

function BoxList({ box }: { box: Exclude<InboxTab, 'tasks'> }) {
  const intl = useIntl()
  const page = { query: { box, limit: 25 } }
  const list = useInfiniteQuery({
    ...listMyMemosInfiniteOptions(page),
    initialPageParam: page,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const items = list.data?.pages.flatMap((p) => p.items) ?? []
  return (
    <Listing
      pending={list.isPending}
      error={list.isError}
      onRetry={() => void list.refetch()}
      empty={items.length === 0 ? intl.formatMessage(emptyLabel[box]) : undefined}
      more={list.hasNextPage ? () => void list.fetchNextPage() : undefined}
      loadingMore={list.isFetchingNextPage}
    >
      {items.map((item) => (
        <li key={item.id}>
          <BoxRow item={item} />
        </li>
      ))}
    </Listing>
  )
}

function BoxRow({ item }: { item: BoxItem }) {
  const intl = useIntl()
  const holders = item.holders.map((h) => h.name).join(', ')
  return (
    <Link to="/memos/$memoId" params={{ memoId: item.id }} className={ROW}>
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate font-medium">{item.subject}</span>
        <span className="text-xs text-muted-foreground">
          {holders
            ? intl.formatMessage(mmv.referenceHolders, { reference: referenceOf(intl, item.reference), names: holders })
            : referenceOf(intl, item.reference)}
        </span>
      </span>
      <span className="flex items-center gap-2 text-xs text-muted-foreground">
        <Badge tone={statusTone[item.status]}>{intl.formatMessage(statusLabels[item.status])}</Badge>
        <time dateTime={item.updatedAt}>
          {intl.formatMessage(mmv.updatedAt, {
            time: intl.formatDate(item.updatedAt, { dateStyle: 'medium', timeStyle: 'short' }),
          })}
        </time>
      </span>
    </Link>
  )
}

function Listing({
  pending,
  error,
  onRetry,
  empty,
  more,
  loadingMore,
  children,
}: {
  pending: boolean
  error: boolean
  onRetry: () => void
  empty?: string
  more?: () => void
  loadingMore: boolean
  children: React.ReactNode
}) {
  const intl = useIntl()
  if (pending) return <LoadingState />
  if (error) return <ErrorState onRetry={onRetry} />
  if (empty) return <EmptyState title={empty} />
  return (
    <>
      <ul className="flex flex-col divide-y rounded-md border">{children}</ul>
      {more ? (
        <div className="mt-4 flex justify-center">
          <Button variant="outline" disabled={loadingMore} onClick={more}>
            {intl.formatMessage(mm.loadMore)}
          </Button>
        </div>
      ) : null}
    </>
  )
}
