import { useQuery } from '@tanstack/react-query'
import { lazy, Suspense } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { getFileVersionRenditionsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { Dialog } from '@/components/ui/dialog'

const PdfViewer = lazy(() => import('./PdfViewer'))

const m = defineMessages({
  pending: { id: 'memo.preview.pending', defaultMessage: 'The preview is being prepared. Try again in a moment.' },
  none: { id: 'memo.preview.none', defaultMessage: 'This file cannot be previewed. Download it to open it.' },
  loading: { id: 'memo.preview.loading', defaultMessage: 'Loading the preview…' },
})

/**
 * Previews a file version (ADR-0045): a PDF (the original, or its rendition) page by page with PDF.js, an image as an
 * image, or says why there is no preview. Full screen on phones.
 */
export function FilePreviewDialog({
  fileId,
  versionId,
  title,
  onClose,
}: {
  fileId: string
  versionId: string
  title: string
  mediaType: string
  onClose: () => void
}) {
  const intl = useIntl()
  const renditions = useQuery(getFileVersionRenditionsOptions({ path: { fileId, versionId } }))
  const kind = renditions.data?.preview
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={title}
      className="max-md:h-dvh max-md:max-h-none max-md:w-screen max-md:max-w-none max-md:rounded-none md:max-w-4xl"
    >
      {kind === 'PDF' ? (
        <Suspense fallback={<p className="text-sm">{intl.formatMessage(m.loading)}</p>}>
          <PdfViewer url={`/api/v1/files/${fileId}/versions/${versionId}/renditions/pdf`} title={title} />
        </Suspense>
      ) : kind === 'IMAGE' ? (
        <img
          src={`/api/v1/files/${fileId}/versions/${versionId}/content`}
          alt={title}
          className="mx-auto max-h-[70vh]"
        />
      ) : kind === 'PENDING' ? (
        <p role="status" className="text-sm">
          {intl.formatMessage(m.pending)}
        </p>
      ) : kind === 'NONE' ? (
        <p className="text-sm">{intl.formatMessage(m.none)}</p>
      ) : (
        <p className="text-sm">{intl.formatMessage(m.loading)}</p>
      )}
    </Dialog>
  )
}
