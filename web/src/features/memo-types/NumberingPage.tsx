import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import {
  getNumberRegisterOptions,
  listNumberSeriesOptions,
  listNumberSeriesQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { startNumberCounter } from '@/api/generated/sdk.gen'
import type { NumberCounterView, RegisterEntry } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { PERMISSION, holds, useMyAccess } from '@/features/access/access'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { statusLabels } from '@/features/memo/memo-messages'
import { newIdempotencyKey } from '@/lib/idempotency'
import { tm } from './messages'

const m = defineMessages({
  title: { id: 'memoNumbering.title', defaultMessage: 'Memo numbering' },
  lead: {
    id: 'memoNumbering.lead',
    defaultMessage:
      'Every number given, in order, by series and period. Numbers are never reused: those of withdrawn or cancelled memos are void.',
  },
  series: { id: 'memoNumbering.series', defaultMessage: 'Series' },
  period: { id: 'memoNumbering.period', defaultMessage: 'Period' },
  unit: { id: 'memoNumbering.unit', defaultMessage: 'Unit' },
  last: { id: 'memoNumbering.last', defaultMessage: 'Last number' },
  started: { id: 'memoNumbering.started', defaultMessage: 'Started by an administrator' },
  open: { id: 'memoNumbering.open', defaultMessage: 'Register' },
  none: { id: 'memoNumbering.none', defaultMessage: 'No numbers given yet.' },
  number: { id: 'memoNumbering.number', defaultMessage: 'Number' },
  memo: { id: 'memoNumbering.memo', defaultMessage: 'Memo' },
  status: { id: 'memoNumbering.status', defaultMessage: 'Status' },
  void: { id: 'memoNumbering.void', defaultMessage: 'Void' },
  hidden: { id: 'memoNumbering.hidden', defaultMessage: 'Not visible to you' },
  complete: { id: 'memoNumbering.complete', defaultMessage: 'Complete: every number up to the last has its memo.' },
  more: { id: 'memoNumbering.more', defaultMessage: 'Show more' },
  start: { id: 'memoNumbering.start', defaultMessage: 'Set a starting number' },
  startLead: {
    id: 'memoNumbering.startLead',
    defaultMessage:
      'To continue an existing paper or legacy series: the first number this counter gives. Possible only before it gives any.',
  },
  startPeriod: { id: 'memoNumbering.startPeriod', defaultMessage: 'Period (for example 2026 or FY2026-27)' },
  startScope: { id: 'memoNumbering.startScope', defaultMessage: 'Unit id (only for a series counted per unit)' },
  startNext: { id: 'memoNumbering.startNext', defaultMessage: 'First number' },
  save: { id: 'memoNumbering.save', defaultMessage: 'Set' },
  caption: { id: 'memoNumbering.caption', defaultMessage: 'Counters of the series' },
  registerCaption: { id: 'memoNumbering.registerCaption', defaultMessage: 'Numbers in order' },
})

const NONE = '—'

const mv = defineMessages<{
  missing: { count: number; first: string }
  usedBy: { types: string }
  registerOf: { series: string; period: string }
  registerOfUnit: { series: string; period: string; unit: string }
}>({
  usedBy: { id: 'memoNumbering.usedByTypes', defaultMessage: 'Used by: {types}' },
  registerOf: { id: 'memoNumbering.registerOf', defaultMessage: 'Register of {series} · {period}' },
  registerOfUnit: { id: 'memoNumbering.registerOfUnit', defaultMessage: 'Register of {series} · {period} · {unit}' },
  missing: {
    id: 'memoNumbering.missing',
    defaultMessage: '{count, plural, one {# number has} other {# numbers have}} no memo: {first}',
  },
})

/**
 * `/admin/memo-numbering` (design E01 §10 flow A3, ADR-0044): the series with the types numbering in them and their
 * counters; a counter's register in order with void numbers and a completeness check; and a starting number for a counter
 * not used yet (`memo.types.approve`, after a step-up).
 */
export function NumberingPage() {
  const intl = useIntl()
  const access = useMyAccess()
  const series = useQuery(listNumberSeriesOptions())
  const [shown, setShown] = useState<{ series: string; counter: NumberCounterView }>()
  const [starting, setStarting] = useState<string>()
  const approve = holds(access.data, PERMISSION.memoTypesApprove)

  if (series.isPending) return <LoadingState />
  if (series.isError) return <ErrorState onRetry={() => void series.refetch()} />

  return (
    <section aria-labelledby="numbering-title" className="flex flex-col gap-6">
      <PageHeader
        title={<span id="numbering-title">{intl.formatMessage(m.title)}</span>}
        description={intl.formatMessage(m.lead)}
      />
      {series.data.map((s) => {
        const columns: DataTableColumn<NumberCounterView>[] = [
          { id: 'period', header: intl.formatMessage(m.period), cell: ({ row }) => row.original.period },
          { id: 'unit', header: intl.formatMessage(m.unit), cell: ({ row }) => row.original.scopeName ?? '' },
          {
            id: 'last',
            header: intl.formatMessage(m.last),
            cell: ({ row }) => (
              <span className="flex items-center gap-2">
                {row.original.lastNumber}
                {row.original.startedByAdmin ? <Badge tone="neutral">{intl.formatMessage(m.started)}</Badge> : null}
              </span>
            ),
          },
          {
            id: 'open',
            header: () => <span className="sr-only">{intl.formatMessage(m.open)}</span>,
            cell: ({ row }) => (
              <Button
                variant="outline"
                onClick={() => {
                  setShown({ series: s.series, counter: row.original })
                }}
              >
                {intl.formatMessage(m.open)}
              </Button>
            ),
          },
        ]
        return (
          <section key={s.series} aria-labelledby={`series-${s.series}`} className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-3">
              <h2 id={`series-${s.series}`} className="text-lg font-semibold">
                <code>{s.series}</code>
              </h2>
              <span className="text-sm text-muted-foreground">
                {intl.formatMessage(mv.usedBy, { types: s.types.map((t) => t.name).join(', ') || NONE })}
              </span>
              {approve ? (
                <Button
                  variant="outline"
                  className="ms-auto"
                  onClick={() => {
                    setStarting(s.series)
                  }}
                >
                  {intl.formatMessage(m.start)}
                </Button>
              ) : null}
            </div>
            {s.counters.length ? (
              <DataTable
                caption={intl.formatMessage(m.caption)}
                columns={columns}
                rows={s.counters}
                getRowId={(row) => `${row.period}-${row.scope}`}
              />
            ) : (
              <p className="text-sm text-muted-foreground">{intl.formatMessage(m.none)}</p>
            )}
          </section>
        )
      })}
      {series.data.length === 0 ? <EmptyState title={intl.formatMessage(m.none)} /> : null}
      {shown ? <Register key={`${shown.series}-${shown.counter.period}-${shown.counter.scope}`} {...shown} /> : null}
      {starting ? (
        <StartDialog
          series={starting}
          onClose={() => {
            setStarting(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function Register({ series, counter }: { series: string; counter: NumberCounterView }) {
  const intl = useIntl()
  const [after, setAfter] = useState(0)
  const [rows, setRows] = useState<RegisterEntry[]>([])
  const page = useQuery(
    getNumberRegisterOptions({
      path: { series },
      query: { period: counter.period, scope: counter.scope, after, limit: 50 },
    }),
  )
  const all = [...rows, ...(page.data?.entries ?? [])]
  const columns: DataTableColumn<RegisterEntry>[] = [
    {
      id: 'number',
      header: intl.formatMessage(m.number),
      cell: ({ row }) => <span dir="ltr">{row.original.number}</span>,
    },
    {
      id: 'memo',
      header: intl.formatMessage(m.memo),
      cell: ({ row }) =>
        row.original.subject ?? <span className="text-muted-foreground">{intl.formatMessage(m.hidden)}</span>,
    },
    {
      id: 'status',
      header: intl.formatMessage(m.status),
      cell: ({ row }) => (
        <span className="flex items-center gap-2">
          {intl.formatMessage(statusLabels[row.original.status])}
          {row.original.void ? <Badge tone="danger">{intl.formatMessage(m.void)}</Badge> : null}
        </span>
      ),
    },
  ]
  const data = page.data
  return (
    <section aria-labelledby="register-title" className="flex flex-col gap-2 rounded-md border p-4">
      <h2 id="register-title" className="text-lg font-semibold">
        {counter.scopeName
          ? intl.formatMessage(mv.registerOfUnit, { series, period: counter.period, unit: counter.scopeName })
          : intl.formatMessage(mv.registerOf, { series, period: counter.period })}
      </h2>
      {data ? (
        <p role="status" className="text-sm">
          {data.missingCount === 0
            ? intl.formatMessage(m.complete)
            : intl.formatMessage(mv.missing, { count: data.missingCount, first: data.missing.join(', ') })}
        </p>
      ) : null}
      <DataTable
        caption={intl.formatMessage(m.registerCaption)}
        columns={columns}
        rows={all}
        getRowId={(row) => String(row.sequence)}
        state={page.isPending && all.length === 0 ? 'loading' : page.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void page.refetch() }}
      />
      {data?.next != null ? (
        <Button
          variant="outline"
          className="self-start"
          onClick={() => {
            setRows(all)
            setAfter(data.next ?? 0)
          }}
        >
          {intl.formatMessage(m.more)}
        </Button>
      ) : null}
    </section>
  )
}

function StartDialog({ series, onClose }: { series: string; onClose: () => void }) {
  const intl = useIntl()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const queryClient = useQueryClient()
  const [key] = useState(() => newIdempotencyKey())
  const [period, setPeriod] = useState('')
  const [scope, setScope] = useState('')
  const [next, setNext] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  const start = async () => {
    setBusy(true)
    setErrors({})
    try {
      await withStepUp(() =>
        startNumberCounter({
          path: { series },
          headers: { 'Idempotency-Key': key },
          body: { period: period.trim(), scope: scope.trim(), next: Number.parseInt(next, 10) || 0 },
          throwOnError: true,
        }),
      )
      await queryClient.invalidateQueries({ queryKey: listNumberSeriesQueryKey() })
      toast.show(intl.formatMessage(tm.done), 'success')
      onClose()
    } catch (problem) {
      const p = isProblem(problem) ? problem : undefined
      if (p?.errors?.length) setErrors(Object.fromEntries(p.errors.map((e) => [e.field, e.message])))
      else setErrors({ next: intl.formatMessage(p?.reason === 'ALREADY_STARTED' ? tm.ALREADY_STARTED : tm.failed) })
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
      title={intl.formatMessage(m.start)}
      description={intl.formatMessage(m.startLead)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(tm.cancel)}
          </Button>
          <Button disabled={busy} onClick={() => void start()}>
            {intl.formatMessage(m.save)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={intl.formatMessage(m.startPeriod)} required error={errors.period}>
          {(p) => (
            <Input
              {...p}
              value={period}
              onChange={(e) => {
                setPeriod(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(m.startScope)} error={errors.scope}>
          {(p) => (
            <Input
              {...p}
              value={scope}
              onChange={(e) => {
                setScope(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(m.startNext)} required error={errors.next}>
          {(p) => (
            <Input
              {...p}
              inputMode="numeric"
              className="w-40"
              value={next}
              onChange={(e) => {
                setNext(e.target.value)
              }}
            />
          )}
        </Field>
      </div>
    </Dialog>
  )
}
