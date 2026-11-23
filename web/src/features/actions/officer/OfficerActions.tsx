import { useQuery } from '@tanstack/react-query'
import { useId, useState, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import { getForumActionsOptions } from '@/api/generated/@tanstack/react-query.gen'
import {
  cancelAction,
  changeActionOwner,
  clearActionRag,
  decideActionDue,
  discardAction,
  holdActionFromReport,
  issueAction,
  overrideActionRag,
  postActionUpdate,
  reopenAction,
} from '@/api/generated/sdk.gen'
import type { ActionResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select, Textarea } from '@/components/ui/form-controls'
import { useTerms } from '@/features/terms/terms'
import { confidenceWord, day, ownerWords, type CancelKind, type Confidence, type How } from '../words'
import { ActionForm, Warnings } from './ActionForm'
import { Choice, OfficerFrame } from './Frame'
import { ao } from './messages'
import { ownerChoice, ownerInput, type OwnerChoice } from './owner'
import { OwnerPicker } from './OwnerPicker'
import { officerRefusal } from './problems'
import { useSend } from './send'
import { ifMatch, ownerUserId, todayIso } from './shared'

type Kind = 'edit' | 'issue' | 'discard' | 'decideDue' | 'owner' | 'colour' | 'cancel' | 'reopen' | 'hold' | 'behalf'

/** What an officer's change reports: the action as it is now (when the answer is one) and the sentence to announce. */
export type OfficerDone = (next: ActionResponse | undefined, said: string) => void

const REASON_MAX = 500
const TEXT_MAX = 4000
const HOWS: readonly How[] = ['PHONE', 'EMAIL', 'IN_PERSON', 'MEETING']
const CONFIDENCES: readonly Confidence[] = ['ON_TRACK', 'AT_RISK', 'OFF_TRACK']
const OPEN: ActionResponse['status'][] = ['OPEN', 'IN_PROGRESS', 'DONE_PENDING']

/**
 * The officers' buttons on the action page (flow AN, design §1.3–§1.10, §1.12), each shown only when the server's
 * `can` allows it: edit, send a draft to its owner or discard it, answer a date request, change the owner, set the colour
 * by hand or take it away, record an update the owner gave by phone, cancel (with "risk accepted" for the chair, or
 * recorded for the chair), reopen, and keep the action out of the next report. A recused officer gets none of them
 * (ADR-0062): the server's `can` is false for them.
 */
export function OfficerActions({
  action: a,
  onDone,
  onStale,
  busy,
}: {
  action: ActionResponse
  onDone: OfficerDone
  onStale: () => void
  busy?: boolean
}) {
  const intl = useIntl()
  const terms = useTerms()
  const [open, setOpen] = useState<Kind>()
  const [clearing, setClearing] = useState(false)
  const headingId = useId()
  const can = a.can
  const issued = a.status !== 'DRAFT'
  const offer = (when: boolean, key: Kind | 'clear', label: string) => (when ? [{ key, label }] : [])
  // Holding back is a secretary's or forums.manage's (the same people who edit a sent action), on an open action.
  const buttons = [
    ...offer(can.issue, 'issue', intl.formatMessage(ao.issue)),
    ...offer(can.edit, 'edit', intl.formatMessage(ao.edit)),
    ...offer(can.discard, 'discard', intl.formatMessage(ao.discard)),
    ...offer(can.decideDue && a.pendingRequest != null, 'decideDue', intl.formatMessage(ao.decideDue)),
    ...offer(can.updateOnBehalf, 'behalf', intl.formatMessage(ao.onBehalf)),
    ...offer(can.changeOwner, 'owner', intl.formatMessage(ao.changeOwner)),
    ...offer(can.overrideRag, 'colour', intl.formatMessage(ao.setColour)),
    ...offer(can.overrideRag && a.rag.override != null, 'clear', intl.formatMessage(ao.clearColour)),
    ...offer(can.edit && issued && OPEN.includes(a.status), 'hold', intl.formatMessage(ao.holdBack)),
    ...offer(can.cancel, 'cancel', intl.formatMessage(ao.cancelAction)),
    ...offer(can.reopen, 'reopen', intl.formatMessage(ao.reopen)),
  ]
  const officer = buttons.length > 0 || (a.warnings.length > 0 && (can.edit || can.issue))
  if (!officer) return null

  const clear = async () => {
    setClearing(true)
    try {
      const next = await clearActionRag({ path: { actionId: a.id }, headers: ifMatch(a), throwOnError: true })
      onDone(next.data, intl.formatMessage(ao.colourCleared))
    } catch (problem) {
      onDone(undefined, officerRefusal(intl, terms, problem))
      onStale()
    } finally {
      setClearing(false)
    }
  }
  const close = () => {
    setOpen(undefined)
  }
  const done: OfficerDone = (next, said) => {
    setOpen(undefined)
    onDone(next, said)
  }
  const props = { action: a, onClose: close, onDone: done, onStale }

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3">
      <h2 id={headingId} className="text-base font-semibold">
        {intl.formatMessage(ao.officerTitle, terms)}
      </h2>
      {a.warnings.length ? <Warnings action={a} title={intl.formatMessage(ao.warningsOnPage)} /> : null}
      {buttons.length ? (
        <div className="flex flex-wrap gap-2">
          {buttons.map((b) => (
            <Button
              key={b.key}
              variant={b.key === 'issue' ? 'default' : 'outline'}
              className="min-h-11 text-base"
              disabled={Boolean(busy) || (b.key === 'clear' && clearing)}
              onClick={() => {
                if (b.key === 'clear') void clear()
                else setOpen(b.key)
              }}
            >
              {b.label}
            </Button>
          ))}
        </div>
      ) : null}
      {open === 'edit' ? (
        <ActionForm
          action={a}
          canSend={can.issue}
          onClose={close}
          onSaved={(next, said) => {
            done(next, said)
          }}
        />
      ) : open === 'issue' ? (
        <IssueDialog {...props} />
      ) : open === 'discard' ? (
        <DiscardDialog {...props} />
      ) : open === 'decideDue' ? (
        <DecideDialog {...props} />
      ) : open === 'owner' ? (
        <OwnerDialog {...props} />
      ) : open === 'colour' ? (
        <ColourDialog {...props} />
      ) : open === 'cancel' ? (
        <CancelDialog {...props} />
      ) : open === 'reopen' ? (
        <ReopenDialog {...props} />
      ) : open === 'hold' ? (
        <HoldDialog {...props} />
      ) : open === 'behalf' ? (
        <BehalfDialog {...props} />
      ) : null}
    </section>
  )
}

interface DialogProps {
  action: ActionResponse
  onClose: () => void
  onDone: OfficerDone
  onStale: () => void
}

function ReasonField({
  label,
  value,
  onChange,
  error,
  required = true,
  hint,
  name = 'reason',
}: {
  label: string
  value: string
  onChange: (v: string) => void
  error?: string | undefined
  required?: boolean
  hint?: string
  name?: string
}) {
  const intl = useIntl()
  return (
    <Field
      label={label}
      required={required}
      optionalLabel={intl.formatMessage(ao.optional)}
      error={error}
      {...(hint ? { hint } : {})}
    >
      {(p) => (
        <Textarea
          {...p}
          data-field={name}
          rows={3}
          maxLength={REASON_MAX}
          value={value}
          className="text-base"
          onChange={(e) => {
            onChange(e.target.value)
          }}
        />
      )}
    </Field>
  )
}

function DateField({
  label,
  value,
  onChange,
  error,
  hint,
  required = true,
  name,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  error?: string | undefined
  hint?: string
  required?: boolean
  name: string
}) {
  const intl = useIntl()
  return (
    <Field
      label={label}
      required={required}
      optionalLabel={intl.formatMessage(ao.optional)}
      error={error}
      {...(hint ? { hint } : {})}
    >
      {(p) => (
        <Input
          {...p}
          data-field={name}
          type="date"
          min={todayIso()}
          className="min-h-11 w-fit text-base"
          value={value}
          onChange={(e) => {
            onChange(e.target.value)
          }}
        />
      )}
    </Field>
  )
}

/** Send a draft to its owner: it gets its reference; the owner is told by email, without its title. */
function IssueDialog({ action: a, onClose, onDone, onStale }: DialogProps) {
  const s = useSend(onStale)
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    void s.send(['owner', 'dueDate', 'contributors'], async (headers) => {
      const next = await issueAction({
        path: { actionId: a.id },
        headers: { ...headers, ...ifMatch(a) },
        throwOnError: true,
      })
      onDone(next.data, s.intl.formatMessage(ao.issued, { reference: next.data.reference ?? '' }))
    })
  }
  return (
    <OfficerFrame
      title={s.intl.formatMessage(ao.issueTitle)}
      description={s.intl.formatMessage(ao.issueExplain, { owner: ownerWords(s.intl, a.owner) ?? '' })}
      busy={s.busy}
      submit={s.intl.formatMessage(ao.issueSend)}
      errors={{}}
      refusal={s.refusal ?? (Object.values(s.errors).join(' ') || undefined)}
      onClose={onClose}
      onSubmit={submit}
    >
      <p className="text-base font-medium">{a.title}</p>
    </OfficerFrame>
  )
}

/** Discard a draft: kept, never numbered. */
function DiscardDialog({ action: a, onClose, onDone, onStale }: DialogProps) {
  const s = useSend(onStale)
  const [reason, setReason] = useState('')
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    void s.send(['reason'], async (headers) => {
      const next = await discardAction({
        path: { actionId: a.id },
        headers: { ...headers, ...ifMatch(a) },
        body: reason.trim() ? { reason: reason.trim() } : {},
        throwOnError: true,
      })
      onDone(next.data, s.intl.formatMessage(ao.discarded))
    })
  }
  return (
    <OfficerFrame
      destructive
      title={s.intl.formatMessage(ao.discardTitle)}
      description={s.intl.formatMessage(ao.discardExplain)}
      busy={s.busy}
      submit={s.intl.formatMessage(ao.discard)}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
    >
      <ReasonField
        label={s.intl.formatMessage(ao.discardWhy)}
        value={reason}
        onChange={setReason}
        error={s.errors.reason}
        required={false}
      />
    </OfficerFrame>
  )
}

/** Answer the owner's request for a new date (design §1.7): agree or decline, with a note for the owner. */
function DecideDialog({ action: a, onClose, onDone, onStale }: DialogProps) {
  const s = useSend(onStale)
  const [decision, setDecision] = useState<'AGREE' | 'DECLINE'>('AGREE')
  const [note, setNote] = useState('')
  const request = a.pendingRequest
  if (!request) return null
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    void s.send(['decision', 'note'], async (headers) => {
      const next = await decideActionDue({
        path: { actionId: a.id, requestId: request.id },
        headers: { ...headers, ...ifMatch(a) },
        body: { decision, ...(note.trim() ? { note: note.trim() } : {}) },
        throwOnError: true,
      })
      onDone(next.data, s.intl.formatMessage(decision === 'AGREE' ? ao.agreed : ao.declined))
    })
  }
  return (
    <OfficerFrame
      title={s.intl.formatMessage(ao.decideTitle)}
      busy={s.busy}
      submit={s.intl.formatMessage(ao.decideSend)}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
    >
      <p className="text-base">
        {s.intl.formatMessage(ao.decideAsked, {
          name: request.requestedBy.name,
          date: day(s.intl, request.newDate),
          current: a.dueDate ? day(s.intl, a.dueDate) : '',
          reason: request.reason,
        })}
      </p>
      <fieldset data-field="decision" className="flex flex-col gap-2">
        <legend className="sr-only">{s.intl.formatMessage(ao.decideTitle)}</legend>
        <Choice
          name="due-decision"
          checked={decision === 'AGREE'}
          onSelect={() => {
            setDecision('AGREE')
          }}
        >
          {s.intl.formatMessage(ao.decideAgree)}
        </Choice>
        <Choice
          name="due-decision"
          checked={decision === 'DECLINE'}
          onSelect={() => {
            setDecision('DECLINE')
          }}
        >
          {s.intl.formatMessage(ao.decideDecline)}
        </Choice>
      </fieldset>
      <ReasonField
        label={s.intl.formatMessage(ao.decideNote)}
        name="note"
        value={note}
        onChange={setNote}
        error={s.errors.note}
        required={false}
      />
    </OfficerFrame>
  )
}

/** Change the owner (design §1.3): a person or a position, with a reason; both are told. */
function OwnerDialog({ action: a, onClose, onDone, onStale }: DialogProps) {
  const s = useSend(onStale)
  const [owner, setOwner] = useState<OwnerChoice>({ kind: ownerChoice(a.owner).kind })
  const [reason, setReason] = useState('')
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    const missing: Record<string, string> = {}
    if (!owner.picked) missing.owner = s.intl.formatMessage(ao.fieldRequiredOwner)
    if (!reason.trim()) missing.reason = s.intl.formatMessage(ao.fieldRequiredReason)
    if (Object.keys(missing).length) {
      s.setErrors(missing)
      return
    }
    void s.send(['owner', 'reason'], async (headers) => {
      const next = await changeActionOwner({
        path: { actionId: a.id },
        headers: { ...headers, ...ifMatch(a) },
        body: { owner: ownerInput(owner) ?? null, reason: reason.trim() },
        throwOnError: true,
      })
      onDone(next.data, s.intl.formatMessage(ao.ownerChanged))
    })
  }
  return (
    <OfficerFrame
      title={s.intl.formatMessage(ao.ownerTitle)}
      description={s.intl.formatMessage(ao.ownerExplain)}
      busy={s.busy}
      submit={s.intl.formatMessage(ao.ownerSend)}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
    >
      <p className="text-sm">{s.intl.formatMessage(ao.ownerNow, { owner: ownerWords(s.intl, a.owner) ?? '' })}</p>
      <OwnerPicker value={owner} onChange={setOwner} error={s.errors.owner} name="newOwner" />
      <ReasonField
        label={s.intl.formatMessage(ao.reason)}
        value={reason}
        onChange={setReason}
        error={s.errors.reason}
      />
    </OfficerFrame>
  )
}

/** Set the colour by hand (design §1.5): red, amber or green, why, and until when (else until the next update). */
function ColourDialog({ action: a, onClose, onDone, onStale }: DialogProps) {
  const s = useSend(onStale)
  const [rag, setRag] = useState<'RED' | 'AMBER' | 'GREEN' | undefined>(a.rag.override?.rag as 'RED' | undefined)
  const [reason, setReason] = useState('')
  const [until, setUntil] = useState('')
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    const missing: Record<string, string> = {}
    if (!rag) missing.rag = s.intl.formatMessage(ao.fieldRequiredRag)
    if (!reason.trim()) missing.reason = s.intl.formatMessage(ao.fieldRequiredReason)
    if (Object.keys(missing).length) {
      s.setErrors(missing)
      return
    }
    void s.send(['rag', 'reason', 'until'], async (headers) => {
      const next = await overrideActionRag({
        path: { actionId: a.id },
        headers: { ...headers, ...ifMatch(a) },
        body: { rag: rag ?? 'RED', reason: reason.trim(), ...(until ? { until } : {}) },
        throwOnError: true,
      })
      onDone(next.data, s.intl.formatMessage(ao.colourSet))
    })
  }
  return (
    <OfficerFrame
      title={s.intl.formatMessage(ao.colourTitle)}
      description={s.intl.formatMessage(ao.colourExplain)}
      busy={s.busy}
      submit={s.intl.formatMessage(ao.colourSend)}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
    >
      <fieldset data-field="rag" className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">{s.intl.formatMessage(ao.colourChoice)}</legend>
        {(['RED', 'AMBER', 'GREEN'] as const).map((c) => (
          <Choice
            key={c}
            name="rag"
            checked={rag === c}
            onSelect={() => {
              setRag(c)
            }}
          >
            {s.intl.formatMessage(ao[`colour${c}`])}
          </Choice>
        ))}
        {s.errors.rag ? <p className="text-xs font-medium text-destructive">{s.errors.rag}</p> : null}
      </fieldset>
      <ReasonField
        label={s.intl.formatMessage(ao.reason)}
        value={reason}
        onChange={setReason}
        error={s.errors.reason}
      />
      <DateField
        name="until"
        label={s.intl.formatMessage(ao.colourUntil)}
        hint={s.intl.formatMessage(ao.colourUntilHint)}
        value={until}
        onChange={setUntil}
        error={s.errors.until}
        required={false}
      />
    </OfficerFrame>
  )
}

const KINDS: readonly Exclude<CancelKind, 'DISCARDED'>[] = [
  'NO_LONGER_NEEDED',
  'SUPERSEDED',
  'DUPLICATE',
  'RISK_ACCEPTED',
]

/**
 * Cancel (design §1.10, flow AN): no longer needed, replaced by another action (which one), a duplicate, or the risk
 * accepted by the chair. The chair accepts the risk themself (`acceptRisk`); a secretary records it for the chair, with
 * how the chair told them.
 */
function CancelDialog({ action: a, onClose, onDone, onStale }: DialogProps) {
  const s = useSend(onStale)
  const [kind, setKind] = useState<(typeof KINDS)[number]>()
  const [reason, setReason] = useState('')
  const [how, setHow] = useState<How>()
  const [replacedBy, setReplacedBy] = useState('')
  const others = useQuery({
    ...getForumActionsOptions({
      path: { forumId: a.forumId },
      query: { status: ['OPEN', 'IN_PROGRESS', 'DONE_PENDING', 'CLOSED'], sort: 'REFERENCE', limit: 100 },
    }),
    enabled: kind === 'SUPERSEDED',
  })
  const forChair = kind === 'RISK_ACCEPTED' && !a.can.acceptRisk
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    const missing: Record<string, string> = {}
    if (!kind) missing.kind = s.intl.formatMessage(ao.fieldRequiredKind)
    if (!reason.trim()) missing.reason = s.intl.formatMessage(ao.fieldRequiredReason)
    if (forChair && !how) missing.how = s.intl.formatMessage(ao.fieldRequiredHowChair)
    if (kind === 'SUPERSEDED' && !replacedBy) missing.supersededBy = s.intl.formatMessage(ao.fieldSupersededBy)
    if (Object.keys(missing).length) {
      s.setErrors(missing)
      return
    }
    void s.send(['kind', 'reason', 'how', 'supersededBy'], async (headers) => {
      const next = await cancelAction({
        path: { actionId: a.id },
        headers: { ...headers, ...ifMatch(a) },
        body: {
          kind: kind ?? 'NO_LONGER_NEEDED',
          reason: reason.trim(),
          ...(forChair && how ? { how } : {}),
          ...(kind === 'SUPERSEDED' ? { supersededBy: replacedBy } : {}),
        },
        throwOnError: true,
      })
      onDone(next.data, s.intl.formatMessage(ao.cancelled))
    })
  }
  const choices = (others.data?.items ?? []).filter((x) => x.id !== a.id && x.reference)
  return (
    <OfficerFrame
      destructive
      title={s.intl.formatMessage(ao.cancelTitle)}
      description={s.intl.formatMessage(ao.cancelExplain)}
      busy={s.busy}
      submit={s.intl.formatMessage(ao.cancelSend)}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
    >
      <fieldset data-field="kind" className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">{s.intl.formatMessage(ao.cancelWhy)}</legend>
        {KINDS.map((k) => (
          <Choice
            key={k}
            name="cancel-kind"
            checked={kind === k}
            onSelect={() => {
              setKind(k)
            }}
          >
            {s.intl.formatMessage(ao[`cancel${k}`])}
          </Choice>
        ))}
        {s.errors.kind ? <p className="text-xs font-medium text-destructive">{s.errors.kind}</p> : null}
      </fieldset>
      {kind === 'SUPERSEDED' ? (
        <Field label={s.intl.formatMessage(ao.cancelReplacedBy)} required error={s.errors.supersededBy}>
          {(p) => (
            <Select
              {...p}
              data-field="supersededBy"
              className="min-h-11"
              value={replacedBy}
              onChange={(e) => {
                setReplacedBy(e.target.value)
              }}
            >
              <option value="">{s.intl.formatMessage(ao.cancelChoose)}</option>
              {choices.map((x) => (
                <option key={x.id} value={x.id}>
                  {s.intl.formatMessage(ao.cancelOption, { reference: x.reference ?? '', title: x.title })}
                </option>
              ))}
            </Select>
          )}
        </Field>
      ) : null}
      {forChair ? (
        <fieldset data-field="how" className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium">{s.intl.formatMessage(ao.cancelHowChair)}</legend>
          <p className="text-sm text-muted-foreground">{s.intl.formatMessage(ao.cancelRiskForChair)}</p>
          {HOWS.map((h) => (
            <Choice
              key={h}
              name="cancel-how"
              checked={how === h}
              onSelect={() => {
                setHow(h)
              }}
            >
              {s.intl.formatMessage(ao[`behalfHow${h}`])}
            </Choice>
          ))}
          {s.errors.how ? <p className="text-xs font-medium text-destructive">{s.errors.how}</p> : null}
        </fieldset>
      ) : null}
      <ReasonField
        label={s.intl.formatMessage(ao.cancelReason)}
        value={reason}
        onChange={setReason}
        error={s.errors.reason}
      />
    </OfficerFrame>
  )
}

/** Reopen within 90 days (design §1.10): why, and the new due date. */
function ReopenDialog({ action: a, onClose, onDone, onStale }: DialogProps) {
  const s = useSend(onStale)
  const [reason, setReason] = useState('')
  const [dueDate, setDueDate] = useState('')
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    const missing: Record<string, string> = {}
    if (!reason.trim()) missing.reason = s.intl.formatMessage(ao.fieldRequiredReason)
    if (!dueDate) missing.dueDate = s.intl.formatMessage(ao.fieldRequiredDue)
    if (Object.keys(missing).length) {
      s.setErrors(missing)
      return
    }
    void s.send(['reason', 'dueDate'], async (headers) => {
      const next = await reopenAction({
        path: { actionId: a.id },
        headers: { ...headers, ...ifMatch(a) },
        body: { reason: reason.trim(), dueDate },
        throwOnError: true,
      })
      onDone(next.data, s.intl.formatMessage(ao.reopened))
    })
  }
  return (
    <OfficerFrame
      title={s.intl.formatMessage(ao.reopenTitle)}
      description={s.intl.formatMessage(ao.reopenExplain)}
      busy={s.busy}
      submit={s.intl.formatMessage(ao.reopenSend)}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
    >
      <ReasonField
        label={s.intl.formatMessage(ao.reopenWhy)}
        value={reason}
        onChange={setReason}
        error={s.errors.reason}
      />
      <DateField
        name="dueDate"
        label={s.intl.formatMessage(ao.reopenDue)}
        value={dueDate}
        onChange={setDueDate}
        error={s.errors.dueDate}
      />
    </OfficerFrame>
  )
}

/** Keep it out of the next meeting's action taken report (design §1.12 held back), with why. */
function HoldDialog({ action: a, onClose, onDone, onStale }: DialogProps) {
  const s = useSend(onStale)
  const [reason, setReason] = useState('')
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    if (!reason.trim()) {
      s.setErrors({ reason: s.intl.formatMessage(ao.fieldRequiredReason) })
      return
    }
    void s.send(['reason'], async (headers) => {
      const held = await holdActionFromReport({
        path: { actionId: a.id },
        headers,
        body: { reason: reason.trim() },
        throwOnError: true,
      })
      const meeting = held.data.meetingLabel ?? s.intl.formatDate(held.data.meetingStartsAt, { dateStyle: 'long' })
      onDone(undefined, s.intl.formatMessage(ao.held, { meeting }))
    })
  }
  return (
    <OfficerFrame
      title={s.intl.formatMessage(ao.holdTitle)}
      description={s.intl.formatMessage(ao.holdExplain)}
      busy={s.busy}
      submit={s.intl.formatMessage(ao.holdSend)}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
    >
      <ReasonField
        label={s.intl.formatMessage(ao.holdWhy)}
        value={reason}
        onChange={setReason}
        error={s.errors.reason}
      />
    </OfficerFrame>
  )
}

/** An update the owner gave by phone, email, in person or at a meeting (principle 3), recorded for them. */
function BehalfDialog({ action: a, onClose, onDone, onStale }: DialogProps) {
  const s = useSend(onStale)
  const [text, setText] = useState('')
  const [how, setHow] = useState<How>()
  const [confidence, setConfidence] = useState<Confidence | undefined>(a.confidence ?? undefined)
  const owner = ownerWords(s.intl, a.owner) ?? ''
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    const missing: Record<string, string> = {}
    if (!text.trim()) missing.text = s.intl.formatMessage(ao.fieldRequiredReason)
    if (!how) missing.how = s.intl.formatMessage(ao.fieldRequiredHow)
    if (Object.keys(missing).length) {
      s.setErrors(missing)
      return
    }
    void s.send(['text', 'how', 'confidence', 'onBehalfOf'], async (headers) => {
      const next = await postActionUpdate({
        path: { actionId: a.id },
        headers: { ...headers, ...ifMatch(a) },
        body: {
          text: text.trim(),
          onBehalfOf: ownerUserId(a) ?? null,
          ...(how ? { how } : {}),
          ...(confidence ? { confidence } : {}),
        },
        throwOnError: true,
      })
      onDone(next.data, s.intl.formatMessage(ao.behalfPosted))
    })
  }
  return (
    <OfficerFrame
      title={s.intl.formatMessage(ao.behalfTitle, { owner })}
      description={s.intl.formatMessage(ao.behalfExplain)}
      busy={s.busy}
      submit={s.intl.formatMessage(ao.behalfSend)}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
    >
      <Field label={s.intl.formatMessage(ao.behalfText)} required error={s.errors.text}>
        {(p) => (
          <Textarea
            {...p}
            data-field="text"
            rows={5}
            maxLength={TEXT_MAX}
            value={text}
            className="text-base"
            onChange={(e) => {
              setText(e.target.value)
            }}
          />
        )}
      </Field>
      <fieldset data-field="how" className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">{s.intl.formatMessage(ao.behalfHow)}</legend>
        {HOWS.map((h) => (
          <Choice
            key={h}
            name="behalf-how"
            checked={how === h}
            onSelect={() => {
              setHow(h)
            }}
          >
            {s.intl.formatMessage(ao[`behalfHow${h}`])}
          </Choice>
        ))}
        {s.errors.how ? <p className="text-xs font-medium text-destructive">{s.errors.how}</p> : null}
      </fieldset>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">
          {s.intl.formatMessage(ao.behalfConfidence)}
          <span className="ms-1 font-normal text-muted-foreground">{s.intl.formatMessage(ao.optional)}</span>
        </legend>
        {CONFIDENCES.map((c) => (
          <Choice
            key={c}
            name="behalf-confidence"
            checked={confidence === c}
            onSelect={() => {
              setConfidence(c)
            }}
          >
            {confidenceWord(s.intl, c)}
          </Choice>
        ))}
      </fieldset>
    </OfficerFrame>
  )
}
