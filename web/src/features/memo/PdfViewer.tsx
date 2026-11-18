import { ChevronLeft, ChevronRight } from 'lucide-react'
import { GlobalWorkerOptions, getDocument, type PDFDocumentProxy } from 'pdfjs-dist'
import { useEffect, useRef, useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'

const m = defineMessages({
  previous: { id: 'memo.preview.previous', defaultMessage: 'Previous page' },
  next: { id: 'memo.preview.next', defaultMessage: 'Next page' },
  failed: {
    id: 'memo.preview.failed',
    defaultMessage: 'The preview could not be shown. Download the file to open it.',
  },
})

const pages = defineMessages<{ page: { page: number; pages: number } }>({
  page: { id: 'memo.preview.page', defaultMessage: 'Page {page} of {pages}' },
})

// The worker comes from our own origin as a module (no blob: or eval under the CSP; research §5.1).
GlobalWorkerOptions.workerPort ??= new Worker(new URL('pdfjs-dist/build/pdf.worker.min.mjs', import.meta.url), {
  type: 'module',
})

/**
 * A PDF one page at a time on a canvas (PDF.js, ADR-0045): no scripting, no XFA, no WebAssembly (the CSP has no
 * `wasm-unsafe-eval`). Loaded only when a preview opens. Pages turn with the buttons or the arrow keys. [failedText]
 * words a failure by its HTTP status (a meeting paper still being prepared answers 409); [onLoaded] runs once the
 * document has loaded (the meeting page's "Opened" tick).
 */
export default function PdfViewer({
  url,
  title,
  failedText,
  onLoaded,
}: {
  url: string
  title: string
  failedText?: (status: number | undefined) => string
  onLoaded?: () => void
}) {
  const intl = useIntl()
  const canvas = useRef<HTMLCanvasElement>(null)
  const loaded = useRef(onLoaded)
  useEffect(() => {
    loaded.current = onLoaded
  })
  const [pdf, setPdf] = useState<PDFDocumentProxy>()
  const [page, setPage] = useState(1)
  const [failed, setFailed] = useState<{ status: number | undefined }>()

  useEffect(() => {
    const task = getDocument({ url, useWasm: false, enableXfa: false, isOffscreenCanvasSupported: false })
    task.promise.then(
      (document) => {
        setPdf(document)
        loaded.current?.()
      },
      (error: unknown) => {
        const status = (error as { status?: unknown } | null)?.status
        setFailed({ status: typeof status === 'number' ? status : undefined })
      },
    )
    return () => {
      void task.destroy()
    }
  }, [url])

  useEffect(() => {
    if (!pdf || !canvas.current) return
    let cancelled = false
    const target = canvas.current
    void pdf.getPage(page).then((p) => {
      if (cancelled) return
      const viewport = p.getViewport({ scale: 1.25 })
      target.width = viewport.width
      target.height = viewport.height
      const context = target.getContext('2d')
      if (context) void p.render({ canvas: target, canvasContext: context, viewport }).promise
    })
    return () => {
      cancelled = true
    }
  }, [pdf, page])

  if (failed) {
    return (
      <p role="status" className="text-sm">
        {failedText ? failedText(failed.status) : intl.formatMessage(m.failed)}
      </p>
    )
  }
  const count = pdf?.numPages ?? 0
  return (
    <div
      className="flex flex-col items-center gap-2"
      onKeyDown={(e) => {
        if (e.key === 'ArrowRight' || e.key === 'PageDown') setPage((p) => Math.min(count, p + 1))
        if (e.key === 'ArrowLeft' || e.key === 'PageUp') setPage((p) => Math.max(1, p - 1))
      }}
    >
      <div className="flex items-center gap-2">
        <Button
          variant="outline"
          size="icon"
          aria-label={intl.formatMessage(m.previous)}
          disabled={page <= 1}
          onClick={() => {
            setPage((p) => p - 1)
          }}
        >
          <ChevronLeft aria-hidden="true" className="rtl:rotate-180" />
        </Button>
        <span role="status" className="text-sm tabular-nums">
          {count ? intl.formatMessage(pages.page, { page, pages: count }) : ''}
        </span>
        <Button
          variant="outline"
          size="icon"
          aria-label={intl.formatMessage(m.next)}
          disabled={page >= count}
          onClick={() => {
            setPage((p) => p + 1)
          }}
        >
          <ChevronRight aria-hidden="true" className="rtl:rotate-180" />
        </Button>
      </div>
      <canvas ref={canvas} aria-label={title} role="img" className="max-w-full border" tabIndex={0} />
    </div>
  )
}
