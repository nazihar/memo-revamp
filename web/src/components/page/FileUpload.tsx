import { Upload } from 'lucide-react'
import { useId, useState, type DragEvent } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { csrfFetch, isProblem } from '@/api/http'
import { cn } from '@/lib/utils'

const messages = defineMessages({
  drop: { id: 'upload.dropHint', defaultMessage: 'Choose a file, or drop it on this area.' },
  uploading: { id: 'upload.uploading', defaultMessage: 'Uploading and checking…' },
  rejected: { id: 'upload.rejected', defaultMessage: 'This file was refused.' },
  tooLarge: { id: 'upload.tooLarge', defaultMessage: 'This file is too large.' },
  failed: { id: 'upload.failed', defaultMessage: 'The upload did not work. Try again.' },
})

export interface Uploaded {
  fileId: string
  versionId: string
}

/**
 * A single-file upload (ADR-0029) for a declared purpose: a native file input (keyboard and screen readers as usual) in a
 * drop area. The server scans, inspects and encrypts the file before it answers; refusals come back with a reason.
 */
export function FileUpload({
  label,
  hint,
  purpose,
  owner,
  accept,
  maxBytes,
  version,
  onUploaded,
}: {
  label: string
  hint?: string
  purpose: string
  owner?: string
  accept: string
  maxBytes: number
  /** Upload a new version of this file instead of a new file; [fileVersion] is its current ETag version. */
  version?: { fileId: string; fileVersion: number }
  onUploaded: (uploaded: Uploaded) => void
}) {
  const intl = useIntl()
  const id = useId()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [dragging, setDragging] = useState(false)

  const send = async (file: File) => {
    setError(undefined)
    if (file.size > maxBytes) {
      setError(intl.formatMessage(messages.tooLarge))
      return
    }
    setBusy(true)
    try {
      const query = new URLSearchParams({ purpose, ...(owner ? { owner } : {}) })
      const url = version ? `/api/v1/files/${version.fileId}/versions` : `/api/v1/files?${query.toString()}`
      const response = await csrfFetch(url, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/octet-stream',
          'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(file.name)}`,
          'Idempotency-Key': crypto.randomUUID(),
          ...(version ? { 'If-Match': `"${version.fileVersion}"` } : {}),
        },
        body: file,
      })
      const body: unknown = await response.json().catch(() => undefined)
      if (!response.ok) {
        const detail = isProblem(body) ? body.detail : undefined
        setError(detail ?? intl.formatMessage(response.status < 500 ? messages.rejected : messages.failed))
        return
      }
      onUploaded(body as Uploaded)
    } catch {
      setError(intl.formatMessage(messages.failed))
    } finally {
      setBusy(false)
    }
  }

  const onDrop = (e: DragEvent) => {
    e.preventDefault()
    setDragging(false)
    const file = e.dataTransfer.files.item(0)
    if (file) void send(file)
  }

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      <div
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => {
          setDragging(false)
        }}
        onDrop={onDrop}
        className={cn(
          'flex flex-col items-start gap-2 rounded-md border border-dashed p-4',
          dragging ? 'border-primary bg-accent' : 'border-input',
        )}
      >
        <span className="flex items-center gap-2 text-sm text-muted-foreground">
          <Upload aria-hidden="true" className="size-4" />
          {intl.formatMessage(messages.drop)}
        </span>
        <input
          id={id}
          type="file"
          accept={accept}
          disabled={busy}
          aria-describedby={`${id}-hint ${id}-status`}
          className="text-sm file:me-3 file:rounded-md file:border file:border-input file:bg-card file:px-3 file:py-1.5"
          onChange={(e) => {
            const file = e.target.files?.item(0)
            if (file) void send(file)
            e.target.value = ''
          }}
        />
      </div>
      {hint ? (
        <p id={`${id}-hint`} className="text-xs text-muted-foreground">
          {hint}
        </p>
      ) : null}
      <p
        id={`${id}-status`}
        role="status"
        className={cn('text-sm', error ? 'text-destructive' : 'text-muted-foreground')}
      >
        {busy ? intl.formatMessage(messages.uploading) : error}
      </p>
    </div>
  )
}
