import { ArrowLeft } from 'lucide-react'
import { lazy, Suspense, useEffect, useState } from 'react'
import { useIntl } from 'react-intl'
import { getMinutesDocument, getMinutesRecord, previewMinutesCopy } from '@/api/generated/sdk.gen'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { useClosable } from '@/features/meetings/page/closable'
import { mn } from './messages'
import { reasonOf } from './words'

const PdfViewer = lazy(() => import('@/features/memo/PdfViewer'))

/** Which copy to show: the viewer's own at the current stage, an officer's preview "as" a reader, or a record version. */
export type CopySource = { kind: 'own' } | { kind: 'preview'; userId: string } | { kind: 'record'; version: number }

/** How long to wait before asking again when the server says nothing (it says `Retry-After: 10`). */
const DEFAULT_RETRY_S = 10
const MAX_RETRY_S = 30

function pathOf(meetingId: string, source: CopySource): string {
  const base = `/api/v1/meetings/${encodeURIComponent(meetingId)}/minutes`
  if (source.kind === 'preview') return `${base}/preview?as=${encodeURIComponent(source.userId)}`
  if (source.kind === 'record') return `${base}/record?version=${String(source.version)}`
  return `${base}/document`
}

/**
 * Asks for the first byte of the copy: 200/206 when it is there, 202 while it is made, 409 `RECORD_PENDING` while the
 * record is made, 409 `RECORD_FAILED` when the record or the reader's copy could not be made.
 */
async function probe(
  meetingId: string,
  source: CopySource,
): Promise<{ response: Response | undefined; error: unknown }> {
  const options = { path: { meetingId }, headers: { Range: 'bytes=0-0' }, parseAs: 'blob' as const }
  const result =
    source.kind === 'preview'
      ? await previewMinutesCopy({ ...options, query: { as: source.userId } })
      : source.kind === 'record'
        ? await getMinutesRecord({ ...options, query: { version: source.version } })
        : await getMinutesDocument(options)
  return { response: result.response, error: result.error }
}

type Probe =
  | { state: 'asking' }
  | { state: 'making'; record: boolean }
  | { state: 'ready' }
  | { state: 'failed'; status?: number; recordFailed?: boolean }

/**
 * A copy of the minutes in the app's PDF viewer, full screen, with "Back to the minutes" always in view (flow AF2): the
 * reader's own copy (with "DRAFT" on every page until approved), an officer's preview of a reader's copy (flow AD4), or
 * a version of the approved record. While the copy is being made (202 with `Retry-After`, or 409 `RECORD_PENDING` just
 * after the approval) it says so in a live region and asks again by itself. When it could not be made (409
 * `RECORD_FAILED`) it says so, and that the secretary has been told, and asks no more.
 */
export function CopyViewer({
  meetingId,
  source,
  title,
  onClose,
}: {
  meetingId: string
  source: CopySource
  title: string
  onClose: () => void
}) {
  const intl = useIntl()
  const dialog = useClosable(onClose)
  const [probed, setProbed] = useState<Probe>({ state: 'asking' })
  const key = JSON.stringify(source)

  useEffect(() => {
    const run = { cancelled: false, timer: undefined as ReturnType<typeof setTimeout> | undefined }
    const ask = async () => {
      let response: Response | undefined
      let error: unknown
      try {
        const probed = await probe(meetingId, JSON.parse(key) as CopySource)
        response = probed.response
        error = probed.error
      } catch {
        response = undefined
      }
      if (run.cancelled) return
      const status = response?.status
      if (status === 200 || status === 206) {
        setProbed({ state: 'ready' })
        return
      }
      if (status === 409 && reasonOf(error) === 'RECORD_FAILED') {
        setProbed({ state: 'failed', status, recordFailed: true })
        return
      }
      if (response && (status === 202 || status === 409)) {
        const after = Number(response.headers.get('Retry-After'))
        const seconds = Number.isFinite(after) && after > 0 ? Math.min(after, MAX_RETRY_S) : DEFAULT_RETRY_S
        setProbed({ state: 'making', record: status === 409 })
        run.timer = setTimeout(() => void ask(), seconds * 1000)
        return
      }
      setProbed({ state: 'failed', ...(status ? { status } : {}) })
    }
    void ask()
    return () => {
      run.cancelled = true
      if (run.timer) clearTimeout(run.timer)
    }
  }, [meetingId, key])

  const failedText = (status: number | undefined) =>
    intl.formatMessage(status === 403 || status === 404 ? mn.copyMissing : mn.copyFailed)

  return (
    <Dialog
      open={dialog.open}
      onOpenChange={dialog.onOpenChange}
      title={title}
      className="h-dvh max-h-none w-screen max-w-none rounded-none border-0"
    >
      <div className="flex flex-col gap-4">
        <div>
          <Button
            variant="outline"
            className="min-h-11 text-base"
            onClick={() => {
              dialog.close()
            }}
          >
            <ArrowLeft aria-hidden="true" className="rtl:rotate-180" />
            {intl.formatMessage(mn.backToMinutes)}
          </Button>
        </div>
        <p role="status" className="text-lg">
          {probed.state === 'asking'
            ? intl.formatMessage(mn.loadingCopy)
            : probed.state === 'making'
              ? intl.formatMessage(probed.record ? mn.recordBeingMade : mn.copyBeingMade)
              : probed.state === 'failed'
                ? probed.recordFailed
                  ? intl.formatMessage(mn.recordFailed)
                  : failedText(probed.status)
                : ''}
        </p>
        {probed.state === 'ready' ? (
          <Suspense fallback={<p>{intl.formatMessage(mn.loadingCopy)}</p>}>
            <PdfViewer url={pathOf(meetingId, source)} title={title} failedText={failedText} />
          </Suspense>
        ) : null}
      </div>
    </Dialog>
  )
}
