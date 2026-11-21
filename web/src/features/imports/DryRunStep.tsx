import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query'
import { Play } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useIntl } from 'react-intl'
import {
  listImportProblemsInfiniteOptions,
  listImportProblemsOptions,
  listImportRowsInfiniteOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import { startImportDryRun } from '@/api/generated/sdk.gen'
import type {
  ImportBatchView,
  ImportProblemView,
  ImportRowView,
  ListImportProblemsData,
} from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/misc'
import { useLocale } from '@/i18n/LocaleProvider'
import { directionOf } from '@/i18n/locales'
import { problemText } from './codes'
import { levelLabels, resultLabels, sheetLabels } from './format'
import { im, imv } from './messages'
import { TableScroll } from '@/components/page/TableScroll'

type Level = ImportProblemView['level']
type Sheet = NonNullable<ImportProblemView['sheet']>
type Result = ImportRowView['result']
const LEVELS: Level[] = ['BATCH', 'ERROR', 'WARNING', 'FILE', 'INFO']
const SHEETS: Sheet[] = ['MEMOS', 'FILES', 'APPROVALS', 'ORIGINALS', 'BATCH']
const RESULTS: Result[] = ['READY', 'REJECTED', 'PENDING', 'IMPORTED', 'SKIPPED', 'FAILED', 'NOT_STARTED']
const PAGE = 100

/**
 * Step 4, Dry run (ux-review M2): run it, follow it while it runs (the page polls the batch), then read its totals and
 * the problems, filtered by level, code, sheet and row, each with the sheet's row number, and the rows with their
 * results. Problems never show a subject; a row's subject shows only within the reader's clearance.
 */
export function DryRunStep({
  batch,
  onChanged,
  onRefused,
}: {
  batch: ImportBatchView
  onChanged: (next: ImportBatchView) => void
  onRefused: (error: unknown) => void
}) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const running = batch.state === 'DRY_RUN'
  // Radix Tabs set their own `dir` (ltr unless told), which would turn the tables left to right in Arabic.
  const dir = directionOf(useLocale().locale)
  const counts = useQuery({
    ...listImportProblemsOptions({ path: { id: batch.id }, query: { limit: 1 } }),
    enabled: Boolean(batch.dryRunAt) && !running,
  })
  const byCode = counts.data?.byCode ?? {}

  useEffect(() => {
    // A new dry run (or a change) replaces the problems and rows.
    void queryClient.invalidateQueries({ queryKey: [{ _id: 'listImportProblems' }] })
    void queryClient.invalidateQueries({ queryKey: [{ _id: 'listImportRows' }] })
  }, [batch.version, queryClient])

  const run = async () => {
    setBusy(true)
    try {
      const next = await startImportDryRun({ path: { id: batch.id }, throwOnError: true })
      onChanged(next.data)
    } catch (error) {
      onRefused(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="step-dry-run" className="flex flex-col gap-4 rounded-md border p-4">
      <div className="flex flex-wrap items-center gap-3">
        <h2 id="step-dry-run" className="me-auto text-base font-semibold">
          {intl.formatMessage(im.stepDryRun)}
        </h2>
        {batch.allowedActions.includes('DRY_RUN') ? (
          <Button disabled={busy || running} onClick={() => void run()} data-testid="run-dry-run">
            <Play aria-hidden="true" />
            {intl.formatMessage(im.runDryRun)}
          </Button>
        ) : null}
      </div>
      <p role="status" className="text-sm" data-testid="dry-run-status">
        {running
          ? intl.formatMessage(im.dryRunning)
          : batch.dryRunAt
            ? intl.formatMessage(imv.lastRun, {
                date: intl.formatDate(batch.dryRunAt, { dateStyle: 'medium', timeStyle: 'short' }),
              })
            : intl.formatMessage(im.dryRunNever)}
      </p>
      {batch.dryRunAt && !running ? (
        <>
          <div className="flex flex-col gap-1 text-sm" data-testid="dry-run-totals">
            <p className="font-medium">
              {intl.formatMessage(imv.totals, {
                ready: batch.rows.READY ?? 0,
                rejected: batch.rows.REJECTED ?? 0,
                warnings: batch.problems.WARNING ?? 0,
                already: byCode.ALREADY_IMPORTED ?? 0,
              })}
            </p>
            <p>
              {intl.formatMessage(imv.originals, {
                received: batch.originals.received,
                unused: byCode.FILE_UNREFERENCED ?? 0,
                refused: batch.originals.refused,
              })}
            </p>
          </div>
          <Tabs dir={dir} defaultValue="problems">
            <TabsList aria-label={intl.formatMessage(im.stepDryRun)}>
              <TabsTrigger value="problems">{intl.formatMessage(im.problems)}</TabsTrigger>
              <TabsTrigger value="rows">{intl.formatMessage(im.rows)}</TabsTrigger>
            </TabsList>
            <TabsContent value="problems" className="pt-4">
              <ProblemList batchId={batch.id} />
            </TabsContent>
            <TabsContent value="rows" className="pt-4">
              <RowList batchId={batch.id} />
            </TabsContent>
          </Tabs>
        </>
      ) : null}
    </section>
  )
}

/** The problems with their filters (level, code, sheet, row) and counts; 100 a page. */
export function ProblemList({ batchId }: { batchId: string }) {
  const intl = useIntl()
  const [level, setLevel] = useState<Level | ''>('')
  const [code, setCode] = useState('')
  const [sheet, setSheet] = useState<Sheet | ''>('')
  const [row, setRow] = useState('')
  const rowNumber = /^\d+$/.test(row.trim()) ? Number(row.trim()) : undefined
  const query: { path: { id: string }; query: NonNullable<ListImportProblemsData['query']> } = {
    path: { id: batchId },
    query: {
      limit: PAGE,
      ...(level ? { level: [level] } : {}),
      ...(code ? { code: [code] } : {}),
      ...(sheet ? { sheet } : {}),
      ...(rowNumber !== undefined ? { row: rowNumber } : {}),
    },
  }
  const problems = useInfiniteQuery({
    ...listImportProblemsInfiniteOptions(query),
    initialPageParam: query,
    getNextPageParam: (last) => (last.next ? { ...query, query: { ...query.query, cursor: last.next } } : undefined),
  })
  const first = problems.data?.pages[0]
  const items = problems.data?.pages.flatMap((p) => p.problems) ?? []
  const byLevel = first?.byLevel ?? {}
  const byCode = first?.byCode ?? {}

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-3" data-testid="problem-filters">
        <Field label={intl.formatMessage(im.level)} className="w-44">
          {(p) => (
            <Select
              {...p}
              value={level}
              onChange={(e) => {
                setLevel(e.target.value as Level | '')
              }}
            >
              <option value="">{intl.formatMessage(im.any)}</option>
              {LEVELS.map((l) => (
                <option key={l} value={l}>
                  {intl.formatMessage(imv.levelCount, {
                    level: intl.formatMessage(levelLabels[l]),
                    count: byLevel[l] ?? 0,
                  })}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(im.code)} className="w-64">
          {(p) => (
            <Select
              {...p}
              dir="ltr"
              value={code}
              onChange={(e) => {
                setCode(e.target.value)
              }}
            >
              <option value="">{intl.formatMessage(im.any)}</option>
              {Object.entries(byCode)
                .sort(([a], [b]) => a.localeCompare(b))
                .map(([c, n]) => (
                  <option key={c} value={c}>
                    {intl.formatMessage(imv.levelCount, { level: c, count: n })}
                  </option>
                ))}
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(im.sheet)} className="w-40">
          {(p) => (
            <Select
              {...p}
              value={sheet}
              onChange={(e) => {
                setSheet(e.target.value as Sheet | '')
              }}
            >
              <option value="">{intl.formatMessage(im.any)}</option>
              {SHEETS.map((s) => (
                <option key={s} value={s}>
                  {intl.formatMessage(sheetLabels[s])}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(im.row)} className="w-28">
          {(p) => (
            <Input
              {...p}
              inputMode="numeric"
              value={row}
              onChange={(e) => {
                setRow(e.target.value)
              }}
            />
          )}
        </Field>
      </div>
      <TableScroll>
        <table className="w-full text-sm" data-testid="problem-table">
          <caption className="sr-only">{intl.formatMessage(im.problemsCaption)}</caption>
          <thead>
            <tr>
              {[im.colLevel, im.colSheet, im.colRow, im.colColumn, im.colProblem].map((h) => (
                <th key={h.id} scope="col" className="py-2 pe-3 text-start font-medium">
                  {intl.formatMessage(h)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {items.map((problem) => (
              <tr key={problem.id} className="border-t align-top">
                <td className="py-2 pe-3">{intl.formatMessage(levelLabels[problem.level])}</td>
                <td className="py-2 pe-3">{problem.sheet ? intl.formatMessage(sheetLabels[problem.sheet]) : ''}</td>
                <td className="py-2 pe-3 tabular-nums">
                  {problem.rowNumber != null ? intl.formatNumber(problem.rowNumber) : ''}
                </td>
                <td className="py-2 pe-3 font-mono text-xs">
                  <bdi dir="ltr">{problem.column ?? ''}</bdi>
                </td>
                <td className="py-2">{problemText(intl, problem)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableScroll>
      {!problems.isPending && items.length === 0 ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(im.noProblems)}</p>
      ) : null}
      {problems.hasNextPage ? (
        <Button
          variant="outline"
          className="self-center"
          disabled={problems.isFetchingNextPage}
          onClick={() => void problems.fetchNextPage()}
        >
          {intl.formatMessage(im.loadMore)}
        </Button>
      ) : null}
    </div>
  )
}

/** The Memos rows with their results; the subject as the API returns it ("Restricted" above the reader's clearance). */
function RowList({ batchId }: { batchId: string }) {
  const intl = useIntl()
  const [result, setResult] = useState<Result | ''>('')
  const query = { path: { id: batchId }, query: { limit: PAGE, ...(result ? { result: [result] } : {}) } }
  const rows = useInfiniteQuery({
    ...listImportRowsInfiniteOptions(query),
    initialPageParam: query,
    getNextPageParam: (last) => (last.next ? { ...query, query: { ...query.query, cursor: last.next } } : undefined),
  })
  const items = rows.data?.pages.flatMap((p) => p.rows) ?? []
  return (
    <div className="flex flex-col gap-3">
      <Field label={intl.formatMessage(im.result)} className="w-44">
        {(p) => (
          <Select
            {...p}
            value={result}
            onChange={(e) => {
              setResult(e.target.value as Result | '')
            }}
          >
            <option value="">{intl.formatMessage(im.any)}</option>
            {RESULTS.map((r) => (
              <option key={r} value={r}>
                {intl.formatMessage(resultLabels[r])}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <TableScroll>
        <table className="w-full text-sm" data-testid="row-table">
          <caption className="sr-only">{intl.formatMessage(im.rowsCaption)}</caption>
          <thead>
            <tr>
              {[im.colRow, im.colLegacyNumber, im.colResult, im.colCodes, im.colSubject].map((h) => (
                <th key={h.id} scope="col" className="py-2 pe-3 text-start font-medium">
                  {intl.formatMessage(h)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {items.map((row) => (
              <tr key={row.rowId} className="border-t align-top">
                <td className="py-2 pe-3 tabular-nums">{intl.formatNumber(row.rowNumber)}</td>
                <td className="py-2 pe-3 font-mono text-xs">
                  <bdi dir="ltr">{row.legacyNumber ?? ''}</bdi>
                </td>
                <td className="py-2 pe-3">{intl.formatMessage(resultLabels[row.result])}</td>
                <td className="py-2 pe-3 font-mono text-xs">
                  <bdi dir="ltr">{row.codes.join(', ')}</bdi>
                </td>
                <td className="py-2">
                  {row.restricted ? (
                    <span className="text-muted-foreground">{intl.formatMessage(im.restricted)}</span>
                  ) : (
                    row.subject
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableScroll>
      {!rows.isPending && items.length === 0 ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(im.noRows)}</p>
      ) : null}
      {rows.hasNextPage ? (
        <Button
          variant="outline"
          className="self-center"
          disabled={rows.isFetchingNextPage}
          onClick={() => void rows.fetchNextPage()}
        >
          {intl.formatMessage(im.loadMore)}
        </Button>
      ) : null}
    </div>
  )
}
