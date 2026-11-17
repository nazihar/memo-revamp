import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import {
  getMemoTypeTemplateOptions,
  getMemoTypeTemplateQueryKey,
  getMemoTypeVersionQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { previewMemoTypeTemplate, removeMemoTypeTemplate } from '@/api/generated/sdk.gen'
import type { TemplateProblemView } from '@/api/generated/types.gen'
import { FileUpload } from '@/components/page/FileUpload'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast-context'
import { FilePreviewDialog } from '@/features/memo/FilePreviewDialog'

const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document,.docx'
const MAX_BYTES = 10 * 1024 * 1024

const m = defineMessages({
  title: { id: 'memoTypes.template.title', defaultMessage: 'Template' },
  none: { id: 'memoTypes.template.none', defaultMessage: 'No template. New memos start without a document.' },
  upload: { id: 'memoTypes.template.upload', defaultMessage: 'Upload a template' },
  replace: { id: 'memoTypes.template.replace', defaultMessage: 'Replace the template' },
  hint: {
    id: 'memoTypes.template.hint',
    defaultMessage: 'A .docx file, at most 10 MB. Macros and templates (.dotx) are refused.',
  },
  preview: { id: 'memoTypes.template.preview', defaultMessage: 'Preview as PDF' },
  previewTitle: { id: 'memoTypes.template.previewTitle', defaultMessage: 'Template preview with sample values' },
  remove: { id: 'memoTypes.template.remove', defaultMessage: 'Remove' },
  previewed: { id: 'memoTypes.template.previewed', defaultMessage: 'Previewed' },
  notPreviewed: { id: 'memoTypes.template.notPreviewed', defaultMessage: 'Preview it before submitting the version' },
  problems: { id: 'memoTypes.template.problems', defaultMessage: 'Problems to fix in Word' },
  used: { id: 'memoTypes.template.used', defaultMessage: 'Tags it uses' },
  available: { id: 'memoTypes.template.available', defaultMessage: 'Tags you can use' },
  copied: { id: 'memoTypes.template.copied', defaultMessage: 'Tag copied.' },
  failed: { id: 'memoTypes.template.failed', defaultMessage: 'That did not work. Try again.' },
})

const mv = defineMessages<{
  lead: { example: string }
  file: { name: string; size: string }
  where: { part: string; paragraph: number; excerpt: string }
  suggestion: { suggestion: string }
  copy: { tag: string }
  problem: { code: string; tag: string }
}>({
  lead: {
    id: 'memoTypes.template.lead',
    defaultMessage:
      'A Word file with tags such as {example}. A new memo of this type starts its document from it, filled with the memo’s details.',
  },
  file: { id: 'memoTypes.template.file', defaultMessage: '{name} ({size})' },
  where: { id: 'memoTypes.template.where', defaultMessage: '{part}, paragraph {paragraph, number}: “{excerpt}”' },
  suggestion: { id: 'memoTypes.template.suggestion', defaultMessage: 'Did you mean {suggestion}?' },
  copy: { id: 'memoTypes.template.copy', defaultMessage: 'Copy {tag}' },
  problem: {
    id: 'memoTypes.template.problem',
    defaultMessage:
      '{code, select, MALFORMED_TAG {A tag is not written correctly} UNSUPPORTED_TAG {This kind of tag is not supported: {tag}} UNKNOWN_TAG {Unknown tag {tag}} UNBALANCED_SECTION {A section is opened and not closed, or closed and not opened} LOOP_WITHOUT_ROW {The approvals list must be inside a table row} UNKNOWN_ROW_FIELD {Unknown field of an approval row: {tag}} TAG_IN_LOOP_ROW {Only approval fields can go in the approvals row: {tag}} other {Too many tags}}',
  },
})

/** How a tag is written in Word: `{{name}}`. */
const written = (tag: string) => `{{${tag}}}`

/**
 * The template of a memo-type version (ADR-0048, design E03 flow A): upload, what the check found, the tags it may use,
 * a PDF preview with sample values (needed before review) and removal. Changes only while the version is a draft.
 */
export function TemplateBlock({
  typeId,
  number,
  revision,
  draft,
}: {
  typeId: string
  number: number
  revision: number
  draft: boolean
}) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const path = { path: { typeId, number: String(number) } }
  const info = useQuery(getMemoTypeTemplateOptions(path))
  const [busy, setBusy] = useState(false)
  const [preview, setPreview] = useState<{ fileId: string; versionId: string }>()

  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: getMemoTypeTemplateQueryKey(path) }),
      queryClient.invalidateQueries({ queryKey: getMemoTypeVersionQueryKey(path) }),
    ])
  const run = async (call: () => Promise<void>) => {
    setBusy(true)
    try {
      await call()
    } catch (problem) {
      toast.show((problem as { detail?: string } | undefined)?.detail ?? intl.formatMessage(m.failed), 'error')
    } finally {
      setBusy(false)
    }
  }
  const showPreview = () =>
    run(async () => {
      const res = await previewMemoTypeTemplate({ ...path, throwOnError: true })
      setPreview(res.data)
      await refresh()
    })
  const remove = () =>
    run(async () => {
      await removeMemoTypeTemplate({ ...path, headers: { 'If-Match': `"${String(revision)}"` }, throwOnError: true })
      await refresh()
    })
  const copy = (tag: string) => {
    void navigator.clipboard.writeText(written(tag)).then(() => {
      toast.show(intl.formatMessage(m.copied), 'success')
    })
  }

  if (info.isPending) return <LoadingState />
  if (info.isError) return <ErrorState onRetry={() => void info.refetch()} />
  const { template, available, owner } = info.data

  return (
    <section aria-labelledby="template-title" className="flex max-w-2xl flex-col gap-3 rounded-md border p-4">
      <h3 id="template-title" className="text-base font-semibold">
        {intl.formatMessage(m.title)}
      </h3>
      <p className="text-sm text-muted-foreground">{intl.formatMessage(mv.lead, { example: written('subject') })}</p>
      {template ? (
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <span>
            {intl.formatMessage(mv.file, {
              name: template.name,
              size: intl.formatNumber(template.sizeBytes / 1024, { maximumFractionDigits: 0 }) + ' KB',
            })}
          </span>
          <Badge tone={template.previewed ? 'ok' : 'warn'}>
            {intl.formatMessage(template.previewed ? m.previewed : m.notPreviewed)}
          </Badge>
          {draft ? (
            <>
              <Button
                variant="outline"
                disabled={busy || template.problems.length > 0}
                onClick={() => void showPreview()}
              >
                {intl.formatMessage(m.preview)}
              </Button>
              <Button variant="ghost" disabled={busy} onClick={() => void remove()}>
                {intl.formatMessage(m.remove)}
              </Button>
            </>
          ) : null}
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(m.none)}</p>
      )}
      {template && template.problems.length > 0 ? (
        <div role="alert" className="rounded-md border border-destructive p-3 text-sm">
          <p className="font-medium text-destructive">{intl.formatMessage(m.problems)}</p>
          <ul className="mt-1 flex list-disc flex-col gap-1 ps-5">
            {template.problems.map((p, i) => (
              <ProblemItem key={`${p.part}-${String(p.paragraph)}-${String(i)}`} problem={p} />
            ))}
          </ul>
        </div>
      ) : null}
      {draft ? (
        <FileUpload
          label={intl.formatMessage(template ? m.replace : m.upload)}
          hint={intl.formatMessage(m.hint)}
          purpose="memo.template"
          owner={owner}
          accept={DOCX}
          maxBytes={MAX_BYTES}
          onUploaded={() => void refresh()}
        />
      ) : null}
      {template && template.tags.length > 0 ? (
        <div className="text-sm">
          <p className="font-medium">{intl.formatMessage(m.used)}</p>
          <p className="font-mono text-xs text-muted-foreground">{template.tags.map(written).join(' ')}</p>
        </div>
      ) : null}
      <details className="text-sm">
        <summary className="cursor-pointer font-medium">{intl.formatMessage(m.available)}</summary>
        <ul className="mt-2 grid gap-1 sm:grid-cols-2">
          {available.map((t) => (
            <li key={t.tag} className="flex items-center justify-between gap-2 rounded border px-2 py-1">
              <span>
                <span className="font-mono text-xs">{written(t.tag)}</span>
                <span className="block text-xs text-muted-foreground">{t.label}</span>
              </span>
              <Button
                variant="ghost"
                size="icon"
                aria-label={intl.formatMessage(mv.copy, { tag: written(t.tag) })}
                onClick={() => {
                  copy(t.tag)
                }}
              >
                <Copy aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      </details>
      {preview ? (
        <FilePreviewDialog
          fileId={preview.fileId}
          versionId={preview.versionId}
          title={intl.formatMessage(m.previewTitle)}
          mediaType="application/vnd.openxmlformats-officedocument.wordprocessingml.document"
          onClose={() => {
            setPreview(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function ProblemItem({ problem }: { problem: TemplateProblemView }) {
  const intl = useIntl()
  return (
    <li>
      {intl.formatMessage(mv.problem, { code: problem.code, tag: problem.tag ?? '' })}
      <span className="block text-xs text-muted-foreground">
        {intl.formatMessage(mv.where, { part: problem.part, paragraph: problem.paragraph, excerpt: problem.excerpt })}
        {problem.suggestion ? (
          <span className="ms-1">{intl.formatMessage(mv.suggestion, { suggestion: problem.suggestion })}</span>
        ) : null}
      </span>
    </li>
  )
}
