import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import {
  listAllMemoTypesOptions,
  listAllMemoTypesQueryKey,
  listClassificationLevelsOptions,
  listMemoTypeDefinitionsOptions,
  listMemoTypesQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { createMemoType, updateMemoType } from '@/api/generated/sdk.gen'
import type { AllowedApprovers, DefinitionChoice, MemoType } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { AllowedApproversEditor } from './AllowedApproversEditor'
import { mm } from './memo-messages'
import { newIdempotencyKey } from '@/lib/idempotency'

const m = defineMessages({
  title: { id: 'memoTypes.title', defaultMessage: 'Memo types' },
  lead: {
    id: 'memoTypes.lead',
    defaultMessage: 'What people choose when they write a memo. A type is never deleted, only switched off.',
  },
  add: { id: 'memoTypes.add', defaultMessage: 'Add memo type' },
  caption: { id: 'memoTypes.caption', defaultMessage: 'Memo types of the organisation' },
  colName: { id: 'memoTypes.col.name', defaultMessage: 'Name' },
  colDefault: { id: 'memoTypes.col.default', defaultMessage: 'Default classification' },
  colStepUp: { id: 'memoTypes.col.stepUp', defaultMessage: 'Confirm before approving' },
  colActions: { id: 'memoTypes.col.actions', defaultMessage: 'Actions' },
  lowest: { id: 'memoTypes.lowest', defaultMessage: 'Lowest level' },
  stepUp: { id: 'memoTypes.stepUp', defaultMessage: 'Approvers confirm it is them before approving' },
  active: { id: 'memoTypes.active', defaultMessage: 'In use' },
  inactive: { id: 'memoTypes.inactive', defaultMessage: 'Switched off' },
  yes: { id: 'memoTypes.yes', defaultMessage: 'Yes' },
  no: { id: 'memoTypes.no', defaultMessage: 'No' },
  lastActive: { id: 'memoTypes.lastActive', defaultMessage: 'Keep at least one memo type in use.' },
  colWorkflow: { id: 'memoTypes.col.workflow', defaultMessage: 'Workflow' },
  policy: { id: 'memoTypes.policy', defaultMessage: 'Approvers' },
  policyInitiator: { id: 'memoTypes.policy.initiator', defaultMessage: 'The author chooses them' },
  policyBoth: { id: 'memoTypes.policy.both', defaultMessage: 'The workflow’s steps, plus those the author chooses' },
  policyFixed: { id: 'memoTypes.policy.fixed', defaultMessage: 'The workflow names every step' },
  doaCheck: {
    id: 'memoTypes.doaCheck',
    defaultMessage: 'The route must give the delegation of authority its approvals before the memo can be submitted',
  },
  workflowHint: {
    id: 'memoTypes.workflow.hint',
    defaultMessage: 'New memos of this type use its published version; memos in review keep theirs.',
  },
})

const mv = defineMessages<{ edit: { name: string } }>({
  edit: { id: 'memoTypes.edit', defaultMessage: 'Edit {name}' },
})

const SEQUENTIAL = 'sequential-approval'

const policyLabels = { INITIATOR_DEFINED: m.policyInitiator, BOTH: m.policyBoth, FIXED: m.policyFixed }

/** `/admin/memo-types` (design E01 §5, E03 §12): the types, each with its workflow and who chooses the approvers. */
export function MemoTypesPage() {
  const intl = useIntl()
  const types = useQuery(listAllMemoTypesOptions())
  const levels = useQuery(listClassificationLevelsOptions())
  const definitions = useQuery(listMemoTypeDefinitionsOptions())
  const [editing, setEditing] = useState<MemoType | 'new'>()
  const levelName = (id: string | null | undefined) =>
    id ? (levels.data?.find((l) => l.id === id)?.name ?? '') : intl.formatMessage(m.lowest)

  const columns: DataTableColumn<MemoType>[] = [
    {
      id: 'name',
      header: intl.formatMessage(m.colName),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-2 font-medium">
          {row.original.name}
          {row.original.active ? null : <Badge tone="neutral">{intl.formatMessage(m.inactive)}</Badge>}
        </span>
      ),
    },
    {
      id: 'default',
      header: intl.formatMessage(m.colDefault),
      cell: ({ row }) => levelName(row.original.defaultClassificationId),
    },
    {
      id: 'workflow',
      header: intl.formatMessage(m.colWorkflow),
      cell: ({ row }) =>
        definitions.data?.find((d) => d.key === row.original.definitionKey)?.name ?? row.original.definitionKey,
    },
    {
      id: 'stepUp',
      header: intl.formatMessage(m.colStepUp),
      cell: ({ row }) => intl.formatMessage(row.original.stepUpOnApprove ? m.yes : m.no),
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{intl.formatMessage(m.colActions)}</span>,
      cell: ({ row }) => (
        <Button
          variant="outline"
          onClick={() => {
            setEditing(row.original)
          }}
        >
          {intl.formatMessage(mm.actionEdit)}
        </Button>
      ),
    },
  ]

  return (
    <section aria-labelledby="memo-types-title">
      <PageHeader
        title={<span id="memo-types-title">{intl.formatMessage(m.title)}</span>}
        description={intl.formatMessage(m.lead)}
        actions={
          <Button
            onClick={() => {
              setEditing('new')
            }}
          >
            <Plus aria-hidden="true" />
            {intl.formatMessage(m.add)}
          </Button>
        }
      />
      <DataTable
        caption={intl.formatMessage(m.caption)}
        columns={columns}
        rows={types.data ?? []}
        getRowId={(row) => row.id}
        state={types.isPending ? 'loading' : types.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void types.refetch() }}
      />
      {editing ? (
        <TypeDialog
          type={editing === 'new' ? undefined : editing}
          levels={levels.data ?? []}
          definitions={definitions.data ?? []}
          onClose={() => {
            setEditing(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function TypeDialog({
  type,
  levels,
  definitions,
  onClose,
}: {
  type?: MemoType
  levels: { id: string; name: string }[]
  definitions: DefinitionChoice[]
  onClose: () => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [key] = useState(() => newIdempotencyKey())
  const [name, setName] = useState(type?.name ?? '')
  const [level, setLevel] = useState(type?.defaultClassificationId ?? '')
  const [stepUp, setStepUp] = useState(type?.stepUpOnApprove ?? true)
  const [active, setActive] = useState(type?.active ?? true)
  const [definitionKey, setDefinitionKey] = useState(type?.definitionKey ?? SEQUENTIAL)
  const [policy, setPolicy] = useState<MemoType['routePolicy']>(type?.routePolicy ?? 'INITIATOR_DEFINED')
  const [allowed, setAllowed] = useState<AllowedApprovers>(type?.allowedApprovers ?? {})
  const [allowedNames, setAllowedNames] = useState<Record<string, string>>(type?.allowedNames ?? {})
  const [doaCheck, setDoaCheck] = useState(type?.doaCheck ?? false)
  const routed = definitions.find((d) => d.key === definitionKey)?.routed ?? true
  const policies: MemoType['routePolicy'][] = routed ? ['INITIATOR_DEFINED', 'BOTH'] : ['FIXED']
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const save = async () => {
    if (!name.trim()) {
      setError(intl.formatMessage(mm.required))
      return
    }
    setBusy(true)
    const body = {
      name: name.trim(),
      defaultClassificationId: level || undefined,
      stepUpOnApprove: stepUp,
      active,
      definitionKey,
      routePolicy: policy,
      allowedApprovers: allowed,
      doaCheck,
    }
    try {
      if (type) {
        await updateMemoType({
          path: { id: type.id },
          headers: { 'If-Match': `"${type.version}"` },
          body,
          throwOnError: true,
        })
      } else {
        await createMemoType({ headers: { 'Idempotency-Key': key }, body, throwOnError: true })
      }
      await queryClient.invalidateQueries({ queryKey: listAllMemoTypesQueryKey() })
      await queryClient.invalidateQueries({ queryKey: listMemoTypesQueryKey() })
      toast.show(intl.formatMessage(mm.done), 'success')
      onClose()
    } catch (problem) {
      if (isProblem(problem) && problem.reason === 'LAST_ACTIVE') setError(intl.formatMessage(m.lastActive))
      else setError((isProblem(problem) ? problem.errors?.[0]?.message : undefined) ?? intl.formatMessage(mm.failed))
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
      title={type ? intl.formatMessage(mv.edit, { name: type.name }) : intl.formatMessage(m.add)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(mm.cancel)}
          </Button>
          <Button disabled={busy} onClick={() => void save()}>
            {intl.formatMessage(mm.save)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={intl.formatMessage(m.colName)} required error={error}>
          {(p) => (
            <Input
              {...p}
              maxLength={200}
              value={name}
              onChange={(e) => {
                setName(e.target.value)
                setError(undefined)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(m.colDefault)}>
          {(p) => (
            <Select
              {...p}
              value={level}
              onChange={(e) => {
                setLevel(e.target.value)
              }}
            >
              <option value="">{intl.formatMessage(m.lowest)}</option>
              {levels.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(m.colWorkflow)} hint={intl.formatMessage(m.workflowHint)}>
          {(p) => (
            <Select
              {...p}
              value={definitionKey}
              onChange={(e) => {
                const next = e.target.value
                setDefinitionKey(next)
                // The policy has to fit the definition: FIXED exactly when it has no route step.
                const nextRouted = definitions.find((d) => d.key === next)?.routed ?? true
                setPolicy(nextRouted ? (policy === 'FIXED' ? 'INITIATOR_DEFINED' : policy) : 'FIXED')
              }}
            >
              {definitions.length === 0 ? <option value={definitionKey}>{definitionKey}</option> : null}
              {definitions.map((d) => (
                <option key={d.key} value={d.key}>
                  {d.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(m.policy)}>
          {(p) => (
            <Select
              {...p}
              value={policy}
              onChange={(e) => {
                setPolicy(e.target.value as MemoType['routePolicy'])
              }}
            >
              {policies.map((option) => (
                <option key={option} value={option}>
                  {intl.formatMessage(policyLabels[option])}
                </option>
              ))}
            </Select>
          )}
        </Field>
        {policy !== 'FIXED' ? (
          <>
            <AllowedApproversEditor
              value={allowed}
              names={allowedNames}
              onChange={(next, names) => {
                setAllowed(next)
                setAllowedNames(names)
              }}
            />
            <label className="flex items-center gap-2 text-sm">
              <Checkbox
                checked={doaCheck}
                onChange={(e) => {
                  setDoaCheck(e.target.checked)
                }}
              />
              {intl.formatMessage(m.doaCheck)}
            </label>
          </>
        ) : null}
        <label className="flex items-center gap-2 text-sm">
          <Checkbox
            checked={stepUp}
            onChange={(e) => {
              setStepUp(e.target.checked)
            }}
          />
          {intl.formatMessage(m.stepUp)}
        </label>
        <label className="flex items-center gap-2 text-sm">
          <Checkbox
            checked={active}
            onChange={(e) => {
              setActive(e.target.checked)
            }}
          />
          {intl.formatMessage(m.active)}
        </label>
      </div>
    </Dialog>
  )
}
