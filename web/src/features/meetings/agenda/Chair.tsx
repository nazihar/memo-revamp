import { CircleCheck, Clock } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { agreeAgenda, commentOnAgenda, decideLateItem, decideLatePaper } from '@/api/generated/sdk.gen'
import { Button } from '@/components/ui/button'
import { AlertDialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { FormDialog } from '@/features/forums/FormDialog'
import { newIdempotencyKey } from '@/lib/idempotency'
import { waitingForChair, type Waiting } from './draft'
import { ap, apv, purposeLabels } from './messages'
import { useAgenda } from './useAgenda'

/**
 * The chair's agreement in the builder's header (flows O2 and Q1): given (by whom, when, and how when a secretary
 * recorded it), or awaited, with "Record the chair's agreement…" for the secretaries.
 */
export function ChairAgreementLine() {
  const intl = useIntl()
  const { draft } = useAgenda()
  const [recording, setRecording] = useState(false)
  const a = draft.chairAgreement
  if (!draft.chairAgreementRequired && !a) return null
  if (a) {
    const when = intl.formatDate(a.at, { dateStyle: 'medium' })
    return (
      <p className="flex items-start gap-2 text-sm">
        <CircleCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-ok-fg" />
        <span>
          {a.onBehalfBy
            ? intl.formatMessage(apv.agreedOnBehalf, {
                chair: a.by?.name ?? '',
                when,
                secretary: a.onBehalfBy.name,
                note: a.note ?? '',
              })
            : intl.formatMessage(apv.agreed, { chair: a.by?.name ?? '', when })}
        </span>
      </p>
    )
  }
  if (draft.currentRevision > 0) return null
  return (
    <div className="flex flex-wrap items-center gap-3 text-sm">
      <span className="flex items-center gap-2">
        <Clock aria-hidden="true" className="size-4 shrink-0" />
        {intl.formatMessage(ap.awaitingAgreement)}
      </span>
      {draft.canEdit ? (
        <Button
          variant="outline"
          onClick={() => {
            setRecording(true)
          }}
        >
          {intl.formatMessage(ap.recordAgreement)}
        </Button>
      ) : null}
      {recording ? (
        <RecordAgreementDialog
          onClose={() => {
            setRecording(false)
          }}
        />
      ) : null}
    </div>
  )
}

function RecordAgreementDialog({ onClose }: { onClose: () => void }) {
  const intl = useIntl()
  const { meeting, change, announce } = useAgenda()
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  return (
    <FormDialog
      title={intl.formatMessage(ap.recordAgreementTitle)}
      description={intl.formatMessage(ap.recordAgreementLead)}
      submitLabel={intl.formatMessage(ap.recordAgreementSubmit)}
      cancelLabel={intl.formatMessage(ap.cancel)}
      onClose={onClose}
      onSubmit={async () => {
        if (!note.trim()) {
          setErrors({ note: intl.formatMessage(ap.howGiven) })
          return undefined
        }
        const out = await change((headers) =>
          agreeAgenda({
            path: { meetingId: meeting.id },
            headers,
            body: { onBehalf: true, note: note.trim() },
            throwOnError: true,
          }),
        )
        if (out.draft) {
          announce(intl.formatMessage(ap.agreementRecorded))
          onClose()
          return undefined
        }
        setErrors(out.fields)
        return Object.keys(out.fields).length ? undefined : out.refusal
      }}
    >
      <Field
        label={intl.formatMessage(ap.howGivenField)}
        hint={intl.formatMessage(ap.howGivenHint)}
        required
        error={errors.note}
      >
        {(p) => (
          <Textarea
            {...p}
            dir="auto"
            rows={2}
            maxLength={500}
            value={note}
            onChange={(e) => {
              setNote(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}

/**
 * The chair's side (flow Q1): while the first publication waits for the agreement, "Agree the agenda"; at any time a
 * comment to the secretaries. The secretaries read the chair's comments here too.
 */
export function ChairPanel() {
  const intl = useIntl()
  const { draft, meeting, change, announce } = useAgenda()
  const [comment, setComment] = useState('')
  const [error, setError] = useState<string>()
  const [refusal, setRefusal] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const awaiting = draft.chairAgreementRequired && !draft.chairAgreement && draft.currentRevision === 0
  if (!draft.canAgree && !draft.chairComments.length) return null

  const send = async () => {
    setRefusal(undefined)
    if (!comment.trim()) {
      setError(intl.formatMessage(ap.required))
      return
    }
    setError(undefined)
    setBusy(true)
    const out = await change(() =>
      commentOnAgenda({
        path: { meetingId: meeting.id },
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        body: { text: comment.trim() },
        throwOnError: true,
      }),
    )
    setBusy(false)
    if (out.draft) {
      setComment('')
      announce(intl.formatMessage(ap.commentSent))
    } else if (out.fields.text) setError(out.fields.text)
    else setRefusal(out.refusal)
  }

  const agree = async () => {
    setRefusal(undefined)
    const out = await change((headers) =>
      agreeAgenda({ path: { meetingId: meeting.id }, headers, body: {}, throwOnError: true }),
    )
    if (out.draft) announce(intl.formatMessage(ap.agreedDone))
    else setRefusal(out.refusal)
  }

  return (
    <section aria-labelledby="agenda-chair" className="flex flex-col gap-3 rounded-md border p-4">
      <h2 id="agenda-chair" className="text-base font-semibold">
        {draft.canAgree && awaiting ? intl.formatMessage(ap.chairWaiting) : intl.formatMessage(ap.chairComments)}
      </h2>
      {draft.chairComments.length ? (
        <ul className="flex flex-col gap-2">
          {draft.chairComments.map((c) => (
            <li key={c.id} className="rounded-md bg-muted/40 p-2 text-sm">
              <p className="text-muted-foreground">
                {intl.formatMessage(apv.commentBy, {
                  name: c.by?.name ?? '',
                  when: intl.formatDate(c.at, { dateStyle: 'medium', timeStyle: 'short' }),
                })}
              </p>
              <p dir="auto" className="whitespace-pre-line">
                {c.text}
              </p>
            </li>
          ))}
        </ul>
      ) : null}
      {draft.canAgree ? (
        <>
          <Field
            label={intl.formatMessage(ap.commentField)}
            optionalLabel={intl.formatMessage(ap.optional)}
            error={error}
          >
            {(p) => (
              <Textarea
                {...p}
                dir="auto"
                rows={3}
                maxLength={2000}
                value={comment}
                onChange={(e) => {
                  setComment(e.target.value)
                }}
              />
            )}
          </Field>
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" disabled={busy} onClick={() => void send()}>
              {intl.formatMessage(ap.sendComments)}
            </Button>
            {awaiting ? (
              <Button
                onClick={() => {
                  setConfirming(true)
                }}
              >
                {intl.formatMessage(ap.agreeAgenda)}
              </Button>
            ) : null}
          </div>
        </>
      ) : null}
      {refusal ? (
        <p role="alert" className="text-sm text-destructive">
          {refusal}
        </p>
      ) : null}
      {confirming ? (
        <AlertDialog
          open
          onOpenChange={(open) => {
            if (!open) setConfirming(false)
          }}
          title={intl.formatMessage(ap.agreeTitle)}
          description={intl.formatMessage(ap.agreeText)}
          confirmLabel={intl.formatMessage(ap.agreeAgenda)}
          cancelLabel={intl.formatMessage(ap.notYet)}
          onConfirm={() => void agree()}
        />
      ) : null}
    </section>
  )
}

/**
 * Late papers waiting for the chair (flow Q2): the chair agrees or says "Not this meeting"; a secretary records the
 * chair's answer on their behalf, with how it was given.
 */
export function LatePapers() {
  const intl = useIntl()
  const { draft } = useAgenda()
  const waiting = waitingForChair(draft)
  if (!waiting.length || (!draft.canAgree && !draft.canEdit)) return null
  return (
    <section
      aria-labelledby="agenda-late"
      className="flex flex-col gap-3 rounded-md border border-status-warn-fg/40 p-4"
    >
      <h2 id="agenda-late" className="text-base font-semibold">
        {intl.formatMessage(draft.canAgree ? ap.lateForYou : ap.lateWaiting)}
      </h2>
      <ul className="flex flex-col gap-3">
        {waiting.map((w) => (
          <LatePaper key={w.paper?.id ?? w.item.id} waiting={w} />
        ))}
      </ul>
    </section>
  )
}

function LatePaper({ waiting }: { waiting: Waiting }) {
  const intl = useIntl()
  const { draft, meeting, change, announce } = useAgenda()
  const [dialog, setDialog] = useState<'decline' | 'record'>()
  const [refusal, setRefusal] = useState<string>()
  const { item, paper } = waiting
  const title = paper ? intl.formatMessage(apv.paperOfItem, { paper: paper.title, item: item.title }) : item.title
  const facts = [
    item.memoReference,
    item.purpose ? intl.formatMessage(purposeLabels[item.purpose]) : undefined,
    paper?.pages ? intl.formatMessage(apv.pages, { count: paper.pages }) : undefined,
  ].filter(Boolean)

  const decide = async (agree: boolean, onBehalf: boolean, note?: string): Promise<string | undefined> => {
    setRefusal(undefined)
    const body = { agree, onBehalf, ...(note ? { note } : {}) }
    const out = await change((headers) =>
      paper
        ? decideLatePaper({ path: { meetingId: meeting.id, paperId: paper.id }, headers, body, throwOnError: true })
        : decideLateItem({ path: { meetingId: meeting.id, itemId: item.id }, headers, body, throwOnError: true }),
    )
    if (out.draft) {
      announce(intl.formatMessage(agree ? apv.lateAgreed : apv.lateDeclined, { title }))
      return undefined
    }
    setRefusal(out.refusal)
    return Object.keys(out.fields).length ? Object.values(out.fields).join(' ') : out.refusal
  }

  return (
    <li className="flex flex-col gap-2">
      <p dir="auto" className="font-medium">
        {title}
      </p>
      {facts.length ? <p className="text-sm text-muted-foreground">{facts.join(' · ')}</p> : null}
      {draft.paperDeadline ? (
        <p className="text-sm">
          {intl.formatMessage(apv.dueOn, {
            date: intl.formatDate(`${draft.paperDeadline}T12:00:00Z`, { dateStyle: 'medium', timeZone: 'UTC' }),
          })}
        </p>
      ) : null}
      <div className="flex flex-wrap gap-2">
        {draft.canAgree ? (
          <>
            <Button
              variant="outline"
              onClick={() => {
                setDialog('decline')
              }}
            >
              {intl.formatMessage(ap.notThisMeeting)}
            </Button>
            <Button onClick={() => void decide(true, false)}>{intl.formatMessage(ap.agreeInclude)}</Button>
          </>
        ) : (
          <Button
            variant="outline"
            onClick={() => {
              setDialog('record')
            }}
          >
            {intl.formatMessage(ap.recordAnswer)}
          </Button>
        )}
      </div>
      {refusal ? (
        <p role="alert" className="text-sm text-destructive">
          {refusal}
        </p>
      ) : null}
      {dialog === 'decline' ? (
        <AlertDialog
          open
          onOpenChange={(open) => {
            if (!open) setDialog(undefined)
          }}
          title={intl.formatMessage(apv.declineTitle, { title })}
          description={intl.formatMessage(paper ? ap.declinePaperText : ap.declineText)}
          confirmLabel={intl.formatMessage(ap.notThisMeeting)}
          cancelLabel={intl.formatMessage(ap.cancel)}
          onConfirm={() => void decide(false, false)}
        />
      ) : null}
      {dialog === 'record' ? (
        <RecordAnswerDialog
          title={title}
          declineText={intl.formatMessage(paper ? ap.declinePaperText : ap.declineText)}
          onDecide={decide}
          onClose={() => {
            setDialog(undefined)
          }}
        />
      ) : null}
    </li>
  )
}

function RecordAnswerDialog({
  title,
  declineText,
  onDecide,
  onClose,
}: {
  title: string
  declineText: string
  onDecide: (agree: boolean, onBehalf: boolean, note?: string) => Promise<string | undefined>
  onClose: () => void
}) {
  const intl = useIntl()
  const [agree, setAgree] = useState(true)
  const [note, setNote] = useState('')
  const [error, setError] = useState<string>()
  return (
    <FormDialog
      title={intl.formatMessage(apv.recordAnswerTitle, { title })}
      description={intl.formatMessage(ap.recordAnswerLead)}
      submitLabel={intl.formatMessage(ap.recordAnswerSubmit)}
      cancelLabel={intl.formatMessage(ap.cancel)}
      onClose={onClose}
      onSubmit={async () => {
        if (!note.trim()) {
          setError(intl.formatMessage(ap.howGiven))
          return undefined
        }
        const refused = await onDecide(agree, true, note.trim())
        if (!refused) onClose()
        return refused
      }}
    >
      <fieldset className="flex flex-col gap-1">
        <legend className="mb-1 text-sm font-medium">{intl.formatMessage(ap.chairAnswer)}</legend>
        {[true, false].map((value) => (
          <label key={String(value)} className="flex min-h-11 items-start gap-2 text-sm">
            <input
              type="radio"
              name="answer"
              className="mt-1 size-4 accent-primary"
              checked={agree === value}
              onChange={() => {
                setAgree(value)
              }}
            />
            <span className="flex flex-col">
              <span className="font-medium">{intl.formatMessage(value ? ap.agreeInclude : ap.notThisMeeting)}</span>
              {!value ? <span className="text-muted-foreground">{declineText}</span> : null}
            </span>
          </label>
        ))}
      </fieldset>
      <Field
        label={intl.formatMessage(ap.howGivenField)}
        hint={intl.formatMessage(ap.howGivenHint)}
        required
        error={error}
      >
        {(p) => (
          <Textarea
            {...p}
            dir="auto"
            rows={2}
            maxLength={500}
            value={note}
            onChange={(e) => {
              setNote(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}
