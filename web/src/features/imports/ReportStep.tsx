import { useQuery } from '@tanstack/react-query'
import { Download } from 'lucide-react'
import { useState } from 'react'
import { useIntl, type MessageDescriptor } from 'react-intl'
import { getImportReportOptions, listClassificationLevelsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { closeImport } from '@/api/generated/sdk.gen'
import type { ImportBatchView, ImportReportPartView } from '@/api/generated/types.gen'
import type { Problem } from '@/api/http'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast-context'
import { answerText } from './codes'
import { im, imv } from './messages'
import { ReasonDialog } from './ReasonDialog'

const partLabels: Record<string, MessageDescriptor> = {
  SUMMARY: im.partSUMMARY,
  DETAIL: im.partDETAIL,
  MANIFEST: im.partMANIFEST,
  SOURCE_WORKBOOK: im.partSOURCE_WORKBOOK,
  SOURCE_MEMOS: im.partSOURCE_MEMOS,
  SOURCE_FILES: im.partSOURCE_FILES,
  SOURCE_APPROVALS: im.partSOURCE_APPROVALS,
}

/**
 * Step 6, Report (ux-review N3): balanced, complete and valid, the totals and the balance lines that fail, and the
 * parts to download through the files API (each download is audited). Closing asks for a reason and deletes the staged
 * copies; the report and the imported memos stay.
 */
export function ReportStep({
  batch,
  onChanged,
  onRefused,
}: {
  batch: ImportBatchView
  onChanged: (next: ImportBatchView) => void
  onRefused: (error: unknown) => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const ready = batch.state === 'COMPLETED' || batch.state === 'CLOSED'
  const report = useQuery({ ...getImportReportOptions({ path: { id: batch.id } }), enabled: ready })
  const levels = useQuery(listClassificationLevelsOptions())
  const [closing, setClosing] = useState(false)
  const level = batch.maxRank != null ? levels.data?.find((l) => l.rank === batch.maxRank) : undefined

  let body: React.ReactNode
  if (!ready) {
    body = (
      <p className="text-sm">{intl.formatMessage(batch.state === 'VERIFYING' ? im.reportPending : im.notImported)}</p>
    )
  } else if (report.isPending) {
    body = <LoadingState rows={3} />
  } else if (report.isError) {
    const problem = report.error as Problem | undefined
    body =
      problem?.status === 403 || problem?.status === 409 ? (
        <p className="text-sm">{answerText(intl, problem.reason ?? 'IMPORTER_NOT_CLEARED')}</p>
      ) : (
        <ErrorState onRetry={() => void report.refetch()} />
      )
  } else {
    const r = report.data
    const t = r.totals
    body = (
      <div className="flex flex-col gap-3 text-sm" data-testid="report">
        <div className="flex flex-wrap gap-2">
          <Badge tone={r.balanced ? 'ok' : 'danger'}>
            {intl.formatMessage(r.balanced ? im.balanced : im.unbalanced)}
          </Badge>
          <Badge tone={r.complete ? 'ok' : 'warn'}>
            {intl.formatMessage(r.complete ? im.complete : im.incomplete)}
          </Badge>
          <Badge tone={r.valid ? 'ok' : 'danger'}>{intl.formatMessage(r.valid ? im.valid : im.invalid)}</Badge>
        </div>
        {r.failing.length > 0 ? (
          <p role="alert" className="font-medium text-destructive">
            {intl.formatMessage(imv.failing as MessageDescriptor, {
              lines: <bdi dir="ltr">{r.failing.join(', ')}</bdi>,
            })}
          </p>
        ) : null}
        <ul className="flex flex-col gap-1">
          <li>
            {intl.formatMessage(imv.reportRows, {
              read: t.rows.read,
              imported: t.rows.imported,
              rejected: t.rows.rejected,
              skipped: t.rows.skipped,
              failed: t.rows.failed,
              notStarted: t.rows.notStarted,
            })}
          </li>
          <li>
            {intl.formatMessage(imv.reportFiles, {
              listed: t.files.listed,
              matched: t.files.matched,
              notReceived: t.files.notReceived,
              ambiguous: t.files.ambiguous,
            })}
          </li>
          <li>
            {intl.formatMessage(imv.reportOriginals, {
              registered: t.originals.registered.files,
              imported: t.originals.imported.files,
              heldBack: t.originals.heldBack.files,
              unreferenced: t.originals.unreferenced.files,
              refused: t.originals.refused.files,
              neverUploaded: t.originals.neverUploaded.files,
            })}
          </li>
          {Object.entries(t.renditions).map(([kind, n]) => (
            <li key={kind}>
              {intl.formatMessage(imv.reportRenditions, {
                kind,
                done: n.done,
                notPossible: n.notPossible,
                notNeeded: n.notNeeded,
                failed: n.failed,
                pending: n.pending,
              })}
            </li>
          ))}
          <li>
            {intl.formatMessage(imv.reportFixity, { verified: t.fixity.verified, mismatches: t.fixity.mismatches })}
          </li>
          {t.storedNotBound > 0 ? <li>{intl.formatMessage(imv.storedNotBound, { count: t.storedNotBound })}</li> : null}
        </ul>
        <div className="flex flex-wrap gap-2">
          {r.parts.map((part) => (
            <PartLink key={`${part.kind}-${part.versionId}`} part={part} />
          ))}
        </div>
        {level ? (
          <p className="text-muted-foreground">{intl.formatMessage(imv.classified, { level: level.name })}</p>
        ) : null}
      </div>
    )
  }

  return (
    <section aria-labelledby="step-report" className="flex flex-col gap-4 rounded-md border p-4">
      <div className="flex flex-wrap items-center gap-3">
        <h2 id="step-report" className="me-auto text-base font-semibold">
          {intl.formatMessage(im.reportTitle)}
        </h2>
        {batch.allowedActions.includes('CLOSE') ? (
          <Button
            variant="outline"
            data-testid="close-batch"
            onClick={() => {
              setClosing(true)
            }}
          >
            {intl.formatMessage(im.closeBatch)}
          </Button>
        ) : null}
      </div>
      {body}
      {closing ? (
        <ReasonDialog
          title={intl.formatMessage(im.closeTitle)}
          text={intl.formatMessage(im.closeText)}
          confirm={intl.formatMessage(im.closeBatch)}
          required
          onClose={() => {
            setClosing(false)
          }}
          onConfirm={async (reason) => {
            try {
              const next = await closeImport({ path: { id: batch.id }, body: { reason }, throwOnError: true })
              setClosing(false)
              onChanged(next.data)
              toast.show(intl.formatMessage(im.closed), 'success')
            } catch (error) {
              setClosing(false)
              onRefused(error)
            }
          }}
        />
      ) : null}
    </section>
  )
}

function PartLink({ part }: { part: ImportReportPartView }) {
  const intl = useIntl()
  const descriptor = partLabels[part.kind]
  const label = descriptor ? intl.formatMessage(descriptor) : part.name
  return (
    <Button variant="outline" asChild>
      <a
        href={`/api/v1/files/${part.fileId}/versions/${part.versionId}/content`}
        download
        aria-label={intl.formatMessage(imv.download, { part: label })}
        data-testid={`report-part-${part.kind}`}
      >
        <Download aria-hidden="true" />
        {label}
      </a>
    </Button>
  )
}
