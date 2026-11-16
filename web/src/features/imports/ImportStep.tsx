import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { listImportProblemsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { finishImport, resumeImport, startImport, stopImport } from '@/api/generated/sdk.gen'
import type { ImportBatchView } from '@/api/generated/types.gen'
import { isProblem, PROBLEM, type Problem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { AlertDialog, Dialog } from '@/components/ui/dialog'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { answerText } from './codes'
import { im, imv } from './messages'
import { ProgressBar } from './ProgressBar'

/**
 * Step 5, Import (ux-review N1, N2): the confirmation with the counts and what will not happen, then the step-up; the
 * progress with Stop; a stopped import's Resume (step-up again) and "Finish without the rest"; the verification that
 * follows. Refusals of the start are said in words in the dialog.
 */
export function ImportStep({
  batch,
  onChanged,
  onRefused,
}: {
  batch: ImportBatchView
  onChanged: (next: ImportBatchView) => void
  onRefused: (error: unknown) => void
}) {
  const intl = useIntl()
  const withStepUp = useWithStepUp()
  const allowed = new Set(batch.allowedActions)
  const [starting, setStarting] = useState(false)
  const [finishing, setFinishing] = useState(false)
  const [busy, setBusy] = useState(false)

  const act = async (call: () => Promise<{ data: ImportBatchView }>) => {
    setBusy(true)
    try {
      onChanged((await call()).data)
    } catch (error) {
      if (problemOf(error)?.type !== PROBLEM.stepUpRequired) onRefused(error)
    } finally {
      setBusy(false)
    }
  }
  const path = { id: batch.id }
  const progress = batch.progress

  return (
    <section aria-labelledby="step-import" className="flex flex-col gap-4 rounded-md border p-4">
      <h2 id="step-import" className="text-base font-semibold">
        {intl.formatMessage(im.stepImport)}
      </h2>
      {!progress ? (
        <div className="flex flex-col items-start gap-3">
          <p className="text-sm">
            {allowed.has('START') ? intl.formatMessage(im.startFinal) : intl.formatMessage(im.notStartable)}
          </p>
          {allowed.has('START') ? (
            <Button
              data-testid="start-import"
              onClick={() => {
                setStarting(true)
              }}
            >
              {intl.formatMessage(im.start)}
            </Button>
          ) : null}
        </div>
      ) : (
        <Progress batch={batch} />
      )}
      <div className="flex flex-wrap items-center gap-3">
        {allowed.has('STOP') ? (
          <Button
            variant="outline"
            disabled={busy}
            onClick={() => void act(() => stopImport({ path, throwOnError: true }))}
          >
            {intl.formatMessage(im.stop)}
          </Button>
        ) : null}
        {allowed.has('RESUME') ? (
          <Button
            disabled={busy}
            onClick={() => void act(() => withStepUp(() => resumeImport({ path, throwOnError: true })))}
          >
            {intl.formatMessage(im.resumeImport)}
          </Button>
        ) : null}
        {allowed.has('FINISH') ? (
          <Button
            variant="outline"
            disabled={busy}
            onClick={() => {
              setFinishing(true)
            }}
          >
            {intl.formatMessage(im.finish)}
          </Button>
        ) : null}
      </div>
      <p role="status" className="text-sm" data-testid="import-status">
        {batch.state === 'STOPPING'
          ? intl.formatMessage(im.stopping)
          : batch.state === 'STOPPED'
            ? intl.formatMessage(im.stopped)
            : batch.state === 'VERIFYING'
              ? intl.formatMessage(im.verifying)
              : null}
      </p>
      {starting ? (
        <StartDialog
          batch={batch}
          onClose={() => {
            setStarting(false)
          }}
          onStarted={(next) => {
            setStarting(false)
            onChanged(next)
          }}
        />
      ) : null}
      <AlertDialog
        open={finishing}
        onOpenChange={setFinishing}
        title={intl.formatMessage(im.finishTitle)}
        description={intl.formatMessage(im.finishText)}
        confirmLabel={intl.formatMessage(im.finish)}
        cancelLabel={intl.formatMessage(im.cancel)}
        destructive
        onConfirm={() => void act(() => finishImport({ path, throwOnError: true }))}
      />
    </section>
  )
}

function problemOf(error: unknown): Problem | undefined {
  return isProblem(error) ? error : undefined
}

/** The progress of a started import (ux-review N2): counts, rate, time left and the throttle. */
function Progress({ batch }: { batch: ImportBatchView }) {
  const intl = useIntl()
  const p = batch.progress
  if (!p) return null
  const percent = p.rows > 0 ? Math.round((p.done / p.rows) * 100) : 100
  const minutes = p.ratePerMinute > 0 ? Math.max(1, Math.round(p.left / p.ratePerMinute)) : undefined
  const line = intl.formatMessage(imv.progressLine, { done: p.done, rows: p.rows })
  return (
    <div className="flex flex-col gap-2 text-sm" data-testid="import-progress">
      <p className="font-medium">
        {batch.state === 'IMPORTING' && minutes !== undefined
          ? intl.formatMessage(imv.progressEnd, { done: p.done, rows: p.rows, minutes })
          : line}
      </p>
      <ProgressBar label={line} percent={percent} />
      {p.throttled && p.throttledSince ? (
        <p role="status">
          {intl.formatMessage(imv.throttled, { time: intl.formatTime(p.throttledSince, { timeStyle: 'short' }) })}
        </p>
      ) : null}
      <p>
        {intl.formatMessage(imv.progressCounts, {
          imported: p.imported,
          skipped: p.skipped,
          rejected: p.rejected,
          failed: p.failed,
          notStarted: p.notStarted,
        })}
      </p>
    </div>
  )
}

/** The start's confirmation (ux-review N1), then the step-up; `expectedRows` is the READY count shown here. */
function StartDialog({
  batch,
  onClose,
  onStarted,
}: {
  batch: ImportBatchView
  onClose: () => void
  onStarted: (next: ImportBatchView) => void
}) {
  const intl = useIntl()
  const withStepUp = useWithStepUp()
  const counts = useQuery(listImportProblemsOptions({ path: { id: batch.id }, query: { limit: 1 } }))
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const ready = batch.rows.READY ?? 0

  const start = async () => {
    setBusy(true)
    setError(undefined)
    try {
      const next = await withStepUp(() =>
        startImport({
          path: { id: batch.id },
          headers: { 'If-Match': `"${String(batch.version)}"` },
          body: { expectedRows: ready },
          throwOnError: true,
        }),
      )
      onStarted(next.data)
    } catch (e) {
      const problem = problemOf(e)
      if (problem?.type === PROBLEM.stepUpRequired) return
      const code = problem?.errors?.[0]?.code ?? problem?.reason
      setError(problem?.status === 412 ? intl.formatMessage(im.conflict) : answerText(intl, code))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(imv.startTitle, { count: ready })}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(im.cancel)}
          </Button>
          <Button disabled={busy || Boolean(error)} onClick={() => void start()} data-testid="start-confirm">
            {intl.formatMessage(im.startConfirm)}
          </Button>
        </>
      }
    >
      <ul className="flex list-disc flex-col gap-2 ps-5 text-sm">
        <li>
          {intl.formatMessage(imv.startRows, {
            ready,
            received: batch.originals.received,
            rejected: batch.rows.REJECTED ?? 0,
          })}
        </li>
        {(batch.problems.WARNING ?? 0) > 0 ? (
          <li>
            {intl.formatMessage(imv.startWarnings, {
              warnings: batch.problems.WARNING ?? 0,
              notLinked: counts.data?.byCode.PERSON_NOT_LINKED ?? 0,
            })}
          </li>
        ) : null}
        <li>{intl.formatMessage(im.startFinal)}</li>
        <li>{intl.formatMessage(im.startUndo)}</li>
      </ul>
      {error ? (
        <p role="alert" className="mt-3 text-sm font-medium text-destructive" data-testid="start-error">
          {error}
        </p>
      ) : null}
    </Dialog>
  )
}
