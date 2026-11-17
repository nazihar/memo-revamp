import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Download, Eye, Paperclip, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import {
  listClassificationLevelsOptions,
  listMemoAttachmentsQueryKey,
  listMemoAttachmentsOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import { changeMemoAttachment, removeMemoAttachment } from '@/api/generated/sdk.gen'
import type { AttachmentView, MemoView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { FileUpload } from '@/components/page/FileUpload'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/form-controls'
import { useMyAccess } from '@/features/access/access'
import { FilePreviewDialog } from './FilePreviewDialog'

const m = defineMessages({
  title: { id: 'memo.attachments', defaultMessage: 'Attachments' },
  lead: {
    id: 'memo.attachments.lead',
    defaultMessage:
      'Supporting papers. Each may be classified above the memo; readers not cleared for it see only that it exists.',
  },
  none: { id: 'memo.attachments.none', defaultMessage: 'No attachments.' },
  upload: { id: 'memo.attachments.upload', defaultMessage: 'Add an attachment' },
  uploadHint: {
    id: 'memo.attachments.upload.hint',
    defaultMessage: 'PDF, images, Word, Excel, PowerPoint, OpenDocument, text or ZIP; up to 50 MB.',
  },
  restricted: { id: 'memo.attachments.restricted', defaultMessage: 'Restricted attachment' },
  restrictedHint: { id: 'memo.attachments.restricted.hint', defaultMessage: 'Classified above your clearance.' },
  sameAsMemo: { id: 'memo.attachments.sameAsMemo', defaultMessage: 'Same as the memo' },
})

const named = defineMessages<{
  remove: { title: string }
  preview: { title: string }
  level: { title: string }
  download: { title: string }
}>({
  download: { id: 'memo.attachments.downloadNamed', defaultMessage: 'Download {title}' },
  remove: { id: 'memo.attachments.removeNamed', defaultMessage: 'Remove {title}' },
  preview: { id: 'memo.attachments.previewNamed', defaultMessage: 'Preview {title}' },
  level: { id: 'memo.attachments.levelNamed', defaultMessage: 'Classification of {title}' },
})

const ACCEPT = '.pdf,.png,.jpg,.jpeg,.webp,.txt,.csv,.zip,.docx,.xlsx,.pptx,.odt,.ods,.odp'
const MAX_BYTES = 50 * 1024 * 1024

function size(intl: ReturnType<typeof useIntl>, bytes: number | null | undefined): string {
  if (bytes == null) return ''
  return bytes >= 1024 * 1024
    ? `${intl.formatNumber(bytes / (1024 * 1024), { maximumFractionDigits: 1 })} MB`
    : `${intl.formatNumber(Math.max(1, Math.round(bytes / 1024)))} KB`
}

/**
 * The memo's attachments (ADR-0046): listed in order, with a stricter classification where set; restricted ones show
 * only that they exist. While the memo may change, its initiator and co-authors add, reclassify and remove them.
 */
export function MemoAttachments({
  memo,
  onProblem,
}: {
  memo: MemoView
  onProblem: (problem: Problem | undefined) => void
}) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const options = listMemoAttachmentsOptions({ path: { id: memo.id } })
  const list = useQuery(options)
  const levels = useQuery(listClassificationLevelsOptions())
  const access = useMyAccess()
  const [previewing, setPreviewing] = useState<AttachmentView>()
  const editable = memo.allowedActions.includes('UPLOAD_DOCUMENT')
  const clearance = access.data?.clearance.rank ?? 0
  // Stricter than the memo, and no higher than the actor may see.
  const stricter = (levels.data ?? []).filter((l) => l.rank > memo.classification.rank && l.rank <= clearance)
  const refresh = () =>
    queryClient.invalidateQueries({ queryKey: listMemoAttachmentsQueryKey({ path: { id: memo.id } }) })

  const reclassify = async (attachment: AttachmentView, value: string) => {
    try {
      await changeMemoAttachment({
        path: { id: memo.id, attachmentId: attachment.id },
        headers: { 'If-Match': `"${String(attachment.version ?? 0)}"` },
        body: value ? { classificationId: value } : { sameAsMemo: true },
        throwOnError: true,
      })
    } catch (problem) {
      onProblem(isProblem(problem) ? problem : undefined)
    }
    await refresh()
  }

  const remove = async (attachment: AttachmentView) => {
    try {
      await removeMemoAttachment({ path: { id: memo.id, attachmentId: attachment.id }, throwOnError: true })
    } catch (problem) {
      onProblem(isProblem(problem) ? problem : undefined)
    }
    await refresh()
  }

  const rows = list.data ?? []
  return (
    <section aria-labelledby="memo-attachments" className="rounded-md border p-4">
      <h2 id="memo-attachments" className="text-base font-semibold">
        {intl.formatMessage(m.title)}
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">{intl.formatMessage(m.lead)}</p>
      {rows.length === 0 ? <p className="text-sm text-muted-foreground">{intl.formatMessage(m.none)}</p> : null}
      <ul className="flex flex-col gap-2">
        {rows.map((a) =>
          a.restricted ? (
            <li key={a.id} className="flex items-center gap-2 text-sm text-muted-foreground">
              <Paperclip aria-hidden="true" className="size-4" />
              <span>{intl.formatMessage(m.restricted)}</span>
              <span className="text-xs">{intl.formatMessage(m.restrictedHint)}</span>
            </li>
          ) : (
            <li key={a.id} className="flex flex-wrap items-center gap-2 text-sm">
              <Paperclip aria-hidden="true" className="size-4" />
              <span className="min-w-0 flex-1 truncate font-medium">{a.title}</span>
              <span className="text-xs text-muted-foreground">{size(intl, a.sizeBytes)}</span>
              {a.classification && !editable ? <Badge tone="warn">{a.classification.name}</Badge> : null}
              {editable && stricter.length > 0 ? (
                <Select
                  aria-label={intl.formatMessage(named.level, { title: a.title ?? '' })}
                  className="w-44"
                  value={a.classification?.id ?? ''}
                  onChange={(e) => void reclassify(a, e.target.value)}
                >
                  <option value="">{intl.formatMessage(m.sameAsMemo)}</option>
                  {stricter.map((l) => (
                    <option key={l.id} value={l.id}>
                      {l.name}
                    </option>
                  ))}
                </Select>
              ) : null}
              <Button
                variant="ghost"
                size="icon"
                aria-label={intl.formatMessage(named.preview, { title: a.title ?? '' })}
                onClick={() => {
                  setPreviewing(a)
                }}
              >
                <Eye aria-hidden="true" />
              </Button>
              <Button variant="ghost" size="icon" asChild>
                <a
                  href={`/api/v1/files/${a.fileId ?? ''}/versions/${a.versionId ?? ''}/content`}
                  download
                  aria-label={intl.formatMessage(named.download, { title: a.title ?? '' })}
                >
                  <Download aria-hidden="true" />
                </a>
              </Button>
              {editable ? (
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={intl.formatMessage(named.remove, { title: a.title ?? '' })}
                  onClick={() => void remove(a)}
                >
                  <Trash2 aria-hidden="true" />
                </Button>
              ) : null}
            </li>
          ),
        )}
      </ul>
      {editable ? (
        <div className="mt-3">
          <FileUpload
            label={intl.formatMessage(m.upload)}
            hint={intl.formatMessage(m.uploadHint)}
            purpose="memo.attachment"
            owner={memo.id}
            accept={ACCEPT}
            maxBytes={MAX_BYTES}
            onUploaded={() => void refresh()}
          />
        </div>
      ) : null}
      {previewing?.fileId && previewing.versionId ? (
        <FilePreviewDialog
          fileId={previewing.fileId}
          versionId={previewing.versionId}
          title={previewing.title ?? ''}
          mediaType={previewing.mediaType ?? ''}
          onClose={() => {
            setPreviewing(undefined)
          }}
        />
      ) : null}
    </section>
  )
}
