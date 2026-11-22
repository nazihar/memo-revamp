import { useQuery } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { useIntl } from 'react-intl'
import { listMinutesCommentsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { respondToMinutesComment } from '@/api/generated/sdk.gen'
import type { MinutesCommentResponse } from '@/api/generated/types.gen'
import { LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/form-controls'
import { useMinutesWrite } from './data'
import { mn, mnv } from './messages'
import { moment, refusal, type Minutes } from './words'

const MAX_NOTE = 1_000

/**
 * The secretary's comments panel (flow AF4): how the readers answered ("7 readers: 4 no comments · 1 suggestion · 2
 * not yet"), who said "No comments", and each suggestion with "Adopt" or "Do not adopt" and a note to the member, once.
 * [respond] false shows the answers only (the minutes have left circulation).
 */
export function CommentsPanel({ page, respond, timeZone }: { page: Minutes; respond: boolean; timeZone?: string }) {
  const intl = useIntl()
  const titleId = useId()
  const comments = useQuery({ ...listMinutesCommentsOptions({ path: { meetingId: page.meetingId } }), retry: false })
  const all = comments.data?.comments ?? []
  const none = all.filter((c) => c.kind === 'NO_COMMENTS')
  const suggestions = all.filter((c) => c.kind === 'SUGGESTION')
  const a = page.answers
  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-3">
      <h2 id={titleId} className="text-xl font-semibold">
        {intl.formatMessage(mn.commentsTitle)}
      </h2>
      {a ? <p className="text-lg">{intl.formatMessage(mnv.answers, { ...a })}</p> : null}
      {comments.isPending ? <LoadingState rows={2} /> : null}
      {none.length ? (
        <p>
          {intl.formatMessage(mnv.noCommentsFrom, {
            names: intl.formatList(
              none.map((c) => c.by.name),
              { type: 'conjunction' },
            ),
          })}
        </p>
      ) : null}
      {comments.isSuccess && !suggestions.length ? <p>{intl.formatMessage(mn.noSuggestions)}</p> : null}
      {suggestions.length ? (
        <ul className="flex flex-col gap-3">
          {suggestions.map((c) => (
            <Suggestion key={c.id} page={page} comment={c} respond={respond} timeZone={timeZone} />
          ))}
        </ul>
      ) : null}
    </section>
  )
}

function Suggestion({
  page,
  comment: c,
  respond,
  timeZone,
}: {
  page: Minutes
  comment: MinutesCommentResponse
  respond: boolean
  timeZone?: string
}) {
  const intl = useIntl()
  const noteId = useId()
  const write = useMinutesWrite()
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [refused, setRefused] = useState<string>()
  const part = c.itemNumber ? intl.formatMessage(mnv.item, { number: c.itemNumber }) : intl.formatMessage(mn.inGeneral)
  const answer = async (response: 'ADOPTED' | 'NOT_ADOPTED') => {
    setBusy(true)
    setRefused(undefined)
    try {
      const body = { response, ...(note.trim() ? { note: note.trim() } : {}) }
      await write([c.id, body], (key) =>
        respondToMinutesComment({
          path: { meetingId: page.meetingId, commentId: c.id },
          headers: { 'Idempotency-Key': key },
          body,
          throwOnError: true,
        }),
      )
    } catch (problem) {
      setRefused(refusal(intl, problem))
    } finally {
      setBusy(false)
    }
  }
  return (
    <li className="flex flex-col gap-2 rounded-md border p-3">
      <p className="text-base text-muted-foreground">
        {intl.formatMessage(mnv.commentLine, { name: c.by.name, part, date: moment(intl, c.at, timeZone) })}
      </p>
      <p className="whitespace-pre-line text-lg">{c.text}</p>
      {c.response && c.respondedBy ? (
        <p className="text-base">
          {c.responseNote
            ? intl.formatMessage(mnv.responseLineNote, {
                response: intl.formatMessage(mn[c.response]),
                name: c.respondedBy.name,
                note: c.responseNote,
              })
            : intl.formatMessage(mnv.responseLine, {
                response: intl.formatMessage(mn[c.response]),
                name: c.respondedBy.name,
              })}
        </p>
      ) : respond ? (
        <div className="flex flex-wrap items-end gap-2">
          <div className="flex min-w-48 flex-1 flex-col gap-1">
            <label htmlFor={noteId} className="text-sm font-medium">
              {intl.formatMessage(mn.responseNote)}
              <span className="ms-1 font-normal text-muted-foreground">{intl.formatMessage(mn.optional)}</span>
            </label>
            <Input
              id={noteId}
              maxLength={MAX_NOTE}
              className="min-h-11"
              value={note}
              onChange={(e) => {
                setNote(e.target.value)
              }}
            />
          </div>
          <Button
            variant="outline"
            className="min-h-11"
            disabled={busy}
            aria-label={intl.formatMessage(mnv.respondNamed, {
              action: intl.formatMessage(mn.adopt),
              name: c.by.name,
              part,
            })}
            onClick={() => void answer('ADOPTED')}
          >
            {intl.formatMessage(mn.adopt)}
          </Button>
          <Button
            variant="outline"
            className="min-h-11"
            disabled={busy}
            aria-label={intl.formatMessage(mnv.respondNamed, {
              action: intl.formatMessage(mn.notAdopt),
              name: c.by.name,
              part,
            })}
            onClick={() => void answer('NOT_ADOPTED')}
          >
            {intl.formatMessage(mn.notAdopt)}
          </Button>
        </div>
      ) : (
        <p className="text-base text-muted-foreground">{intl.formatMessage(mn.awaitingReply)}</p>
      )}
      {refused ? (
        <p role="alert" className="text-base text-destructive">
          {refused}
        </p>
      ) : null}
    </li>
  )
}
