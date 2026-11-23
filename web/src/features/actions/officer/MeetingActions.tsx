import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Plus } from 'lucide-react'
import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { getActionOptions, getMeetingActionsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { issueMeetingActions } from '@/api/generated/sdk.gen'
import type { ActionResponse, ActionSummary, DecisionResponse, MeetingResponse } from '@/api/generated/types.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/form-controls'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { RagBadge } from '../RagBadge'
import { day, ownerWords, referenceWords } from '../words'
import { ActionForm, type RaiseFrom } from './ActionForm'
import { issueWords } from './issue'
import { ao } from './messages'
import { reasonWords } from './problems'
import { RAISING, refreshActions } from './shared'

/** What a list says after a change, and a count that moves focus to it each time. */
function useSaid() {
  const [said, setSaid] = useState({ text: '', times: 0 })
  const ref = useRef<HTMLParagraphElement>(null)
  useEffect(() => {
    if (said.times > 0) ref.current?.focus()
  }, [said])
  const say = (text: string) => {
    setSaid((s) => ({ text, times: s.times + 1 }))
  }
  const region = (
    <p
      ref={ref}
      tabIndex={-1}
      role="status"
      className="text-base font-medium whitespace-pre-line focus-visible:outline-2 focus-visible:outline-ring empty:hidden"
    >
      {said.text}
    </p>
  )
  return { say, region }
}

/**
 * One action in a list (flow AJ1): its colour or "Draft" in words, the reference, what is to be done, the owner and
 * the date; a draft has "Edit" (44 px), a sent action opens on its page.
 */
function ActionRow({
  action: a,
  withSource,
  onEdit,
}: {
  action: ActionSummary
  withSource?: boolean
  onEdit?: (a: ActionSummary) => void
}) {
  const intl = useIntl()
  const owner = ownerWords(intl, a.owner) ?? intl.formatMessage(ao.rowNoOwner)
  const facts = [owner, a.dueDate ? intl.formatMessage(ao.rowDue, { date: day(intl, a.dueDate, 'medium') }) : null]
    .filter(Boolean)
    .join(' · ')
  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
      <RagBadge rag={a.rag} />
      <span className="flex min-w-0 flex-[1_1_16rem] flex-col">
        <span className="font-medium">
          {a.reference ? <span className="me-2 tabular-nums">{a.reference}</span> : null}
          {a.title}
        </span>
        <span className="text-sm text-muted-foreground">
          {withSource ? intl.formatMessage(ao.rowWithSource, { source: a.sourceLabel, facts }) : facts}
        </span>
      </span>
      {a.status === 'DRAFT' && onEdit ? (
        <Button
          variant="outline"
          className="min-h-11"
          aria-label={intl.formatMessage(ao.editNamed, { title: a.title })}
          onClick={() => {
            onEdit(a)
          }}
        >
          {intl.formatMessage(ao.edit)}
        </Button>
      ) : (
        <Button asChild variant="outline" className="min-h-11">
          <Link
            to="/actions/$actionId"
            params={{ actionId: a.id }}
            aria-label={intl.formatMessage(ao.openNamed, { reference: referenceWords(intl, a.reference) })}
          >
            {intl.formatMessage(ao.open)}
          </Link>
        </Button>
      )}
    </li>
  )
}

/** The form opened from a list: raising from a source, or editing a draft (loaded in full first). */
type Open = { from: RaiseFrom } | { action: ActionResponse }

function useForm(meeting: MeetingResponse, say: (text: string) => void) {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState<Open>()
  const canSend = meeting.status !== 'IN_PROGRESS'
  const edit = async (a: ActionSummary) => {
    const full = await queryClient.query({ ...getActionOptions({ path: { actionId: a.id } }), staleTime: 0 })
    setOpen({ action: full })
  }
  const dialog = open ? (
    <ActionForm
      {...('from' in open ? { from: open.from } : { action: open.action })}
      canSend={canSend}
      onClose={() => {
        setOpen(undefined)
      }}
      onSaved={(_, said) => {
        setOpen(undefined)
        say(said)
      }}
    />
  ) : null
  const raise = useCallback((from: RaiseFrom) => {
    setOpen({ from })
  }, [])
  return { raise, edit, dialog }
}

/** "Make this an action" beside a condition of the decision (flow AJ1), with the condition's number in its name. */
export function MakeActionButton({ index, onPress }: { index: number; onPress: () => void }) {
  const intl = useIntl()
  return (
    <Button
      variant="outline"
      className="ms-2 min-h-11 align-middle"
      aria-label={intl.formatMessage(ao.makeActionNamed, { number: index + 1 })}
      onClick={onPress}
    >
      {intl.formatMessage(ao.makeAction)}
    </Button>
  )
}

/**
 * "Actions from this item" under the item on the decision panel (flow AJ1): its actions and drafts, "Add an action"
 * (from the decision when there is one, else from the item), and the form a condition's "Make this an action" opens
 * ([raise], set by the panel). Officers not recused from the item, while the meeting runs, is held or has its minutes
 * approved.
 */
export function ItemActions({
  meeting,
  itemId,
  item,
  decision,
  raise,
  onRaised,
}: {
  meeting: MeetingResponse
  itemId: string
  /** "Item 5". */
  item: string
  decision: DecisionResponse | undefined
  /** A condition chosen on the decision: the form opens on it. */
  raise?: number | undefined
  onRaised: () => void
}) {
  const intl = useIntl()
  const headingId = useId()
  const { say, region } = useSaid()
  const form = useForm(meeting, say)
  const list = useQuery(getMeetingActionsOptions({ path: { meetingId: meeting.id } }))
  const raising = meeting.canManage && RAISING.includes(meeting.status)
  const from = useCallback(
    (conditionIndex?: number): RaiseFrom =>
      decision
        ? {
            kind: 'DECISION',
            meeting,
            itemId,
            item,
            decisionId: decision.id,
            ...(conditionIndex != null
              ? { conditionIndex, conditionText: decision.conditions[conditionIndex] ?? '' }
              : {}),
          }
        : { kind: 'ITEM', meeting, itemId, item },
    [decision, meeting, itemId, item],
  )
  const { raise: openForm } = form
  useEffect(() => {
    if (raise != null) {
      openForm(from(raise))
      onRaised()
    }
  }, [raise, from, openForm, onRaised])

  const actions = (list.data?.actions ?? []).filter((a) => a.itemId === itemId)
  const drafts = actions.filter((a) => a.status === 'DRAFT').length
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-2 border-t pt-3">
      <h4 id={headingId} className="text-base font-semibold">
        {intl.formatMessage(ao.itemActions)}
        {drafts ? (
          <span className="ms-1 font-normal text-muted-foreground">
            {intl.formatMessage(ao.itemDrafts, { count: drafts })}
          </span>
        ) : null}
      </h4>
      {list.isPending ? (
        <LoadingState rows={1} />
      ) : list.isError ? (
        <ErrorState onRetry={() => void list.refetch()} />
      ) : actions.length ? (
        <ul className="flex flex-col divide-y">
          {actions.map((a) => (
            <ActionRow key={a.id} action={a} {...(raising ? { onEdit: (x) => void form.edit(x) } : {})} />
          ))}
        </ul>
      ) : (
        <p className="text-muted-foreground">{intl.formatMessage(ao.itemNone)}</p>
      )}
      {region}
      {raising ? (
        <div>
          <Button
            variant="outline"
            className="min-h-11"
            onClick={() => {
              form.raise(from())
            }}
          >
            <Plus aria-hidden="true" />
            {intl.formatMessage(ao.addAction)}
          </Button>
        </div>
      ) : null}
      {form.dialog}
    </section>
  )
}

/**
 * "Actions from this meeting" on the meeting page (flows AJ1 and AJ3), for its secretaries: how many were sent to their
 * owners and how many drafts are not sent, with "Send the drafts to their owners" once the meeting is held; every
 * action raised at the meeting with its source; and "Add an action for the meeting" (any other business).
 */
export function MeetingActions({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const terms = useTerms()
  const queryClient = useQueryClient()
  const headingId = useId()
  const { say, region } = useSaid()
  const form = useForm(m, say)
  const list = useQuery(getMeetingActionsOptions({ path: { meetingId: m.id } }))
  const [key, setKey] = useState(() => newIdempotencyKey())
  const [sending, setSending] = useState(false)
  const raising = m.canManage && RAISING.includes(m.status)
  if (list.isPending) return <LoadingState rows={2} />
  if (list.isError) return <ErrorState onRetry={() => void list.refetch()} />
  const actions = list.data.actions
  const drafts = list.data.drafts
  const sent = actions.filter((a) => a.status !== 'DRAFT' && a.status !== 'CANCELLED').length
  const canSend = m.canManage && m.status !== 'IN_PROGRESS' && drafts > 0

  const send = async () => {
    setSending(true)
    try {
      const results = await issueMeetingActions({
        path: { meetingId: m.id },
        headers: { 'Idempotency-Key': key },
        throwOnError: true,
      })
      setKey(newIdempotencyKey())
      await refreshActions(queryClient)
      say(issueWords(intl, terms, results.data.results, new Map(actions.map((a) => [a.id, a.title]))))
    } catch (problem) {
      say(reasonWords(intl, terms, (problem as { reason?: string } | undefined)?.reason))
    } finally {
      setSending(false)
    }
  }

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3 print:hidden">
      <h2 id={headingId} className="text-lg font-semibold">
        {intl.formatMessage(ao.meetingActions)}
      </h2>
      <p className="text-sm text-muted-foreground">{intl.formatMessage(ao.meetingActionsHint)}</p>
      {sent || drafts ? (
        <ul className="flex flex-col gap-1 text-base">
          {sent ? <li>{intl.formatMessage(ao.sentLine, { count: sent })}</li> : null}
          {drafts ? <li className="font-medium">{intl.formatMessage(ao.draftsLine, { count: drafts })}</li> : null}
        </ul>
      ) : null}
      {drafts && m.canManage && m.status === 'IN_PROGRESS' ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(ao.sendLater)}</p>
      ) : null}
      {region}
      {actions.length ? (
        <ul className="flex flex-col divide-y rounded-md border px-3">
          {actions.map((a) => (
            <ActionRow key={a.id} action={a} withSource {...(raising ? { onEdit: (x) => void form.edit(x) } : {})} />
          ))}
        </ul>
      ) : (
        <p className="text-muted-foreground">{intl.formatMessage(ao.meetingNone)}</p>
      )}
      <div className="flex flex-wrap gap-2">
        {canSend ? (
          <Button className="min-h-11" disabled={sending} onClick={() => void send()}>
            {intl.formatMessage(ao.sendDrafts, { count: drafts })}
          </Button>
        ) : null}
        {raising ? (
          <Button
            variant="outline"
            className="min-h-11"
            onClick={() => {
              form.raise({ kind: 'MEETING', meeting: m })
            }}
          >
            <Plus aria-hidden="true" />
            {intl.formatMessage(ao.meetingAdd)}
          </Button>
        ) : null}
      </div>
      {form.dialog}
    </section>
  )
}

/**
 * The line "Mark the meeting as held" gains (flow AJ3): "Send the 5 draft actions to their owners now", ticked by
 * default; shown only when the meeting has drafts the secretary may send.
 */
export function HeldDraftsLine({
  meetingId,
  send,
  onChange,
}: {
  meetingId: string
  send: boolean
  onChange: (send: boolean, drafts: number) => void
}) {
  const intl = useIntl()
  const id = useId()
  const list = useQuery(getMeetingActionsOptions({ path: { meetingId } }))
  const drafts = list.data?.drafts ?? 0
  useEffect(() => {
    onChange(send, drafts)
    // Reports the drafts once they are known; the tick is the parent's.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [drafts])
  if (!drafts) return null
  return (
    <label htmlFor={id} className="mt-2 flex min-h-11 cursor-pointer items-center gap-3 text-base">
      <Checkbox
        id={id}
        className="size-5"
        checked={send}
        onChange={(e) => {
          onChange(e.target.checked, drafts)
        }}
      />
      {intl.formatMessage(ao.heldSend, { count: drafts })}
    </label>
  )
}
