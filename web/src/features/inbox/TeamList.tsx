import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Lock } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { listTeamTasksInfiniteOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { Named, TeamTask } from '@/api/generated/types.gen'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/form-controls'
import { dueLabel } from '@/features/memo/due'
import { referenceOf } from '@/features/memo/memo-fields'
import { mm } from '@/features/memo/memo-messages'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { useBulk, useSelection } from './bulk'
import { BulkBar, BulkResults, BulkSpacer, SelectAll } from './BulkBar'
import { im, imv } from './inbox-messages'
import { Listing, ROW, SelectableItem } from './Listing'
import { ReassignDialog } from './ReassignDialog'
import { memoName } from './team'

export type TeamStatus = 'pending' | 'overdue'

/** Overdue first, then the soonest due, then those without a due time (the server's order, kept across pages). */
function byDue(a: TeamTask, b: TeamTask): number {
  if (a.overdue !== b.overdue) return a.overdue ? -1 : 1
  if (a.dueAt && b.dueAt) return a.dueAt.localeCompare(b.dueAt)
  if (a.dueAt) return -1
  if (b.dueAt) return 1
  return 0
}

/**
 * The Team tab (flow I1, approved): open tasks of the people in the units I oversee, by due time; filters by unit,
 * status and person; Reassign… (step-up, with a reason) and Watch in bulk.
 */
export function TeamList({
  units,
  unit,
  status,
  onFilters,
}: {
  units: Named[]
  unit?: string
  status: TeamStatus
  onFilters: (filters: { unit?: string; status?: TeamStatus }) => void
}) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const [person, setPerson] = useState<Picked>()
  const [reassigning, setReassigning] = useState(false)
  const page = {
    query: {
      status,
      limit: 25,
      ...(unit ? { unit } : {}),
      ...(person ? { person: person.id } : {}),
    },
  }
  const list = useInfiniteQuery({
    ...listTeamTasksInfiniteOptions(page),
    initialPageParam: page,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  const items = (list.data?.pages.flatMap((p) => p.items) ?? []).slice().sort(byDue)
  const selection = useSelection(items.map((i) => i.taskId))
  const bulk = useBulk(intl, async () => {
    selection.clear()
    await queryClient.invalidateQueries({ queryKey: [{ _id: 'listTeamTasks' }] })
  })
  const chosen = items.filter((i) => selection.has(i.taskId))
  const overdue = items.filter((i) => i.overdue).length

  const watch = () => {
    const memos = new Map(chosen.map((i) => [i.memo.id, memoName(intl, i.memo)]))
    void bulk.perform('WATCH', [...memos.keys()], Object.fromEntries(memos))
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap items-start gap-4">
        <div className="flex min-w-48 flex-col gap-1.5">
          <label htmlFor="team-unit" className="text-sm font-medium">
            {intl.formatMessage(im.filterTeam)}
          </label>
          <Select
            id="team-unit"
            value={unit ?? ''}
            onChange={(e) => {
              onFilters({ unit: e.target.value || undefined, status })
            }}
          >
            <option value="">{intl.formatMessage(im.filterAllTeams)}</option>
            {units.map((u) => (
              <option key={u.id} value={u.id}>
                {u.name}
              </option>
            ))}
          </Select>
        </div>
        <div className="flex min-w-36 flex-col gap-1.5">
          <label htmlFor="team-status" className="text-sm font-medium">
            {intl.formatMessage(im.filterStatus)}
          </label>
          <Select
            id="team-status"
            value={status}
            onChange={(e) => {
              onFilters({ unit, status: e.target.value === 'overdue' ? 'overdue' : 'pending' })
            }}
          >
            <option value="pending">{intl.formatMessage(im.statusPending)}</option>
            <option value="overdue">{intl.formatMessage(im.statusOverdue)}</option>
          </Select>
        </div>
        <div className="flex min-w-56 flex-1 flex-col gap-1.5 sm:max-w-80">
          <label htmlFor="team-person" className="text-sm font-medium">
            {intl.formatMessage(im.filterPerson)}
          </label>
          <PeoplePicker id="team-person" kind="user" value={person} onChange={setPerson} />
        </div>
      </div>
      {list.isSuccess && !list.hasNextPage && items.length > 0 ? (
        <p className="mb-3 text-sm text-muted-foreground">
          {intl.formatMessage(imv.teamCount, { count: items.length, overdue })}
        </p>
      ) : null}
      <BulkResults run={bulk.run} onDismiss={bulk.dismiss} />
      <BulkBar count={selection.selected.length} onClear={selection.clear}>
        <Button
          variant="outline"
          className="min-h-11"
          disabled={bulk.busy}
          onClick={() => {
            setReassigning(true)
          }}
        >
          {intl.formatMessage(im.reassign)}
        </Button>
        <Button variant="outline" className="min-h-11" disabled={bulk.busy} onClick={watch}>
          {intl.formatMessage(im.watch)}
        </Button>
      </BulkBar>
      <Listing
        pending={list.isPending}
        error={list.isError}
        onRetry={() => void list.refetch()}
        empty={items.length === 0 ? intl.formatMessage(im.emptyTeam) : undefined}
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
            <TeamRow item={item} />
          </SelectableItem>
        ))}
      </Listing>
      <BulkSpacer count={selection.selected.length} />
      {reassigning ? (
        <ReassignDialog
          count={chosen.length}
          busy={bulk.busy}
          onConfirm={(to, reason) =>
            bulk.perform(
              'REASSIGN',
              chosen.map((i) => i.taskId),
              Object.fromEntries(chosen.map((i) => [i.taskId, memoName(intl, i.memo)])),
              { to: to.id, reason },
            )
          }
          onClose={() => {
            setReassigning(false)
          }}
        />
      ) : null}
    </>
  )
}

function TeamRow({ item }: { item: TeamTask }) {
  const intl = useIntl()
  const who = item.stepName
    ? intl.formatMessage(imv.holderStep, { name: item.holder.name, step: item.stepName })
    : item.holder.name
  const details = (
    <span className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
      <span>{who}</span>
      {item.dueAt ? (
        <Badge tone={item.overdue ? 'danger' : 'neutral'}>{dueLabel(intl, item.dueAt)}</Badge>
      ) : item.overdue ? (
        <Badge tone="danger">{intl.formatMessage(im.statusOverdue)}</Badge>
      ) : (
        <span>{intl.formatMessage(im.noDue)}</span>
      )}
      <span>{intl.formatMessage(imv.waiting, { days: item.ageWorkingDays })}</span>
    </span>
  )
  if (item.memo.restricted) {
    return (
      <div className={`${ROW} hover:bg-transparent`}>
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="flex items-center gap-2 font-medium">
            <Lock aria-hidden="true" className="size-4" />
            {intl.formatMessage(mm.restricted)}
          </span>
          {item.unit ? <span className="text-xs text-muted-foreground">{item.unit.name}</span> : null}
        </span>
        {details}
      </div>
    )
  }
  return (
    <Link to="/memos/$memoId" params={{ memoId: item.memo.id }} className={ROW}>
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="truncate font-medium">{item.memo.subject}</span>
        <span className="text-xs text-muted-foreground">
          {item.unit
            ? intl.formatMessage(imv.referenceUnit, {
                reference: referenceOf(intl, item.memo.reference),
                unit: item.unit.name,
              })
            : referenceOf(intl, item.memo.reference)}
        </span>
      </span>
      {details}
    </Link>
  )
}
