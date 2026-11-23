import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import { listClassificationLevelsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { isProblem } from '@/api/http'
import { editAction, issueAction, raiseDirectAction, raiseMeetingAction } from '@/api/generated/sdk.gen'
import type { ActionPerson, ActionResponse, ActionWarning, MeetingResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select, Textarea } from '@/components/ui/form-controls'
import { newIdempotencyKey } from '@/lib/idempotency'
import { day, ownerWords } from '../words'
import { Choice, OfficerFrame } from './Frame'
import { ao } from './messages'
import { ownerChoice, ownerInput, type OwnerChoice } from './owner'
import { ContributorsField, OwnerPicker } from './OwnerPicker'
import { officerFieldProblems, officerRefusal } from './problems'
import { useSend } from './send'
import { ifMatch, later, refreshActions, todayIso, useNextMeeting } from './shared'

const TITLE_MAX = 200
const DESCRIPTION_MAX = 4000
const CONTEXT_MAX = 2000
const NOTE_MAX = 300
const REASON_MAX = 500

/** Where a new action comes from (design §1.2): a decision (and one of its conditions), an item, the meeting, or none. */
export type RaiseFrom =
  | {
      kind: 'DECISION'
      meeting: MeetingResponse
      itemId: string
      /** "Item 5", for the title. */
      item: string
      decisionId: string
      conditionIndex?: number
      conditionText?: string
    }
  | { kind: 'ITEM'; meeting: MeetingResponse; itemId: string; item: string }
  | { kind: 'MEETING'; meeting: MeetingResponse }
  | { kind: 'DIRECT'; forumId: string; forumName: string }

/** What the form reports when it saved: the action as it is now, and the sentence the page announces. */
export type Saved = (action: ActionResponse, said: string) => void

/**
 * The action form (flow AJ2, design §1.1–§1.3), a modal dialog: what is to be done, details, what the owner is told
 * (with what they will not see), the owner (a person or a position), the people also involved, the due date (by the
 * next meeting, or a date with quick picks), priority and level. Raising: "Save as draft", and once the meeting is held
 * "Save and send to the owner". Editing a draft: the same. Editing a sent action: what may change, with a reason; the
 * owner and the date have their own buttons. Warnings about the people named come in words (OWNER_RECUSED, NOT_CLEARED).
 */
export function ActionForm({
  from,
  action,
  canSend,
  onClose,
  onSaved,
}: {
  /** Raising: where it comes from. */
  from?: RaiseFrom
  /** Editing: the action as it is. */
  action?: ActionResponse
  /** Whether "Save and send to the owner" is offered (raising after the meeting is held, or a direct action). */
  canSend: boolean
  onClose: () => void
  onSaved: Saved
}) {
  const intl = useIntl()
  // Raising outside the meeting's window (409 WRONG_STATE) says when actions are raised.
  const s = useSend(undefined, (problem) =>
    !action && from?.kind !== 'DIRECT' && isProblem(problem) && problem.reason === 'WRONG_STATE'
      ? intl.formatMessage(ao.refusedRaiseWindow)
      : undefined,
  )
  const queryClient = useQueryClient()
  const levels = useQuery(listClassificationLevelsOptions())
  const forumId = action?.forumId ?? (from?.kind === 'DIRECT' ? from.forumId : (from?.meeting.forumId ?? ''))
  const meetingId = from && from.kind !== 'DIRECT' ? from.meeting.id : (action?.source.meetingId ?? undefined)
  const next = useNextMeeting(forumId, meetingId ?? undefined)
  const [current, setCurrent] = useState<ActionResponse | undefined>(action)
  const issued = current ? current.status !== 'DRAFT' : false
  const direct = current ? current.source.kind === 'DIRECT' : from?.kind === 'DIRECT'
  const condition = from?.kind === 'DECISION' ? from.conditionText : undefined

  const [title, setTitle] = useState(action?.title ?? condition ?? '')
  const [description, setDescription] = useState(action?.description ?? '')
  const [context, setContext] = useState(action?.context ?? condition ?? '')
  const [sourceNote, setSourceNote] = useState(action?.source.note ?? '')
  const [owner, setOwner] = useState<OwnerChoice>(ownerChoice(action?.owner))
  const [contributors, setContributors] = useState<ActionPerson[]>(action?.contributors ?? [])
  const [dueMode, setDueMode] = useState<'next' | 'date' | undefined>(action?.dueDate ? 'date' : undefined)
  const [dueDate, setDueDate] = useState(action?.dueDate ?? '')
  const [priority, setPriority] = useState<'NORMAL' | 'HIGH'>(action?.priority ?? 'NORMAL')
  const [level, setLevel] = useState(action?.classificationId ?? '')
  const [reason, setReason] = useState('')
  const [sendKey] = useState(() => newIdempotencyKey())
  const ids = { due: useId() }

  // "By the next meeting" is the first choice when one is planned, unless a date was chosen.
  const mode = dueMode ?? (next ? 'next' : 'date')
  const due = mode === 'next' ? next?.date : dueDate || undefined

  const formTitle = current
    ? issued
      ? intl.formatMessage(ao.editIssued, { reference: current.reference ?? '' })
      : intl.formatMessage(ao.editDraft)
    : from?.kind === 'DECISION' && from.conditionIndex != null
      ? intl.formatMessage(ao.newFromCondition, { item: from.item, number: from.conditionIndex + 1 })
      : from?.kind === 'DECISION' || from?.kind === 'ITEM'
        ? intl.formatMessage(ao.newFromItem, { item: from.item })
        : from?.kind === 'MEETING'
          ? intl.formatMessage(ao.newFromMeeting)
          : intl.formatMessage(ao.newDirect, { ...s.terms, forum: from?.forumName ?? s.terms.forum })

  const currentRank = levels.data?.find((l) => l.id === current?.classificationId)?.rank
  const levelChoices = (levels.data ?? [])
    .filter((l) => currentRank == null || l.rank >= currentRank)
    .sort((a, b) => a.rank - b.rank)

  const fields = () => {
    const common = {
      title: title.trim(),
      description: description.trim() || null,
      context: context.trim() || null,
      contributors: contributors.map((c) => c.id),
      priority,
      classificationId: level || null,
    }
    if (issued) return { ...common, reason: reason.trim() }
    return {
      ...common,
      owner: ownerInput(owner) ?? null,
      dueDate: due ?? null,
      ...(direct ? { sourceNote: sourceNote.trim() || null } : {}),
    }
  }

  /** What is missing before anything is sent; sending to the owner also needs an owner and a date. */
  const missing = (send: boolean) => {
    const found: Record<string, string> = {}
    if (!title.trim()) found.title = intl.formatMessage(ao.fieldRequiredTitle)
    if (issued && !reason.trim()) found.reason = intl.formatMessage(ao.fieldRequiredReason)
    if (send && !owner.picked) found.owner = intl.formatMessage(ao.fieldRequiredOwner)
    if (send && !due) found.dueDate = intl.formatMessage(ao.fieldRequiredDue)
    return found
  }

  const SHOWN = ['title', 'description', 'context', 'sourceNote', 'owner', 'contributors', 'dueDate', 'priority']
  const shown = [...SHOWN, 'classificationId', 'reason', 'conditionIndex']

  const save = async (send: boolean) => {
    const found = missing(send)
    if (Object.keys(found).length) {
      s.setErrors(found)
      return
    }
    const out: { saved?: ActionResponse } = {}
    const ok = await s.send(shown, async (headers) => {
      if (current) {
        out.saved = (
          await editAction({
            path: { actionId: current.id },
            headers: ifMatch(current),
            body: fields(),
            throwOnError: true,
          })
        ).data
      } else if (from?.kind === 'DIRECT') {
        out.saved = (
          await raiseDirectAction({ path: { forumId: from.forumId }, headers, body: fields(), throwOnError: true })
        ).data
      } else if (from) {
        out.saved = (
          await raiseMeetingAction({
            path: { meetingId: from.meeting.id },
            headers,
            body: {
              source: from.kind,
              ...(from.kind !== 'MEETING' ? { itemId: from.itemId } : {}),
              ...(from.kind === 'DECISION'
                ? {
                    decisionId: from.decisionId,
                    ...(from.conditionIndex != null ? { conditionIndex: from.conditionIndex } : {}),
                  }
                : {}),
              ...fields(),
            },
            throwOnError: true,
          })
        ).data
      }
    })
    if (!ok || !out.saved) return
    const draft = out.saved
    setCurrent(draft)
    if (!send) {
      await refreshActions(queryClient, draft.id)
      onSaved(draft, intl.formatMessage(issued ? ao.saved : ao.savedDraft, { title: draft.title }))
      return
    }
    // Sending is its own request: a refusal leaves the saved draft, and the form stays open on it with the reason.
    try {
      const sent = await issueAction({
        path: { actionId: draft.id },
        headers: { ...ifMatch(draft), 'Idempotency-Key': sendKey },
        throwOnError: true,
      })
      await refreshActions(queryClient, draft.id)
      onSaved(sent.data, intl.formatMessage(ao.savedSent, { reference: sent.data.reference ?? '' }))
    } catch (problem) {
      await refreshActions(queryClient, draft.id)
      const byField = officerFieldProblems(intl, s.terms, problem)
      s.setErrors(Object.fromEntries(Object.entries(byField).filter(([f]) => shown.includes(f))))
      s.setRefusal(intl.formatMessage(ao.savedSentNot, { why: officerRefusal(intl, s.terms, problem) }))
    }
  }

  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    void save(false)
  }

  return (
    <OfficerFrame
      wide
      title={formTitle}
      busy={s.busy}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
      actions={(formId) =>
        issued ? (
          <Button type="submit" form={formId} className="min-h-11" disabled={s.busy}>
            {intl.formatMessage(ao.save)}
          </Button>
        ) : (
          <>
            <Button
              type="submit"
              form={formId}
              variant={canSend ? 'outline' : 'default'}
              className="min-h-11"
              disabled={s.busy}
            >
              {intl.formatMessage(ao.saveDraft)}
            </Button>
            {canSend ? (
              <Button type="button" className="min-h-11" disabled={s.busy} onClick={() => void save(true)}>
                {intl.formatMessage(ao.saveSend)}
              </Button>
            ) : null}
          </>
        )
      }
    >
      {current?.warnings.length ? <Warnings action={current} /> : null}
      {!canSend && !issued && from && from.kind !== 'DIRECT' ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(ao.inMeeting)}</p>
      ) : null}
      <Field label={intl.formatMessage(ao.title)} required error={s.errors.title}>
        {(p) => (
          <Input
            {...p}
            data-field="title"
            maxLength={TITLE_MAX}
            value={title}
            className="min-h-11 text-base"
            onChange={(e) => {
              setTitle(e.target.value)
            }}
          />
        )}
      </Field>
      <Field
        label={intl.formatMessage(ao.description)}
        optionalLabel={intl.formatMessage(ao.optional)}
        error={s.errors.description}
      >
        {(p) => (
          <Textarea
            {...p}
            data-field="description"
            rows={3}
            maxLength={DESCRIPTION_MAX}
            value={description}
            className="text-base"
            onChange={(e) => {
              setDescription(e.target.value)
            }}
          />
        )}
      </Field>
      <Field
        label={intl.formatMessage(ao.context)}
        hint={intl.formatMessage(ao.contextHint)}
        optionalLabel={intl.formatMessage(ao.optional)}
        error={s.errors.context}
      >
        {(p) => (
          <Textarea
            {...p}
            data-field="context"
            rows={3}
            maxLength={CONTEXT_MAX}
            value={context}
            className="text-base"
            onChange={(e) => {
              setContext(e.target.value)
            }}
          />
        )}
      </Field>
      {direct && !issued ? (
        <Field
          label={intl.formatMessage(ao.sourceNote)}
          hint={intl.formatMessage(ao.sourceNoteHint)}
          optionalLabel={intl.formatMessage(ao.optional)}
          error={s.errors.sourceNote}
        >
          {(p) => (
            <Input
              {...p}
              data-field="sourceNote"
              maxLength={NOTE_MAX}
              value={sourceNote}
              className="min-h-11 text-base"
              onChange={(e) => {
                setSourceNote(e.target.value)
              }}
            />
          )}
        </Field>
      ) : null}
      {issued ? (
        <p className="text-sm">{intl.formatMessage(ao.ownerNow, { owner: ownerWords(intl, current?.owner) ?? '' })}</p>
      ) : (
        <OwnerPicker value={owner} onChange={setOwner} error={s.errors.owner} />
      )}
      <ContributorsField value={contributors} onChange={setContributors} error={s.errors.contributors} />
      {issued ? null : (
        <fieldset data-field="dueDate" className="flex flex-col gap-2" aria-describedby={ids.due}>
          <legend className="mb-1 text-sm font-medium">{intl.formatMessage(ao.due)}</legend>
          {next ? (
            <Choice
              name="action-due"
              checked={mode === 'next'}
              onSelect={() => {
                setDueMode('next')
              }}
            >
              {intl.formatMessage(ao.dueNextMeeting, { date: day(intl, next.date) })}
            </Choice>
          ) : null}
          <Choice
            name="action-due"
            checked={mode === 'date'}
            onSelect={() => {
              setDueMode('date')
            }}
          >
            {intl.formatMessage(ao.dueOnDate)}
          </Choice>
          {mode === 'date' ? (
            <div className="flex flex-col gap-2 ps-2">
              <label className="flex flex-col gap-1 text-sm font-medium">
                {intl.formatMessage(ao.dueDate)}
                <Input
                  type="date"
                  min={todayIso()}
                  className="min-h-11 w-fit text-base"
                  value={dueDate}
                  aria-invalid={s.errors.dueDate ? true : undefined}
                  aria-describedby={ids.due}
                  onChange={(e) => {
                    setDueDate(e.target.value)
                  }}
                />
              </label>
              <div role="group" aria-label={intl.formatMessage(ao.dueQuick)} className="flex flex-wrap gap-2">
                {(
                  [
                    [ao.in2Weeks, { days: 14 }],
                    [ao.in1Month, { months: 1 }],
                    [ao.in3Months, { months: 3 }],
                  ] as const
                ).map(([label, offset]) => (
                  <Button
                    key={label.id}
                    type="button"
                    variant="outline"
                    className="min-h-11"
                    onClick={() => {
                      setDueDate(later(offset))
                    }}
                  >
                    {intl.formatMessage(label)}
                  </Button>
                ))}
              </div>
            </div>
          ) : null}
          <p id={ids.due} className="text-xs text-muted-foreground">
            {due ? intl.formatMessage(ao.dueChosen, { date: day(intl, due) }) : intl.formatMessage(ao.dueHint)}
          </p>
          {s.errors.dueDate ? <p className="text-xs font-medium text-destructive">{s.errors.dueDate}</p> : null}
        </fieldset>
      )}
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">{intl.formatMessage(ao.priority)}</legend>
        <div className="flex flex-wrap gap-2">
          {(['NORMAL', 'HIGH'] as const).map((p) => (
            <Choice
              key={p}
              name="action-priority"
              checked={priority === p}
              onSelect={() => {
                setPriority(p)
              }}
            >
              {intl.formatMessage(ao[`priority${p}`])}
            </Choice>
          ))}
        </div>
      </fieldset>
      <Field
        label={intl.formatMessage(ao.level)}
        hint={intl.formatMessage(ao.levelHint)}
        error={s.errors.classificationId}
      >
        {(p) => (
          <Select
            {...p}
            data-field="classificationId"
            className="min-h-11 w-fit"
            value={level}
            onChange={(e) => {
              setLevel(e.target.value)
            }}
          >
            {current ? null : <option value="">{intl.formatMessage(ao.levelSource)}</option>}
            {levelChoices.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
      {issued ? (
        <Field
          label={intl.formatMessage(ao.reason)}
          hint={intl.formatMessage(ao.reasonHint)}
          required
          error={s.errors.reason}
        >
          {(p) => (
            <Textarea
              {...p}
              data-field="reason"
              rows={2}
              maxLength={REASON_MAX}
              value={reason}
              className="text-base"
              onChange={(e) => {
                setReason(e.target.value)
              }}
            />
          )}
        </Field>
      ) : null}
    </OfficerFrame>
  )
}

/** The people named on the action, by id: the owner and the people involved. */
function namesOf(a: ActionResponse): Map<string, string> {
  const names = new Map<string, string>()
  for (const p of [a.owner?.person, a.owner?.holder, ...a.contributors]) if (p) names.set(p.id, p.name)
  return names
}

/** "S. Chowdhury declared an interest in item 5. They will see this action, but not the item." (flow AJ2), for officers. */
export function Warnings({ action, title }: { action: ActionResponse; title?: string }) {
  const intl = useIntl()
  const names = namesOf(action)
  return (
    <div
      role="note"
      className="rounded-md border border-status-warn-fg/40 bg-status-warn-bg px-3 py-2 text-status-warn-fg"
    >
      <p className="font-medium">{title ?? intl.formatMessage(ao.warnings)}</p>
      <ul className="list-disc ps-5">
        {action.warnings.map((w: ActionWarning) => (
          <li key={`${w.code}-${w.userId}`}>
            {intl.formatMessage(w.code === 'OWNER_RECUSED' ? ao.warnOWNER_RECUSED : ao.warnNOT_CLEARED, {
              name: names.get(w.userId) ?? intl.formatMessage(ao.someone),
            })}
          </li>
        ))}
      </ul>
    </div>
  )
}
