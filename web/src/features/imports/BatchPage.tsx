import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Check } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getImportBatchOptions,
  getImportBatchQueryKey,
  listImportBatchesInfiniteQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { abandonImport } from '@/api/generated/sdk.gen'
import type { ImportBatchView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast-context'
import { cn } from '@/lib/utils'
import { answerText } from './codes'
import { BUSY, stateLabels, stateTone } from './format'
import { BatchSettingsDialog } from './ImportsPage'
import { DryRunStep } from './DryRunStep'
import { ImportStep } from './ImportStep'
import { MappingsStep } from './MappingsStep'
import { MetadataStep } from './MetadataStep'
import { im, imv } from './messages'
import { OriginalsStep } from './OriginalsStep'
import { ReasonDialog } from './ReasonDialog'
import { ReportStep } from './ReportStep'
import { defaultStep, STEPS, stepDone, stepLabels, type StepId } from './steps'

/**
 * `/admin/imports/$batchId` (ux-review L3 to N3): the batch's settings and state, and a stepper from the metadata to
 * the report. The step is in the address (`?step=`), so a link or reload opens it. The page polls while a job works.
 */
export function BatchPage({ batchId, step }: { batchId: string; step?: StepId }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const key = getImportBatchQueryKey({ path: { id: batchId } })
  const batch = useQuery({
    ...getImportBatchOptions({ path: { id: batchId } }),
    refetchInterval: (query) => (query.state.data && BUSY.has(query.state.data.state) ? 3000 : false),
  })
  const [editing, setEditing] = useState(false)
  const [abandoning, setAbandoning] = useState(false)

  if (batch.isPending) return <LoadingState />
  if (batch.isError) {
    const status = (batch.error as Problem | undefined)?.status
    return status === 404 || status === 403 ? (
      <EmptyState title={intl.formatMessage(im.notFound)} />
    ) : (
      <ErrorState onRetry={() => void batch.refetch()} />
    )
  }
  const view = batch.data
  const allowed = new Set(view.allowedActions)
  const current = step ?? defaultStep(view)

  const changed = (next: ImportBatchView) => {
    queryClient.setQueryData(key, next)
    void queryClient.invalidateQueries({ queryKey: listImportBatchesInfiniteQueryKey({ query: { limit: 50 } }) })
  }
  /** Shows a refusal in words; a stale or moved batch is reloaded. */
  const refused = (error: unknown) => {
    const problem: Problem | undefined = isProblem(error) ? error : undefined
    const code = problem?.errors?.[0]?.code ?? problem?.reason
    if (problem?.status === 412) toast.show(intl.formatMessage(im.conflict), 'error')
    else toast.show(code ? answerText(intl, code) : intl.formatMessage(im.failed), 'error')
    void batch.refetch()
  }

  return (
    <section aria-labelledby="batch-title">
      <PageHeader
        title={
          <span id="batch-title">{intl.formatMessage(imv.header, { name: view.name, source: view.sourceCode })}</span>
        }
        description={view.timeZone}
        status={
          <Badge tone={stateTone[view.state]}>
            <span data-testid="batch-state">
              {intl.formatMessage(imv.stateLabel, { state: intl.formatMessage(stateLabels[view.state]) })}
            </span>
          </Badge>
        }
        actions={
          <>
            {allowed.has('EDIT') ? (
              <Button
                variant="outline"
                onClick={() => {
                  setEditing(true)
                }}
              >
                {intl.formatMessage(im.editSettings)}
              </Button>
            ) : null}
            {allowed.has('ABANDON') ? (
              <Button
                variant="outline"
                onClick={() => {
                  setAbandoning(true)
                }}
              >
                {intl.formatMessage(im.abandon)}
              </Button>
            ) : null}
          </>
        }
      />
      <nav aria-label={intl.formatMessage(im.steps)} className="mb-5">
        <ol className="flex flex-wrap gap-2">
          {STEPS.map((id, i) => {
            const done = stepDone(view, id)
            const label = intl.formatMessage(stepLabels[id])
            const content = (
              <>
                <span aria-hidden="true" className="tabular-nums">
                  {intl.formatNumber(i + 1)}
                </span>
                <span>{done ? intl.formatMessage(imv.stepDone, { step: label }) : label}</span>
                {done ? <Check aria-hidden="true" className="size-4" /> : null}
              </>
            )
            const box = 'inline-flex min-h-10 items-center gap-2 rounded-md border px-3 text-sm'
            return (
              <li key={id}>
                {id === current ? (
                  // The step shown is not a link to itself; aria-current says where the reader is (design E06 §16).
                  <span
                    aria-current="step"
                    data-testid={`step-${id}`}
                    className={cn(box, 'border-primary bg-accent font-semibold text-accent-foreground')}
                  >
                    {content}
                  </span>
                ) : (
                  <Link
                    to="/admin/imports/$batchId"
                    params={{ batchId }}
                    search={{ step: id }}
                    data-testid={`step-${id}`}
                    className={cn(box, 'hover:bg-accent')}
                  >
                    {content}
                  </Link>
                )}
              </li>
            )
          })}
        </ol>
      </nav>
      {view.state === 'OPEN' && view.dryRunAt ? (
        <p role="note" className="mb-4 rounded-md bg-status-warn-bg p-3 text-sm text-status-warn-fg">
          {intl.formatMessage(im.stale)}
        </p>
      ) : null}
      {view.state === 'ABANDONED' || view.state === 'CLOSED' ? (
        <p role="note" className="mb-4 rounded-md bg-muted p-3 text-sm">
          {view.closeReason && view.closedAt
            ? intl.formatMessage(imv.closedReason, {
                date: intl.formatDate(view.closedAt, { dateStyle: 'medium' }),
                reason: view.closeReason,
              })
            : intl.formatMessage(im.readOnly)}
        </p>
      ) : null}
      <div>
        {current === 'metadata' ? <MetadataStep batch={view} onChanged={() => void batch.refetch()} /> : null}
        {current === 'originals' ? <OriginalsStep batch={view} onChanged={() => void batch.refetch()} /> : null}
        {current === 'mappings' ? <MappingsStep batch={view} onChanged={() => void batch.refetch()} /> : null}
        {current === 'dryRun' ? <DryRunStep batch={view} onChanged={changed} onRefused={refused} /> : null}
        {current === 'import' ? <ImportStep batch={view} onChanged={changed} onRefused={refused} /> : null}
        {current === 'report' ? <ReportStep batch={view} onChanged={changed} onRefused={refused} /> : null}
      </div>
      {editing ? (
        <BatchSettingsDialog
          batch={view}
          onClose={() => {
            setEditing(false)
          }}
          onSaved={(saved) => {
            setEditing(false)
            changed(saved)
            toast.show(`${intl.formatMessage(im.saved)} ${intl.formatMessage(im.rerun)}`, 'success')
          }}
        />
      ) : null}
      {abandoning ? (
        <ReasonDialog
          title={intl.formatMessage(im.abandonTitle)}
          text={intl.formatMessage(im.abandonText)}
          confirm={intl.formatMessage(im.abandon)}
          required={false}
          onClose={() => {
            setAbandoning(false)
          }}
          onConfirm={async (reason) => {
            try {
              const next = await abandonImport({
                path: { id: batchId },
                body: { reason: reason || null },
                throwOnError: true,
              })
              setAbandoning(false)
              changed(next.data)
              toast.show(intl.formatMessage(im.abandoned), 'success')
            } catch (error) {
              setAbandoning(false)
              refused(error)
            }
          }}
        />
      ) : null}
    </section>
  )
}
