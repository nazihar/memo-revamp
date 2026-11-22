import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { useId, useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { getMinutesQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { confirmMinutes, reviewMinutes } from '@/api/generated/sdk.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { refreshMeetings } from '@/features/meetings/shared'
import { CopyViewer } from './CopyViewer'
import { useMinutesWrite } from './data'
import { mn, mnv } from './messages'
import { day, fieldProblems, ifMatch, refusal, type Minutes } from './words'

const MAX_NOTE = 1_000

/**
 * The chair's review (flow AE1): read the draft (their copy), suggest changes in the document (review mode: tracked
 * changes the secretary accepts or rejects), comments to the secretary, then "Send back with comments" (a comment
 * required) or "Ready to circulate". Sent back, the reviewer reads the minutes no more: they go back to the meeting.
 */
export function ReviewCard({ page, meetingName }: { page: Minutes; meetingName: string }) {
  const intl = useIntl()
  const titleId = useId()
  const navigate = useNavigate()
  const toast = useToast()
  const queryClient = useQueryClient()
  const write = useMinutesWrite()
  const [comment, setComment] = useState('')
  const [error, setError] = useState<string>()
  const [refused, setRefused] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [reading, setReading] = useState(false)
  const commentRef = useRef<HTMLTextAreaElement>(null)

  const review = async (decision: 'AGREE' | 'RETURN') => {
    setError(undefined)
    setRefused(undefined)
    if (decision === 'RETURN' && !comment.trim()) {
      setError(intl.formatMessage(mn.sayWhat))
      commentRef.current?.focus()
      return
    }
    setBusy(true)
    try {
      const body = { decision, ...(comment.trim() ? { comment: comment.trim() } : {}) }
      const headers = ifMatch(page)
      const result = await write(
        { body, headers },
        (key) =>
          reviewMinutes({
            path: { meetingId: page.meetingId },
            headers: { 'Idempotency-Key': key, ...headers },
            body,
            throwOnError: true,
          }),
        decision === 'AGREE',
      )
      if (decision === 'RETURN') {
        // 204: the draft is back with the secretary and the chair reads it no more.
        toast.show(intl.formatMessage(mn.sentBack), 'success')
        queryClient.removeQueries({ queryKey: getMinutesQueryKey({ path: { meetingId: page.meetingId } }) })
        await refreshMeetings(queryClient)
        await navigate({ to: '/meetings/$meetingId', params: { meetingId: page.meetingId } })
        return
      }
      const after = (result.data as Minutes | undefined)?.state
      toast.show(intl.formatMessage(after === 'CIRCULATED' ? mn.agreedCirculated : mn.agreedChanges), 'success')
    } catch (problem) {
      const fields = fieldProblems(intl, problem)
      if (fields.comment) {
        setError(fields.comment)
        commentRef.current?.focus()
      } else setRefused(refusal(intl, problem))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-4 rounded-lg border bg-card p-4 text-lg">
      <h2 id={titleId} className="text-xl font-semibold">
        {intl.formatMessage(mnv.reviewTitle, { meeting: meetingName })}
      </h2>
      <div className="flex flex-wrap gap-2">
        {page.mayReadCopy ? (
          <Button
            variant="outline"
            className="min-h-11 text-base"
            onClick={() => {
              setReading(true)
            }}
          >
            {intl.formatMessage(mn.readDraft)}
          </Button>
        ) : null}
        {page.mayEdit ? (
          <Button asChild variant="outline" className="min-h-11 text-base">
            <Link
              to="/meetings/$meetingId/minutes/edit"
              params={{ meetingId: page.meetingId }}
              aria-describedby={`${titleId}-suggest`}
            >
              {intl.formatMessage(mn.suggestChanges)}
            </Link>
          </Button>
        ) : null}
      </div>
      {page.mayEdit ? (
        <p id={`${titleId}-suggest`} className="text-base text-muted-foreground">
          {intl.formatMessage(mn.suggestHint)}
        </p>
      ) : null}
      <Field
        label={intl.formatMessage(mn.commentsToSecretary)}
        optionalLabel={intl.formatMessage(mn.optional)}
        error={error}
      >
        {(p) => (
          <Textarea
            {...p}
            ref={commentRef}
            name="comment"
            rows={4}
            maxLength={MAX_NOTE}
            className="text-base"
            value={comment}
            onChange={(e) => {
              setComment(e.target.value)
            }}
          />
        )}
      </Field>
      {refused ? (
        <p
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-base text-destructive"
        >
          {refused}
        </p>
      ) : null}
      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="outline" className="min-h-11 text-base" disabled={busy} onClick={() => void review('RETURN')}>
          {intl.formatMessage(mn.sendBack)}
        </Button>
        <Button className="min-h-11 text-base" disabled={busy} onClick={() => void review('AGREE')}>
          {intl.formatMessage(mn.readyToCirculate)}
        </Button>
      </div>
      {reading ? (
        <CopyViewer
          meetingId={page.meetingId}
          source={{ kind: 'own' }}
          title={intl.formatMessage(mnv.draftOf, { meeting: meetingName })}
          onClose={() => {
            setReading(false)
          }}
        />
      ) : null}
    </section>
  )
}

/**
 * The chair's approval (flow AG3): how the minutes were approved, "Read the final minutes", what approving means, and
 * "Approve the minutes" with a fresh step-up (the authenticator code). "Not yet" goes back to the meeting.
 */
export function ConfirmCard({
  page,
  meetingName,
  meetingDate,
  timeZone,
}: {
  page: Minutes
  meetingName: string
  meetingDate?: string
  timeZone?: string
}) {
  const intl = useIntl()
  const titleId = useId()
  const toast = useToast()
  const write = useMinutesWrite()
  const withStepUp = useWithStepUp()
  const [refused, setRefused] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [reading, setReading] = useState(false)

  const approve = async () => {
    setRefused(undefined)
    setBusy(true)
    try {
      const headers = ifMatch(page)
      await write({ headers }, (key) =>
        withStepUp(() =>
          confirmMinutes({
            path: { meetingId: page.meetingId },
            headers: { 'Idempotency-Key': key, ...headers },
            throwOnError: true,
          }),
        ),
      )
      toast.show(intl.formatMessage(mn.approvedNow), 'success')
    } catch (problem) {
      setRefused(refusal(intl, problem))
    } finally {
      setBusy(false)
    }
  }

  const approving = page.approvingMeeting
  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-4 rounded-lg border bg-card p-4 text-lg">
      <h2 id={titleId} className="text-xl font-semibold">
        {meetingDate
          ? intl.formatMessage(mnv.approveOf, { meeting: meetingName, date: day(intl, meetingDate, timeZone) })
          : intl.formatMessage(mn.approveTitle)}
      </h2>
      <p>
        {approving
          ? intl.formatMessage(mnv.approvedAt, {
              meeting: approving.label ?? day(intl, approving.startsAt, timeZone),
              date: day(intl, approving.startsAt, timeZone),
            })
          : intl.formatMessage(mn.endedByCirculation)}
      </p>
      {page.mayReadCopy ? (
        <div>
          <Button
            variant="outline"
            className="min-h-11 text-base"
            onClick={() => {
              setReading(true)
            }}
          >
            {intl.formatMessage(mn.readFinal)}
          </Button>
        </div>
      ) : null}
      <p>{intl.formatMessage(mn.approveLead)}</p>
      {refused ? (
        <p
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-base text-destructive"
        >
          {refused}
        </p>
      ) : null}
      <div className="flex flex-wrap items-center justify-end gap-2">
        <Button asChild variant="outline" className="min-h-11 text-base">
          <Link to="/meetings/$meetingId" params={{ meetingId: page.meetingId }}>
            {intl.formatMessage(mn.notYet)}
          </Link>
        </Button>
        <Button
          className="min-h-11 text-base"
          disabled={busy}
          aria-describedby={`${titleId}-stepup`}
          onClick={() => void approve()}
        >
          {intl.formatMessage(mn.approve)}
        </Button>
      </div>
      <p id={`${titleId}-stepup`} className="text-end text-base text-muted-foreground">
        {intl.formatMessage(mn.approveStepUp)}
      </p>
      {reading ? (
        <CopyViewer
          meetingId={page.meetingId}
          source={{ kind: 'own' }}
          title={intl.formatMessage(mnv.draftOf, { meeting: meetingName })}
          onClose={() => {
            setReading(false)
          }}
        />
      ) : null}
    </section>
  )
}
