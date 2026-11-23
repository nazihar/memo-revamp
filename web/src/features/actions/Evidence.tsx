import { Paperclip, X } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import type { EvidenceFile } from '@/api/generated/types.gen'
import { FileUpload } from '@/components/page/FileUpload'
import { am } from './messages'

/** What evidence an action takes (ADR-0029, design §1.6): the types `action.evidence` allows, 25 MiB each, 10 at most. */
export const EVIDENCE_PURPOSE = 'action.evidence'
export const EVIDENCE_MAX_FILES = 10
export const EVIDENCE_MAX_BYTES = 25 * 1024 * 1024
const EVIDENCE_TYPES = [
  '.pdf',
  '.png',
  '.jpg',
  '.jpeg',
  '.webp',
  '.txt',
  '.docx',
  '.xlsx',
  '.pptx',
  '.odt',
  '.ods',
  '.odp',
  'application/pdf',
  'image/png',
  'image/jpeg',
  'image/webp',
  'text/plain',
].join(',')

export interface Attached {
  fileId: string
  name: string
}

/**
 * Files attached to an update or to "Mark as done": each uploaded at once (scanned and checked by the server), listed with
 * a 44 px "Remove", and announced as it is added or removed.
 */
export function EvidenceFiles({
  actionId,
  files,
  onChange,
  label,
  error,
}: {
  actionId: string
  files: Attached[]
  onChange: (files: Attached[]) => void
  label: string
  error?: string
}) {
  const intl = useIntl()
  const [said, setSaid] = useState('')
  return (
    <div className="flex flex-col gap-2">
      {files.length ? (
        <ul aria-label={intl.formatMessage(am.attached)} className="flex flex-col gap-1">
          {files.map((f) => (
            <li key={f.fileId} className="flex items-center gap-2 text-sm">
              <Paperclip aria-hidden="true" className="size-4 shrink-0" />
              <span className="min-w-0 flex-1 truncate">{f.name}</span>
              <button
                type="button"
                className="inline-flex size-11 items-center justify-center rounded-md hover:bg-accent focus-visible:outline-2 focus-visible:outline-ring"
                aria-label={intl.formatMessage(am.removeFile, { name: f.name })}
                onClick={() => {
                  onChange(files.filter((x) => x.fileId !== f.fileId))
                  setSaid(intl.formatMessage(am.fileRemoved, { name: f.name }))
                }}
              >
                <X aria-hidden="true" className="size-4" />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {files.length < EVIDENCE_MAX_FILES ? (
        <FileUpload
          label={label}
          hint={intl.formatMessage(am.attachHint)}
          purpose={EVIDENCE_PURPOSE}
          owner={actionId}
          accept={EVIDENCE_TYPES}
          maxBytes={EVIDENCE_MAX_BYTES}
          onUploaded={(u) => {
            const name = (u as { name?: string }).name ?? ''
            onChange([...files, { fileId: u.fileId, name }])
            setSaid(intl.formatMessage(am.fileAdded, { name }))
          }}
        />
      ) : (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(am.tooManyFiles)}</p>
      )}
      {error ? (
        <p role="alert" className="text-sm font-medium text-destructive">
          {error}
        </p>
      ) : null}
      <p role="status" className="sr-only">
        {said}
      </p>
    </div>
  )
}

/** Evidence on the timeline: each file opens through the backend (the one rule reads it; every open is audited). */
export function EvidenceLinks({ files }: { files: EvidenceFile[] }) {
  if (!files.length) return null
  return (
    <ul className="flex flex-wrap gap-2">
      {files.map((f) => (
        <li key={f.id}>
          <a
            href={`/api/v1/files/${f.id}/content`}
            target="_blank"
            rel="noopener"
            className="inline-flex min-h-11 items-center gap-1.5 rounded-md border px-3 text-sm underline-offset-4 hover:underline"
          >
            <Paperclip aria-hidden="true" className="size-4 shrink-0" />
            {f.name}
          </a>
        </li>
      ))}
    </ul>
  )
}
