import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Plus } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { listAllMemoTypesOptions, listAllMemoTypesQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { createMemoType } from '@/api/generated/sdk.gen'
import type { MemoType } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { PERMISSION, holds, useMyAccess } from '@/features/access/access'
import { stateLabels } from '@/features/workflows/workflow-messages'
import { newIdempotencyKey } from '@/lib/idempotency'
import { tm, tmv } from './messages'

/**
 * `/admin/memo-types` (design E01 §10 flow A1): every type with its code, published version, open draft or review, and
 * whether it is in use. A new type starts as a draft; a second administrator publishes it.
 */
export function MemoTypesPage() {
  const intl = useIntl()
  const types = useQuery(listAllMemoTypesOptions())
  const access = useMyAccess()
  const [adding, setAdding] = useState(false)

  const changes = (type: MemoType) => {
    const pending = type.pending
    if (!pending) return intl.formatMessage(tm.noChanges)
    if (pending.state === 'IN_REVIEW' && pending.submittedBy) {
      return intl.formatMessage(tmv.pendingBy, { number: pending.number, name: pending.submittedBy.name })
    }
    return intl.formatMessage(tmv.pending, {
      number: pending.number,
      state: intl.formatMessage(stateLabels[pending.state]),
    })
  }

  const columns: DataTableColumn<MemoType>[] = [
    {
      id: 'name',
      header: intl.formatMessage(tm.colName),
      cell: ({ row }) => (
        <Link
          to="/admin/memo-types/$typeId"
          params={{ typeId: row.original.id }}
          className="font-medium underline-offset-4 hover:underline"
        >
          {row.original.name}
        </Link>
      ),
    },
    { id: 'code', header: intl.formatMessage(tm.colCode), cell: ({ row }) => <code>{row.original.code}</code> },
    {
      id: 'published',
      header: intl.formatMessage(tm.colPublished),
      cell: ({ row }) =>
        row.original.publishedVersion
          ? `v${String(row.original.publishedVersion)}`
          : intl.formatMessage(tm.notPublished),
    },
    { id: 'changes', header: intl.formatMessage(tm.colChanges), cell: ({ row }) => changes(row.original) },
    {
      id: 'status',
      header: intl.formatMessage(tm.colStatus),
      cell: ({ row }) => (
        <Badge tone={row.original.active ? 'ok' : 'neutral'}>
          {intl.formatMessage(row.original.active ? tm.active : tm.inactive)}
        </Badge>
      ),
    },
  ]

  return (
    <section aria-labelledby="memo-types-title">
      <PageHeader
        title={<span id="memo-types-title">{intl.formatMessage(tm.title)}</span>}
        description={intl.formatMessage(tm.lead)}
        actions={
          holds(access.data, PERMISSION.memoTypesManage) ? (
            <Button
              onClick={() => {
                setAdding(true)
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(tm.add)}
            </Button>
          ) : undefined
        }
      />
      <DataTable
        caption={intl.formatMessage(tm.caption)}
        columns={columns}
        rows={types.data ?? []}
        getRowId={(row) => row.id}
        state={types.isPending ? 'loading' : types.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void types.refetch() }}
      />
      {adding ? (
        <NewTypeDialog
          onClose={() => {
            setAdding(false)
          }}
        />
      ) : null}
    </section>
  )
}

function NewTypeDialog({ onClose }: { onClose: () => void }) {
  const intl = useIntl()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [key] = useState(() => newIdempotencyKey())
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  const create = async () => {
    const missing: Record<string, string> = {}
    if (!name.trim()) missing.name = intl.formatMessage(tm.required)
    if (!code.trim()) missing.code = intl.formatMessage(tm.required)
    setErrors(missing)
    if (Object.keys(missing).length) return
    setBusy(true)
    try {
      const created = await createMemoType({
        headers: { 'Idempotency-Key': key },
        body: { name: name.trim(), code: code.trim() },
        throwOnError: true,
      })
      await queryClient.invalidateQueries({ queryKey: listAllMemoTypesQueryKey() })
      await navigate({ to: '/admin/memo-types/$typeId', params: { typeId: created.data.id }, search: { version: 1 } })
    } catch (problem) {
      const found = isProblem(problem) ? problem.errors : undefined
      if (found?.length) setErrors(Object.fromEntries(found.map((e) => [e.field, e.message])))
      else setErrors({ name: intl.formatMessage(tm.failed) })
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
      title={intl.formatMessage(tm.add)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(tm.cancel)}
          </Button>
          <Button disabled={busy} onClick={() => void create()}>
            {intl.formatMessage(tm.create)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={intl.formatMessage(tm.name)} required error={errors.name}>
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
        <Field label={intl.formatMessage(tm.code)} hint={intl.formatMessage(tm.codeHint)} required error={errors.code}>
          {(p) => (
            <Input
              {...p}
              maxLength={10}
              className="w-40 uppercase"
              value={code}
              onChange={(e) => {
                setCode(e.target.value.toUpperCase())
              }}
            />
          )}
        </Field>
      </div>
    </Dialog>
  )
}
