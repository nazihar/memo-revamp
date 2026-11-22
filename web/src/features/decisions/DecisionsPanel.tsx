import { useQuery } from '@tanstack/react-query'
import { Check, Circle } from 'lucide-react'
import { useEffect, useId, useRef, useState, type ComponentProps, type RefObject } from 'react'
import { useIntl } from 'react-intl'
import {
  getDecisionsToBeRatifiedOptions,
  getMeetingAgendaOptions,
  getMeetingAttendanceOptions,
  getMeetingDecisionsOptions,
  getMeetingQuorumOptions,
  getMinutesOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import type { DecisionResponse, LiveQuorumResponse, MeetingResponse } from '@/api/generated/types.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { LIVE_REFRESH_MS } from '@/features/meetings/attendance/words'
import { cn } from '@/lib/utils'
import { ConsentDialog } from './ConsentDialog'
import { DecisionForm } from './DecisionForm'
import { DecisionText } from './DecisionText'
import { History } from './History'
import { dm, dmv } from './messages'
import { NotesBox } from './NotesBox'
import {
  itemName,
  itemShort,
  panelItems,
  purposeWords,
  RECORDING,
  stateLine,
  useDecisionPolicy,
  voters,
  type PanelItem,
} from './shared'

const IN_REVIEW = ['CHAIR_REVIEW', 'CIRCULATED', 'AWAITING_CHAIR']

/**
 * The officers' Decisions tab on the meeting page (flow AA, design §1.1): the agenda with each item's decision in words
 * ("Approved with conditions — numbered when the meeting ends", "Not decided yet"), and beside it (below on a phone) the
 * chosen item's panel: the quorum for it, the decision form or the decision with Correct and History, and the notes for
 * the minutes. The item in progress ("Now: item n", E05) is chosen first. Consent sections are decided in one action.
 * From the start of the meeting until its minutes are approved decisions are recorded and corrected; after that the tab
 * only reads.
 */
export function DecisionsPanel({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const path = { path: { meetingId: m.id } }
  const live = m.status === 'IN_PROGRESS'
  const recording = RECORDING.includes(m.status)
  const agenda = useQuery(getMeetingAgendaOptions(path))
  const decisions = useQuery(getMeetingDecisionsOptions(path))
  const register = useQuery({ ...getMeetingAttendanceOptions(path), refetchInterval: live ? LIVE_REFRESH_MS : false })
  const minutes = useQuery({ ...getMinutesOptions(path), enabled: m.status !== 'IN_PROGRESS', retry: false })
  const ratifiable = useQuery({
    ...getDecisionsToBeRatifiedOptions({ path: { forumId: m.forumId } }),
    enabled: recording,
    retry: false,
  })
  const policy = useDecisionPolicy(m)
  const items = panelItems(agenda.data)
  const byItem = new Map((decisions.data?.decisions ?? []).map((d) => [d.itemId, d]))
  const progress = register.data?.itemInProgress
  const [chosen, setChosen] = useState<string>()
  const [consent, setConsent] = useState<string>()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const focusPanel = useRef(false)
  const current =
    items.find((i) => i.id === chosen) ??
    items.find((i) => i.id === progress?.itemId) ??
    items.find((i) => !byItem.has(i.id)) ??
    items[0]

  useEffect(() => {
    if (focusPanel.current) {
      focusPanel.current = false
      headingRef.current?.focus()
    }
  }, [current?.id])

  if (agenda.isPending || decisions.isPending) return <LoadingState rows={4} />
  if (agenda.isError || decisions.isError) {
    return <ErrorState onRetry={() => void Promise.all([agenda.refetch(), decisions.refetch()])} />
  }
  if (!items.length) return <p className="text-lg">{intl.formatMessage(dm.noItems)}</p>

  const minutesInReview = IN_REVIEW.includes(minutes.data?.state ?? '')
  const notesEditable = recording && (minutes.data == null || minutes.data.state === 'DRAFT')
  const nowItem = live && progress ? items.find((i) => i.id === progress.itemId) : undefined
  const sections = [...new Set(items.filter((i) => i.consent).map((i) => i.sectionId))]
  const choose = (id: string) => {
    focusPanel.current = true
    setChosen(id)
  }

  return (
    <section aria-labelledby="decisions-title" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="decisions-title" className="text-xl font-semibold">
          {intl.formatMessage(dm.heading)}
        </h2>
        {nowItem ? <p className="text-lg">{intl.formatMessage(dmv.now, { item: itemName(intl, nowItem) })}</p> : null}
      </div>
      <div className="grid gap-4 md:grid-cols-[minmax(0,15rem)_minmax(0,1fr)]">
        <nav aria-label={intl.formatMessage(dm.agenda)} className="flex flex-col gap-3">
          <ItemList
            items={items.filter((i) => !i.consent)}
            current={current?.id}
            decisions={byItem}
            meeting={m}
            onChoose={choose}
          />
          {sections.map((sid) => (
            <div key={sid} className="flex flex-col gap-2">
              <h3 className="text-base font-semibold">{intl.formatMessage(dm.consentItems)}</h3>
              <ItemList
                items={items.filter((i) => i.sectionId === sid)}
                current={current?.id}
                decisions={byItem}
                meeting={m}
                onChoose={choose}
              />
              {recording && items.some((i) => i.sectionId === sid && !i.lifted && !byItem.has(i.id)) ? (
                <Button
                  variant="outline"
                  className="h-auto min-h-11 whitespace-normal text-base"
                  onClick={() => {
                    setConsent(sid)
                  }}
                >
                  {intl.formatMessage(dm.decideConsent)}
                </Button>
              ) : null}
            </div>
          ))}
        </nav>
        {current ? (
          <ItemPanel
            key={current.id}
            meeting={m}
            item={current}
            decision={byItem.get(current.id)}
            headingRef={headingRef}
            policy={policy}
            toBeRatified={ratifiable.data ?? []}
            startedAt={progress?.itemId === current.id ? progress.startedAt : undefined}
            minutesInReview={minutesInReview}
            notesEditable={notesEditable}
            onRecorded={() => {
              headingRef.current?.focus()
            }}
          />
        ) : null}
      </div>
      {consent ? (
        <ConsentDialog
          meeting={m}
          sectionId={consent}
          items={items}
          decisions={byItem}
          outcomes={policy.outcomes}
          minutesInReview={minutesInReview}
          onClose={() => {
            setConsent(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

/** The items of one part of the agenda, each a 44 px button with its decision in words. */
function ItemList({
  items,
  current,
  decisions,
  meeting,
  onChoose,
}: {
  items: PanelItem[]
  current: string | undefined
  decisions: Map<string, DecisionResponse>
  meeting: MeetingResponse
  onChoose: (id: string) => void
}) {
  const intl = useIntl()
  if (!items.length) return null
  return (
    <ul className="flex flex-col gap-1">
      {items.map((i) => {
        const d = decisions.get(i.id)
        return (
          <li key={i.id}>
            <button
              type="button"
              aria-current={i.id === current ? 'true' : undefined}
              className={cn(
                'flex min-h-11 w-full items-start gap-2 rounded-md border px-3 py-2 text-start text-base',
                i.id === current ? 'border-primary bg-primary/5' : 'hover:bg-accent',
              )}
              onClick={() => {
                onChoose(i.id)
              }}
            >
              {d ? (
                <Check aria-hidden="true" className="mt-1 size-4 shrink-0 text-status-ok-fg" />
              ) : (
                <Circle aria-hidden="true" className="mt-1 size-4 shrink-0 text-muted-foreground" />
              )}
              <span className="flex min-w-0 flex-col">
                <span className="font-medium">
                  {itemName(intl, i)}
                  {i.lifted ? <span className="ms-1 font-normal">{intl.formatMessage(dm.lifted)}</span> : null}
                </span>
                <span className="text-sm text-muted-foreground">{stateLine(intl, d, meeting)}</span>
                {d?.toBeRatified ? <span className="text-sm">{intl.formatMessage(dm.toBeRatified)}</span> : null}
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}

/** One item's side: the quorum for it, the decision (form or words, Correct, History) and the notes. */
function ItemPanel({
  meeting: m,
  item,
  decision,
  headingRef,
  policy,
  toBeRatified,
  startedAt,
  minutesInReview,
  notesEditable,
  onRecorded,
}: {
  meeting: MeetingResponse
  item: PanelItem
  decision: DecisionResponse | undefined
  headingRef: RefObject<HTMLHeadingElement | null>
  policy: ReturnType<typeof useDecisionPolicy>
  toBeRatified: ComponentProps<typeof DecisionForm>['toBeRatified']
  startedAt: string | undefined
  minutesInReview: boolean
  notesEditable: boolean
  onRecorded: () => void
}) {
  const intl = useIntl()
  const titleId = useId()
  const [correcting, setCorrecting] = useState(false)
  const [history, setHistory] = useState(false)
  const live = m.status === 'IN_PROGRESS'
  const recording = RECORDING.includes(m.status)
  // The quorum for the item: now while the meeting runs; once held, at the time it was taken when known.
  const at = live ? undefined : (decision?.decidedAt ?? startedAt)
  const quorum = useQuery({
    ...getMeetingQuorumOptions({ path: { meetingId: m.id }, query: { itemId: item.id, ...(at ? { at } : {}) } }),
    enabled: recording && !item.recused && (live || at != null),
    refetchInterval: live ? LIVE_REFRESH_MS : false,
  })
  const frozen = decision?.state === 'FINAL'
  const purpose = purposeWords(intl, item.purpose)
  const editing = recording && !item.recused && !frozen && (!decision || correcting)

  return (
    <section aria-labelledby={titleId} className="flex min-w-0 flex-col gap-4 rounded-md border p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 id={titleId} ref={headingRef} tabIndex={-1} className="text-xl font-semibold outline-none">
          {itemName(intl, item)}
        </h3>
        {purpose ? <span className="text-base text-muted-foreground">{purpose}</span> : null}
      </div>
      {item.resolutionSought ? (
        <p className="text-base">{intl.formatMessage(dmv.resolutionSought, { text: item.resolutionSought })}</p>
      ) : null}
      {item.recused ? (
        <p className="rounded-md bg-muted/50 p-3 text-base">{intl.formatMessage(dm.recusedOfficer)}</p>
      ) : null}
      {editing ? <QuorumLine item={item} quorum={quorum.data} pendingTime={!live && at == null} /> : null}
      {decision?.drifted && !correcting ? <p className="text-base">{intl.formatMessage(dm.drifted)}</p> : null}

      {editing ? (
        <DecisionForm
          meeting={m}
          item={item}
          decision={decision}
          outcomes={policy.outcomes}
          quorum={quorum.data}
          quorumOverride={policy.quorumOverride}
          toBeRatified={toBeRatified}
          {...(startedAt ? { startedAt } : {})}
          minutesInReview={minutesInReview}
          onDone={() => {
            setCorrecting(false)
            onRecorded()
          }}
          {...(decision
            ? {
                onCancel: () => {
                  setCorrecting(false)
                  headingRef.current?.focus()
                },
              }
            : {})}
        />
      ) : decision ? (
        <div className="flex flex-col gap-3">
          <DecisionText decision={decision} timeZone={m.timeZone} />
          {decision.toBeRatified ? <Badge tone="warn">{intl.formatMessage(dm.toBeRatified)}</Badge> : null}
          {frozen ? <p className="text-base text-muted-foreground">{intl.formatMessage(dm.frozenLine)}</p> : null}
          {!item.recused ? (
            <div className="flex flex-wrap gap-2">
              {recording && !frozen ? (
                <Button
                  variant="outline"
                  className="min-h-11 text-base"
                  onClick={() => {
                    setCorrecting(true)
                  }}
                >
                  {intl.formatMessage(dm.correct)}
                </Button>
              ) : null}
              <Button
                variant="outline"
                className="min-h-11 text-base"
                aria-expanded={history}
                onClick={() => {
                  setHistory(!history)
                }}
              >
                {intl.formatMessage(history ? dm.hideHistory : dm.history)}
              </Button>
            </div>
          ) : null}
          {history ? <History meetingId={m.id} itemId={item.id} timeZone={m.timeZone} /> : null}
        </div>
      ) : (
        <p className="text-base text-muted-foreground">{intl.formatMessage(dm.notYet)}</p>
      )}

      {recording && !item.recused ? (
        <NotesBox meetingId={m.id} itemId={item.id} timeZone={m.timeZone} editable={notesEditable} />
      ) : null}
    </section>
  )
}

/**
 * "Quorate for item 5: 5 of 7 voting members present, 4 needed (Demo Director declared an interest)", with an icon and
 * in words; not quorate, the form says it and what can be done. Once held, before the time is known, a sentence says
 * the quorum is checked at that time.
 */
function QuorumLine({
  item,
  quorum,
  pendingTime,
}: {
  item: PanelItem
  quorum: LiveQuorumResponse | undefined
  pendingTime: boolean
}) {
  const intl = useIntl()
  const name = itemShort(intl, item)
  if (pendingTime) return <p className="text-base">{intl.formatMessage(dmv.quorumAtTime, { item: name })}</p>
  if (!quorum?.quorate) return null
  const declared = voters(quorum).recused.map((p) => p.name)
  const numbers = { item: name, present: quorum.present, needed: quorum.needed, basis: quorum.basis }
  return (
    <p role="status" className="flex items-start gap-2 text-base font-medium">
      <Check aria-hidden="true" className="mt-1 size-4 shrink-0 text-status-ok-fg" />
      <span>
        {declared.length
          ? intl.formatMessage(dmv.quorateDeclared, { ...numbers, names: intl.formatList(declared) })
          : intl.formatMessage(dmv.quorate, numbers)}
      </span>
    </p>
  )
}
