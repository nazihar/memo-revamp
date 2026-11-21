import { useRef, useState, type Ref } from 'react'
import { useIntl } from 'react-intl'
import type { MeetingResponse } from '@/api/generated/types.gen'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select, Textarea } from '@/components/ui/form-controls'
import { FormDialog } from '@/features/forums/FormDialog'
import { instantOf, joinLocal, splitLocal } from '@/features/meetings/time'
import { am, amv } from './messages'
import type { Entry } from './writes'
import {
  attendanceRefusal,
  eventWords,
  itemTitle,
  localTime,
  modesFor,
  type AgendaItem,
  type AttendanceEvent,
  type Kind,
  type Mode,
  type Person,
} from './words'

/** The longest reason the server takes for a correction or a void (design E05 §1.6). */
const MAX_REASON = 500

/** The kinds that take a time and an item. */
const TIMED: Kind[] = ['ARRIVED', 'LEFT', 'MODE_CHANGED', 'STEPPED_OUT', 'STEPPED_BACK']

/** What a correction can record as the right entry. */
const RIGHT_KINDS: Kind[] = ['ARRIVED', 'LEFT', 'APOLOGIES', 'ABSENT']

const KIND_LABEL = {
  ARRIVED: am.kindArrived,
  LEFT: am.kindLeft,
  APOLOGIES: am.kindApologies,
  ABSENT: am.kindAbsent,
  MODE_CHANGED: am.kindModeChanged,
  STEPPED_OUT: am.kindSteppedOut,
  STEPPED_BACK: am.kindSteppedBack,
  VOIDED: am.eventVoided,
} as const

const MODE_LABEL = { IN_PERSON: am.modeInPersonChoice, REMOTE: am.modeOnlineChoice } as const

export interface Draft {
  kind: Kind
  mode: Mode | undefined
  /** "10:20" on the meeting's clock. */
  time: string
  /** '' : the item in progress at that time (the server's default). */
  itemId: string
}

/** The entry as the server takes it, at [draft]'s time on the meeting's date and clock. */
function entryOf(m: MeetingResponse, draft: Draft): Entry | undefined {
  const timed = TIMED.includes(draft.kind)
  const at = timed ? instantOf(joinLocal(splitLocal(m.localStartsAt).date, draft.time), m.timeZone) : undefined
  if (timed && !at) return undefined
  return {
    kind: draft.kind,
    ...(draft.kind === 'ARRIVED' || draft.kind === 'MODE_CHANGED' ? { mode: draft.mode } : {}),
    ...(at ? { at } : {}),
    ...(timed && draft.itemId ? { itemId: draft.itemId } : {}),
  }
}

/** Now on the meeting's clock, or its start once it is over (a correction is mostly about the meeting's hours). */
function defaultTime(m: MeetingResponse): string {
  return m.status === 'IN_PROGRESS' ? localTime(Date.now(), m.timeZone) : splitLocal(m.localStartsAt).time
}

/** The kind, the way of attending, the time and the item of one entry. */
function EntryFields({
  meeting: m,
  kinds,
  items,
  draft,
  onChange,
  timeError,
  timeRef,
}: {
  meeting: MeetingResponse
  kinds: Kind[]
  items: readonly AgendaItem[]
  draft: Draft
  onChange: (draft: Draft) => void
  timeError: string | undefined
  timeRef: Ref<HTMLInputElement>
}) {
  const intl = useIntl()
  const set = (next: Partial<Draft>) => {
    onChange({ ...draft, ...next })
  }
  const withMode = draft.kind === 'ARRIVED' || draft.kind === 'MODE_CHANGED'
  const modes = modesFor(m.mode)
  return (
    <>
      {kinds.length > 1 ? (
        <Choice
          legend={intl.formatMessage(am.entryKind)}
          name="entry-kind"
          options={kinds.map((k) => ({ value: k, label: intl.formatMessage(KIND_LABEL[k]) }))}
          value={draft.kind}
          onChange={(kind) => {
            set({ kind, mode: draft.mode ?? modes[0] })
          }}
        />
      ) : null}
      {withMode ? (
        <Choice
          legend={intl.formatMessage(am.entryMode)}
          name="entry-mode"
          options={modes.map((mode) => ({ value: mode, label: intl.formatMessage(MODE_LABEL[mode]) }))}
          value={draft.mode}
          onChange={(mode) => {
            set({ mode })
          }}
        />
      ) : null}
      {TIMED.includes(draft.kind) ? (
        <>
          <Field label={intl.formatMessage(am.entryTime)} hint={intl.formatMessage(am.entryTimeHint)} error={timeError}>
            {(p) => (
              <Input
                {...p}
                ref={timeRef}
                type="time"
                className="min-h-11 w-40"
                value={draft.time}
                onChange={(e) => {
                  set({ time: e.target.value })
                }}
              />
            )}
          </Field>
          {items.length ? (
            <Field label={intl.formatMessage(am.entryItem)}>
              {(p) => (
                <Select
                  {...p}
                  className="min-h-11"
                  value={draft.itemId}
                  onChange={(e) => {
                    set({ itemId: e.target.value })
                  }}
                >
                  <option value="">{intl.formatMessage(am.entryItemThen)}</option>
                  {items.map((i) => (
                    <option key={i.id} value={i.id}>
                      {itemTitle(intl, i)}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          ) : null}
        </>
      ) : null}
    </>
  )
}

/**
 * An entry at another time (flow X1's "at another time…"), or, once the meeting is held, a correction with its reason
 * (flow X2): the arrival, departure, apologies or absence missed in the room.
 */
export function EntryDialog({
  meeting: m,
  person,
  kinds,
  items,
  correcting,
  record,
  onDone,
  onClose,
}: {
  meeting: MeetingResponse
  person: Person
  kinds: Kind[]
  items: readonly AgendaItem[]
  correcting: boolean
  record: (person: Person, entry: Entry) => Promise<unknown>
  onDone: () => void
  onClose: () => void
}) {
  const intl = useIntl()
  const first = kinds[0] ?? 'ARRIVED'
  const [draft, setDraft] = useState<Draft>({
    kind: first,
    mode: modesFor(m.mode)[0],
    time: defaultTime(m),
    itemId: '',
  })
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<{ time?: string; reason?: string }>({})
  const timeRef = useRef<HTMLInputElement>(null)
  const reasonRef = useRef<HTMLTextAreaElement>(null)

  const submit = async (): Promise<string | undefined> => {
    const entry = entryOf(m, draft)
    const text = reason.trim()
    const missing = {
      ...(entry ? {} : { time: intl.formatMessage(am.entryTimeRequired) }),
      ...(correcting && !text ? { reason: intl.formatMessage(am.REASON_REQUIRED) } : {}),
      ...(text.length > MAX_REASON ? { reason: intl.formatMessage(am.LENGTH) } : {}),
    }
    setErrors(missing)
    if (missing.time) timeRef.current?.focus()
    else if (missing.reason) reasonRef.current?.focus()
    if (!entry || missing.time || missing.reason) return undefined
    try {
      await record(person, { ...entry, ...(text ? { reason: text } : {}) })
      onDone()
      onClose()
      return undefined
    } catch (problem) {
      return attendanceRefusal(intl, problem, person.name)
    }
  }

  return (
    <FormDialog
      side
      large
      title={intl.formatMessage(correcting ? amv.correctionTitle : amv.laterTitle, { name: person.name })}
      description={intl.formatMessage(correcting ? am.correctionHint : am.laterHint)}
      submitLabel={intl.formatMessage(am.entrySubmit)}
      cancelLabel={intl.formatMessage(am.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      <EntryFields
        meeting={m}
        kinds={kinds}
        items={items}
        draft={draft}
        onChange={setDraft}
        timeError={errors.time}
        timeRef={timeRef}
      />
      {correcting ? (
        <Field label={intl.formatMessage(am.correctionReason)} error={errors.reason} required>
          {(p) => (
            <Textarea
              {...p}
              ref={reasonRef}
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

/**
 * "Correct an entry for R. Das" (flow X2): which entry is wrong, what is wrong (required), and optionally the right entry,
 * recorded at once. The wrong entry is voided (`…/events/{eid}/void` with the person's row version as `If-Match`) and
 * stays in the history, struck through, with who corrected it, when and why; the right one is then recorded with the
 * version the void answered.
 */
export function CorrectDialog({
  meeting: m,
  person,
  events,
  items,
  correcting,
  voidEntry,
  record,
  onDone,
  onClose,
}: {
  meeting: MeetingResponse
  person: Person
  events: AttendanceEvent[]
  items: readonly AgendaItem[]
  correcting: boolean
  voidEntry: (person: Person, eventId: string, reason: string) => Promise<{ person: Person }>
  record: (person: Person, entry: Entry, version?: number | null) => Promise<unknown>
  onDone: () => void
  onClose: () => void
}) {
  const intl = useIntl()
  const ctx = { items, timeZone: m.timeZone }
  const [eventId, setEventId] = useState(events[0]?.id ?? '')
  const chosen = events.find((e) => e.id === eventId)
  const [reason, setReason] = useState('')
  const [again, setAgain] = useState(false)
  const draftOf = (e: AttendanceEvent | undefined): Draft => ({
    kind: e && RIGHT_KINDS.includes(e.kind) ? e.kind : 'ARRIVED',
    mode: e?.mode ?? modesFor(m.mode)[0],
    time: e ? localTime(e.at, m.timeZone) : defaultTime(m),
    itemId: e?.itemId ?? '',
  })
  const [draft, setDraft] = useState<Draft>(() => draftOf(events[0]))
  const [errors, setErrors] = useState<{ reason?: string; time?: string }>({})
  // Once the wrong entry is voided, a retry records only the right one, at the version the void answered.
  const [voided, setVoided] = useState<{ eventId: string; version: number | null | undefined }>()
  const reasonRef = useRef<HTMLTextAreaElement>(null)
  const timeRef = useRef<HTMLInputElement>(null)

  const submit = async (): Promise<string | undefined> => {
    const text = reason.trim()
    const entry = again ? entryOf(m, draft) : undefined
    const missing = {
      ...(!text ? { reason: intl.formatMessage(am.REASON_REQUIRED) } : {}),
      ...(text.length > MAX_REASON ? { reason: intl.formatMessage(am.LENGTH) } : {}),
      ...(again && !entry ? { time: intl.formatMessage(am.entryTimeRequired) } : {}),
    }
    setErrors(missing)
    if (missing.reason) reasonRef.current?.focus()
    else if (missing.time) timeRef.current?.focus()
    if (missing.reason || missing.time || !chosen) return undefined
    let version = voided?.eventId === chosen.id ? voided.version : undefined
    if (voided?.eventId !== chosen.id) {
      try {
        version = (await voidEntry(person, chosen.id, text)).person.version
        setVoided({ eventId: chosen.id, version })
      } catch (problem) {
        return attendanceRefusal(intl, problem, person.name)
      }
    }
    if (entry) {
      try {
        await record(person, { ...entry, ...(correcting ? { reason: text } : {}) }, version)
      } catch (problem) {
        return intl.formatMessage(amv.voidedNotRecorded, { problem: attendanceRefusal(intl, problem, person.name) })
      }
    }
    onDone()
    onClose()
    return undefined
  }

  return (
    <FormDialog
      side
      large
      title={intl.formatMessage(amv.correctTitle, { name: person.name })}
      description={intl.formatMessage(am.correctHint)}
      submitLabel={intl.formatMessage(am.correctSubmit)}
      cancelLabel={intl.formatMessage(am.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      <Field label={intl.formatMessage(am.correctEntry)}>
        {(p) => (
          <Select
            {...p}
            className="min-h-11"
            value={eventId}
            onChange={(e) => {
              setEventId(e.target.value)
              setDraft(draftOf(events.find((x) => x.id === e.target.value)))
            }}
          >
            {events.map((e) => (
              <option key={e.id} value={e.id}>
                {eventWords(intl, e, ctx)}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field
        label={intl.formatMessage(am.correctWhat)}
        hint={intl.formatMessage(amv.correctWhatHint, { name: person.name })}
        error={errors.reason}
        required
      >
        {(p) => (
          <Textarea
            {...p}
            ref={reasonRef}
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
      <label className="flex min-h-11 cursor-pointer items-center gap-3 text-base">
        <Checkbox
          className="size-5"
          checked={again}
          onChange={(e) => {
            setAgain(e.target.checked)
          }}
        />
        {intl.formatMessage(am.correctAgain)}
      </label>
      {again ? (
        <EntryFields
          meeting={m}
          kinds={RIGHT_KINDS}
          items={items}
          draft={draft}
          onChange={setDraft}
          timeError={errors.time}
          timeRef={timeRef}
        />
      ) : null}
    </FormDialog>
  )
}

/** A radio group with a legend, 44 px per choice. */
function Choice<T extends string>({
  legend,
  name,
  options,
  value,
  onChange,
}: {
  legend: string
  name: string
  options: { value: T; label: string }[]
  value: T | undefined
  onChange: (value: T) => void
}) {
  return (
    <fieldset className="flex flex-col gap-1">
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
    </fieldset>
  )
}
