import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, Plus, X } from 'lucide-react'
import { useId, useRef, useState, type ReactNode } from 'react'
import { useIntl } from 'react-intl'
import { listForumMeetingsOptions, listForumsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { recordItemDecision } from '@/api/generated/sdk.gen'
import type {
  DecisionRequest,
  DecisionResponse,
  LiveQuorumResponse,
  MeetingResponse,
  ToBeRatifiedResponse,
} from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select, Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { localTime } from '@/features/meetings/attendance/words'
import { NotQuorateNotice, QuorumOverrideDialog } from '@/features/meetings/attendance/QuorumOverrideDialog'
import { instantOf, joinLocal } from '@/features/meetings/time'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { cn } from '@/lib/utils'
import { dm, dmt, dmv } from './messages'
import {
  decisionRefusal,
  fieldProblems,
  itemShort,
  refreshDecisions,
  shortfall,
  voters,
  type OutcomeChoice,
  type OutcomeKind,
  type PanelItem,
  type Person,
} from './shared'

/** The longest texts the server takes (design §1.1). */
const MAX_TEXT = 4000
const MAX_CONDITION = 1000
const MAX_CONDITIONS = 20
const MAX_REASON = 500
const MAX_DISSENT_REASON = 1000

type Named = 'DISSENT' | 'ABSTAIN'
type DeferMode = 'next' | 'date' | 'meeting'

interface Draft {
  outcome: OutcomeKind | undefined
  text: string
  conditions: string[]
  validUntil: string
  deferMode: DeferMode
  deferDate: string
  deferMeetingId: string
  referToForumId: string
  referReason: string
  voteOpen: boolean
  votesFor: string
  votesAgainst: string
  votesAbstain: string
  peopleOpen: boolean
  named: Record<string, { kind: Named; reason: string }>
  ratifiesDecisionId: string
  time: string
  reason: string
  recheck: boolean
}

const count = (text: string): number | null => {
  const t = text.trim()
  if (!t) return null
  const n = Number(t)
  return Number.isInteger(n) && n >= 0 ? n : null
}

/** The form filled from [decision] (a correction), or empty with the item's resolution sought (a first record). */
function draftOf(item: PanelItem, decision: DecisionResponse | undefined, timeZone: string, startedAt?: string): Draft {
  const named: Draft['named'] = {}
  for (const d of decision?.dissent ?? []) named[d.person.id] = { kind: 'DISSENT', reason: d.reason ?? '' }
  for (const a of decision?.abstentions ?? []) named[a.id] = { kind: 'ABSTAIN', reason: '' }
  const vote = decision?.vote
  const deferTo = decision?.deferTo
  return {
    outcome: decision?.outcome,
    text: decision ? (decision.text ?? '') : (item.resolutionSought ?? ''),
    conditions: decision?.conditions.length ? [...decision.conditions] : [''],
    validUntil: decision?.validUntil ?? '',
    deferMode: deferTo?.date ? 'date' : deferTo?.meetingId ? 'meeting' : 'next',
    deferDate: deferTo?.date ?? '',
    deferMeetingId: deferTo?.meetingId ?? '',
    referToForumId: decision?.referToForumId ?? '',
    referReason: decision?.referReason ?? '',
    voteOpen: vote?.mode === 'VOTE',
    votesFor: vote?.votesFor != null ? String(vote.votesFor) : '',
    votesAgainst: vote?.votesAgainst != null ? String(vote.votesAgainst) : '',
    votesAbstain: vote?.votesAbstain != null ? String(vote.votesAbstain) : '',
    peopleOpen: Object.keys(named).length > 0,
    named,
    ratifiesDecisionId: decision?.ratifiesDecisionId ?? '',
    time: decision ? localTime(decision.decidedAt, timeZone) : startedAt ? localTime(startedAt, timeZone) : '',
    reason: '',
    recheck: false,
  }
}

/**
 * The decision form of one item (flow AA1, AA2, AA4): the committee type's outcomes as large choices, the resolution as
 * it will be minuted, conditions and validity, where a deferred or referred item goes, an optional vote ("5 voting members
 * present and able to vote"), dissent and abstentions chosen from the people present who may vote (a person who declared
 * an interest is not offered), who took no part, what this decision ratifies, the time the item was taken once the
 * meeting is held, and for a correction its reason. Not quorate ([quorum]): the button gives way to "Record without a
 * quorum…" (E05's dialog). After the memo has moved on the outcome stays as it is ([decision] RELEASED). Refusals are
 * said in words, at the field when they are about one.
 */
export function DecisionForm({
  meeting,
  item,
  decision,
  outcomes,
  quorum,
  quorumOverride,
  toBeRatified,
  startedAt,
  minutesInReview,
  onDone,
  onCancel,
}: {
  meeting: MeetingResponse
  item: PanelItem
  decision?: DecisionResponse
  outcomes: OutcomeChoice[]
  quorum: LiveQuorumResponse | undefined
  quorumOverride: 'NEVER' | 'WITH_REASON' | undefined
  toBeRatified: ToBeRatifiedResponse[]
  /** When the item was started ("Now: item n"), for the time once the meeting is held. */
  startedAt?: string
  minutesInReview: boolean
  onDone: (decision: DecisionResponse) => void
  onCancel?: () => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const queryClient = useQueryClient()
  const formId = useId()
  const correcting = decision != null
  const held = meeting.status !== 'IN_PROGRESS'
  const [draft, setDraft] = useState<Draft>(() => draftOf(item, decision, meeting.timeZone, startedAt))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [refusal, setRefusal] = useState<string>()
  const [notQuorate, setNotQuorate] = useState<{ present: number; needed: number }>()
  const [overriding, setOverriding] = useState(false)
  const [returnToDraft, setReturnToDraft] = useState(minutesInReview)
  const [busy, setBusy] = useState(false)
  const sent = useRef<{ key: string; request: string } | null>(null)
  const refusalRef = useRef<HTMLParagraphElement>(null)
  const formRef = useRef<HTMLFormElement>(null)

  const set = (next: Partial<Draft>) => {
    setDraft((d) => ({ ...d, ...next }))
  }
  const chosen = outcomes.find((o) => o.kind === draft.outcome)
  const released = decision?.state === 'RELEASED'
  const { eligible, recused, steppedOut } = voters(quorum)
  const seats = quorum?.seats != null
  const approval = draft.outcome === 'APPROVED' || draft.outcome === 'APPROVED_WITH_CONDITIONS'
  const ratifiable = (draft.outcome === 'APPROVED' || draft.outcome === 'NOTED') && toBeRatified.length > 0
  const others = toBeRatified.filter((r) => r.decisionId !== decision?.id)
  // A first record (or one whose time changes) takes the quorum now; a correction keeps the earlier snapshot.
  const timeChanged = correcting && draft.time !== localTime(decision.decidedAt, meeting.timeZone)
  const gated = (!correcting || timeChanged || draft.recheck) && quorum != null && !quorum.quorate
  const shortBy = notQuorate ?? (gated ? { present: quorum.present, needed: quorum.needed } : undefined)

  const later = useQuery({
    ...listForumMeetingsOptions({ path: { forumId: meeting.forumId }, query: { status: ['DRAFT', 'SCHEDULED'] } }),
    enabled: draft.outcome === 'DEFERRED',
  })
  const laterMeetings = (later.data?.items ?? []).filter((m) => m.startsAt > meeting.startsAt && m.id !== meeting.id)
  const forums = useQuery({ ...listForumsOptions(), enabled: draft.outcome === 'REFERRED' })
  const otherForums = (forums.data ?? []).filter((f) => f.id !== meeting.forumId && f.status === 'ACTIVE')

  const totalVotes = [draft.votesFor, draft.votesAgainst, draft.votesAbstain].reduce((a, v) => a + (count(v) ?? 0), 0)
  const voting = draft.voteOpen && [draft.votesFor, draft.votesAgainst, draft.votesAbstain].some((v) => v.trim())
  const forNotMore =
    voting && approval && (count(draft.votesFor) ?? 0) <= (count(draft.votesAgainst) ?? 0) && totalVotes > 0

  /** The people named, in the order they are listed (the server's problems point at these positions). */
  const dissenters = eligible.filter((p) => draft.named[p.id]?.kind === 'DISSENT')
  const abstainers = eligible.filter((p) => draft.named[p.id]?.kind === 'ABSTAIN')
  // A correction keeps the people named before even when they are no longer in the item's quorum (the server decides).
  const keptDissent = (decision?.dissent ?? [])
    .filter((d) => draft.named[d.person.id]?.kind === 'DISSENT' && !eligible.some((p) => p.id === d.person.id))
    .map((d) => d.person)
  const keptAbstain = (decision?.abstentions ?? []).filter(
    (a) => draft.named[a.id]?.kind === 'ABSTAIN' && !eligible.some((p) => p.id === a.id),
  )
  const dissentList = [...dissenters, ...keptDissent]
  const abstainList = [...abstainers, ...keptAbstain]

  const validate = (): Record<string, string> => {
    const out: Record<string, string> = {}
    if (!chosen) out.outcome = intl.formatMessage(dm.outcomeRequired)
    if (chosen?.resolution && !draft.text.trim()) out.text = intl.formatMessage(dm.TEXT_REQUIRED)
    if (draft.text.trim().length > MAX_TEXT) out.text = intl.formatMessage(dm.LENGTH)
    if (draft.outcome === 'APPROVED_WITH_CONDITIONS' && !draft.conditions.some((c) => c.trim())) {
      out.conditions = intl.formatMessage(dm.CONDITIONS_REQUIRED)
    }
    if (draft.outcome === 'DEFERRED') {
      if (draft.deferMode === 'date' && !draft.deferDate) out.deferTo = intl.formatMessage(dm.TARGET_REQUIRED)
      if (draft.deferMode === 'meeting' && !draft.deferMeetingId) out.deferTo = intl.formatMessage(dm.TARGET_REQUIRED)
    }
    if (draft.outcome === 'REFERRED') {
      if (!draft.referToForumId) out.referToForumId = intl.formatMessage(dm.TARGET_REQUIRED)
      if (!draft.referReason.trim()) out.referReason = intl.formatMessage(dm.REASON_REQUIRED)
    }
    if (voting) {
      if ([draft.votesFor, draft.votesAgainst, draft.votesAbstain].some((v) => v.trim() && count(v) == null)) {
        out.votes = intl.formatMessage(dm.invalid)
      } else if (seats && totalVotes > eligible.length) {
        out.votes = intl.formatMessage(dmv.tooManyVotes, { count: eligible.length })
      }
    }
    if (correcting && !draft.reason.trim()) out.reason = intl.formatMessage(dm.REASON_REQUIRED)
    if (draft.reason.trim().length > MAX_REASON) out.reason = intl.formatMessage(dm.LENGTH)
    return out
  }

  const request = (override?: string): DecisionRequest => {
    const outcome: OutcomeKind = released ? decision.outcome : (draft.outcome ?? 'NOTED')
    const time =
      held && draft.time && (!correcting || timeChanged)
        ? joinLocal(meeting.localStartsAt.slice(0, 10), draft.time)
        : undefined
    const decidedAt = time ? instantOf(time, meeting.timeZone) : undefined
    return {
      outcome,
      text: draft.text.trim() || null,
      conditions: outcome === 'APPROVED_WITH_CONDITIONS' ? draft.conditions.map((c) => c.trim()).filter(Boolean) : [],
      validUntil:
        (outcome === 'APPROVED' || outcome === 'APPROVED_WITH_CONDITIONS') && draft.validUntil
          ? draft.validUntil
          : null,
      deferTo:
        outcome === 'DEFERRED'
          ? draft.deferMode === 'date'
            ? { date: draft.deferDate }
            : draft.deferMode === 'meeting'
              ? { meetingId: draft.deferMeetingId }
              : { next: true }
          : null,
      referToForumId: outcome === 'REFERRED' ? draft.referToForumId : null,
      referReason: outcome === 'REFERRED' ? draft.referReason.trim() : null,
      voteMode: voting ? 'VOTE' : 'CONSENSUS',
      votesFor: voting ? (count(draft.votesFor) ?? 0) : null,
      votesAgainst: voting ? (count(draft.votesAgainst) ?? 0) : null,
      votesAbstain: voting ? (count(draft.votesAbstain) ?? 0) : null,
      dissent: dissentList.map((p) => {
        const reason = draft.named[p.id]?.reason.trim()
        return reason ? { userId: p.id, reason } : { userId: p.id }
      }),
      abstentions: abstainList.map((p) => p.id),
      ratifiesDecisionId: ratifiable && draft.ratifiesDecisionId ? draft.ratifiesDecisionId : null,
      ...(decidedAt ? { decidedAt } : {}),
      ...(correcting ? { reason: draft.reason.trim() } : {}),
      ...(draft.recheck ? { recheckQuorum: true } : {}),
      ...(returnToDraft ? { returnMinutesToDraft: true } : {}),
      ...(override ? { override: { reason: override } } : {}),
    }
  }

  /** The server's 422s at their fields; a person's at the person. */
  const showFieldProblems = (problem: unknown) => {
    const found = fieldProblems(intl, problem, eligible.length)
    const out: Record<string, string> = {}
    for (const [field, message] of Object.entries(found)) {
      const dissent = /^dissent\/(\d+)\/(userId|reason)$/.exec(field)
      const abstain = /^abstentions\/(\d+)$/.exec(field)
      const person = dissent ? dissentList[Number(dissent[1])] : abstain ? abstainList[Number(abstain[1])] : undefined
      if (person) {
        out[`person:${person.id}`] = message
        set({ peopleOpen: true })
      } else if (field.startsWith('votes')) {
        out.votes = message
        set({ voteOpen: true })
      } else if (field === 'dissent' || field === 'abstentions') {
        out.people = message
        set({ peopleOpen: true })
      } else {
        out[field.split('/')[0] ?? field] = message
      }
    }
    setErrors(out)
    focusFirstError()
  }

  const focusFirstError = () => {
    requestAnimationFrame(() => {
      formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"], [data-error="true"] input')?.focus()
    })
  }

  /** Sends the decision; throws the problem when [rethrow] says the caller (the override dialog) words it. */
  const send = async (override?: string, rethrow?: (problem: unknown) => boolean): Promise<void> => {
    const local = validate()
    if (Object.keys(local).length) {
      setErrors(local)
      setRefusal(intl.formatMessage(dm.checkForm))
      focusFirstError()
      return
    }
    setErrors({})
    setRefusal(undefined)
    const body = request(override)
    const text = JSON.stringify(body)
    if (sent.current?.request !== text) sent.current = { key: newIdempotencyKey(), request: text }
    setBusy(true)
    try {
      const result = await recordItemDecision({
        path: { meetingId: meeting.id, itemId: item.id },
        body,
        headers: {
          'Idempotency-Key': sent.current.key,
          ...(decision ? { 'If-Match': `"${String(decision.version)}"` } : {}),
        },
        throwOnError: true,
      })
      sent.current = null
      setNotQuorate(undefined)
      toast.show(intl.formatMessage(correcting ? dm.corrected : dm.recorded), 'success')
      await refreshDecisions(queryClient, meeting.id)
      onDone(result.data)
    } catch (problem) {
      if (rethrow?.(problem)) throw problem
      if (!isProblem(problem)) {
        setRefusal(intl.formatMessage(dm.failed))
      } else if (problem.status === 422) {
        showFieldProblems(problem)
        setRefusal(intl.formatMessage(dm.checkForm))
      } else if (problem.reason === 'MINUTES_IN_REVIEW') {
        setReturnToDraft(true)
        setRefusal(intl.formatMessage(dm.minutesInReview))
      } else if (problem.reason === 'NOT_QUORATE') {
        setNotQuorate(shortfall(problem))
      } else {
        if (problem.status === 412 || problem.status === 409) await refreshDecisions(queryClient, meeting.id)
        setRefusal(decisionRefusal(intl, problem))
      }
      requestAnimationFrame(() => refusalRef.current?.focus())
    } finally {
      setBusy(false)
    }
  }

  const overrideProblem = (problem: unknown) =>
    isProblem(problem) &&
    (['OVERRIDE_NOT_ALLOWED', 'NOT_QUORATE', 'RECUSED'].includes(problem.reason ?? '') ||
      (problem.status === 422 && (problem.errors ?? []).some((e) => e.field.startsWith('override'))))

  const name = itemShort(intl, item)

  return (
    <>
      <form
        ref={formRef}
        noValidate
        aria-labelledby={`${formId}-title`}
        className="flex flex-col gap-5"
        onSubmit={(e) => {
          e.preventDefault()
          void send()
        }}
      >
        <h3 id={`${formId}-title`} className="sr-only">
          {correcting ? intl.formatMessage(dmv.correctTitle, { item: name }) : intl.formatMessage(dm.decision)}
        </h3>
        <fieldset className="flex flex-col gap-2" aria-describedby={released ? `${formId}-released` : undefined}>
          <legend className="mb-1 text-base font-semibold">{intl.formatMessage(dm.decision)}</legend>
          <div className="grid gap-2 sm:grid-cols-2" data-error={errors.outcome ? 'true' : undefined}>
            {outcomes.map((o) => (
              <label
                key={o.kind}
                className={cn(
                  'flex min-h-11 cursor-pointer items-center gap-3 rounded-md border px-3 py-2 text-base',
                  draft.outcome === o.kind && 'border-primary bg-primary/5 font-semibold',
                  released && 'cursor-not-allowed opacity-70',
                )}
              >
                <input
                  type="radio"
                  name={`${formId}-outcome`}
                  className="size-5 shrink-0 accent-primary"
                  checked={draft.outcome === o.kind}
                  disabled={released}
                  aria-invalid={errors.outcome ? true : undefined}
                  onChange={() => {
                    set({ outcome: o.kind })
                  }}
                />
                {o.label}
              </label>
            ))}
          </div>
          {errors.outcome ? <p className="text-sm text-destructive">{errors.outcome}</p> : null}
          {released ? (
            <p id={`${formId}-released`} className="rounded-md bg-muted/50 p-3 text-base">
              {intl.formatMessage(dm.RELEASED)} {intl.formatMessage(dm.releasedOutcome)}
            </p>
          ) : null}
        </fieldset>

        <Field
          label={intl.formatMessage(chosen?.resolution === false ? dm.decisionText : dm.resolutionText)}
          optionalLabel={intl.formatMessage(dm.optional)}
          required={chosen?.resolution ?? true}
          error={errors.text}
        >
          {(p) => (
            <Textarea
              {...p}
              dir="auto"
              rows={4}
              maxLength={MAX_TEXT}
              className="field-sizing-content min-h-24 text-base"
              value={draft.text}
              onChange={(e) => {
                set({ text: e.target.value })
              }}
            />
          )}
        </Field>

        {draft.outcome === 'APPROVED_WITH_CONDITIONS' ? (
          <Conditions
            conditions={draft.conditions}
            error={errors.conditions}
            onChange={(conditions) => {
              set({ conditions })
            }}
          />
        ) : null}

        {approval ? (
          <Field
            label={intl.formatMessage(dm.validUntil)}
            optionalLabel={intl.formatMessage(dm.optional)}
            error={errors.validUntil}
            className="max-w-xs"
          >
            {(p) => (
              <Input
                {...p}
                type="date"
                className="min-h-11"
                value={draft.validUntil}
                onChange={(e) => {
                  set({ validUntil: e.target.value })
                }}
              />
            )}
          </Field>
        ) : null}

        {draft.outcome === 'DEFERRED' ? (
          <fieldset className="flex flex-col gap-2" data-error={errors.deferTo ? 'true' : undefined}>
            <legend className="mb-1 text-sm font-medium">{intl.formatMessage(dm.deferTo)}</legend>
            {(['next', 'date', 'meeting'] as const).map((mode) => (
              <label key={mode} className="flex min-h-11 cursor-pointer items-center gap-3 text-base">
                <input
                  type="radio"
                  name={`${formId}-defer`}
                  className="size-5 accent-primary"
                  checked={draft.deferMode === mode}
                  onChange={() => {
                    set({ deferMode: mode })
                  }}
                />
                {intl.formatMessage(mode === 'next' ? dm.deferNext : mode === 'date' ? dm.deferDate : dm.deferMeeting)}
              </label>
            ))}
            {draft.deferMode === 'date' ? (
              <Field label={intl.formatMessage(dm.deferDateLabel)} error={errors.deferTo} required className="max-w-xs">
                {(p) => (
                  <Input
                    {...p}
                    type="date"
                    className="min-h-11"
                    value={draft.deferDate}
                    onChange={(e) => {
                      set({ deferDate: e.target.value })
                    }}
                  />
                )}
              </Field>
            ) : null}
            {draft.deferMode === 'meeting' ? (
              laterMeetings.length ? (
                <Field label={intl.formatMessage(dm.deferMeetingLabel)} error={errors.deferTo} required>
                  {(p) => (
                    <Select
                      {...p}
                      className="min-h-11"
                      value={draft.deferMeetingId}
                      onChange={(e) => {
                        set({ deferMeetingId: e.target.value })
                      }}
                    >
                      <option value="">{intl.formatMessage(dm.chooseCommittee)}</option>
                      {laterMeetings.map((m) => (
                        <option key={m.id} value={m.id}>
                          {[m.label, intl.formatDate(m.startsAt, { dateStyle: 'long', timeZone: m.timeZone })]
                            .filter(Boolean)
                            .join(' · ')}
                        </option>
                      ))}
                    </Select>
                  )}
                </Field>
              ) : (
                <p className="text-base text-muted-foreground">{intl.formatMessage(dm.noLaterMeetings)}</p>
              )
            ) : null}
            {errors.deferTo && draft.deferMode === 'next' ? (
              <p className="text-sm text-destructive">{errors.deferTo}</p>
            ) : null}
          </fieldset>
        ) : null}

        {draft.outcome === 'REFERRED' ? (
          <div className="flex flex-col gap-3">
            {otherForums.length || forums.isPending ? (
              <Field label={intl.formatMessage(dmt.referTo, terms)} error={errors.referToForumId} required>
                {(p) => (
                  <Select
                    {...p}
                    className="min-h-11"
                    value={draft.referToForumId}
                    onChange={(e) => {
                      set({ referToForumId: e.target.value })
                    }}
                  >
                    <option value="">{intl.formatMessage(dm.chooseCommittee)}</option>
                    {otherForums.map((f) => (
                      <option key={f.id} value={f.id}>
                        {f.name}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
            ) : (
              <p className="text-base text-muted-foreground">{intl.formatMessage(dmt.noCommittees, terms)}</p>
            )}
            <Field label={intl.formatMessage(dm.referReason)} error={errors.referReason} required>
              {(p) => (
                <Textarea
                  {...p}
                  dir="auto"
                  rows={2}
                  maxLength={MAX_REASON}
                  className="field-sizing-content min-h-16 text-base"
                  value={draft.referReason}
                  onChange={(e) => {
                    set({ referReason: e.target.value })
                  }}
                />
              )}
            </Field>
          </div>
        ) : null}

        {ratifiable && others.length ? (
          <Field
            label={intl.formatMessage(dm.ratifies)}
            hint={intl.formatMessage(dm.ratifiesHint)}
            error={errors.ratifiesDecisionId}
          >
            {(p) => (
              <Select
                {...p}
                className="min-h-11"
                value={draft.ratifiesDecisionId}
                onChange={(e) => {
                  set({ ratifiesDecisionId: e.target.value })
                }}
              >
                <option value="">{intl.formatMessage(dm.ratifiesNone)}</option>
                {others.map((r) => (
                  <option key={r.decisionId} value={r.decisionId}>
                    {intl.formatMessage(dmv.ratifiesItem, {
                      meeting: r.meetingLabel ?? '',
                      item: r.itemNumber ?? '',
                      outcome: r.outcomeLabel,
                    })}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        ) : null}

        {held ? (
          <Field
            label={intl.formatMessage(dm.whenTaken)}
            hint={intl.formatMessage(dm.whenTakenHint)}
            error={errors.decidedAt}
            className="max-w-xs"
          >
            {(p) => (
              <Input
                {...p}
                type="time"
                className="min-h-11"
                value={draft.time}
                onChange={(e) => {
                  set({ time: e.target.value })
                }}
              />
            )}
          </Field>
        ) : null}

        <Disclosure
          label={`${intl.formatMessage(dm.recordVote)} ${intl.formatMessage(dm.optional)}`}
          open={draft.voteOpen}
          onToggle={() => {
            set({ voteOpen: !draft.voteOpen })
          }}
        >
          <fieldset className="flex flex-col gap-2" data-error={errors.votes ? 'true' : undefined}>
            <legend className="sr-only">{intl.formatMessage(dm.recordVote)}</legend>
            <div className="flex flex-wrap gap-3">
              {(
                [
                  ['votesFor', dm.votesFor],
                  ['votesAgainst', dm.votesAgainst],
                  ['votesAbstain', dm.votesAbstain],
                ] as const
              ).map(([key, label]) => (
                <Field key={key} label={intl.formatMessage(label)} className="w-28">
                  {(p) => (
                    <Input
                      {...p}
                      inputMode="numeric"
                      className="min-h-11 text-base tabular-nums"
                      aria-invalid={errors.votes ? true : undefined}
                      value={draft[key]}
                      onChange={(e) => {
                        set({ [key]: e.target.value })
                      }}
                    />
                  )}
                </Field>
              ))}
            </div>
            {seats ? (
              <p className="text-base">{intl.formatMessage(dmv.votersLine, { count: eligible.length })}</p>
            ) : null}
            {errors.votes ? (
              <p role="alert" className="text-sm text-destructive">
                {errors.votes}
              </p>
            ) : forNotMore ? (
              <p className="text-sm text-status-warn-fg">{intl.formatMessage(dmv.forNotMore, {})}</p>
            ) : null}
          </fieldset>
        </Disclosure>

        <Disclosure
          label={`${intl.formatMessage(dm.people)} ${intl.formatMessage(dm.optional)}`}
          open={draft.peopleOpen}
          onToggle={() => {
            set({ peopleOpen: !draft.peopleOpen })
          }}
        >
          <People
            formId={formId}
            eligible={eligible}
            kept={[...keptDissent, ...keptAbstain]}
            named={draft.named}
            errors={errors}
            onChange={(named) => {
              set({ named })
            }}
          />
        </Disclosure>

        <TookNoPart decision={decision} recused={recused} steppedOut={steppedOut} />

        {correcting ? (
          <>
            <Field label={intl.formatMessage(dm.correctReason)} error={errors.reason} required>
              {(p) => (
                <Textarea
                  {...p}
                  dir="auto"
                  rows={2}
                  maxLength={MAX_REASON}
                  className="field-sizing-content min-h-16 text-base"
                  value={draft.reason}
                  onChange={(e) => {
                    set({ reason: e.target.value })
                  }}
                />
              )}
            </Field>
            {decision.drifted ? (
              <label className="flex min-h-11 cursor-pointer items-center gap-3 text-base">
                <Checkbox
                  className="size-5"
                  checked={draft.recheck}
                  onChange={(e) => {
                    set({ recheck: e.target.checked })
                  }}
                />
                {intl.formatMessage(dm.recheck)}
              </label>
            ) : null}
          </>
        ) : null}

        {returnToDraft && correcting ? (
          <p className="rounded-md border border-status-warn-fg/40 bg-status-warn-bg p-3 text-base">
            {intl.formatMessage(dm.minutesInReview)}
          </p>
        ) : null}

        {refusal ? (
          <p
            ref={refusalRef}
            tabIndex={-1}
            role="alert"
            className="rounded-md border border-destructive/40 p-3 text-base"
          >
            {refusal}
          </p>
        ) : null}

        {shortBy ? <NotQuorateNotice quorum={shortBy} /> : null}

        <div className="flex flex-wrap justify-end gap-2">
          {onCancel ? (
            <Button type="button" variant="outline" className="min-h-11 text-base" onClick={onCancel}>
              {intl.formatMessage(dm.cancel)}
            </Button>
          ) : null}
          {shortBy ? (
            quorumOverride !== 'NEVER' ? (
              <Button
                type="button"
                className="min-h-11 text-base"
                disabled={busy}
                onClick={() => {
                  const local = validate()
                  if (Object.keys(local).length) {
                    setErrors(local)
                    setRefusal(intl.formatMessage(dm.checkForm))
                    focusFirstError()
                    return
                  }
                  setOverriding(true)
                }}
              >
                {intl.formatMessage(dm.recordWithoutQuorum)}
              </Button>
            ) : null
          ) : (
            <Button type="submit" className="min-h-11 text-base" disabled={busy}>
              {intl.formatMessage(correcting ? dm.correctSubmit : dm.record)}
            </Button>
          )}
        </div>
      </form>
      {overriding && shortBy ? (
        <QuorumOverrideDialog
          quorum={shortBy}
          allowed={quorumOverride !== 'NEVER'}
          onRecord={(reason) => send(reason, overrideProblem)}
          onClose={() => {
            setOverriding(false)
          }}
        />
      ) : null}
    </>
  )
}

/** A section that opens and closes with a 44 px button ("▸ Record a vote (optional)"). */
function Disclosure({
  label,
  open,
  onToggle,
  children,
}: {
  label: string
  open: boolean
  onToggle: () => void
  children: ReactNode
}) {
  const id = useId()
  return (
    <div className="flex flex-col gap-2 rounded-md border">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={id}
        className="flex min-h-11 items-center gap-2 px-3 text-start text-base font-medium"
        onClick={onToggle}
      >
        <ChevronDown
          aria-hidden="true"
          className={cn('size-5 transition-transform', !open && '-rotate-90 rtl:rotate-90')}
        />
        {label}
      </button>
      <div id={id} hidden={!open} className="px-3 pb-3">
        {children}
      </div>
    </div>
  )
}

/** The conditions, one line each, with "Add a condition" and a remove button per line. */
function Conditions({
  conditions,
  error,
  onChange,
}: {
  conditions: string[]
  error?: string
  onChange: (conditions: string[]) => void
}) {
  const intl = useIntl()
  const listRef = useRef<HTMLOListElement>(null)
  const add = () => {
    onChange([...conditions, ''])
    requestAnimationFrame(() => {
      const inputs = listRef.current?.querySelectorAll('input')
      inputs?.[inputs.length - 1]?.focus()
    })
  }
  return (
    <fieldset className="flex flex-col gap-2" data-error={error ? 'true' : undefined}>
      <legend className="mb-1 text-sm font-medium">{intl.formatMessage(dm.conditions)}</legend>
      <ol ref={listRef} className="flex flex-col gap-2">
        {conditions.map((c, i) => (
          <li key={i} className="flex items-center gap-2">
            <span aria-hidden="true" className="w-6 text-end tabular-nums">
              {i + 1}
            </span>
            <Input
              dir="auto"
              aria-label={intl.formatMessage(dmv.conditionLabel, { number: i + 1 })}
              aria-invalid={error && !conditions.some((x) => x.trim()) ? true : undefined}
              maxLength={MAX_CONDITION}
              className="min-h-11 flex-1 text-base"
              value={c}
              onChange={(e) => {
                onChange(conditions.map((x, j) => (j === i ? e.target.value : x)))
              }}
            />
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label={intl.formatMessage(dmv.conditionRemove, { number: i + 1 })}
              onClick={() => {
                onChange(conditions.length > 1 ? conditions.filter((_, j) => j !== i) : [''])
              }}
            >
              <X aria-hidden="true" />
            </Button>
          </li>
        ))}
      </ol>
      {error ? <p className="text-sm text-destructive">{error}</p> : null}
      {conditions.length < MAX_CONDITIONS ? (
        <div>
          <Button type="button" variant="outline" className="min-h-11 text-base" onClick={add}>
            <Plus aria-hidden="true" />
            {intl.formatMessage(dm.addCondition)}
          </Button>
        </div>
      ) : null}
    </fieldset>
  )
}

/**
 * The people present who may vote, each "Agreed", "Dissented" or "Abstained", with the reason a dissenter asked to be
 * recorded; people a correction named before and who are no longer offered are kept. Nobody who declared an interest is
 * offered (flow AA1).
 */
function People({
  formId,
  eligible,
  kept,
  named,
  errors,
  onChange,
}: {
  formId: string
  eligible: Person[]
  kept: Person[]
  named: Draft['named']
  errors: Record<string, string>
  onChange: (named: Draft['named']) => void
}) {
  const intl = useIntl()
  const people = [...eligible, ...kept]
  const choose = (id: string, kind: Named | undefined) => {
    const next = Object.fromEntries(Object.entries(named).filter(([k]) => k !== id))
    if (kind) next[id] = { kind, reason: named[id]?.reason ?? '' }
    onChange(next)
  }
  return (
    <div className="flex flex-col gap-3" data-error={errors.people ? 'true' : undefined}>
      <p className="text-sm text-muted-foreground">{intl.formatMessage(dm.peopleHint)}</p>
      {errors.people ? (
        <p role="alert" className="text-sm text-destructive">
          {errors.people}
        </p>
      ) : null}
      {people.length === 0 ? <p className="text-base">{intl.formatMessage(dm.noVoters)}</p> : null}
      <ul className="flex flex-col divide-y">
        {people.map((p) => {
          const mine = named[p.id]
          const error = errors[`person:${p.id}`]
          return (
            <li key={p.id} className="flex flex-col gap-2 py-2" data-error={error ? 'true' : undefined}>
              <fieldset>
                <legend className="text-base font-medium">{p.name}</legend>
                <div className="flex flex-wrap gap-x-4">
                  {(
                    [
                      [undefined, dm.agreed],
                      ['DISSENT', dm.dissented],
                      ['ABSTAIN', dm.abstained],
                    ] as const
                  ).map(([kind, label]) => (
                    <label key={kind ?? 'none'} className="flex min-h-11 cursor-pointer items-center gap-2 text-base">
                      <input
                        type="radio"
                        name={`${formId}-person-${p.id}`}
                        className="size-5 accent-primary"
                        checked={mine?.kind === kind}
                        aria-invalid={error ? true : undefined}
                        onChange={() => {
                          choose(p.id, kind)
                        }}
                      />
                      {intl.formatMessage(label)}
                    </label>
                  ))}
                </div>
              </fieldset>
              {error ? <p className="text-sm text-destructive">{error}</p> : null}
              {mine?.kind === 'DISSENT' ? (
                <Field label={intl.formatMessage(dm.dissentReason)}>
                  {(f) => (
                    <Input
                      {...f}
                      dir="auto"
                      maxLength={MAX_DISSENT_REASON}
                      className="min-h-11 text-base"
                      value={mine.reason}
                      onChange={(e) => {
                        onChange({ ...named, [p.id]: { kind: 'DISSENT', reason: e.target.value } })
                      }}
                    />
                  )}
                </Field>
              ) : null}
            </li>
          )
        })}
      </ul>
    </div>
  )
}

/** "Took no part: Demo Director (declared an interest)": the decision's own, or from the item's quorum before it exists. */
function TookNoPart({
  decision,
  recused,
  steppedOut,
}: {
  decision?: DecisionResponse
  recused: Person[]
  steppedOut: Person[]
}) {
  const intl = useIntl()
  const names = decision?.tookNoPart
    ? decision.tookNoPart.map((p) => p.name)
    : [
        ...recused.map((p) =>
          intl.formatMessage(dmv.tookNoPartWhy, { name: p.name, why: intl.formatMessage(dm.whyDeclared) }),
        ),
        ...steppedOut.map((p) =>
          intl.formatMessage(dmv.tookNoPartWhy, { name: p.name, why: intl.formatMessage(dm.whySteppedOut) }),
        ),
      ]
  if (!names.length) return null
  return <p className="text-base">{intl.formatMessage(dmv.tookNoPart, { names: intl.formatList(names) })}</p>
}
