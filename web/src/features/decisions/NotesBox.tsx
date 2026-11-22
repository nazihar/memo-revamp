import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { getItemNotesOptions, getItemNotesQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { saveItemNotes } from '@/api/generated/sdk.gen'
import type { ItemNoteResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Textarea } from '@/components/ui/form-controls'
import { newIdempotencyKey } from '@/lib/idempotency'
import { dm, dmv } from './messages'

/** How long typing goes on before the notes are saved (design §1.2: every 10 seconds of typing). */
export const AUTOSAVE_MS = 10_000
const MAX_NOTES = 10_000

type Status = 'idle' | 'unsaved' | 'saving' | 'saved' | 'failed' | 'conflict' | 'closed'

/**
 * The secretary's notes on one item (design §1.2, flow AA1): plain text only secretaries read, saved as a new version
 * 10 seconds into typing and when the box is left, only when the text changed, with If-Match on the version read (`"0"`
 * for none). The state is said in words ("Saved at 10:42"), politely. Another secretary's newer version is shown
 * instead of overwriting it. Nothing is shown to someone who may not read the notes (404).
 */
export function NotesBox({
  meetingId,
  itemId,
  timeZone,
  editable,
}: {
  meetingId: string
  itemId: string
  timeZone: string
  editable: boolean
}) {
  const intl = useIntl()
  const id = useId()
  const queryClient = useQueryClient()
  const path = { path: { meetingId, itemId } }
  const notes = useQuery({ ...getItemNotesOptions(path), retry: false })
  const [text, setText] = useState<string>()
  const [status, setStatus] = useState<Status>('idle')
  const [savedAt, setSavedAt] = useState<string>()
  const saved = useRef<ItemNoteResponse | undefined>(undefined)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const latest = useRef('')
  const inFlight = useRef(false)

  // The version read first is the one the next save follows (If-Match); later reads come from the saves.
  useEffect(() => {
    if (notes.data && saved.current === undefined) {
      saved.current = notes.data
      latest.current = notes.data.text
    }
  }, [notes.data])

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current)
    },
    [],
  )

  const save = async () => {
    if (timer.current) {
      clearTimeout(timer.current)
      timer.current = undefined
    }
    const current = saved.current
    const value = latest.current
    if (!current || inFlight.current || value === current.text) return
    inFlight.current = true
    setStatus('saving')
    try {
      const result = await saveItemNotes({
        ...path,
        body: { text: value },
        headers: { 'Idempotency-Key': newIdempotencyKey(), 'If-Match': `"${String(current.seq)}"` },
        throwOnError: true,
      })
      saved.current = result.data
      queryClient.setQueryData(getItemNotesQueryKey(path), result.data)
      setSavedAt(result.data.savedAt ?? new Date().toISOString())
      setStatus(latest.current === result.data.text ? 'saved' : 'unsaved')
    } catch (problem) {
      if (isProblem(problem) && (problem.status === 412 || problem.status === 428)) {
        const fresh = await notes.refetch()
        if (fresh.data) {
          saved.current = fresh.data
          latest.current = fresh.data.text
          setText(fresh.data.text)
        }
        setStatus('conflict')
      } else if (isProblem(problem) && (problem.status === 409 || problem.status === 403)) {
        setStatus('closed')
      } else {
        setStatus('failed')
      }
    } finally {
      inFlight.current = false
    }
  }

  if (notes.isPending || notes.isError) return null
  const value = text ?? notes.data.text
  const closed = status === 'closed' || !editable

  const words: Record<Status, string | undefined> = {
    idle: notes.data.savedAt
      ? intl.formatMessage(dmv.saved, {
          time: intl.formatTime(notes.data.savedAt, { hour: 'numeric', minute: '2-digit', timeZone }),
        })
      : undefined,
    unsaved: intl.formatMessage(dm.notesUnsaved),
    saving: intl.formatMessage(dm.notesSaving),
    saved: savedAt
      ? intl.formatMessage(dmv.saved, {
          time: intl.formatTime(savedAt, { hour: 'numeric', minute: '2-digit', timeZone }),
        })
      : intl.formatMessage(dm.notesSaved),
    failed: intl.formatMessage(dm.notesFailed),
    conflict: intl.formatMessage(dm.notesConflict),
    closed: intl.formatMessage(dm.notesClosed),
  }

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <label htmlFor={id} className="text-sm font-medium">
          {intl.formatMessage(dm.notes)}
        </label>
        <span role="status" aria-live="polite" className="inline-flex items-center gap-1 text-sm text-muted-foreground">
          {status === 'saved' || (status === 'idle' && words.idle) ? (
            <Check aria-hidden="true" className="size-4" />
          ) : null}
          {words[status]}
        </span>
      </div>
      <p id={`${id}-hint`} className="text-sm text-muted-foreground">
        {intl.formatMessage(editable ? dm.notesHint : dm.notesClosed)}
      </p>
      <Textarea
        id={id}
        dir="auto"
        rows={4}
        maxLength={MAX_NOTES}
        readOnly={closed}
        aria-describedby={`${id}-hint`}
        className="field-sizing-content min-h-24 text-base"
        value={value}
        onChange={(e) => {
          setText(e.target.value)
          latest.current = e.target.value
          setStatus('unsaved')
          timer.current ??= setTimeout(() => {
            timer.current = undefined
            void save()
          }, AUTOSAVE_MS)
        }}
        onBlur={() => void save()}
      />
    </div>
  )
}
