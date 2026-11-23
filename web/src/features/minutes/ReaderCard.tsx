import { useQuery } from '@tanstack/react-query'
import { MessageSquareText } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { listMinutesCommentsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { commentOnMinutes } from '@/api/generated/sdk.gen'
import type { MinutesCommentResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Select, Textarea } from '@/components/ui/form-controls'
import { FormDialog } from '@/features/forums/FormDialog'
import { CopyViewer } from './CopyViewer'
import { useMinutesWrite } from './data'
import { mn, mnv } from './messages'
import { fieldProblems, itemName, moment, refusal, weekday, type Minutes, type MinutesItem } from './words'

const MAX_SUGGESTION = 2_000

/**
 * "Minutes to read" for a reader of circulated minutes (flows AF1–AF3), on the meeting page and the minutes page: the
 * deadline, "Read the minutes" (their own copy), "No comments" (asked once) and "Suggest a correction" (per item or in
 * general), then their own comments with the secretary's replies. The next meeting's audience (LINKED) only reads.
 */
export function ReaderCard({
  page,
  meetingName,
  items,
  timeZone,
  headingLevel = 2,
}: {
  page: Minutes
  meetingName: string
  items: MinutesItem[]
  timeZone?: string
  headingLevel?: 2 | 3
}) {
  const intl = useIntl()
  const titleId = useId()
  const [reading, setReading] = useState(false)
  const [asking, setAsking] = useState<'none' | 'suggest'>()
  const [said, setSaid] = useState<string>()
  const saidRef = useRef<HTMLParagraphElement>(null)
  const comments = useQuery({
    ...listMinutesCommentsOptions({ path: { meetingId: page.meetingId } }),
    enabled: page.mayComment || page.answered,
    retry: false,
  })
  const mine = (comments.data?.comments ?? []).filter((c) => c.mine)
  const Heading = headingLevel === 2 ? 'h2' : 'h3'
  const done = (text: string) => {
    setSaid(text)
    setAsking(undefined)
    requestAnimationFrame(() => saidRef.current?.focus())
  }
  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-3 rounded-lg border bg-card p-4 text-lg">
      <Heading id={titleId} className="text-xl font-semibold">
        {intl.formatMessage(mnv.draftOf, { meeting: meetingName })}
      </Heading>
      {page.state === 'CIRCULATED' && page.mayComment && page.commentsUntil ? (
        <p>{intl.formatMessage(mnv.commentsBy, { date: weekday(intl, page.commentsUntil, timeZone) })}</p>
      ) : null}
      {page.viewerAccess === 'LINKED' ? <p>{intl.formatMessage(mn.linkedReader)}</p> : null}
      <div className="flex flex-wrap gap-2">
        {page.mayReadCopy ? (
          <Button
            className="min-h-11 text-base"
            onClick={() => {
              setReading(true)
            }}
          >
            {intl.formatMessage(mn.readMinutes)}
          </Button>
        ) : null}
        {page.mayComment && !page.answered ? (
          <Button
            variant="outline"
            className="min-h-11 text-base"
            onClick={() => {
              setAsking('none')
            }}
          >
            {intl.formatMessage(mn.noComments)}
          </Button>
        ) : null}
        {page.mayComment ? (
          <Button
            variant="outline"
            className="min-h-11 text-base"
            onClick={() => {
              setAsking('suggest')
            }}
          >
            {intl.formatMessage(mn.suggestCorrection)}
          </Button>
        ) : null}
      </div>
      <p ref={saidRef} tabIndex={-1} role="status" className="outline-none">
        {said ??
          (page.answered && mine.some((c) => c.kind === 'NO_COMMENTS') ? intl.formatMessage(mn.noCommentsSent) : '')}
      </p>
      {mine.some((c) => c.kind === 'SUGGESTION') ? (
        <div className="flex flex-col gap-2">
          <Heading className="text-lg font-semibold">{intl.formatMessage(mn.myComments)}</Heading>
          <ul className="flex flex-col gap-3">
            {mine
              .filter((c) => c.kind === 'SUGGESTION')
              .map((c) => (
                <MyComment key={c.id} comment={c} timeZone={timeZone} />
              ))}
          </ul>
        </div>
      ) : null}
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
      {asking === 'none' ? (
        <NoCommentsDialog
          page={page}
          onDone={() => {
            done(`${intl.formatMessage(mn.noCommentsSent)} ${intl.formatMessage(mn.laterSuggestion)}`)
          }}
          onClose={() => {
            setAsking(undefined)
          }}
        />
      ) : null}
      {asking === 'suggest' ? (
        <SuggestDialog
          page={page}
          items={items}
          onDone={() => {
            done(intl.formatMessage(mn.suggestionSent))
          }}
          onClose={() => {
            setAsking(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function MyComment({ comment: c, timeZone }: { comment: MinutesCommentResponse; timeZone?: string }) {
  const intl = useIntl()
  const part = c.itemNumber ? intl.formatMessage(mnv.item, { number: c.itemNumber }) : intl.formatMessage(mn.inGeneral)
  return (
    <li className="flex flex-col gap-1 rounded-md border p-3">
      <p className="text-base text-muted-foreground">
        <MessageSquareText aria-hidden="true" className="me-1 inline size-4" />
        {intl.formatMessage(mnv.partSent, { part, date: moment(intl, c.at, timeZone) })}
      </p>
      <p className="whitespace-pre-line">{c.text}</p>
      <p className="text-base">
        {c.response && c.respondedBy
          ? c.responseNote
            ? intl.formatMessage(mnv.responseLineNote, {
                response: intl.formatMessage(mn[c.response]),
                name: c.respondedBy.name,
                note: c.responseNote,
              })
            : intl.formatMessage(mnv.responseLine, {
                response: intl.formatMessage(mn[c.response]),
                name: c.respondedBy.name,
              })
          : intl.formatMessage(mn.awaitingReply)}
      </p>
    </li>
  )
}

/** "No comments", asked once (flow AF3). */
function NoCommentsDialog({ page, onDone, onClose }: { page: Minutes; onDone: () => void; onClose: () => void }) {
  const intl = useIntl()
  const write = useMinutesWrite()
  return (
    <FormDialog
      large
      title={intl.formatMessage(mn.noCommentsTitle)}
      submitLabel={intl.formatMessage(mn.noComments)}
      onClose={onClose}
      onSubmit={async () => {
        try {
          const body = { kind: 'NO_COMMENTS' as const }
          await write(body, (key) =>
            commentOnMinutes({
              path: { meetingId: page.meetingId },
              headers: { 'Idempotency-Key': key },
              body,
              throwOnError: true,
            }),
          )
          onDone()
          return undefined
        } catch (problem) {
          return refusal(intl, problem)
        }
      }}
    />
  )
}

/** "Suggest a correction" (flow AF3): which part, and the suggestion. */
function SuggestDialog({
  page,
  items,
  onDone,
  onClose,
}: {
  page: Minutes
  items: MinutesItem[]
  onDone: () => void
  onClose: () => void
}) {
  const intl = useIntl()
  const write = useMinutesWrite()
  const [itemId, setItemId] = useState('')
  const [text, setText] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const textRef = useRef<HTMLTextAreaElement>(null)
  return (
    <FormDialog
      large
      title={intl.formatMessage(mn.suggestCorrection)}
      description={intl.formatMessage(mn.suggestionHint)}
      submitLabel={intl.formatMessage(mn.sendSuggestion)}
      onClose={onClose}
      onSubmit={async () => {
        if (!text.trim()) {
          setErrors({ text: intl.formatMessage(mn.writeSuggestion) })
          textRef.current?.focus()
          return undefined
        }
        try {
          const body = { kind: 'SUGGESTION' as const, text: text.trim(), ...(itemId ? { itemId } : {}) }
          await write(body, (key) =>
            commentOnMinutes({
              path: { meetingId: page.meetingId },
              headers: { 'Idempotency-Key': key },
              body,
              throwOnError: true,
            }),
          )
          onDone()
          return undefined
        } catch (problem) {
          const fields = fieldProblems(intl, problem)
          if (fields.itemId) fields.itemId = intl.formatMessage(mn.itemUnknown)
          setErrors(fields)
          return Object.keys(fields).length ? undefined : refusal(intl, problem)
        }
      }}
    >
      <Field label={intl.formatMessage(mn.whichPart)} error={errors.itemId}>
        {(p) => (
          <Select
            {...p}
            className="min-h-11 text-base"
            value={itemId}
            onChange={(e) => {
              setItemId(e.target.value)
            }}
          >
            <option value="">{intl.formatMessage(mn.inGeneral)}</option>
            {items.map((i) => (
              <option key={i.itemId} value={i.itemId}>
                {itemName(intl, i)}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field label={intl.formatMessage(mn.suggestion)} required error={errors.text}>
        {(p) => (
          <Textarea
            {...p}
            ref={textRef}
            name="text"
            rows={5}
            maxLength={MAX_SUGGESTION}
            className="text-base"
            value={text}
            onChange={(e) => {
              setText(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}
