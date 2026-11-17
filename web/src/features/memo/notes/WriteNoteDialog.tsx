import { useQuery } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { getMemoNoteDraft, postMemoNote, saveMemoNoteDraft, shareMemo } from '@/api/generated/sdk.gen'
import type { NoteEntryView } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { FileUpload } from '@/components/page/FileUpload'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { MAX_FILES, MAX_TEXT, plainText, type NoteDoc } from './note-doc'

const NoteEditor = lazy(() => import('./NoteEditor'))

const m = defineMessages({
  title: { id: 'memo.notes.dialog.title', defaultMessage: 'Write a note' },
  label: { id: 'memo.notes.dialog.label', defaultMessage: 'Note' },
  final: { id: 'memo.notes.dialog.final', defaultMessage: 'Notes cannot be edited. You can post a correction.' },
  addFile: { id: 'memo.notes.dialog.addFile', defaultMessage: 'Add a file' },
  post: { id: 'memo.notes.dialog.post', defaultMessage: 'Post note' },
  cancel: { id: 'memo.notes.dialog.cancel', defaultMessage: 'Cancel' },
  reason: { id: 'memo.notes.dialog.reason', defaultMessage: 'Reason' },
  shareAndMention: { id: 'memo.notes.dialog.shareAndMention', defaultMessage: 'Share and mention' },
})

const mv = defineMessages<{
  correctTitle: { when: string }
  saved: { time: string }
  fileHint: { max: number }
  removeFile: { name: string }
  noAccess: { names: string }
  failed: { message: string }
}>({
  correctTitle: { id: 'memo.notes.dialog.correct', defaultMessage: 'Correction of your note of {when}' },
  saved: { id: 'memo.notes.dialog.saved', defaultMessage: 'Draft saved {time} · only you can see it' },
  fileHint: { id: 'memo.notes.dialog.fileHint', defaultMessage: 'Up to {max, number} files, as for attachments.' },
  removeFile: { id: 'memo.notes.dialog.removeFile', defaultMessage: 'Remove {name}' },
  noAccess: {
    id: 'memo.notes.dialog.noAccess',
    defaultMessage: '{names} cannot see this memo. Share it with them?',
  },
  failed: { id: 'memo.notes.dialog.failed', defaultMessage: 'The note could not be posted: {message}' },
})

interface Draft {
  body: NoteDoc
  version: number
  updatedAt: string
}

const AUTOSAVE_MS = 3000
const FILE_MAX_BYTES = 50 * 1024 * 1024
/** The types memo attachments take (ADR-0046); the server checks the content. */
const NOTE_FILE_TYPES = '.pdf,.png,.jpg,.jpeg,.webp,.txt,.csv,.zip,.docx,.xlsx,.pptx,.odt,.ods,.odp'

/**
 * Writing a note (flow C2): the editor, files on the note, a private draft saved every few seconds (it survives a closed
 * browser and is removed when the note is posted), and "share and mention" when someone mentioned cannot see the memo.
 * A correction (C3) starts from the corrected text.
 */
export default function WriteNoteDialog({
  memoId,
  corrects,
  onClose,
  onPosted,
}: {
  memoId: string
  corrects?: NoteEntryView
  onClose: () => void
  onPosted: () => void
}) {
  const intl = useIntl()
  const draft = useQuery({
    queryKey: ['memo-note-draft', memoId],
    queryFn: async (): Promise<Draft | null> => {
      const res = await getMemoNoteDraft({ path: { id: memoId } })
      return res.response?.status === 200 && res.data ? (res.data as unknown as Draft) : null
    },
    enabled: corrects === undefined,
    staleTime: Infinity,
  })
  const [doc, setDoc] = useState<NoteDoc | undefined>()
  const [files, setFiles] = useState<{ fileId: string; name: string }[]>([])
  const [savedAt, setSavedAt] = useState<string | undefined>()
  const version = useRef<number | undefined>(undefined)
  const [blind, setBlind] = useState<{ ids: string[]; names: string[] } | undefined>()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | undefined>()
  const [posting, setPosting] = useState(false)

  useEffect(() => {
    if (draft.data && version.current === undefined) version.current = draft.data.version
  }, [draft.data])

  // Saves the draft a few seconds after the last change.
  useEffect(() => {
    if (!doc || corrects) return
    const timer = setTimeout(() => {
      void saveMemoNoteDraft({
        path: { id: memoId },
        body: { body: doc as never },
        headers: version.current === undefined ? {} : { 'If-Match': `"${version.current}"` },
      }).then((res) => {
        const saved = res.data as unknown as Draft | undefined
        if (saved) {
          version.current = saved.version
          setSavedAt(saved.updatedAt)
        }
      })
    }, AUTOSAVE_MS)
    return () => {
      clearTimeout(timer)
    }
  }, [doc, memoId, corrects])

  const text = doc ? plainText(doc) : ''
  const initial = corrects ? (corrects.body as unknown as NoteDoc) : (draft.data?.body ?? undefined)

  async function post() {
    if (!doc) return
    setPosting(true)
    setError(undefined)
    const res = await postMemoNote({
      path: { id: memoId },
      body: { body: doc as never, correctsId: corrects?.id ?? null, attachmentIds: files.map((f) => f.fileId) },
    })
    setPosting(false)
    if (res.response?.ok) {
      onPosted()
      return
    }
    const problem = isProblem(res.error) ? res.error : undefined
    const noAccess = (problem?.errors ?? []).filter((e) => e.code === 'MENTION_NO_ACCESS')
    if (noAccess.length > 0) {
      setBlind({
        ids: noAccess.map((e) => e.field.replace('mentions.', '')),
        names: noAccess.map((e) => e.message.replace(/ cannot see this memo\.$/, '')),
      })
      return
    }
    setError(
      intl.formatMessage(mv.failed, {
        message: problem?.detail ?? problem?.title ?? String(res.response?.status ?? ''),
      }),
    )
  }

  async function shareAndMention() {
    if (!blind || reason.trim().length === 0) return
    for (const userId of blind.ids) {
      await shareMemo({ path: { id: memoId }, body: { userId, reason: reason.trim() } })
    }
    setBlind(undefined)
    await post()
  }

  const when = corrects ? intl.formatDate(corrects.createdAt, { dateStyle: 'medium', timeStyle: 'short' }) : ''
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      className="w-[min(44rem,calc(100vw-2rem))]"
      title={corrects ? intl.formatMessage(mv.correctTitle, { when }) : intl.formatMessage(m.title)}
      description={intl.formatMessage(m.final)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(m.cancel)}
          </Button>
          <Button onClick={() => void post()} disabled={posting || text.length === 0 || text.length > MAX_TEXT}>
            {intl.formatMessage(m.post)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        {draft.isPending && !corrects ? null : (
          <Suspense fallback={null}>
            <NoteEditor initial={initial} label={intl.formatMessage(m.label)} onChange={setDoc} autoFocus />
          </Suspense>
        )}
        {files.length > 0 ? (
          <ul className="flex flex-wrap gap-2 text-sm">
            {files.map((f) => (
              <li key={f.fileId} className="flex items-center gap-1 rounded border px-2 py-0.5">
                {f.name}
                <button
                  type="button"
                  aria-label={intl.formatMessage(mv.removeFile, { name: f.name })}
                  onClick={() => {
                    setFiles(files.filter((x) => x.fileId !== f.fileId))
                  }}
                >
                  <X className="size-3.5" aria-hidden="true" />
                </button>
              </li>
            ))}
          </ul>
        ) : null}
        {files.length < MAX_FILES ? (
          <FileUpload
            label={intl.formatMessage(m.addFile)}
            hint={intl.formatMessage(mv.fileHint, { max: MAX_FILES })}
            purpose="memo.note.attachment"
            accept={NOTE_FILE_TYPES}
            owner={memoId}
            maxBytes={FILE_MAX_BYTES}
            onUploaded={(u) => {
              setFiles([...files, { fileId: u.fileId, name: (u as { name?: string }).name ?? '' }])
            }}
          />
        ) : null}
        {(savedAt ?? draft.data?.updatedAt) && !corrects ? (
          <p className="text-xs text-muted-foreground">
            {intl.formatMessage(mv.saved, {
              time: intl.formatTime(savedAt ?? draft.data?.updatedAt, { timeStyle: 'short' }),
            })}
          </p>
        ) : null}
        {blind ? (
          <div className="flex flex-col gap-2 rounded-md border p-3" role="alert">
            <p className="text-sm">{intl.formatMessage(mv.noAccess, { names: intl.formatList(blind.names) })}</p>
            <Field label={intl.formatMessage(m.reason)}>
              {(props) => (
                <Input
                  {...props}
                  value={reason}
                  maxLength={500}
                  onChange={(e) => {
                    setReason(e.target.value)
                  }}
                />
              )}
            </Field>
            <div className="flex gap-2">
              <Button
                variant="outline"
                onClick={() => {
                  setBlind(undefined)
                }}
              >
                {intl.formatMessage(m.cancel)}
              </Button>
              <Button onClick={() => void shareAndMention()} disabled={reason.trim().length === 0}>
                {intl.formatMessage(m.shareAndMention)}
              </Button>
            </div>
          </div>
        ) : null}
        {error ? (
          <p className="text-sm text-destructive" role="alert">
            {error}
          </p>
        ) : null}
      </div>
    </Dialog>
  )
}
