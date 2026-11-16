import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { FolderOpen, Pause, Play } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import {
  listImportOriginalsInfiniteOptions,
  listImportOriginalsInfiniteQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import type { ImportBatchView } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { answerText, refusalText } from './codes'
import { PARALLEL, selectFolder } from './folder'
import { bytesText } from './format'
import { beginIntake, useIntakeSession, type IntakeSession } from './intake-session'
import { im, imv } from './messages'
import { ProgressBar } from './ProgressBar'

const REFUSED_PAGE = 100
const REFUSED_SHOWN = 10

/**
 * Step 2, Originals (ux-review L4): choose the unpacked folder; its paths are registered in chunks of 1,000 and each
 * file is sent on its own to `imports.intake`, three at a time, with progress, pause and resume. After a reload, choosing
 * the same folder sends only what is missing or changed. Refused files are listed with their reason. Phones cannot
 * choose a folder, so the step says to use a computer and the rest stays readable.
 */
export function OriginalsStep({ batch, onChanged }: { batch: ImportBatchView; onChanged: () => void }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const session = useIntakeSession(batch.id)
  const input = useRef<HTMLInputElement>(null)
  const editable = batch.allowedActions.includes('REGISTER_ORIGINALS')
  const refusedQuery = { path: { id: batch.id }, query: { state: ['REFUSED' as const], limit: REFUSED_PAGE } }
  const phase = session?.queue?.getSnapshot().phase

  useEffect(() => {
    // `webkitdirectory` is not in React's attribute list.
    input.current?.setAttribute('webkitdirectory', '')
    input.current?.setAttribute('directory', '')
  }, [editable])

  useEffect(() => {
    if (phase === 'done' || phase === 'stopped' || phase === 'paused') {
      onChanged()
      void queryClient.invalidateQueries({ queryKey: listImportOriginalsInfiniteQueryKey(refusedQuery) })
    }
    // Only when the phase changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase])

  const o = batch.originals
  return (
    <section aria-labelledby="step-originals" className="flex flex-col gap-4 rounded-md border p-4">
      <h2 id="step-originals" className="text-base font-semibold">
        {intl.formatMessage(im.stepOriginals)}
      </h2>
      <p className="text-sm" data-testid="originals-counts">
        {intl.formatMessage(imv.received, {
          received: o.received,
          expected: o.expected,
          refused: o.refused,
          size: bytesText(intl, o.receivedBytes),
        })}
      </p>
      {editable ? (
        <>
          <p className="text-sm font-medium sm:hidden">{intl.formatMessage(im.phone)}</p>
          <div className="flex flex-col gap-3 max-sm:hidden">
            <div className="flex flex-wrap items-center gap-3">
              <p className="min-w-0 flex-[1_1_20rem] text-sm">{intl.formatMessage(im.originalsLead)}</p>
              <Button
                variant="outline"
                disabled={session?.stage === 'registering' || phase === 'sending' || phase === 'pausing'}
                onClick={() => input.current?.click()}
              >
                <FolderOpen aria-hidden="true" />
                {intl.formatMessage(im.chooseFolder)}
              </Button>
              <input
                ref={input}
                type="file"
                multiple
                tabIndex={-1}
                aria-hidden="true"
                data-testid="folder-input"
                className="sr-only"
                onChange={(e) => {
                  const files = Array.from(e.target.files ?? [])
                  e.target.value = ''
                  if (files.length > 0) void beginIntake(batch.id, selectFolder(files))
                }}
              />
            </div>
            {!session && (o.received > 0 || o.refused > 0) ? (
              <p className="text-sm text-muted-foreground">
                {intl.formatMessage(imv.alreadyHere, { count: o.received })}
              </p>
            ) : null}
            {session ? <SessionView session={session} /> : null}
          </div>
        </>
      ) : null}
      {o.refused > 0 || (session?.queue?.getSnapshot().refused.length ?? 0) > 0 ? (
        <RefusedList batchId={batch.id} query={refusedQuery} count={o.refused} />
      ) : null}
    </section>
  )
}

function SessionView({ session }: { session: IntakeSession }) {
  const intl = useIntl()
  const [showLarge, setShowLarge] = useState(false)
  const { selection } = session
  const snapshot = session.queue?.getSnapshot()
  return (
    <div className="flex flex-col gap-2 rounded-md bg-muted/40 p-3 text-sm" data-testid="intake-session">
      <p>
        {intl.formatMessage(imv.folder, {
          name: selection.folder,
          files: selection.files.length,
          size: bytesText(intl, selection.bytes),
        })}
      </p>
      {selection.skipped.length > 0 || selection.tooLarge.length > 0 || selection.invalid.length > 0 ? (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-muted-foreground">
          {selection.skipped.length > 0 ? (
            <span>{intl.formatMessage(imv.skipped, { count: selection.skipped.length })}</span>
          ) : null}
          {selection.tooLarge.length > 0 ? (
            <span className="inline-flex items-center gap-2">
              {intl.formatMessage(imv.tooLarge, { count: selection.tooLarge.length })}
              <Button
                variant="link"
                aria-expanded={showLarge}
                onClick={() => {
                  setShowLarge((v) => !v)
                }}
              >
                {intl.formatMessage(showLarge ? im.hide : im.view)}
              </Button>
            </span>
          ) : null}
          {selection.invalid.length > 0 ? (
            <span>{intl.formatMessage(imv.invalid, { count: selection.invalid.length })}</span>
          ) : null}
        </div>
      ) : null}
      {showLarge ? (
        <ul className="ms-4 list-disc">
          {selection.tooLarge.map((f) => (
            <li key={f.path}>
              <bdi dir="ltr">{f.path}</bdi>
            </li>
          ))}
        </ul>
      ) : null}
      {session.stage === 'registering' ? (
        <p role="status">
          {intl.formatMessage(imv.registering, { done: session.registered, total: selection.files.length })}
        </p>
      ) : null}
      {session.stage === 'failed' ? (
        <p role="alert" className="font-medium text-destructive">
          {intl.formatMessage(imv.registerFailed, { reason: answerText(intl, session.error) })}
        </p>
      ) : null}
      {session.queue && snapshot ? (
        <QueueView
          snapshot={snapshot}
          alreadyHere={session.alreadyHere}
          onPause={() => {
            session.queue?.pause()
          }}
          onResume={() => {
            session.queue?.start()
          }}
        />
      ) : null}
    </div>
  )
}

function QueueView({
  snapshot,
  alreadyHere,
  onPause,
  onResume,
}: {
  snapshot: NonNullable<ReturnType<NonNullable<IntakeSession['queue']>['getSnapshot']>>
  alreadyHere: number
  onPause: () => void
  onResume: () => void
}) {
  const intl = useIntl()
  const percent = snapshot.bytes > 0 ? Math.round((snapshot.sentBytes / snapshot.bytes) * 100) : 100
  // The live region changes at most every 5% (design E06 §16).
  const announced = Math.floor(percent / 5) * 5
  const left = snapshot.rate
    ? Math.max(1, Math.round((snapshot.bytes - snapshot.sentBytes) / snapshot.rate / 60))
    : undefined
  const line = intl.formatMessage(imv.sending, {
    done: snapshot.sentFiles,
    total: snapshot.files,
    sent: bytesText(intl, snapshot.sentBytes),
    size: bytesText(intl, snapshot.bytes),
  })
  return (
    <div className="flex flex-col gap-2">
      {alreadyHere > 0 ? (
        <p className="text-muted-foreground">{intl.formatMessage(imv.alreadySkipped, { count: alreadyHere })}</p>
      ) : null}
      <ProgressBar label={line} percent={percent} />
      <p data-testid="intake-progress">{line}</p>
      <p aria-live="polite" className="sr-only">
        {intl.formatMessage(imv.sending, {
          done: Math.round((snapshot.files * announced) / 100),
          total: snapshot.files,
          sent: intl.formatNumber(announced / 100, { style: 'percent' }),
          size: bytesText(intl, snapshot.bytes),
        })}
      </p>
      {snapshot.phase === 'sending' && left !== undefined ? (
        <p className="text-muted-foreground">
          {intl.formatMessage(imv.timeLeft, { minutes: left, parallel: PARALLEL })}
        </p>
      ) : null}
      <div className="flex flex-wrap items-center gap-3">
        {snapshot.phase === 'sending' ? (
          <Button variant="outline" onClick={onPause}>
            <Pause aria-hidden="true" />
            {intl.formatMessage(im.pause)}
          </Button>
        ) : null}
        {snapshot.phase === 'paused' ? (
          <Button variant="outline" onClick={onResume}>
            <Play aria-hidden="true" />
            {intl.formatMessage(im.resume)}
          </Button>
        ) : null}
        <p role="status">
          {snapshot.phase === 'pausing'
            ? intl.formatMessage(im.pausing)
            : snapshot.phase === 'paused'
              ? intl.formatMessage(im.paused)
              : snapshot.phase === 'done'
                ? intl.formatMessage(im.sendingDone)
                : snapshot.phase === 'stopped'
                  ? intl.formatMessage(imv.stopped, { reason: answerText(intl, snapshot.stopReason) })
                  : null}
        </p>
      </div>
      {snapshot.failed.length > 0 ? (
        <p role="alert" className="font-medium text-destructive">
          {intl.formatMessage(im.failedSend)}
        </p>
      ) : null}
    </div>
  )
}

function RefusedList({
  batchId,
  query,
  count,
}: {
  batchId: string
  query: Parameters<typeof listImportOriginalsInfiniteOptions>[0]
  count: number
}) {
  const intl = useIntl()
  const [all, setAll] = useState(false)
  const refused = useInfiniteQuery({
    ...listImportOriginalsInfiniteOptions(query),
    initialPageParam: query,
    getNextPageParam: (last) => (last.next ? { ...query, query: { ...query.query, cursor: last.next } } : undefined),
  })
  const entries = refused.data?.pages.flatMap((p) => p.files) ?? []
  const shown = all ? entries : entries.slice(0, REFUSED_SHOWN)
  return (
    <section aria-labelledby={`refused-${batchId}`} className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-3">
        <h3 id={`refused-${batchId}`} className="text-sm font-semibold">
          {intl.formatMessage(imv.refused, { count: Math.max(count, entries.length) })}
        </h3>
        {!all && entries.length > REFUSED_SHOWN ? (
          <Button
            variant="link"
            onClick={() => {
              setAll(true)
            }}
          >
            {intl.formatMessage(im.viewAll)}
          </Button>
        ) : null}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">{intl.formatMessage(im.refusedCaption)}</caption>
          <thead>
            <tr className="text-start text-muted-foreground">
              <th scope="col" className="py-1 pe-3 text-start font-medium">
                {intl.formatMessage(im.colPath)}
              </th>
              <th scope="col" className="py-1 text-start font-medium">
                {intl.formatMessage(im.colReason)}
              </th>
            </tr>
          </thead>
          <tbody>
            {shown.map((entry) => (
              <tr key={entry.intakeId} className="border-t">
                <td className="py-1 pe-3 font-mono text-xs">
                  <bdi dir="ltr">{entry.path}</bdi>
                </td>
                <td className="py-1">{refusalText(intl, entry.refusal)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {all && refused.hasNextPage ? (
        <Button variant="outline" disabled={refused.isFetchingNextPage} onClick={() => void refused.fetchNextPage()}>
          {intl.formatMessage(im.loadMore)}
        </Button>
      ) : null}
    </section>
  )
}
