import { useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { ruleOnDeclaration, withdrawDeclaration } from '@/api/generated/sdk.gen'
import type { DeclarationResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { dm, dmv } from './messages'
import { om, omv } from './officer-messages'
import { HOWS, officerRefusal, when, type How } from './officers'
import { RadioGroup } from './RecordSheet'
import { fieldCode } from './words'

const MAX_NOTE = 500
const MAX_REASON = 500

type Outcome = 'LEAVE' | 'TAKE_PART'

/** The rulings that change [state] (E04 §1.2): from a pending request either; otherwise the other way only. */
function outcomesFor(state: DeclarationResponse['state']): Outcome[] {
  if (state === 'AWAITING_RULING') return ['LEAVE', 'TAKE_PART']
  if (state === 'RECUSED') return ['TAKE_PART']
  if (state === 'TAKING_PART') return ['LEAVE']
  return []
}

const refreshing = (problem: unknown) =>
  isProblem(problem) && (problem.status === 409 || problem.status === 412 || problem.status === 404)

/**
 * The chair's decision on a declaration (flow T3). [onBehalf] false: the person who rules (the chair, or the vice-chair
 * when the chair declared or is recused) decides with an optional note — "They should leave the room" or "They may take
 * part". [onBehalf] true: a secretary records the decision the chair gave by email, by phone or in person, saying how.
 * The change carries the declaration's version as If-Match.
 */
export function RulingDialog({
  declaration: d,
  item,
  meetingDate,
  timeZone,
  onBehalf,
  ruler,
  chairDeclared,
  refresh,
  onDone,
  onClose,
}: {
  declaration: DeclarationResponse
  item: string
  meetingDate: string
  timeZone: string
  onBehalf: boolean
  /** Who rules, when known: named when a secretary records the decision. */
  ruler?: string | undefined
  /** The declarant is the chair: the vice-chair decides. */
  chairDeclared: boolean
  refresh: () => Promise<void>
  onDone: () => void
  onClose: () => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const outcomes = outcomesFor(d.state)
  const [outcome, setOutcome] = useState<Outcome | undefined>(outcomes.length === 1 ? outcomes[0] : undefined)
  const [how, setHow] = useState<How>()
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<{ outcome?: string; how?: string; note?: string }>({})
  const noteRef = useRef<HTMLTextAreaElement>(null)
  const name = d.user.name

  const send = async (chosen: Outcome | undefined): Promise<string | undefined> => {
    const found: typeof errors = {}
    if (!chosen) found.outcome = intl.formatMessage(om.decisionRequired)
    if (onBehalf && !how) found.how = intl.formatMessage(om.givenHowRequired)
    if (note.trim().length > MAX_NOTE) found.note = intl.formatMessage(om.rulingNoteLength)
    setErrors(found)
    if (found.note) noteRef.current?.focus()
    if (!chosen || Object.keys(found).length) return undefined
    try {
      await ruleOnDeclaration({
        path: { meetingId: d.meetingId, declarationId: d.id },
        headers: { 'If-Match': `"${String(d.version)}"` },
        body: {
          outcome: chosen,
          ...(note.trim() ? { note: note.trim() } : {}),
          ...(onBehalf ? { onBehalf: true, ...(how ? { how } : {}) } : {}),
        },
        throwOnError: true,
      })
      toast.show(intl.formatMessage(omv.ruledDone, { name, outcome: chosen }), 'success')
      await refresh()
      onDone()
      return undefined
    } catch (problem) {
      if (fieldCode(problem, 'note')) {
        setErrors({ note: intl.formatMessage(om.rulingNoteLength) })
        noteRef.current?.focus()
        return undefined
      }
      if (fieldCode(problem, 'how')) {
        setErrors({ how: intl.formatMessage(om.givenHowRequired) })
        return undefined
      }
      if (refreshing(problem)) await refresh()
      return officerRefusal(intl, problem)
    }
  }

  const facts = (
    <div className="flex flex-col gap-2 text-base">
      <p className="font-medium">{intl.formatMessage(omv.rulingWho, { name, item })}</p>
      <blockquote dir="auto" className="whitespace-pre-line border-s-4 ps-3 text-lg">
        {intl.formatMessage(dmv.nature, { nature: d.nature })}
      </blockquote>
      <p className="text-muted-foreground">
        {intl.formatMessage(omv.declaredOn, { date: when(intl, d.declaredAt, timeZone) })}
      </p>
      {d.state === 'AWAITING_RULING' ? <p>{intl.formatMessage(omv.asks, { name })}</p> : null}
      {chairDeclared ? <p>{intl.formatMessage(om.chairDeclared)}</p> : null}
      {onBehalf && ruler ? <p>{intl.formatMessage(omv.rulerIs, { name: ruler })}</p> : null}
    </div>
  )
  const noteField = (
    <Field
      label={intl.formatMessage(om.rulingNote)}
      optionalLabel={intl.formatMessage(om.optional)}
      error={errors.note}
    >
      {(p) => (
        <Textarea
          {...p}
          ref={noteRef}
          dir="auto"
          rows={2}
          className="field-sizing-content min-h-16 text-base"
          value={note}
          onChange={(e) => {
            setNote(e.target.value)
          }}
        />
      )}
    </Field>
  )
  const label = (o: Outcome) => intl.formatMessage(o === 'LEAVE' ? om.ruleLeave : om.ruleTakePart)

  if (onBehalf) {
    return (
      <FormDialog
        side
        large
        title={intl.formatMessage(omv.recordRulingTitle, { name, item })}
        description={intl.formatMessage(om.recordRulingExplain)}
        submitLabel={intl.formatMessage(om.recordSubmit)}
        cancelLabel={intl.formatMessage(dm.cancel)}
        onSubmit={() => send(outcome)}
        onClose={onClose}
      >
        {facts}
        <RadioGroup<Outcome>
          legend={intl.formatMessage(om.decisionLegend)}
          name="ruling-outcome"
          options={outcomes.map((o) => ({ value: o, label: label(o) }))}
          value={outcome}
          error={errors.outcome}
          onChange={setOutcome}
        />
        <RadioGroup<How>
          legend={intl.formatMessage(om.givenHow)}
          name="ruling-how"
          options={HOWS.map((h) => ({ value: h, label: intl.formatMessage(om[`how${h}`]) }))}
          value={how}
          error={errors.how}
          onChange={setHow}
        />
        {noteField}
      </FormDialog>
    )
  }

  // The person who rules: one button per decision, the one that keeps the papers withheld first.
  const [primary, secondary] = outcomes.length === 2 ? (['TAKE_PART', 'LEAVE'] as const) : [outcomes[0], undefined]
  return (
    <FormDialog
      side
      large
      title={intl.formatMessage(omv.rulingTitle, { date: meetingDate })}
      submitLabel={primary ? label(primary) : intl.formatMessage(dm.close)}
      cancelLabel={intl.formatMessage(dm.cancel)}
      {...(secondary ? { secondary: { label: label(secondary), onSubmit: () => send(secondary) } } : {})}
      onSubmit={() => send(primary)}
      onClose={onClose}
    >
      {facts}
      {noteField}
    </FormDialog>
  )
}

/**
 * An officer withdrawing someone's declaration (flow T1, the row's Withdraw): a reason is required; the declarant, the
 * secretaries and the chair are told, and it is in the minutes. Once the meeting is held only a secretary may, as a
 * correction of the record.
 */
export function WithdrawForDialog({
  declaration: d,
  item,
  refresh,
  onDone,
  onClose,
}: {
  declaration: DeclarationResponse
  item: string
  refresh: () => Promise<void>
  onDone: () => void
  onClose: () => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const reasonRef = useRef<HTMLTextAreaElement>(null)
  const name = d.user.name

  const refuse = (message: string) => {
    setError(message)
    reasonRef.current?.focus()
  }

  const submit = async (): Promise<string | undefined> => {
    const text = reason.trim()
    if (!text) {
      refuse(intl.formatMessage(dm.reasonRequired))
      return undefined
    }
    if (text.length > MAX_REASON) {
      refuse(intl.formatMessage(dm.reasonLength))
      return undefined
    }
    setError(undefined)
    try {
      await withdrawDeclaration({
        path: { meetingId: d.meetingId, declarationId: d.id },
        headers: { 'If-Match': `"${String(d.version)}"` },
        body: { reason: text },
        throwOnError: true,
      })
      toast.show(intl.formatMessage(omv.withdrawn, { name }), 'success')
      await refresh()
      onDone()
      return undefined
    } catch (problem) {
      const code = fieldCode(problem, 'reason')
      if (code) {
        refuse(intl.formatMessage(code === 'LENGTH' ? dm.reasonLength : dm.reasonRequired))
        return undefined
      }
      if (refreshing(problem)) await refresh()
      return officerRefusal(intl, problem)
    }
  }

  return (
    <FormDialog
      large
      destructive
      title={intl.formatMessage(omv.withdrawTitle, { name, item })}
      description={intl.formatMessage(omv.withdrawExplain, { name })}
      submitLabel={intl.formatMessage(dm.withdrawSubmit)}
      cancelLabel={intl.formatMessage(dm.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      <Field label={intl.formatMessage(om.withdrawReason)} required error={error}>
        {(p) => (
          <Textarea
            {...p}
            ref={reasonRef}
            dir="auto"
            rows={3}
            className="field-sizing-content min-h-20 text-base"
            value={reason}
            onChange={(e) => {
              setReason(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}
