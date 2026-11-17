import { useQuery, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { listDistributionListsOptions, listDistributionListsQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { createDistributionList, deactivateDistributionList, replaceDistributionList } from '@/api/generated/sdk.gen'
import type { DistributionListView, ListMemberView } from '@/api/generated/types.gen'
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
  title: { id: 'memo.lists.title', defaultMessage: 'Distribution lists' },
  lead: {
    id: 'memo.lists.lead',
    defaultMessage:
      'Named groups of people and positions to copy memos to. A list is expanded when the copy is sent: whoever holds a position then gets it.',
  },
  caption: { id: 'memo.lists.caption', defaultMessage: 'Distribution lists' },
  colName: { id: 'memo.lists.col.name', defaultMessage: 'Name' },
  colMembers: { id: 'memo.lists.col.members', defaultMessage: 'Members' },
  colActions: { id: 'memo.lists.col.actions', defaultMessage: 'Actions' },
  empty: { id: 'memo.lists.empty', defaultMessage: 'No distribution lists yet.' },
  add: { id: 'memo.lists.add', defaultMessage: 'Add list' },
  edit: { id: 'memo.lists.edit', defaultMessage: 'Edit' },
  name: { id: 'memo.lists.name', defaultMessage: 'Name' },
  memberKind: { id: 'memo.lists.memberKind', defaultMessage: 'Add a' },
  person: { id: 'memo.lists.person', defaultMessage: 'Person' },
  position: { id: 'memo.lists.position', defaultMessage: 'Position' },
  member: { id: 'memo.lists.member', defaultMessage: 'Member' },
  addMember: { id: 'memo.lists.addMember', defaultMessage: 'Add member' },
  noMembers: { id: 'memo.lists.noMembers', defaultMessage: 'No members yet.' },
  required: { id: 'memo.lists.required', defaultMessage: 'A name and at least one member are required.' },
  failed: { id: 'memo.lists.failed', defaultMessage: 'That did not work. Try again.' },
  conflict: { id: 'memo.lists.conflict', defaultMessage: 'Someone changed this list meanwhile. Open it again.' },
  cancel: { id: 'memo.lists.cancel', defaultMessage: 'Cancel' },
  save: { id: 'memo.lists.save', defaultMessage: 'Save' },
  switchOff: { id: 'memo.lists.switchOff', defaultMessage: 'Switch off' },
  done: { id: 'memo.lists.done', defaultMessage: 'Saved.' },
  switchedOff: {
    id: 'memo.lists.switchedOff',
    defaultMessage: 'The list is switched off. Memos that used it keep it.',
  },
})

const mv = defineMessages<{ editName: { name: string }; remove: { name: string } }>({
  editName: { id: 'memo.lists.editName', defaultMessage: 'Edit {name}' },
  remove: { id: 'memo.lists.remove', defaultMessage: 'Remove {name}' },
})

/** `/admin/distribution-lists` (design E03 §5, flow D3): lists for "Copy to", for holders of `memo.distribution.manage`. */
export function DistributionListsPage() {
  const intl = useIntl()
  const lists = useQuery(listDistributionListsOptions())
  const [editing, setEditing] = useState<DistributionListView | 'new'>()

  const columns: DataTableColumn<DistributionListView>[] = [
    { id: 'name', header: intl.formatMessage(m.colName), cell: ({ row }) => row.original.name },
    {
      id: 'members',
      header: intl.formatMessage(m.colMembers),
      cell: ({ row }) => row.original.members.map((x) => x.name).join(', '),
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
    <section aria-labelledby="lists-title">
      <PageHeader
        title={<span id="lists-title">{intl.formatMessage(m.title)}</span>}
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
        rows={lists.data ?? []}
        getRowId={(row) => row.id}
        state={lists.isPending ? 'loading' : lists.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void lists.refetch() }}
        empty={intl.formatMessage(m.empty)}
      />
      {editing ? (
        <ListDialog
          list={editing === 'new' ? undefined : editing}
          onClose={() => {
            setEditing(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function ListDialog({ list, onClose }: { list?: DistributionListView; onClose: () => void }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [name, setName] = useState(list?.name ?? '')
  const [members, setMembers] = useState<ListMemberView[]>(list?.members ?? [])
  const [kind, setKind] = useState<ListMemberView['kind']>('USER')
  const [picked, setPicked] = useState<Picked>()
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const addMember = () => {
    if (!picked || members.some((x) => x.kind === kind && x.id === picked.id)) return
    setMembers([...members, { kind, id: picked.id, name: picked.label }])
    setPicked(undefined)
  }

  const run = async (call: () => Promise<unknown>, message: string) => {
    setBusy(true)
    try {
      await call()
      await queryClient.invalidateQueries({ queryKey: listDistributionListsQueryKey() })
      toast.show(message, 'success')
      onClose()
    } catch (problem) {
      const p = isProblem(problem) ? problem : undefined
      if (p?.status === 412) setError(intl.formatMessage(m.conflict))
      else setError(p?.errors?.[0]?.message ?? p?.detail ?? intl.formatMessage(m.failed))
    } finally {
      setBusy(false)
    }
  }

  const save = () => {
    if (!name.trim() || members.length === 0) {
      setError(intl.formatMessage(m.required))
      return
    }
    const body = { name: name.trim(), members: members.map(({ kind: k, id }) => ({ kind: k, id })) }
    void run(
      () =>
        list
          ? replaceDistributionList({
              path: { id: list.id },
              headers: { 'If-Match': `"${String(list.version)}"` },
              body,
              throwOnError: true,
            })
          : createDistributionList({ body, throwOnError: true }),
      intl.formatMessage(m.done),
    )
  }

  const switchOff = (current: DistributionListView) => {
    void run(
      () =>
        deactivateDistributionList({
          path: { id: current.id },
          headers: { 'If-Match': `"${String(current.version)}"` },
          throwOnError: true,
        }),
      intl.formatMessage(m.switchedOff),
    )
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={list ? intl.formatMessage(mv.editName, { name: list.name }) : intl.formatMessage(m.add)}
      footer={
        <>
          {list ? (
            <Button
              variant="destructive"
              className="me-auto"
              disabled={busy}
              onClick={() => {
                switchOff(list)
              }}
            >
              {intl.formatMessage(m.switchOff)}
            </Button>
          ) : null}
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(m.cancel)}
          </Button>
          <Button disabled={busy} onClick={save}>
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
                  setKind(e.target.value as ListMemberView['kind'])
                  setPicked(undefined)
                }}
              >
                <option value="USER">{intl.formatMessage(m.person)}</option>
                <option value="POSITION">{intl.formatMessage(m.position)}</option>
              </Select>
            )}
          </Field>
          <Field label={intl.formatMessage(m.member)} className="min-w-56 flex-1">
            {(p) => (
              <PeoplePicker
                key={kind}
                id={p.id}
                kind={kind === 'USER' ? 'user' : 'position'}
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
