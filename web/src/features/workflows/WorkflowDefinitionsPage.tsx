import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Plus } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  listWorkflowDefinitionsOptions,
  listWorkflowDefinitionsQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { createWorkflowDefinition } from '@/api/generated/sdk.gen'
import type { DefinitionSummary } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Textarea } from '@/components/ui/form-controls'
import { holds, PERMISSION, useMyAccess } from '@/features/access/access'
import { stateLabels, wm, wmv } from './workflow-messages'

const KEY = /^[a-z][a-z0-9-]{0,99}$/

/** A new definition starts as the built-in shape: the author's route, then the end. */
const STARTER = {
  schemaVersion: 2,
  start: 'route',
  steps: [{ id: 'route', type: 'route', name: 'Approvers', next: 'end' }],
}

/** `/admin/workflows` (design E03 §12): the definitions, which version is published and which one is being worked on. */
export function WorkflowDefinitionsPage() {
  const intl = useIntl()
  const definitions = useQuery(listWorkflowDefinitionsOptions())
  const access = useMyAccess()
  const [creating, setCreating] = useState(false)

  const columns: DataTableColumn<DefinitionSummary>[] = [
    {
      id: 'name',
      header: intl.formatMessage(wm.colName),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-2">
          <Link to="/admin/workflows/$key" params={{ key: row.original.key }} className="font-medium underline">
            {row.original.name}
          </Link>
          {row.original.builtIn ? <Badge tone="neutral">{intl.formatMessage(wm.builtIn)}</Badge> : null}
          {row.original.fragment ? <Badge tone="neutral">{intl.formatMessage(wm.fragment)}</Badge> : null}
        </span>
      ),
    },
    { id: 'key', header: intl.formatMessage(wm.colKey), cell: ({ row }) => <code>{row.original.key}</code> },
    {
      id: 'published',
      header: intl.formatMessage(wm.colPublished),
      cell: ({ row }) =>
        row.original.publishedVersion
          ? intl.formatMessage(wmv.version, { number: row.original.publishedVersion })
          : intl.formatMessage(wm.none),
    },
    {
      id: 'open',
      header: intl.formatMessage(wm.colOpen),
      cell: ({ row }) => {
        const open = row.original.openVersion
        return open
          ? `${intl.formatMessage(wmv.version, { number: open.number })} · ${intl.formatMessage(stateLabels[open.state])}`
          : intl.formatMessage(wm.none)
      },
    },
  ]

  return (
    <section aria-labelledby="workflows-title">
      <PageHeader
        title={<span id="workflows-title">{intl.formatMessage(wm.title)}</span>}
        description={intl.formatMessage(wm.lead)}
        actions={
          holds(access.data, PERMISSION.workflowDefinitionsManage) ? (
            <Button
              onClick={() => {
                setCreating(true)
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(wm.add)}
            </Button>
          ) : null
        }
      />
      <DataTable
        caption={intl.formatMessage(wm.caption)}
        columns={columns}
        rows={definitions.data ?? []}
        getRowId={(row) => row.key}
        state={definitions.isPending ? 'loading' : definitions.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void definitions.refetch() }}
      />
      {creating ? (
        <CreateDialog
          onClose={() => {
            setCreating(false)
          }}
        />
      ) : null}
    </section>
  )
}

function CreateDialog({ onClose }: { onClose: () => void }) {
  const intl = useIntl()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [fragment, setFragment] = useState(false)
  const [errors, setErrors] = useState<{ key?: string; name?: string; form?: string }>({})
  const [busy, setBusy] = useState(false)

  const create = async () => {
    const next = {
      key: KEY.test(key.trim()) ? undefined : intl.formatMessage(key.trim() ? wm.keyHint : wm.required),
      name: name.trim() ? undefined : intl.formatMessage(wm.required),
    }
    setErrors(next)
    if (next.key || next.name) return
    setBusy(true)
    try {
      await createWorkflowDefinition({
        body: {
          key: key.trim(),
          name: name.trim(),
          description: description.trim() || undefined,
          fragment,
          body: STARTER,
        },
        throwOnError: true,
      })
      await queryClient.invalidateQueries({ queryKey: listWorkflowDefinitionsQueryKey() })
      await navigate({ to: '/admin/workflows/$key', params: { key: key.trim() } })
    } catch (problem) {
      const reason = isProblem(problem) ? problem.reason : undefined
      setErrors({ form: intl.formatMessage(reason === 'KEY_TAKEN' ? wm.keyTaken : wm.failed) })
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
      title={intl.formatMessage(wm.createTitle)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(wm.cancel)}
          </Button>
          <Button disabled={busy} onClick={() => void create()}>
            {intl.formatMessage(wm.create)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {errors.form ? (
          <p role="alert" className="text-sm text-destructive">
            {errors.form}
          </p>
        ) : null}
        <Field label={intl.formatMessage(wm.name)} required error={errors.name}>
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
        <Field label={intl.formatMessage(wm.key)} hint={intl.formatMessage(wm.keyHint)} required error={errors.key}>
          {(p) => (
            <Input
              {...p}
              dir="ltr"
              maxLength={100}
              value={key}
              onChange={(e) => {
                setKey(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(wm.description)}>
          {(p) => (
            <Textarea
              {...p}
              rows={3}
              maxLength={2000}
              value={description}
              onChange={(e) => {
                setDescription(e.target.value)
              }}
            />
          )}
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <Checkbox
            checked={fragment}
            onChange={(e) => {
              setFragment(e.target.checked)
            }}
          />
          {intl.formatMessage(wm.fragmentField)}
        </label>
      </div>
    </Dialog>
  )
}
