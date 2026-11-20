import { useQuery } from '@tanstack/react-query'
import { CalendarPlus, Check, TriangleAlert } from 'lucide-react'
import { useEffect, useId, useRef, useState, type RefObject } from 'react'
import { useIntl } from 'react-intl'
import { getMyMeetingRsvpOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MeetingResponse, MeetingSummaryResponse, MyRsvpResponse } from '@/api/generated/types.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { ApologiesDialog, MAX_NOTE } from './ApologiesDialog'
import { rm, rmv } from './messages'
import { useAnswer } from './useAnswer'
import { BIG, calendarOffered, howWords, meetingWhen, offeredFor, shortWhen, type Answer } from './words'

/**
 * "Add to my calendar": the meeting as a calendar file (`GET /meetings/{id}/calendar.ics`, PUBLISH), with the reminder
 * that a calendar's Accept and Decline do not reach the secretary.
 */
export function CalendarLink({ meeting: m }: { meeting: Pick<MeetingSummaryResponse, 'id' | 'status' | 'relation'> }) {
  const intl = useIntl()
  const hintId = useId()
  if (!calendarOffered(m)) return null
  return (
    <div className="flex flex-col gap-1 print:hidden">
      <div>
        <Button asChild variant="outline" className="min-h-11 text-base">
          <a href={`/api/v1/meetings/${m.id}/calendar.ics`} download aria-describedby={hintId}>
            <CalendarPlus aria-hidden="true" />
            {intl.formatMessage(rm.addToCalendar)}
          </a>
        </Button>
      </div>
      <p id={hintId} className="text-sm text-muted-foreground">
        {intl.formatMessage(rm.calendarHint)}
      </p>
    </div>
  )
}

/**
 * "Will you attend?" on the meeting page (flow V2–V4) for whoever answers the meeting (a member, an alternate standing
 * in, a guest; `rsvp` on the meeting): the answers the meeting's mode offers as large buttons, a yes saved at once and
 * apologies through their dialog (V3, with the alternate); then "You said: Yes, online (12 Nov, 10:14)" with "Change my
 * answer" and a note for the secretary. After a new date or time, the old answer is shown "for the old time" and the
 * question is asked again (V4). From the start of the meeting the answer is shown without changes. "Add to my calendar"
 * closes the card.
 */
export function WillYouAttend({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const shown = m.rsvp != null && m.status !== 'DRAFT' && m.status !== 'CANCELLED'
  const mine = useQuery({ ...getMyMeetingRsvpOptions({ path: { meetingId: m.id } }), enabled: shown })
  const titleId = useId()
  if (!shown) return null
  return (
    <section
      aria-labelledby={titleId}
      className="flex flex-col gap-4 rounded-md border bg-card p-4 text-lg text-card-foreground print:hidden"
    >
      {mine.isPending ? (
        <>
          <h2 id={titleId} className="text-xl font-semibold">
            {intl.formatMessage(rm.title)}
          </h2>
          <LoadingState rows={1} />
        </>
      ) : mine.isError ? (
        <>
          <h2 id={titleId} className="text-xl font-semibold">
            {intl.formatMessage(rm.title)}
          </h2>
          <ErrorState onRetry={() => void mine.refetch()} />
        </>
      ) : (
        <Question meeting={m} mine={mine.data} titleId={titleId} />
      )}
      <CalendarLink meeting={m} />
    </section>
  )
}

function Question({ meeting: m, mine, titleId }: { meeting: MeetingResponse; mine: MyRsvpResponse; titleId: string }) {
  const intl = useIntl()
  const answer = useAnswer(m.id)
  const [changing, setChanging] = useState(false)
  const [apologizing, setApologizing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [refusal, setRefusal] = useState<string>()
  const [done, setDone] = useState<string>()
  // Where focus goes after the next render: to the saved answer, or to the answers when they open again.
  const focus = useRef<'result' | 'answers' | undefined>(undefined)
  const setFocus = (to: 'result' | 'answers') => {
    focus.current = to
  }
  const resultRef = useRef<HTMLParagraphElement>(null)
  const answersRef = useRef<HTMLDivElement>(null)

  const asking = mine.canAnswer && (!mine.answer || mine.stale || changing)
  const offered = mine.offered.length ? mine.offered : offeredFor(m.mode)

  useEffect(() => {
    if (focus.current === 'result') resultRef.current?.focus()
    if (focus.current === 'answers') answersRef.current?.querySelector<HTMLButtonElement>('button')?.focus()
    focus.current = undefined
  })

  const send = async (body: { answer: Answer; alternateAttends?: boolean; note?: string | null }, saved: string) => {
    setBusy(true)
    setRefusal(undefined)
    setDone(undefined)
    try {
      const result = await answer(body, mine)
      if (result.refusal !== undefined) return result.refusal
      setChanging(false)
      const alternate = result.mine.alternateStandingIn ? result.mine.alternate?.name : undefined
      setDone(
        body.answer === 'APOLOGIES' && alternate && !mine.alternateStandingIn
          ? intl.formatMessage(rmv.alternateAsked, { name: alternate })
          : saved,
      )
      setFocus('result')
      return undefined
    } finally {
      setBusy(false)
    }
  }

  const choose = async (choice: Answer) => {
    if (choice === 'APOLOGIES') {
      setApologizing(true)
      return
    }
    const refused = await send({ answer: choice, note: mine.note ?? null }, intl.formatMessage(rm.saved))
    if (refused) setRefusal(refused)
  }

  const replyBy = mine.replyBy
    ? intl.formatDate(`${mine.replyBy}T00:00:00Z`, {
        weekday: 'long',
        day: 'numeric',
        month: 'long',
        timeZone: 'UTC',
      })
    : undefined

  return (
    <>
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 id={titleId} className="text-xl font-semibold">
          {intl.formatMessage(rm.title)}
        </h2>
        {asking && replyBy ? <p className="text-base">{intl.formatMessage(rmv.replyBy, { date: replyBy })}</p> : null}
      </div>
      {mine.standingInFor ? <p>{intl.formatMessage(rmv.standingInFor, { name: mine.standingInFor.name })}</p> : null}
      {mine.answer && mine.stale && mine.forStartsAt ? (
        <div className="flex gap-2 rounded-md bg-status-warn-bg p-3 text-status-warn-fg">
          <TriangleAlert aria-hidden="true" className="mt-1 size-5 shrink-0" />
          <div className="flex flex-col gap-1">
            <p>
              {intl.formatMessage(rmv.staleWas, {
                answer: intl.formatMessage(rm[mine.answer]),
                when: meetingWhen(intl, mine.forStartsAt, m.timeZone),
              })}
            </p>
            {mine.canAnswer ? (
              <p>{intl.formatMessage(rmv.staleNow, { when: meetingWhen(intl, m.startsAt, m.timeZone) })}</p>
            ) : null}
          </div>
        </div>
      ) : null}
      {asking ? (
        <>
          <div ref={answersRef} role="group" aria-labelledby={titleId} className="flex flex-wrap gap-3">
            {offered.map((choice) => (
              <Button
                key={choice}
                variant={changing && !mine.stale && mine.answer === choice ? 'default' : 'outline'}
                aria-pressed={changing && !mine.stale ? mine.answer === choice : undefined}
                className={BIG}
                disabled={busy}
                onClick={() => void choose(choice)}
              >
                {intl.formatMessage(rm[choice])}
              </Button>
            ))}
          </div>
          {changing ? (
            <div>
              <Button
                variant="ghost"
                className="min-h-11 text-base"
                onClick={() => {
                  setChanging(false)
                  setFocus('result')
                }}
              >
                {intl.formatMessage(rm.keepAnswer)}
              </Button>
            </div>
          ) : null}
        </>
      ) : mine.answer ? (
        <Answered mine={mine} resultRef={resultRef} />
      ) : (
        <p ref={resultRef} tabIndex={-1}>
          {intl.formatMessage(rm.noReply)}
        </p>
      )}
      {!mine.canAnswer && m.status === 'IN_PROGRESS' ? (
        <p className="text-base text-muted-foreground">{intl.formatMessage(rm.closed)}</p>
      ) : null}
      {mine.canAnswer && mine.answer && !mine.stale && !changing ? (
        <div className="flex flex-col gap-3">
          <div>
            <Button
              variant="outline"
              className="min-h-11 text-base"
              onClick={() => {
                setDone(undefined)
                setChanging(true)
                setFocus('answers')
              }}
            >
              {intl.formatMessage(rm.changeAnswer)}
            </Button>
          </div>
          <NoteForm
            key={mine.version ?? 0}
            mine={mine}
            onSave={(note) =>
              send(
                { answer: mine.answer ?? 'APOLOGIES', alternateAttends: mine.alternateAttends, note },
                intl.formatMessage(rm.noteSaved),
              )
            }
          />
        </div>
      ) : null}
      {refusal ? (
        <p
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-base text-destructive"
        >
          {refusal}
        </p>
      ) : null}
      <p role="status" className="text-base empty:hidden">
        {done}
      </p>
      {apologizing ? (
        <ApologiesDialog
          mine={mine}
          onSend={async (alternateAttends, note) => {
            const refused = await send(
              { answer: 'APOLOGIES', alternateAttends, note },
              intl.formatMessage(rm.apologiesSaved),
            )
            if (!refused) setApologizing(false)
            return refused
          }}
          onClose={() => {
            setApologizing(false)
          }}
        />
      ) : null}
    </>
  )
}

/** "✓ You said: Yes, online (12 Nov, 10:14)", who stands in, and who recorded it on the member's behalf. */
function Answered({ mine, resultRef }: { mine: MyRsvpResponse; resultRef: RefObject<HTMLParagraphElement | null> }) {
  const intl = useIntl()
  const answer = mine.answer
  if (!answer) return null
  const at = mine.answeredAt ? shortWhen(intl, mine.answeredAt) : undefined
  const said =
    answer === 'APOLOGIES'
      ? at
        ? intl.formatMessage(rmv.apologiesAt, { at })
        : intl.formatMessage(rm.sentApologies)
      : at
        ? intl.formatMessage(rmv.youSaidAt, { answer: intl.formatMessage(rm[answer]), at })
        : intl.formatMessage(rmv.youSaid, { answer: intl.formatMessage(rm[answer]) })
  return (
    <div className="flex flex-col gap-1">
      <p ref={resultRef} tabIndex={-1} className="flex items-center gap-2 font-medium">
        <Check aria-hidden="true" className="size-5 shrink-0" />
        {said}
      </p>
      {answer === 'APOLOGIES' && mine.alternateAttends ? (
        mine.alternateStandingIn && mine.alternate ? (
          <p>{intl.formatMessage(rmv.alternateAttends, { name: mine.alternate.name })}</p>
        ) : (
          <p>{intl.formatMessage(rm.alternateNotStanding)}</p>
        )
      ) : null}
      {mine.recordedBy && mine.how && mine.how !== 'IN_APP' ? (
        <p className="text-base text-muted-foreground">
          {intl.formatMessage(rmv.recordedBy, { name: mine.recordedBy.name, how: howWords(intl, mine.how) })}
        </p>
      ) : null}
    </div>
  )
}

/** "A note for the secretary (optional)", saved with the answer as it is. */
function NoteForm({
  mine,
  onSave,
}: {
  mine: MyRsvpResponse
  onSave: (note: string | null) => Promise<string | undefined>
}) {
  const intl = useIntl()
  const [note, setNote] = useState(mine.note ?? '')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const changed = note.trim() !== (mine.note ?? '')
  return (
    <form
      noValidate
      className="flex flex-col gap-2"
      onSubmit={(e) => {
        e.preventDefault()
        const text = note.trim()
        if (text.length > MAX_NOTE) {
          setError(intl.formatMessage(rm.noteHint))
          return
        }
        setError(undefined)
        setBusy(true)
        void onSave(text || null).then((refused) => {
          setBusy(false)
          if (refused) setError(refused)
        })
      }}
    >
      <Field
        label={intl.formatMessage(rm.noteLabel)}
        optionalLabel={intl.formatMessage(rm.optional)}
        hint={error ? undefined : intl.formatMessage(rm.noteHint)}
        error={error}
      >
        {(p) => (
          <Textarea
            {...p}
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
      {changed ? (
        <div>
          <Button type="submit" variant="outline" className="min-h-11 text-base" disabled={busy}>
            {intl.formatMessage(rm.saveNote)}
          </Button>
        </div>
      ) : null}
    </form>
  )
}
