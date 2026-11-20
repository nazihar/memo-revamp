import { useId, useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { declareInterest } from '@/api/generated/sdk.gen'
import type { DeclarationRequest } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Field } from '@/components/ui/field'
import { Input, Select, Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { newIdempotencyKey } from '@/lib/idempotency'
import { dm } from './messages'
import { om, omv } from './officer-messages'
import { HOWS, officerRefusal, type How } from './officers'
import { fieldCode } from './words'

const MAX_NATURE = 1_000
const MAX_NOTE = 200
const MAX_REASON = 500

export interface Choice {
  id: string
  name: string
}

type Errors = Partial<Record<'onBehalfOf' | 'item' | 'nature' | 'how' | 'howNote' | 'reason', string>>

/** Radio buttons in a fieldset, 44 px each, with the group's error said once (flows T2, T3). */
export function RadioGroup<T extends string>({
  legend,
  name,
  options,
  value,
  error,
  onChange,
}: {
  legend: string
  name: string
  options: { value: T; label: string }[]
  value: T | undefined
  error?: string | undefined
  onChange: (value: T) => void
}) {
  const errorId = useId()
  return (
    <fieldset aria-describedby={error ? errorId : undefined} className="flex flex-col gap-1">
      <legend className="mb-1 text-sm font-medium">{legend}</legend>
      <div className="flex flex-wrap gap-x-4">
        {options.map((o) => (
          <label key={o.value} className="flex min-h-11 cursor-pointer items-center gap-2 text-base">
            <input
              type="radio"
              name={name}
              value={o.value}
              className="size-5 shrink-0 accent-primary"
              checked={value === o.value}
              onChange={() => {
                onChange(o.value)
              }}
            />
            {o.label}
          </label>
        ))}
      </div>
      {error ? (
        <p id={errorId} className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
    </fieldset>
  )
}

/**
 * "Record a declaration of interest" (flow T2): a secretary or the chair records a declaration someone made to them —
 * who, on which item, their interest, how it was made (at the meeting, in person, by email or by phone) with a note, and
 * what they will do. During the meeting "At the meeting" is chosen already; once the meeting is held the sheet says it
 * corrects the record and asks why. The same request keeps its Idempotency-Key; a changed one gets a new key.
 */
export function RecordSheet({
  meetingId,
  status,
  people,
  items,
  refresh,
  onDone,
  onClose,
}: {
  meetingId: string
  status: 'DRAFT' | 'SCHEDULED' | 'IN_PROGRESS' | 'HELD' | 'MINUTES_APPROVED' | 'CLOSED' | 'CANCELLED'
  people: Choice[]
  items: Choice[]
  refresh: () => Promise<void>
  onDone: () => void
  onClose: () => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const held = status === 'HELD'
  const [person, setPerson] = useState('')
  const [item, setItem] = useState('')
  const [nature, setNature] = useState('')
  const [how, setHow] = useState<How | undefined>(status === 'IN_PROGRESS' || held ? 'AT_MEETING' : undefined)
  const [note, setNote] = useState('')
  const [intent, setIntent] = useState<'LEAVE' | 'ASK_CHAIR'>('LEAVE')
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<Errors>({})
  const sent = useRef<{ key: string; body: string } | null>(null)
  const refs = {
    onBehalfOf: useRef<HTMLSelectElement>(null),
    item: useRef<HTMLSelectElement>(null),
    nature: useRef<HTMLTextAreaElement>(null),
    howNote: useRef<HTMLInputElement>(null),
    reason: useRef<HTMLTextAreaElement>(null),
  }

  /** Shows [found] and puts focus on the first field refused. */
  const refuse = (found: Errors) => {
    setErrors(found)
    const first = (['onBehalfOf', 'item', 'nature', 'how', 'howNote', 'reason'] as const).find((k) => found[k])
    if (first === 'how') document.querySelector<HTMLInputElement>('input[name="record-how"]')?.focus()
    else if (first) refs[first].current?.focus()
  }

  const check = (): Errors => {
    const found: Errors = {}
    if (!person) found.onBehalfOf = intl.formatMessage(om.memberRequired)
    if (!item) found.item = intl.formatMessage(om.itemRequired)
    const text = nature.trim()
    if (!text) found.nature = intl.formatMessage(om.theirInterestRequired)
    else if (text.length > MAX_NATURE) found.nature = intl.formatMessage(dm.natureLength)
    if (!how) found.how = intl.formatMessage(om.howRequired)
    if (note.trim().length > MAX_NOTE) found.howNote = intl.formatMessage(om.noteLength)
    if (held && !reason.trim()) found.reason = intl.formatMessage(om.reasonRequired)
    else if (reason.trim().length > MAX_REASON) found.reason = intl.formatMessage(dm.reasonLength)
    return found
  }

  const fromServer = (problem: unknown): Errors => {
    const found: Errors = {}
    const natureCode = fieldCode(problem, 'nature')
    if (natureCode)
      found.nature = intl.formatMessage(natureCode === 'LENGTH' ? dm.natureLength : om.theirInterestRequired)
    if (fieldCode(problem, 'onBehalfOf')) found.onBehalfOf = intl.formatMessage(om.NOT_IN_AUDIENCE)
    if (fieldCode(problem, 'how')) found.how = intl.formatMessage(om.howRequired)
    if (fieldCode(problem, 'howNote')) found.howNote = intl.formatMessage(om.noteLength)
    const reasonCode = fieldCode(problem, 'reason')
    if (reasonCode) found.reason = intl.formatMessage(reasonCode === 'LENGTH' ? dm.reasonLength : om.reasonRequired)
    return found
  }

  const submit = async (): Promise<string | undefined> => {
    const found = check()
    if (Object.keys(found).length) {
      refuse(found)
      return undefined
    }
    setErrors({})
    const body: DeclarationRequest = {
      nature: nature.trim(),
      intent,
      onBehalfOf: person,
      ...(how ? { how } : {}),
      ...(note.trim() ? { howNote: note.trim() } : {}),
      ...(held ? { reason: reason.trim() } : {}),
    }
    const json = JSON.stringify({ item, ...body })
    if (sent.current?.body !== json) sent.current = { key: newIdempotencyKey(), body: json }
    try {
      await declareInterest({
        path: { meetingId, itemId: item },
        headers: { 'Idempotency-Key': sent.current.key },
        body,
        throwOnError: true,
      })
      const name = people.find((p) => p.id === person)?.name ?? ''
      toast.show(intl.formatMessage(omv.recorded, { name }), 'success')
      await refresh()
      onDone()
      return undefined
    } catch (problem) {
      const fields = fromServer(problem)
      if (Object.keys(fields).length) {
        refuse(fields)
        return undefined
      }
      if (isProblem(problem) && (problem.status === 409 || problem.status === 404)) await refresh()
      return officerRefusal(intl, problem)
    }
  }

  return (
    <FormDialog
      side
      large
      title={intl.formatMessage(om.recordTitle)}
      description={intl.formatMessage(held ? om.correctionExplain : om.recordExplain)}
      submitLabel={intl.formatMessage(om.recordSubmit)}
      cancelLabel={intl.formatMessage(dm.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      {held ? (
        <p
          role="note"
          className="rounded-md border border-status-warn-fg/40 bg-status-warn-bg p-3 font-medium text-status-warn-fg"
        >
          {intl.formatMessage(om.correctionTitle)}
        </p>
      ) : null}
      <Field label={intl.formatMessage(om.member)} required error={errors.onBehalfOf}>
        {(p) => (
          <Select
            {...p}
            ref={refs.onBehalfOf}
            className="min-h-11 text-base"
            value={person}
            onChange={(e) => {
              setPerson(e.target.value)
            }}
          >
            <option value="">{intl.formatMessage(om.chooseMember)}</option>
            {people.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field label={intl.formatMessage(om.item)} required error={errors.item}>
        {(p) => (
          <Select
            {...p}
            ref={refs.item}
            className="min-h-11 text-base"
            value={item}
            onChange={(e) => {
              setItem(e.target.value)
            }}
          >
            <option value="">{intl.formatMessage(om.chooseItem)}</option>
            {items.map((i) => (
              <option key={i.id} value={i.id}>
                {i.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field label={intl.formatMessage(om.theirInterest)} required error={errors.nature}>
        {(p) => (
          <Textarea
            {...p}
            ref={refs.nature}
            dir="auto"
            rows={3}
            className="field-sizing-content min-h-20 text-base"
            value={nature}
            onChange={(e) => {
              setNature(e.target.value)
            }}
          />
        )}
      </Field>
      <RadioGroup<How>
        legend={intl.formatMessage(om.how)}
        name="record-how"
        options={HOWS.map((h) => ({ value: h, label: intl.formatMessage(om[`how${h}`]) }))}
        value={how}
        error={errors.how}
        onChange={setHow}
      />
      <Field
        label={intl.formatMessage(om.note)}
        optionalLabel={intl.formatMessage(om.optional)}
        hint={intl.formatMessage(om.noteHint)}
        error={errors.howNote}
      >
        {(p) => (
          <Input
            {...p}
            ref={refs.howNote}
            dir="auto"
            className="min-h-11 text-base"
            value={note}
            onChange={(e) => {
              setNote(e.target.value)
            }}
          />
        )}
      </Field>
      <RadioGroup<'LEAVE' | 'ASK_CHAIR'>
        legend={intl.formatMessage(om.theyWill)}
        name="record-intent"
        options={[
          { value: 'LEAVE', label: intl.formatMessage(om.theyLeave) },
          { value: 'ASK_CHAIR', label: intl.formatMessage(om.theyAsk) },
        ]}
        value={intent}
        onChange={setIntent}
      />
      {held ? (
        <Field label={intl.formatMessage(om.reason)} required error={errors.reason}>
          {(p) => (
            <Textarea
              {...p}
              ref={refs.reason}
              dir="auto"
              rows={2}
              className="field-sizing-content min-h-16 text-base"
              value={reason}
              onChange={(e) => {
                setReason(e.target.value)
              }}
            />
          )}
        </Field>
      ) : null}
    </FormDialog>
  )
}
