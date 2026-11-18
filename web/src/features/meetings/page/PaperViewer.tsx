import { ArrowLeft } from 'lucide-react'
import { lazy, Suspense } from 'react'
import { useIntl } from 'react-intl'
import type { MemberPaperResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { useClosable } from './closable'
import { pm } from './messages'
import { paperUrl } from './words'

const PdfViewer = lazy(() => import('@/features/memo/PdfViewer'))

/**
 * A paper in the PDF viewer, full screen inside the app (flow P1), with "Back to the agenda" always in view and no
 * download. A PDF still being prepared (409 `PAPER_NOT_READY`) is said in words; [onOpened] runs once it has loaded.
 */
export function PaperViewer({
  meetingId,
  revision,
  paper,
  onOpened,
  onClose,
}: {
  meetingId: string
  revision: number
  paper: MemberPaperResponse
  onOpened: () => void
  onClose: () => void
}) {
  const intl = useIntl()
  // Back closes the dialog itself, so focus returns to the paper's button.
  const dialog = useClosable(onClose)
  return (
    <Dialog
      open={dialog.open}
      onOpenChange={dialog.onOpenChange}
      title={paper.title}
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
            {intl.formatMessage(pm.back)}
          </Button>
        </div>
        <Suspense fallback={<p role="status">{intl.formatMessage(pm.loadingPaper)}</p>}>
          <PdfViewer
            url={paperUrl(meetingId, paper.id, revision)}
            title={paper.title}
            onLoaded={onOpened}
            failedText={(status) => intl.formatMessage(status === 409 ? pm.notReady : pm.viewerFailed)}
          />
        </Suspense>
      </div>
    </Dialog>
  )
}
