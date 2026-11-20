import { useQueryClient } from '@tanstack/react-query'
import { useRef, useState, type Ref } from 'react'
import { useIntl } from 'react-intl'
import { recordMeetingRsvp } from '@/api/generated/sdk.gen'
import type { MeetingResponse, RecordRsvpRequest, ReplyResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Field } from '@/components/ui/field'
import { Checkbox, Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { rm, rmv } from './messages'
import { answerRefusal, ifMatchVersion, offeredFor, refreshReplies, type Answer, type How } from './words'

const MAX_HOW_NOTE = 200

const HOWS: { value: Exclude<How, 'IN_APP'>; label: keyof typeof rm }[] = [
  { value: 'EMAIL', label: 'howEmail' },
  { value: 'PHONE', label: 'howPhone' },
  { value: 'IN_PERSON', label: 'howInPerson' },
  { value: 'OTHER', label: 'howOther' },
]

const ANSWER_LABEL = { IN_PERSON: rm.replyInPerson, REMOTE: rm.replyRemote, APOLOGIES: rm.replyApologies } as const

/**
 * "Record an answer for M. Hossain" (flow W2): the answer the person gave by email, by phone, in person or otherwise,
 * with their alternate when they send apologies from a member's seat, how they told you (required) and a note; the
 * person is told. `PUT /meetings/{id}/rsvps/{userId}` with an `Idempotency-Key` and, once they have an answer, its
 * version as `If-Match`; a stale version reloads the panel and says so.
 */
export function RecordDialog({
  meeting,
  reply,
  onClose,
}: {
  meeting: MeetingResponse
  reply: ReplyResponse
  onClose: () => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const queryClient = useQueryClient()
  const offered = offeredFor(meeting.mode)
  const [answer, setAnswer] = useState<Answer | undefined>(
    reply.answer && offered.includes(reply.answer) ? reply.answer : undefined,
  )
  const [alternate, setAlternate] = useState(reply.alternateAttends)
  const [how, setHow] = useState<How>()
  const [howNote, setHowNote] = useState('')
  const [errors, setErrors] = useState<{ answer?: string; how?: string; howNote?: string }>({})
  const sent = useRef<{ key: string; request: string } | null>(null)
  const answerRef = useRef<HTMLFieldSetElement>(null)
  const howRef = useRef<HTMLFieldSetElement>(null)
  const member = reply.capacity === 'MEMBER'

  const submit = async (): Promise<string | undefined> => {
    const note = howNote.trim()
    const missing = {
      ...(answer ? {} : { answer: intl.formatMessage(rm.recordAnswerRequired) }),
      ...(how ? {} : { how: intl.formatMessage(rm.HOW_REQUIRED) }),
      ...(note.length > MAX_HOW_NOTE ? { howNote: intl.formatMessage(rm.LENGTH) } : {}),
    }
    setErrors(missing)
    if (missing.answer) answerRef.current?.querySelector('input')?.focus()
    else if (missing.how) howRef.current?.querySelector('input')?.focus()
    if (!answer || !how || missing.howNote) return undefined
    const body: RecordRsvpRequest = {
      answer,
      alternateAttends: member && answer === 'APOLOGIES' && alternate,
      how,
      howNote: note || null,
      note: reply.note ?? null,
    }
    const headers = ifMatchVersion(reply.version)
    const request = JSON.stringify({ body, headers })
    if (sent.current?.request !== request) sent.current = { key: newIdempotencyKey(), request }
    try {
      await recordMeetingRsvp({
        path: { meetingId: meeting.id, userId: reply.userId },
        headers: { 'Idempotency-Key': sent.current.key, ...headers },
        body,
        throwOnError: true,
      })
      await refreshReplies(queryClient, false)
      toast.show(intl.formatMessage(rm.recorded), 'success')
      onClose()
      return undefined
    } catch (problem) {
      if (isProblem(problem) && problem.status === 422) {
        const code = problem.errors?.[0]?.code
        if (code === 'HOW_REQUIRED') {
          setErrors({ how: intl.formatMessage(rm.HOW_REQUIRED) })
          return undefined
        }
        if (code === 'LENGTH') {
          setErrors({ howNote: intl.formatMessage(rm.LENGTH) })
          return undefined
        }
      }
      if (isProblem(problem) && (problem.status === 409 || problem.status === 412 || problem.status === 428)) {
        await refreshReplies(queryClient, false)
      }
      return answerRefusal(intl, terms, problem, { name: reply.name })
    }
  }

  return (
    <FormDialog
      side
      title={intl.formatMessage(rmv.recordTitle, { name: reply.name })}
      description={intl.formatMessage(rmv.recordTold, { name: reply.name })}
      submitLabel={intl.formatMessage(rm.recordSubmit)}
      cancelLabel={intl.formatMessage(rm.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      <Choice
        ref={answerRef}
        legend={intl.formatMessage(rm.recordAnswer)}
        name="answer"
        error={errors.answer}
        options={offered.map((value) => ({ value, label: intl.formatMessage(ANSWER_LABEL[value]) }))}
        value={answer}
        onChange={setAnswer}
      />
      {member && answer === 'APOLOGIES' ? (
        <label className="flex min-h-11 cursor-pointer items-center gap-3 text-base">
          <Checkbox
            className="size-5"
            checked={alternate}
            onChange={(e) => {
              setAlternate(e.target.checked)
            }}
          />
          {intl.formatMessage(rm.recordAlternate)}
        </label>
      ) : null}
      <Choice
        ref={howRef}
        legend={intl.formatMessage(rm.recordHow)}
        name="how"
        error={errors.how}
        options={HOWS.map((h) => ({ value: h.value, label: intl.formatMessage(rm[h.label]) }))}
        value={how}
        onChange={setHow}
      />
      <Field
        label={intl.formatMessage(rm.recordNote)}
        optionalLabel={intl.formatMessage(rm.optional)}
        hint={intl.formatMessage(rm.recordNoteHint)}
        error={errors.howNote}
      >
        {(p) => (
          <Textarea
            {...p}
            dir="auto"
            rows={2}
            className="field-sizing-content min-h-16"
            value={howNote}
            onChange={(e) => {
              setHowNote(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}

/** A radio group with a legend and its error, 44 px per choice. */
function Choice<T extends string>({
  ref,
  legend,
  name,
  error,
  options,
  value,
  onChange,
}: {
  ref: Ref<HTMLFieldSetElement>
  legend: string
  name: string
  error: string | undefined
  options: { value: T; label: string }[]
  value: T | undefined
  onChange: (value: T) => void
}) {
  const errorId = `${name}-error`
  return (
    <fieldset ref={ref} className="flex flex-col gap-1" aria-describedby={error ? errorId : undefined}>
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
              aria-invalid={error ? true : undefined}
              onChange={() => {
                onChange(o.value)
              }}
            />
            {o.label}
          </label>
        ))}
      </div>
      {error ? (
        <p id={errorId} className="text-xs font-medium text-destructive">
          {error}
        </p>
      ) : null}
    </fieldset>
  )
}
