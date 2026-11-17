import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Download, Plus } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getImportBatchQueryKey,
  listClassificationLevelsOptions,
  listImportBatchesInfiniteOptions,
  listImportBatchesInfiniteQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { createImportBatch, updateImportBatch } from '@/api/generated/sdk.gen'
import type { ImportBatchSummary, ImportBatchView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { newIdempotencyKey } from '@/lib/idempotency'
import { answerText } from './codes'
import { stateLabels, stateTone } from './format'
import { im } from './messages'

const PAGE = { query: { limit: 50 } }

/**
 * `/admin/imports` (ux-review L1): the batches, newest first, each opening its page; New batch and the template. The
 * menu shows it to holders of `memo.import`; the API answers 403 to anyone else.
 */
export function ImportsPage() {
  const intl = useIntl()
  const [adding, setAdding] = useState(false)
  const batches = useInfiniteQuery({
    ...listImportBatchesInfiniteOptions(PAGE),
    initialPageParam: PAGE,
    getNextPageParam: (last) => (last.next ? { query: { ...PAGE.query, cursor: last.next } } : undefined),
  })
  const rows = batches.data?.pages.flatMap((p) => p.batches) ?? []

  const columns: DataTableColumn<ImportBatchSummary>[] = [
    {
      id: 'name',
      header: intl.formatMessage(im.colName),
      cell: ({ row }) => (
        <Link
          to="/admin/imports/$batchId"
          params={{ batchId: row.original.id }}
          className="font-medium underline-offset-4 hover:underline"
        >
          {row.original.name}
        </Link>
      ),
    },
    {
      id: 'source',
      header: intl.formatMessage(im.colSource),
      cell: ({ row }) => <code dir="ltr">{row.original.sourceCode}</code>,
    },
    {
      id: 'state',
      header: intl.formatMessage(im.colState),
      cell: ({ row }) => (
        <Badge tone={stateTone[row.original.state]}>{intl.formatMessage(stateLabels[row.original.state])}</Badge>
      ),
    },
    {
      id: 'updated',
      header: intl.formatMessage(im.colUpdated),
      cell: ({ row }) => intl.formatDate(row.original.updatedAt, { dateStyle: 'medium' }),
    },
  ]

  return (
    <section aria-labelledby="imports-title">
      <PageHeader
        title={<span id="imports-title">{intl.formatMessage(im.title)}</span>}
        description={intl.formatMessage(im.lead)}
        actions={
          <>
            <Button variant="outline" asChild>
              <a href="/api/v1/admin/imports/template" download>
                <Download aria-hidden="true" />
                {intl.formatMessage(im.template)}
              </a>
            </Button>
            <Button
              data-testid="imports-new"
              onClick={() => {
                setAdding(true)
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(im.newBatch)}
            </Button>
          </>
        }
      />
      <DataTable
        caption={intl.formatMessage(im.caption)}
        columns={columns}
        rows={rows}
        getRowId={(row) => row.id}
        state={batches.isPending ? 'loading' : batches.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void batches.refetch() }}
        empty={intl.formatMessage(im.empty)}
      />
      {batches.hasNextPage ? (
        <div className="mt-3 flex justify-center">
          <Button variant="outline" disabled={batches.isFetchingNextPage} onClick={() => void batches.fetchNextPage()}>
            {intl.formatMessage(im.loadMore)}
          </Button>
        </div>
      ) : null}
      {adding ? (
        <BatchSettingsDialog
          onClose={() => {
            setAdding(false)
          }}
        />
      ) : null}
    </section>
  )
}

/** Time zones the browser knows (IANA ids), with [current] first. */
function timeZones(current: string): string[] {
  const all = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : ['UTC', 'Asia/Dhaka']
  return [current, ...all.filter((z) => z !== current)]
}

function browserZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
}

/**
 * New batch (ux-review L2), or the settings of an existing one ([batch]): name, source code, time zone, the default
 * level for blank cells and the number prefix. Changing settings sends the batch back to "needs a new dry run".
 */
export function BatchSettingsDialog({
  batch,
  onClose,
  onSaved,
}: {
  batch?: ImportBatchView
  onClose: () => void
  onSaved?: (saved: ImportBatchView) => void
}) {
  const intl = useIntl()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const levels = useQuery(listClassificationLevelsOptions())
  const [key] = useState(() => newIdempotencyKey())
  const [name, setName] = useState(batch?.name ?? '')
  const [sourceCode, setSourceCode] = useState(batch?.sourceCode ?? '')
  const [timeZone, setTimeZone] = useState(batch?.timeZone ?? browserZone())
  const [defaultLevel, setDefaultLevel] = useState(batch?.defaultClassificationId ?? '')
  const [prefix, setPrefix] = useState(batch?.numberPrefix ?? '')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  const save = async () => {
    const missing: Record<string, string> = {}
    if (!name.trim()) missing.name = intl.formatMessage(im.required)
    if (!sourceCode.trim()) missing.sourceCode = intl.formatMessage(im.required)
    setErrors(missing)
    if (Object.keys(missing).length) return
    const body = {
      name: name.trim(),
      sourceCode: sourceCode.trim(),
      timeZone,
      defaultClassificationId: defaultLevel || null,
      numberPrefix: prefix.trim() || null,
    }
    setBusy(true)
    try {
      if (batch) {
        const saved = await updateImportBatch({
          path: { id: batch.id },
          headers: { 'If-Match': `"${String(batch.version)}"` },
          body,
          throwOnError: true,
        })
        queryClient.setQueryData(getImportBatchQueryKey({ path: { id: batch.id } }), saved.data)
        onSaved?.(saved.data)
      } else {
        const created = await createImportBatch({ headers: { 'Idempotency-Key': key }, body, throwOnError: true })
        await queryClient.invalidateQueries({ queryKey: listImportBatchesInfiniteQueryKey(PAGE) })
        await navigate({ to: '/admin/imports/$batchId', params: { batchId: created.data.id } })
      }
    } catch (error) {
      const problem: Problem | undefined = isProblem(error) ? error : undefined
      if (problem?.errors?.length) {
        setErrors(
          Object.fromEntries(
            problem.errors.map((e) => [e.field, e.code ? answerText(intl, e.code) : e.message] as const),
          ),
        )
      } else if (problem?.status === 412) {
        setErrors({ name: intl.formatMessage(im.conflict) })
      } else {
        setErrors({ name: problem?.reason ? answerText(intl, problem.reason) : intl.formatMessage(im.failed) })
      }
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
      title={intl.formatMessage(batch ? im.settingsTitle : im.newTitle)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(im.cancel)}
          </Button>
          <Button disabled={busy} onClick={() => void save()}>
            {intl.formatMessage(batch ? im.save : im.create)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={intl.formatMessage(im.name)} required error={errors.name}>
          {(p) => (
            <Input
              {...p}
              maxLength={200}
              value={name}
              onChange={(e) => {
                setName(e.target.value)
              }}
            />
          )}
        </Field>
        <Field
          label={intl.formatMessage(im.sourceCode)}
          hint={intl.formatMessage(im.sourceCodeHint)}
          required
          error={errors.sourceCode}
        >
          {(p) => (
            <Input
              {...p}
              dir="ltr"
              maxLength={20}
              className="w-48 uppercase"
              value={sourceCode}
              onChange={(e) => {
                setSourceCode(e.target.value.toUpperCase())
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(im.timeZone)} required error={errors.timeZone}>
          {(p) => (
            <Select
              {...p}
              dir="ltr"
              value={timeZone}
              onChange={(e) => {
                setTimeZone(e.target.value)
              }}
            >
              {timeZones(timeZone).map((zone) => (
                <option key={zone} value={zone}>
                  {zone}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(im.defaultClassification)} error={errors.defaultClassificationId}>
          {(p) => (
            <Select
              {...p}
              value={defaultLevel}
              onChange={(e) => {
                setDefaultLevel(e.target.value)
              }}
            >
              <option value="">{intl.formatMessage(im.noDefault)}</option>
              {(levels.data ?? []).map((level) => (
                <option key={level.id} value={level.id}>
                  {level.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field
          label={intl.formatMessage(im.numberPrefix)}
          hint={intl.formatMessage(im.numberPrefixHint)}
          optionalLabel={intl.formatMessage(im.optional)}
          error={errors.numberPrefix}
        >
          {(p) => (
            <Input
              {...p}
              dir="ltr"
              maxLength={20}
              className="w-48"
              value={prefix}
              onChange={(e) => {
                setPrefix(e.target.value)
              }}
            />
          )}
        </Field>
      </div>
    </Dialog>
  )
}
