import { keepPreviousData, useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { CircleCheck, CircleDot, Contrast, Download, Plus, TriangleAlert, type LucideIcon } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { getForumActionsInfiniteOptions, listForumMeetingsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { askForActionUpdates, exportForumActions } from '@/api/generated/sdk.gen'
import type { ActionSummary, ForumResponse, GetForumActionsData } from '@/api/generated/types.gen'
import { isProblem, PROBLEM, type Problem } from '@/api/http'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { TableScroll } from '@/components/page/TableScroll'
import { Button } from '@/components/ui/button'
import { Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { cn } from '@/lib/utils'
import { am } from '../messages'
import { RagBadge, RagKey } from '../RagBadge'
import { day, ownerWords, referenceWords } from '../words'
import { ActionForm } from './ActionForm'
import { ao } from './messages'
import { officerRefusal } from './problems'
import { refreshActions, todayIso } from './shared'

type Query = NonNullable<GetForumActionsData['query']>
type StatusFilter = 'OPEN' | 'ALL' | 'DRAFT' | 'TO_VERIFY' | 'CLOSED' | 'CANCELLED'
type ColourFilter = 'ALL' | 'OVERDUE' | 'RED' | 'AMBER' | 'GREEN' | 'BLUE'
type OwnerFilter = 'ANYONE' | 'PERSON' | 'POSITION'

const STATUSES: Record<StatusFilter, Query['status']> = {
  OPEN: ['OPEN', 'IN_PROGRESS', 'DONE_PENDING'],
  ALL: undefined,
  DRAFT: ['DRAFT'],
  TO_VERIFY: ['DONE_PENDING'],
  CLOSED: ['CLOSED'],
  CANCELLED: ['CANCELLED'],
}
const COLOURS: ColourFilter[] = ['ALL', 'OVERDUE', 'RED', 'AMBER', 'GREEN', 'BLUE']
const SORTS: NonNullable<Query['sort']>[] = ['DUE', 'UPDATED', 'REFERENCE']
const TOTALS: {
  colour: 'RED' | 'AMBER' | 'GREEN' | 'BLUE'
  key: 'red' | 'amber' | 'green' | 'toVerify'
  icon: LucideIcon
  tone: string
}[] = [
  { colour: 'RED', key: 'red', icon: TriangleAlert, tone: 'bg-status-danger-bg text-status-danger-fg' },
  { colour: 'AMBER', key: 'amber', icon: CircleDot, tone: 'bg-status-warn-bg text-status-warn-fg' },
  { colour: 'GREEN', key: 'green', icon: CircleCheck, tone: 'bg-status-ok-bg text-status-ok-fg' },
  { colour: 'BLUE', key: 'toVerify', icon: Contrast, tone: 'bg-status-neutral-bg text-status-neutral-fg' },
]

/**
 * The committee's action tracker (flow AN, design §1.14 "As built in T8"): "New action" (a direct draft) and "Export"
 * for those the server allows; filters by status, colour, owner (a person or a position), meeting and words, and the
 * sort; the totals per colour in words with their icons, each a toggle for that colour (the totals keep every filter but
 * the colour, as the server counts them); "Ask owners for an update" for the secretaries; the rows (a table, cards on a
 * phone, the status word first) each opening the action page; and the key with this committee type's numbers. Members
 * see the same list without the officers' buttons, and nothing from an item they declared an interest in: the server
 * leaves those rows out of the list and the counts.
 */
export function Tracker({ forum }: { forum: ForumResponse }) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const queryClient = useQueryClient()
  const headingId = useId()
  const [status, setStatus] = useState<StatusFilter>('OPEN')
  const [colour, setColour] = useState<ColourFilter>('ALL')
  const [ownerKind, setOwnerKind] = useState<OwnerFilter>('ANYONE')
  const [owner, setOwner] = useState<Picked>()
  const [meetingId, setMeetingId] = useState('')
  const [text, setText] = useState('')
  const [term, setTerm] = useState('')
  const [sort, setSort] = useState<NonNullable<Query['sort']>>('DUE')
  const [creating, setCreating] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [asking, setAsking] = useState(false)
  const [askedNow, setAskedNow] = useState(false)
  const [askKey, setAskKey] = useState(() => newIdempotencyKey())
  const [said, setSaid] = useState({ text: '', times: 0 })
  const saidRef = useRef<HTMLParagraphElement>(null)
  useEffect(() => {
    if (said.times > 0) saidRef.current?.focus()
  }, [said])
  const say = (t: string) => {
    setSaid((s) => ({ text: t, times: s.times + 1 }))
  }
  useEffect(() => {
    const timer = setTimeout(() => {
      setTerm(text.trim())
    }, 300)
    return () => {
      clearTimeout(timer)
    }
  }, [text])

  const query: Query = {
    sort,
    limit: 50,
    ...(STATUSES[status] ? { status: STATUSES[status] } : {}),
    ...(colour === 'OVERDUE' ? { overdue: true } : colour !== 'ALL' ? { rag: colour } : {}),
    ...(ownerKind !== 'ANYONE' && owner ? { owner: owner.id } : {}),
    ...(meetingId ? { meetingId } : {}),
    ...(term ? { text: term.slice(0, 200) } : {}),
  }
  const page = { path: { forumId: forum.id }, query }
  const rows = useInfiniteQuery({
    ...getForumActionsInfiniteOptions(page),
    initialPageParam: page,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    // A new filter keeps the rows in view (and focus on the control) until its answer comes.
    placeholderData: keepPreviousData,
    retry: false,
  })
  const meetings = useQuery({
    ...listForumMeetingsOptions({
      path: { forumId: forum.id },
      query: { status: ['IN_PROGRESS', 'HELD', 'MINUTES_APPROVED', 'CLOSED'] },
    }),
    staleTime: 60_000,
  })

  if (rows.isPending) return <LoadingState rows={4} />
  if (rows.isError) {
    return (rows.error as Problem | undefined)?.status === 404 ? (
      <EmptyState title={intl.formatMessage(ao.notYours)} />
    ) : (
      <ErrorState onRetry={() => void rows.refetch()} />
    )
  }
  const first = rows.data.pages[0]
  const items = rows.data.pages.flatMap((p) => p.items)
  if (!first) return null
  const { totals, key, can, matching } = first

  const download = async () => {
    setExporting(true)
    try {
      const { sort: by, status: st, rag, overdue, owner: who, meetingId: mid, text: words } = query
      const filters = { sort: by, status: st, rag, overdue, owner: who, meetingId: mid, text: words }
      const { data } = await exportForumActions({
        path: { forumId: forum.id },
        query: filters,
        parseAs: 'blob',
        throwOnError: true,
      })
      const url = URL.createObjectURL(data)
      const link = document.createElement('a')
      link.href = url
      link.download = `${forum.code}-actions-${todayIso()}.xlsx`
      link.click()
      URL.revokeObjectURL(url)
      toast.show(intl.formatMessage(ao.exported), 'success')
    } catch (problem) {
      const limited = isProblem(problem) && (problem.status === 429 || problem.type === PROBLEM.rateLimited)
      say(intl.formatMessage(limited ? ao.exportLimited : ao.exportFailed))
    } finally {
      setExporting(false)
    }
  }
  const ask = async () => {
    setAsking(true)
    try {
      const { data } = await askForActionUpdates({
        path: { forumId: forum.id },
        headers: { 'Idempotency-Key': askKey },
        throwOnError: true,
      })
      setAskKey(newIdempotencyKey())
      setAskedNow(true)
      say(intl.formatMessage(ao.asked, { count: data.asked }))
    } catch (problem) {
      // Once a day per committee: asked already (by someone else, a moment ago) is as good as asked now.
      if (isProblem(problem) && problem.reason === 'ALREADY_ASKED') setAskedNow(true)
      say(officerRefusal(intl, terms, problem))
    } finally {
      setAsking(false)
    }
  }
  // The server's rule (can.askForUpdates, once a day); after asking here the button gives way to the words.
  const askable = can.askForUpdates && !askedNow
  const filtered = status !== 'OPEN' || colour !== 'ALL' || Boolean(owner) || Boolean(meetingId) || Boolean(term)

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={headingId} className="text-xl font-semibold">
          {intl.formatMessage(ao.trackerTitle, { name: forum.name })}
        </h2>
        <div className="flex flex-wrap gap-2">
          {can.raise ? (
            <Button
              className="min-h-11"
              onClick={() => {
                setCreating(true)
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(ao.newAction)}
            </Button>
          ) : null}
          {can.export ? (
            <Button variant="outline" className="min-h-11" disabled={exporting} onClick={() => void download()}>
              <Download aria-hidden="true" />
              {intl.formatMessage(ao.export)}
            </Button>
          ) : null}
        </div>
      </div>

      <fieldset className="flex flex-wrap items-end gap-3">
        <legend className="sr-only">{intl.formatMessage(ao.filters)}</legend>
        <Labelled label={intl.formatMessage(ao.filterStatus)}>
          {(id) => (
            <Select
              id={id}
              className="min-h-11"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value as StatusFilter)
              }}
            >
              {(Object.keys(STATUSES) as StatusFilter[]).map((s) => (
                <option key={s} value={s}>
                  {intl.formatMessage(ao[`status${s}`])}
                </option>
              ))}
            </Select>
          )}
        </Labelled>
        <Labelled label={intl.formatMessage(ao.filterColour)}>
          {(id) => (
            <Select
              id={id}
              className="min-h-11"
              value={colour}
              onChange={(e) => {
                setColour(e.target.value as ColourFilter)
              }}
            >
              {COLOURS.map((c) => (
                <option key={c} value={c}>
                  {intl.formatMessage(
                    c === 'ALL' ? ao.colourALL : c === 'OVERDUE' ? ao.colourOVERDUE : ao[`colourFilter${c}`],
                  )}
                </option>
              ))}
            </Select>
          )}
        </Labelled>
        <Labelled label={intl.formatMessage(ao.filterOwner)}>
          {(id) => (
            <Select
              id={id}
              className="min-h-11"
              value={ownerKind}
              onChange={(e) => {
                setOwnerKind(e.target.value as OwnerFilter)
                setOwner(undefined)
              }}
            >
              <option value="ANYONE">{intl.formatMessage(ao.ownerANYONE)}</option>
              <option value="PERSON">{intl.formatMessage(ao.ownerPerson)}</option>
              <option value="POSITION">{intl.formatMessage(ao.ownerPosition)}</option>
            </Select>
          )}
        </Labelled>
        {ownerKind !== 'ANYONE' ? (
          <Labelled label={intl.formatMessage(ao.ownerWho)} className="w-64">
            {(id) => (
              <PeoplePicker
                key={ownerKind}
                id={id}
                kind={ownerKind === 'PERSON' ? 'user' : 'position'}
                {...(owner ? { value: owner } : {})}
                onChange={setOwner}
              />
            )}
          </Labelled>
        ) : null}
        <Labelled label={intl.formatMessage(ao.filterMeeting)}>
          {(id) => (
            <Select
              id={id}
              className="min-h-11"
              value={meetingId}
              onChange={(e) => {
                setMeetingId(e.target.value)
              }}
            >
              <option value="">{intl.formatMessage(ao.meetingANY)}</option>
              {(meetings.data?.items ?? []).map((mt) => (
                <option key={mt.id} value={mt.id}>
                  {mt.label ?? mt.title ?? intl.formatDate(mt.startsAt, { dateStyle: 'medium', timeZone: mt.timeZone })}
                </option>
              ))}
            </Select>
          )}
        </Labelled>
        <Labelled label={intl.formatMessage(ao.search)} className="w-56">
          {(id) => (
            <Input
              id={id}
              type="search"
              maxLength={200}
              className="min-h-11"
              value={text}
              onChange={(e) => {
                setText(e.target.value)
              }}
            />
          )}
        </Labelled>
        <Labelled label={intl.formatMessage(ao.sort)}>
          {(id) => (
            <Select
              id={id}
              className="min-h-11"
              value={sort}
              onChange={(e) => {
                setSort(e.target.value as NonNullable<Query['sort']>)
              }}
            >
              {SORTS.map((s) => (
                <option key={s} value={s}>
                  {intl.formatMessage(ao[`sort${s}`])}
                </option>
              ))}
            </Select>
          )}
        </Labelled>
      </fieldset>

      <div className="flex flex-wrap items-center justify-between gap-2">
        <div role="group" aria-label={intl.formatMessage(ao.totals)} className="flex flex-wrap gap-2">
          {TOTALS.map((t) => {
            const Icon = t.icon
            const pressed = colour === t.colour
            return (
              <button
                key={t.colour}
                type="button"
                aria-pressed={pressed}
                data-rag={t.colour}
                className={cn(
                  'inline-flex min-h-11 items-center gap-2 rounded-full border px-3 text-base font-medium',
                  t.tone,
                  pressed ? 'border-foreground' : 'border-transparent',
                )}
                onClick={() => {
                  setColour(pressed ? 'ALL' : t.colour)
                }}
              >
                <Icon aria-hidden="true" className="size-4 shrink-0" />
                {intl.formatMessage(ao[`total${t.colour}`], { count: totals[t.key] })}
              </button>
            )
          })}
        </div>
        {askable ? (
          <Button variant="outline" className="min-h-11" disabled={asking} onClick={() => void ask()}>
            {intl.formatMessage(ao.askUpdates)}
          </Button>
        ) : can.askedToday || askedNow ? (
          <p className="text-sm text-muted-foreground">{intl.formatMessage(ao.askedToday)}</p>
        ) : null}
      </div>
      <p className="text-sm text-muted-foreground">
        {intl.formatMessage(ao.totalsMore, { closed: totals.closed, drafts: totals.drafts })}
      </p>
      <p aria-live="polite" className="text-sm">
        {intl.formatMessage(ao.matching, { count: matching })}
      </p>
      <p
        ref={saidRef}
        tabIndex={-1}
        role="status"
        className="text-base font-medium focus-visible:outline-2 focus-visible:outline-ring empty:hidden"
      >
        {said.text}
      </p>

      {items.length === 0 ? (
        <p className="text-lg">{intl.formatMessage(filtered ? ao.noneMatch : ao.empty)}</p>
      ) : (
        <Rows items={items} />
      )}
      {rows.hasNextPage ? (
        <div>
          <Button
            variant="outline"
            className="min-h-11"
            disabled={rows.isFetchingNextPage}
            onClick={() => void rows.fetchNextPage()}
          >
            {intl.formatMessage(am.loadMore)}
          </Button>
        </div>
      ) : null}
      <RagKey amberWorkingDays={key.amberWorkingDays} />
      <p className="text-sm text-muted-foreground">
        {key.staleWorkingDays
          ? intl.formatMessage(ao.keyStale, { amber: key.amberWorkingDays, stale: key.staleWorkingDays })
          : intl.formatMessage(ao.keyNoStale, { amber: key.amberWorkingDays })}
      </p>
      {creating ? (
        <ActionForm
          from={{ kind: 'DIRECT', forumId: forum.id, forumName: forum.name }}
          canSend
          onClose={() => {
            setCreating(false)
          }}
          onSaved={(_, sentence) => {
            setCreating(false)
            void refreshActions(queryClient)
            say(sentence)
          }}
        />
      ) : null}
    </section>
  )
}

function Labelled({
  label,
  className,
  children,
}: {
  label: string
  className?: string
  children: (id: string) => React.ReactNode
}) {
  const id = useId()
  return (
    <div className={cn('flex flex-col gap-1', className)}>
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {children(id)}
    </div>
  )
}

/**
 * The rows (reference, action, owner, due with its revisions, status); each reference opens the action page.
 */
function Rows({ items }: { items: ActionSummary[] }) {
  const intl = useIntl()
  const due = (a: ActionSummary) =>
    a.dueDate
      ? `${day(intl, a.dueDate, 'medium')}${a.dueRevisions ? ` ${intl.formatMessage(ao.revised, { count: a.dueRevisions })}` : ''}`
      : intl.formatMessage(am.noDue)
  const link = (a: ActionSummary) => (
    <Link
      to="/actions/$actionId"
      params={{ actionId: a.id }}
      className="inline-flex min-h-11 items-center font-medium underline-offset-4 hover:underline"
    >
      {referenceWords(intl, a.reference)}
    </Link>
  )
  // One table: on a phone each row becomes a card with the status word first (flow AN).
  return (
    <TableScroll>
      <table className="w-full border-collapse text-start">
        <thead className="max-md:sr-only">
          <tr className="border-b text-sm text-muted-foreground">
            <th scope="col" className="py-2 pe-3 text-start font-medium">
              {intl.formatMessage(ao.colRef)}
            </th>
            <th scope="col" className="py-2 pe-3 text-start font-medium">
              {intl.formatMessage(ao.colAction)}
            </th>
            <th scope="col" className="py-2 pe-3 text-start font-medium">
              {intl.formatMessage(ao.colOwner)}
            </th>
            <th scope="col" className="py-2 pe-3 text-start font-medium">
              {intl.formatMessage(ao.colDue)}
            </th>
            <th scope="col" className="py-2 text-start font-medium">
              {intl.formatMessage(ao.colStatus)}
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((a) => (
            <tr
              key={a.id}
              className="border-b align-top max-md:mb-2 max-md:flex max-md:flex-col max-md:rounded-md max-md:border max-md:p-3"
            >
              <td className="py-1 pe-3 whitespace-nowrap tabular-nums max-md:py-0">{link(a)}</td>
              <td className="py-3 pe-3 max-md:py-0">
                {a.title}
                <span className="block text-sm text-muted-foreground">{a.sourceLabel}</span>
              </td>
              <td className="py-3 pe-3 max-md:py-0">
                {ownerWords(intl, a.owner) ?? intl.formatMessage(ao.rowNoOwner)}
              </td>
              <td className="py-3 pe-3 whitespace-nowrap max-md:py-0">{due(a)}</td>
              <td className="py-3 max-md:order-first max-md:py-1">
                <RagBadge rag={a.rag} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </TableScroll>
  )
}
