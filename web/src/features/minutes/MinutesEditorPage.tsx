import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { getMeetingOptions } from '@/api/generated/@tanstack/react-query.gen'
import { getEditSession, openEditor } from '@/api/generated/sdk.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { loadEditorScript, withoutInlineHandlers } from '@/lib/docs-api'
import { newIdempotencyKey } from '@/lib/idempotency'
import { refreshMinutes, useMinutes } from './data'
import { mn, mnv } from './messages'
import { meetingWords } from './words'

const SAVE_WAIT_MS = 30_000
const POLL_MS = 1_500

/**
 * `/meetings/$meetingId/minutes/edit` (flow AD2): the draft minutes in the browser editor (ONLYOFFICE through docedit's
 * edit sessions, ADR-0047), full screen with "Back to the minutes" always in view. Each item is a framed block that
 * cannot be deleted. The secretaries edit (several share one room); the chair reviewing it opens it in review mode, the
 * server making every change a tracked suggestion. Leaving waits for the save to land, then the minutes page checks the
 * new version's blocks.
 */
export function MinutesEditorPage({ meetingId }: { meetingId: string }) {
  const intl = useIntl()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const minutes = useMinutes(meetingId)
  const meeting = useQuery({ ...getMeetingOptions({ path: { meetingId } }), retry: false })
  const [session, setSession] = useState<string>()
  const [state, setState] = useState<'loading' | 'ready' | 'failed' | 'saving' | 'notSaved'>('loading')
  const editor = useRef<{ destroyEditor: () => void } | undefined>(undefined)
  const page = minutes.data
  // The file is taken once: the page reading itself again (a version being checked) must not close the editor.
  const [fileId, setFileId] = useState<string>()
  if (!fileId && page?.mayEdit && page.editFileId) setFileId(page.editFileId)
  const reviewing = page?.viewerAccess === 'REVIEWER'

  useEffect(() => {
    if (!fileId) return
    const run = { cancelled: false }
    void (async () => {
      try {
        const opened = await openEditor({
          path: { fileId },
          headers: { 'Idempotency-Key': newIdempotencyKey() },
          body: { mode: 'EDIT', language: intl.locale.startsWith('ar') ? 'ar' : 'en' },
          throwOnError: true,
        })
        await loadEditorScript(opened.data.scriptUrl)
        if (run.cancelled || !window.DocsAPI) return
        const api = window.DocsAPI
        editor.current = withoutInlineHandlers(() => new api.DocEditor('minutes-editor', opened.data.config))
        setSession(opened.data.sessionId)
        setState('ready')
      } catch {
        if (!run.cancelled) setState('failed')
      }
    })()
    return () => {
      run.cancelled = true
      editor.current?.destroyEditor()
      editor.current = undefined
    }
  }, [fileId, intl.locale])

  /** Closing the editor makes the document server send the save; wait for it before going back. */
  const leave = async () => {
    editor.current?.destroyEditor()
    editor.current = undefined
    if (session) {
      setState('saving')
      const deadline = Date.now() + SAVE_WAIT_MS
      while (Date.now() < deadline) {
        const current = await getEditSession({ path: { id: session } }).catch(() => undefined)
        const result = current?.data?.state
        if (result === 'SAVED' || result === 'CLOSED') break
        if (result === 'FAILED') {
          setState('notSaved')
          return
        }
        await new Promise((resolve) => setTimeout(resolve, POLL_MS))
      }
    }
    await refreshMinutes(queryClient)
    await navigate({ to: '/meetings/$meetingId/minutes', params: { meetingId } })
  }

  if (minutes.isPending) return <LoadingState />
  if (minutes.isError) return <ErrorState onRetry={() => void minutes.refetch()} />
  const name = meetingWords(intl, meeting.data)
  return (
    <section aria-labelledby="minutes-editor-title" className="flex h-[calc(100dvh-8rem)] flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3">
        <Button
          variant="outline"
          className="min-h-11 text-base"
          onClick={() => void leave()}
          disabled={state === 'saving'}
        >
          <ArrowLeft aria-hidden="true" className="rtl:rotate-180" />
          {intl.formatMessage(mn.backToMinutes)}
        </Button>
        <h1 id="minutes-editor-title" className="text-lg font-semibold">
          {intl.formatMessage(reviewing ? mnv.reviewing : mnv.editing, { meeting: name })}
        </h1>
      </div>
      {reviewing ? <p className="text-muted-foreground">{intl.formatMessage(mn.suggestHint)}</p> : null}
      <p role="status" className="text-sm text-muted-foreground">
        {state === 'saving' ? intl.formatMessage(mn.editorSaving) : ''}
      </p>
      {state === 'notSaved' ? (
        <p role="alert" className="text-destructive">
          {intl.formatMessage(mn.editorNotSaved)}
        </p>
      ) : null}
      {!fileId ? <ErrorState title={intl.formatMessage(mn.editorUnavailable)} /> : null}
      {fileId && state === 'failed' ? <ErrorState title={intl.formatMessage(mn.editorFailed)} /> : null}
      {fileId && state === 'loading' ? <LoadingState rows={3} /> : null}
      <div className="min-h-0 flex-1 overflow-hidden rounded-md border" hidden={state !== 'ready'}>
        <div id="minutes-editor" />
      </div>
    </section>
  )
}
