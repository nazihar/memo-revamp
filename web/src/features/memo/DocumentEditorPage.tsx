import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { getMemoOptions } from '@/api/generated/@tanstack/react-query.gen'
import { getEditSession, openEditor } from '@/api/generated/sdk.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { newIdempotencyKey } from '@/lib/idempotency'
import { isolated } from './memo-fields'

const m = defineMessages({
  back: { id: 'editor.back', defaultMessage: 'Back to the memo' },
  saving: { id: 'editor.saving', defaultMessage: 'Saving your changes as a new version…' },
  notSaved: {
    id: 'editor.notSaved',
    defaultMessage:
      'Your changes could not be saved: the memo can no longer be changed, or a newer version was uploaded.',
  },
  failed: { id: 'editor.failed', defaultMessage: 'The editor could not be opened.' },
})

const titles = defineMessages<{ editing: { reference: string }; viewing: { reference: string } }>({
  editing: { id: 'editor.editing', defaultMessage: 'Editing {reference}' },
  viewing: { id: 'editor.viewing', defaultMessage: 'Reading {reference}' },
})

/** The editor's global, defined by the script the document server serves (ADR-0037). */
interface DocsApi {
  DocEditor: new (element: string, config: object) => { destroyEditor: () => void }
}
declare global {
  interface Window {
    DocsAPI?: DocsApi
  }
}

const SAVE_WAIT_MS = 30_000
const POLL_MS = 1_500

function loadScript(src: string): Promise<void> {
  const existing = document.querySelector<HTMLScriptElement>(`script[data-editor="${src}"]`)
  if (existing && window.DocsAPI) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = src
    script.dataset.editor = src
    script.onload = () => {
      resolve()
    }
    script.onerror = () => {
      reject(new Error('editor script'))
    }
    document.head.append(script)
  })
}

/**
 * Runs [create] without inline event-handler attributes: the editor script sets an empty `onmousewheel` on its frame (a
 * Safari workaround) that our CSP refuses anyway. Skipping it keeps the CSP free of `unsafe-hashes` and the console clean.
 */
function withoutInlineHandlers<T>(create: () => T): T {
  // eslint-disable-next-line @typescript-eslint/unbound-method -- restored below and only ever called with an element
  const setAttribute = Element.prototype.setAttribute
  Element.prototype.setAttribute = function (this: Element, name: string, value: string) {
    if (!/^on/i.test(name)) setAttribute.call(this, name, value)
  }
  try {
    return create()
  } finally {
    Element.prototype.setAttribute = setAttribute
  }
}

/**
 * `/memos/$memoId/document` (design E02 §9): the memo's document in the browser editor, editable by the initiator
 * while they hold the memo, read-only for everyone else who sees it. Leaving waits for the save to land.
 */
export function DocumentEditorPage({ memoId, mode }: { memoId: string; mode: 'edit' | 'view' }) {
  const intl = useIntl()
  const navigate = useNavigate()
  const memo = useQuery(getMemoOptions({ path: { id: memoId } }))
  const [session, setSession] = useState<string>()
  const [state, setState] = useState<'loading' | 'ready' | 'failed' | 'saving' | 'notSaved'>('loading')
  const editor = useRef<{ destroyEditor: () => void } | undefined>(undefined)
  const fileId = memo.data?.document?.fileId

  useEffect(() => {
    if (!fileId) return
    // An object, not a `let`: the cleanup flips it while the request is in flight.
    const run = { cancelled: false }
    void (async () => {
      try {
        const opened = await openEditor({
          path: { fileId },
          headers: { 'Idempotency-Key': newIdempotencyKey() },
          body: { mode: mode === 'edit' ? 'EDIT' : 'VIEW', language: intl.locale.startsWith('ar') ? 'ar' : 'en' },
          throwOnError: true,
        })
        await loadScript(opened.data.scriptUrl)
        if (run.cancelled || !window.DocsAPI) return
        const api = window.DocsAPI
        editor.current = withoutInlineHandlers(() => new api.DocEditor('memo-document-editor', opened.data.config))
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
  }, [fileId, mode, intl.locale])

  /** Closing the editor makes the document server send the save; wait for it before going back. */
  const leave = async () => {
    editor.current?.destroyEditor()
    editor.current = undefined
    if (mode === 'view' || !session) {
      await navigate({ to: '/memos/$memoId', params: { memoId } })
      return
    }
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
    await navigate({ to: '/memos/$memoId', params: { memoId } })
  }

  if (memo.isPending) return <LoadingState />
  if (memo.isError || !fileId) return <ErrorState onRetry={() => void memo.refetch()} />
  const reference = memo.data.reference
  return (
    <section aria-labelledby="editor-title" className="flex h-[calc(100dvh-8rem)] flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="outline" onClick={() => void leave()} disabled={state === 'saving'}>
          <ArrowLeft aria-hidden="true" className="rtl:rotate-180" />
          {intl.formatMessage(m.back)}
        </Button>
        <h1 id="editor-title" className="text-lg font-semibold">
          {intl.formatMessage(mode === 'edit' ? titles.editing : titles.viewing, { reference: isolated(reference) })}
        </h1>
      </div>
      {state === 'saving' ? (
        <p role="status" className="text-sm text-muted-foreground">
          {intl.formatMessage(m.saving)}
        </p>
      ) : null}
      {state === 'notSaved' ? (
        <p role="alert" className="text-sm text-destructive">
          {intl.formatMessage(m.notSaved)}
          <Link to="/memos/$memoId" params={{ memoId }} className="ms-1 underline">
            {intl.formatMessage(m.back)}
          </Link>
        </p>
      ) : null}
      {state === 'failed' ? <ErrorState title={intl.formatMessage(m.failed)} /> : null}
      {state === 'loading' ? <LoadingState rows={3} /> : null}
      <div className="min-h-0 flex-1 overflow-hidden rounded-md border" hidden={state !== 'ready'}>
        <div id="memo-document-editor" />
      </div>
    </section>
  )
}
