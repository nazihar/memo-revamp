import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { FilePlus2, Lock } from 'lucide-react'
import { useIntl } from 'react-intl'
import { listMyMemosInfiniteOptions, listMyTasksInfiniteOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { BoxItem, InboxItem } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/form-controls'
import { useBulk, useSelection, type BulkAction } from '@/features/inbox/bulk'
import { BulkBar, BulkResults, BulkSpacer, SelectAll } from '@/features/inbox/BulkBar'
import { im } from '@/features/inbox/inbox-messages'
import type { InboxSearch, InboxTab } from '@/features/inbox/inbox-params'
import { Listing, ROW, SelectableItem } from '@/features/inbox/Listing'
import { memoName, useTeamAccess } from '@/features/inbox/team'
import { TeamList } from '@/features/inbox/TeamList'
import { WatchingList } from '@/features/inbox/WatchingList'
import { cn } from '@/lib/utils'
import { dueLabel, isOverdue } from './due'
import { referenceOf } from './memo-fields'
import { mm, mmv, statusLabels } from './memo-messages'
import { statusTone } from './memo-status'

const tabLabel = {
  tasks: mm.tabTasks,
  drafts: mm.tabDrafts,
  sent: mm.tabSent,
  closed: mm.tabClosed,
  watching: im.tabWatching,
  team: im.tabTeam,
}
const emptyLabel = { tasks: mm.emptyTasks, drafts: mm.emptyDrafts, sent: mm.emptySent, closed: mm.emptyClosed }

/**
 * `/inbox` (design E01 §13, ux-review §2; E05 flows I and J): my tasks, drafts, sent and closed memos, the memos I
 * follow, and the Team tab for people who oversee a unit. Rows become two-line cards on phones.
 */
export function InboxPage({ search }: { search: InboxSearch }) {
  const intl = useIntl()
  const navigate = useNavigate()
  const tab = search.tab ?? 'tasks'
  const team = useTeamAccess()
  const tabs: InboxTab[] = ['tasks', 'drafts', 'sent', 'closed', 'watching', ...(team.visible ? ['team' as const] : [])]
  const go = (next: InboxSearch) => void navigate({ to: '/inbox', search: next })

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
        {tabs.map((t) => (
          <button
            key={t}
            type="button"
            role="tab"
            id={`inbox-tab-${t}`}
            aria-selected={t === tab}
            aria-controls="inbox-panel"
            tabIndex={t === tab ? 0 : -1}
            onClick={() => {
              go({ tab: t })
            }}
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
      <div
        role="tabpanel"
        id="inbox-panel"
        aria-labelledby={tabs.includes(tab) ? `inbox-tab-${tab}` : undefined}
        aria-label={tabs.includes(tab) ? undefined : intl.formatMessage(im.tabTeam)}
      >
        {tab === 'tasks' ? (
          <TaskList
            sort={search.sort}
            onSort={(sort) => {
              go({ tab: 'tasks', ...(sort === 'due' ? { sort } : {}) })
            }}
          />
        ) : tab === 'watching' ? (
          <WatchingList />
        ) : tab === 'team' ? (
          team.pending ? (
            <LoadingState />
          ) : team.visible ? (
            <TeamList
              units={team.units}
              unit={search.unit}
              status={search.status ?? 'pending'}
              onFilters={({ unit, status }) => {
                go({
                  tab: 'team',
                  ...(unit ? { unit } : {}),
                  ...(status === 'overdue' ? { status } : {}),
                })
              }}
            />
          ) : (
            <EmptyState title={intl.formatMessage(im.noTeam)} />
          )
        ) : (
          <BoxList box={tab} />
        )}
      </div>
    </section>
  )
}

/** My tasks: by assignment or by due time, with Claim, Release and Watch in bulk (never a decision). */
function TaskList({ sort, onSort }: { sort?: 'due'; onSort: (sort: 'assigned' | 'due') => void }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const page = { query: { limit: 25, ...(sort ? { sort } : {}) } }
  const list = useInfiniteQuery({
    ...listMyTasksInfiniteOptions(page),
    initialPageParam: page,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const items = list.data?.pages.flatMap((p) => p.items) ?? []
  const selection = useSelection(items.map((i) => i.taskId))
  const bulk = useBulk(intl, async () => {
    selection.clear()
    await queryClient.invalidateQueries({ queryKey: [{ _id: 'listMyTasks' }] })
  })
  const chosen = items.filter((i) => selection.has(i.taskId))
  const act = (action: Exclude<BulkAction, 'WATCH' | 'UNWATCH' | 'REASSIGN'>) =>
    void bulk.perform(
      action,
      chosen.map((i) => i.taskId),
      Object.fromEntries(chosen.map((i) => [i.taskId, memoName(intl, i.memo)])),
    )
  const watch = () => {
    const memos = new Map(chosen.map((i) => [i.memo.id, memoName(intl, i.memo)]))
    void bulk.perform('WATCH', [...memos.keys()], Object.fromEntries(memos))
  }

  return (
    <>
      <div className="mb-3 flex flex-wrap items-center justify-end gap-2">
        <label htmlFor="inbox-sort" className="text-sm font-medium">
          {intl.formatMessage(im.sort)}
        </label>
        <Select
          id="inbox-sort"
          className="w-auto"
          value={sort ?? 'assigned'}
          onChange={(e) => {
            onSort(e.target.value === 'due' ? 'due' : 'assigned')
          }}
        >
          <option value="assigned">{intl.formatMessage(im.sortAssigned)}</option>
          <option value="due">{intl.formatMessage(im.sortDue)}</option>
        </Select>
      </div>
      <BulkResults run={bulk.run} onDismiss={bulk.dismiss} />
      <BulkBar count={selection.selected.length} onClear={selection.clear}>
        <Button
          variant="outline"
          className="min-h-11"
          disabled={bulk.busy}
          onClick={() => {
            act('CLAIM')
          }}
        >
          {intl.formatMessage(im.claim)}
        </Button>
        <Button
          variant="outline"
          className="min-h-11"
          disabled={bulk.busy}
          onClick={() => {
            act('RELEASE')
          }}
        >
          {intl.formatMessage(im.release)}
        </Button>
        <Button variant="outline" className="min-h-11" disabled={bulk.busy} onClick={watch}>
          {intl.formatMessage(im.watch)}
        </Button>
      </BulkBar>
      <Listing
        pending={list.isPending}
        error={list.isError}
        onRetry={() => void list.refetch()}
        empty={items.length === 0 ? intl.formatMessage(emptyLabel.tasks) : undefined}
        more={list.hasNextPage ? () => void list.fetchNextPage() : undefined}
        loadingMore={list.isFetchingNextPage}
        header={<SelectAll total={items.length} selected={selection.selected.length} onChange={selection.setAll} />}
      >
        {items.map((item) => (
          <SelectableItem
            key={item.taskId}
            name={memoName(intl, item.memo)}
            checked={selection.has(item.taskId)}
            onChange={(on) => {
              selection.toggle(item.taskId, on)
            }}
          >
            <TaskRow item={item} />
          </SelectableItem>
        ))}
      </Listing>
      <BulkSpacer count={selection.selected.length} />
    </>
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
      <span className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
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

function BoxList({ box }: { box: 'drafts' | 'sent' | 'closed' }) {
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
          {item.withForum
            ? intl.formatMessage(mmv.referenceWithForum, {
                reference: referenceOf(intl, item.reference),
                committee: item.withForum,
              })
            : holders
              ? intl.formatMessage(mmv.referenceHolders, {
                  reference: referenceOf(intl, item.reference),
                  names: holders,
                })
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
