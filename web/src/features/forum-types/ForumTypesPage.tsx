import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Plus } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  listForumTypePresetsOptions,
  listForumTypesOptions,
  listForumTypesQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { createForumType } from '@/api/generated/sdk.gen'
import type { ForumTypePresetResponse, ForumTypeResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { PERMISSION, holds, useMyAccess } from '@/features/access/access'
import { useTerms } from '@/features/terms/terms'
import { ft, ftt, ftv } from './messages'

type Preset = ForumTypePresetResponse['key']

/**
 * `/admin/committee-types` (flow J1, ADR-0056): every committee type with its code, published version, the version
 * open for change or review, and whether new committees may use it. A new type starts as a draft, blank or from a
 * preset; a second administrator publishes it.
 */
export function ForumTypesPage() {
  const intl = useIntl()
  const terms = useTerms()
  const types = useQuery(listForumTypesOptions())
  const access = useMyAccess()
  const [adding, setAdding] = useState(false)

  const changes = (t: ForumTypeResponse) => {
    const number = t.openVersion
    if (number == null || !t.openState) return intl.formatMessage(ft.noChanges)
    if (t.openState === 'IN_REVIEW') {
      return t.openSubmittedBy
        ? intl.formatMessage(ftv.inReviewBy, { number, name: t.openSubmittedBy.name })
        : intl.formatMessage(ftv.inReview, { number })
    }
    return intl.formatMessage(ftv.draft, { number })
  }

  const columns: DataTableColumn<ForumTypeResponse>[] = [
    {
      id: 'name',
      header: intl.formatMessage(ft.colName),
      cell: ({ row }) => (
        <Link
          to="/admin/committee-types/$typeId"
          params={{ typeId: row.original.id }}
          className="font-medium underline-offset-4 hover:underline"
        >
          {row.original.name}
        </Link>
      ),
    },
    { id: 'code', header: intl.formatMessage(ft.colCode), cell: ({ row }) => <code>{row.original.code}</code> },
    {
      id: 'published',
      header: intl.formatMessage(ft.colPublished),
      cell: ({ row }) =>
        row.original.publishedVersion != null
          ? intl.formatMessage(ftv.versionShort, { number: row.original.publishedVersion })
          : intl.formatMessage(ft.notPublished),
    },
    { id: 'changes', header: intl.formatMessage(ft.colChanges), cell: ({ row }) => changes(row.original) },
    {
      id: 'status',
      header: intl.formatMessage(ft.colStatus),
      cell: ({ row }) => (
        <Badge tone={row.original.active ? 'ok' : 'neutral'}>
          {intl.formatMessage(row.original.active ? ft.active : ft.inactive)}
        </Badge>
      ),
    },
  ]

  return (
    <section aria-labelledby="forum-types-title">
      <PageHeader
        title={<span id="forum-types-title">{intl.formatMessage(ftt.title, terms)}</span>}
        description={intl.formatMessage(ftt.lead, terms)}
        actions={
          holds(access.data, PERMISSION.forumTypesManage) ? (
            <Button
              onClick={() => {
                setAdding(true)
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(ftt.add, terms)}
            </Button>
          ) : undefined
        }
      />
      <DataTable
        caption={intl.formatMessage(ftt.caption, terms)}
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

/** "New committee type" (flow J1): name, code and what to start from; opens the type on its draft version 1. */
function NewTypeDialog({ onClose }: { onClose: () => void }) {
  const intl = useIntl()
  const terms = useTerms()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const presets = useQuery(listForumTypePresetsOptions())
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [preset, setPreset] = useState<Preset | ''>('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  const known: Record<string, string> = {
    'code:FORMAT': intl.formatMessage(ft.codeFormat),
    'code:CODE_TAKEN': intl.formatMessage(ftt.codeTaken, terms),
  }

  const create = async () => {
    const missing: Record<string, string> = {}
    if (!name.trim()) missing.name = intl.formatMessage(ft.required)
    if (!code.trim()) missing.code = intl.formatMessage(ft.required)
    setErrors(missing)
    if (Object.keys(missing).length) return
    setBusy(true)
    try {
      const created = await createForumType({
        body: { name: name.trim(), code: code.trim(), ...(preset ? { preset } : {}) },
        throwOnError: true,
      })
      await queryClient.invalidateQueries({ queryKey: listForumTypesQueryKey() })
      await navigate({
        to: '/admin/committee-types/$typeId',
        params: { typeId: created.data.id },
        search: { version: 1 },
      })
    } catch (problem) {
      const found = isProblem(problem) ? problem.errors : undefined
      if (found?.length) {
        setErrors(Object.fromEntries(found.map((e) => [e.field, known[`${e.field}:${e.code ?? ''}`] ?? e.message])))
      } else {
        setErrors({ name: intl.formatMessage(ft.failed) })
      }
    } finally {
      setBusy(false)
    }
  }

  const choices: { value: Preset | ''; label: string }[] = [
    { value: '', label: intl.formatMessage(ft.blank) },
    ...(presets.data ?? []).map((p) => ({
      value: p.key,
      label: p.key in ft ? intl.formatMessage(ft[p.key]) : p.name,
    })),
  ]

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(ftt.add, terms)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(ft.cancel)}
          </Button>
          <Button disabled={busy} onClick={() => void create()}>
            {intl.formatMessage(ft.create)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={intl.formatMessage(ft.name)} required error={errors.name}>
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
        <Field label={intl.formatMessage(ft.code)} hint={intl.formatMessage(ft.codeHint)} required error={errors.code}>
          {(p) => (
            <Input
              {...p}
              maxLength={10}
              dir="ltr"
              className="w-40 uppercase"
              value={code}
              onChange={(e) => {
                setCode(e.target.value.toUpperCase())
              }}
            />
          )}
        </Field>
        <fieldset className="flex flex-col gap-1" aria-describedby="start-from-hint">
          <legend className="mb-1 text-sm font-medium">{intl.formatMessage(ft.startFrom)}</legend>
          {choices.map((c) => (
            <label key={c.value || 'blank'} className="flex min-h-10 items-center gap-3 text-sm">
              <input
                type="radio"
                name="forum-type-preset"
                className="size-5 accent-primary"
                checked={preset === c.value}
                onChange={() => {
                  setPreset(c.value)
                }}
              />
              {c.label}
            </label>
          ))}
          <p id="start-from-hint" className="text-xs text-muted-foreground">
            {intl.formatMessage(ft.startFromHint)}
          </p>
        </fieldset>
      </div>
    </Dialog>
  )
}
