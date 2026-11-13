import { useQuery, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { listWorkflowQueuesOptions, listWorkflowQueuesQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { createWorkflowQueue, updateWorkflowQueue } from '@/api/generated/sdk.gen'
import type { QueueResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'

const m = defineMessages({
  title: { id: 'workflows.queues.title', defaultMessage: 'Queues' },
  lead: {
    id: 'workflows.queues.lead',
    defaultMessage:
      'A workflow step can go to a queue: every member gets the task, one claims it, and the first decision completes it. Members are people, or whoever holds a position.',
  },
  caption: { id: 'workflows.queues.caption', defaultMessage: 'Workflow queues' },
  colName: { id: 'workflows.queues.col.name', defaultMessage: 'Name' },
  colKey: { id: 'workflows.queues.col.key', defaultMessage: 'Key' },
  colMembers: { id: 'workflows.queues.col.members', defaultMessage: 'Members' },
  colActions: { id: 'workflows.queues.col.actions', defaultMessage: 'Actions' },
  empty: { id: 'workflows.queues.empty', defaultMessage: 'No queues yet.' },
  add: { id: 'workflows.queues.add', defaultMessage: 'Add queue' },
  edit: { id: 'workflows.queues.edit', defaultMessage: 'Edit' },
  key: { id: 'workflows.queues.key', defaultMessage: 'Key' },
  keyHint: {
    id: 'workflows.queues.key.hint',
    defaultMessage:
      'Definitions name the queue by its key: lower case letters, digits and dashes. It cannot change later.',
  },
  name: { id: 'workflows.queues.name', defaultMessage: 'Name' },
  memberKind: { id: 'workflows.queues.memberKind', defaultMessage: 'Add a' },
  person: { id: 'workflows.queues.person', defaultMessage: 'Person' },
  position: { id: 'workflows.queues.position', defaultMessage: 'Position' },
  member: { id: 'workflows.queues.member', defaultMessage: 'Member' },
  addMember: { id: 'workflows.queues.addMember', defaultMessage: 'Add member' },
  noMembers: { id: 'workflows.queues.noMembers', defaultMessage: 'No members yet.' },
  required: { id: 'workflows.queues.required', defaultMessage: 'A key and a name are required.' },
  KEY_TAKEN: { id: 'workflows.queues.refused.keyTaken', defaultMessage: 'A queue with this key exists.' },
  failed: { id: 'workflows.queues.failed', defaultMessage: 'That did not work. Try again.' },
  conflict: { id: 'workflows.queues.conflict', defaultMessage: 'Someone changed this queue meanwhile. Open it again.' },
  cancel: { id: 'workflows.queues.cancel', defaultMessage: 'Cancel' },
  save: { id: 'workflows.queues.save', defaultMessage: 'Save' },
  done: { id: 'workflows.queues.done', defaultMessage: 'Saved.' },
})

const mv = defineMessages<{ editName: { name: string }; remove: { name: string } }>({
  editName: { id: 'workflows.queues.editName', defaultMessage: 'Edit {name}' },
  remove: { id: 'workflows.queues.remove', defaultMessage: 'Remove {name}' },
})

const KEY = /^[a-z][a-z0-9-]{0,99}$/

interface Member {
  kind: 'person' | 'position'
  id: string
  name: string
}

function membersOf(queue: QueueResponse): Member[] {
  return queue.members.flatMap((m): Member[] => {
    if (m.user) return [{ kind: 'person', id: m.user.id, name: m.user.name }]
    if (m.positionId) return [{ kind: 'position', id: m.positionId, name: m.positionTitle ?? m.positionId }]
    return []
  })
}

/** `/admin/workflow/queues` (design E04 §4.2, §5): queues and their members, for holders of `workflow.queues.manage`. */
export function QueuesPage() {
  const intl = useIntl()
  const list = useQuery(listWorkflowQueuesOptions())
  const [editing, setEditing] = useState<QueueResponse | 'new'>()

  const columns: DataTableColumn<QueueResponse>[] = [
    { id: 'name', header: intl.formatMessage(m.colName), cell: ({ row }) => row.original.name },
    { id: 'key', header: intl.formatMessage(m.colKey), cell: ({ row }) => <code>{row.original.key}</code> },
    {
      id: 'members',
      header: intl.formatMessage(m.colMembers),
      cell: ({ row }) =>
        membersOf(row.original)
          .map((x) => x.name)
          .join(', '),
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{intl.formatMessage(m.colActions)}</span>,
      cell: ({ row }) => (
        <Button
          variant="outline"
          aria-label={intl.formatMessage(mv.editName, { name: row.original.name })}
          onClick={() => {
            setEditing(row.original)
          }}
        >
          {intl.formatMessage(m.edit)}
        </Button>
      ),
    },
  ]

  return (
    <section aria-labelledby="queues-title">
      <PageHeader
        title={<span id="queues-title">{intl.formatMessage(m.title)}</span>}
        description={intl.formatMessage(m.lead)}
        actions={
          <Button
            onClick={() => {
              setEditing('new')
            }}
          >
            {intl.formatMessage(m.add)}
          </Button>
        }
      />
      <DataTable
        caption={intl.formatMessage(m.caption)}
        columns={columns}
        rows={list.data ?? []}
        getRowId={(row) => row.key}
        state={list.isPending ? 'loading' : list.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void list.refetch() }}
        empty={intl.formatMessage(m.empty)}
      />
      {editing ? (
        <QueueDialog
          queue={editing === 'new' ? undefined : editing}
          onClose={() => {
            setEditing(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function QueueDialog({ queue, onClose }: { queue?: QueueResponse; onClose: () => void }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [key, setKey] = useState(queue?.key ?? '')
  const [name, setName] = useState(queue?.name ?? '')
  const [members, setMembers] = useState<Member[]>(queue ? membersOf(queue) : [])
  const [kind, setKind] = useState<Member['kind']>('person')
  const [picked, setPicked] = useState<Picked>()
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const addMember = () => {
    if (!picked || members.some((x) => x.kind === kind && x.id === picked.id)) return
    setMembers([...members, { kind, id: picked.id, name: picked.label }])
    setPicked(undefined)
  }

  const save = async () => {
    if (!name.trim() || (!queue && !KEY.test(key))) {
      setError(intl.formatMessage(m.required))
      return
    }
    setBusy(true)
    const body = {
      key: queue ? undefined : key,
      name: name.trim(),
      members: members.map((x) => (x.kind === 'person' ? { userId: x.id } : { positionId: x.id })),
    }
    try {
      if (queue) {
        await updateWorkflowQueue({
          path: { key: queue.key },
          headers: { 'If-Match': `"${queue.version}"` },
          body,
          throwOnError: true,
        })
      } else {
        await createWorkflowQueue({ body, throwOnError: true })
      }
      await queryClient.invalidateQueries({ queryKey: listWorkflowQueuesQueryKey() })
      toast.show(intl.formatMessage(m.done), 'success')
      onClose()
    } catch (problem) {
      const p = isProblem(problem) ? problem : undefined
      if (p?.status === 412 || p?.status === 409)
        setError(intl.formatMessage(p.reason === 'KEY_TAKEN' ? m.KEY_TAKEN : m.conflict))
      else setError(p?.errors?.[0]?.message ?? p?.detail ?? intl.formatMessage(m.failed))
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
      title={queue ? intl.formatMessage(mv.editName, { name: queue.name }) : intl.formatMessage(m.add)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(m.cancel)}
          </Button>
          <Button disabled={busy} onClick={() => void save()}>
            {intl.formatMessage(m.save)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {error ? (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        ) : null}
        <Field label={intl.formatMessage(m.key)} hint={intl.formatMessage(m.keyHint)} required>
          {(p) => (
            <Input
              {...p}
              value={key}
              disabled={Boolean(queue)}
              maxLength={100}
              onChange={(e) => {
                setKey(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(m.name)} required>
          {(p) => (
            <Input
              {...p}
              value={name}
              maxLength={200}
              onChange={(e) => {
                setName(e.target.value)
              }}
            />
          )}
        </Field>
        {members.length === 0 ? (
          <p className="text-sm text-muted-foreground">{intl.formatMessage(m.noMembers)}</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {members.map((x) => (
              <li key={`${x.kind}:${x.id}`} className="flex items-center gap-1 rounded-md border px-2 py-1 text-sm">
                {x.name}
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={intl.formatMessage(mv.remove, { name: x.name })}
                  onClick={() => {
                    setMembers(members.filter((y) => y !== x))
                  }}
                >
                  <X aria-hidden="true" />
                </Button>
              </li>
            ))}
          </ul>
        )}
        <div className="flex flex-wrap items-end gap-2">
          <Field label={intl.formatMessage(m.memberKind)}>
            {(p) => (
              <Select
                {...p}
                value={kind}
                onChange={(e) => {
                  setKind(e.target.value as Member['kind'])
                  setPicked(undefined)
                }}
              >
                <option value="person">{intl.formatMessage(m.person)}</option>
                <option value="position">{intl.formatMessage(m.position)}</option>
              </Select>
            )}
          </Field>
          <Field label={intl.formatMessage(m.member)} className="min-w-56 flex-1">
            {(p) => (
              <PeoplePicker
                key={kind}
                id={p.id}
                kind={kind === 'person' ? 'user' : 'position'}
                value={picked}
                onChange={setPicked}
              />
            )}
          </Field>
          <Button variant="outline" disabled={!picked} onClick={addMember}>
            {intl.formatMessage(m.addMember)}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
