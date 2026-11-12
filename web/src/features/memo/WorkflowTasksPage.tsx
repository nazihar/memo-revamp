import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Lock } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { listOpenTasksOptions, listOpenTasksQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { reassignTask } from '@/api/generated/sdk.gen'
import type { AdminTaskResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { isolated } from './memo-fields'
import { mm, mmv } from './memo-messages'
import { newIdempotencyKey } from '@/lib/idempotency'

const m = defineMessages({
  title: { id: 'workflowTasks.title', defaultMessage: 'Open tasks' },
  lead: {
    id: 'workflowTasks.lead',
    defaultMessage: 'Give a task to someone else when its approver cannot act, for example after they left.',
  },
  filterBlocked: { id: 'workflowTasks.filter.blocked', defaultMessage: 'Waiting for reassignment' },
  filterOpen: { id: 'workflowTasks.filter.open', defaultMessage: 'All open tasks' },
  caption: { id: 'workflowTasks.caption', defaultMessage: 'Open workflow tasks' },
  colMemo: { id: 'workflowTasks.col.memo', defaultMessage: 'Memo' },
  colAssignee: { id: 'workflowTasks.col.assignee', defaultMessage: 'Approver' },
  colSince: { id: 'workflowTasks.col.since', defaultMessage: 'Waiting since' },
  colActions: { id: 'workflowTasks.col.actions', defaultMessage: 'Actions' },
  reassign: { id: 'workflowTasks.reassign', defaultMessage: 'Reassign' },
  reassignLead: {
    id: 'workflowTasks.reassignLead',
    defaultMessage: 'The new approver must be active and cleared for the memo. Both approvers are told.',
  },
  newAssignee: { id: 'workflowTasks.newAssignee', defaultMessage: 'New approver' },
  reason: { id: 'workflowTasks.reason', defaultMessage: 'Reason' },
  empty: { id: 'workflowTasks.empty', defaultMessage: 'No task matches.' },
  NO_CLEARANCE: { id: 'workflowTasks.blocked.clearance', defaultMessage: 'Not cleared' },
  INACTIVE: { id: 'workflowTasks.blocked.inactive', defaultMessage: 'Cannot sign in' },
  UNRESOLVED: { id: 'workflowTasks.blocked.unresolved', defaultMessage: 'Nobody found for this step' },
  nobody: { id: 'workflowTasks.nobody', defaultMessage: 'Nobody yet' },
  ASSIGNEE_NOT_ELIGIBLE: {
    id: 'workflowTasks.refused.notEligible',
    defaultMessage: 'This person is inactive or not cleared for the memo.',
  },
  ASSIGNEE_IN_ROUND: { id: 'workflowTasks.refused.inRound', defaultMessage: 'This person is already on the route.' },
  INITIATOR_AS_APPROVER: { id: 'workflowTasks.refused.initiator', defaultMessage: 'The author cannot approve.' },
  SAME_ASSIGNEE: { id: 'workflowTasks.refused.same', defaultMessage: 'This person holds the task already.' },
})

const mv = defineMessages<{ reassignFor: { reference: string } }>({
  reassignFor: { id: 'workflowTasks.reassignFor', defaultMessage: 'Reassign {reference}' },
})

/** `/admin/workflow/tasks` (ux-review §7): open tasks, blocked first by filter, and reassignment with a reason. */
export function WorkflowTasksPage() {
  const intl = useIntl()
  const [filter, setFilter] = useState<'blocked' | 'open'>('blocked')
  const query = { query: { filter, limit: 50 } }
  const list = useQuery(listOpenTasksOptions(query))
  const [reassigning, setReassigning] = useState<AdminTaskResponse>()

  const columns: DataTableColumn<AdminTaskResponse>[] = [
    {
      id: 'memo',
      header: intl.formatMessage(m.colMemo),
      cell: ({ row }) => {
        const s = row.original.subject
        return (
          <div className="flex flex-col">
            <span className="flex items-center gap-1.5 font-medium">
              {s.restricted ? <Lock aria-hidden="true" className="size-3.5" /> : null}
              {s.restricted ? intl.formatMessage(mm.restricted) : s.title}
            </span>
            <span className="text-xs text-muted-foreground">
              {intl.formatMessage(mmv.referenceRound, { reference: isolated(s.reference), round: row.original.round })}
            </span>
          </div>
        )
      },
    },
    {
      id: 'assignee',
      header: intl.formatMessage(m.colAssignee),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-2">
          {row.original.assignee?.name ?? intl.formatMessage(m.nobody)}
          {row.original.blocked ? <Badge tone="warn">{intl.formatMessage(m[row.original.blocked])}</Badge> : null}
        </span>
      ),
    },
    {
      id: 'since',
      header: intl.formatMessage(m.colSince),
      cell: ({ row }) => intl.formatDate(row.original.createdAt, { dateStyle: 'medium', timeStyle: 'short' }),
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{intl.formatMessage(m.colActions)}</span>,
      cell: ({ row }) => (
        <Button
          variant="outline"
          onClick={() => {
            setReassigning(row.original)
          }}
        >
          {intl.formatMessage(m.reassign)}
        </Button>
      ),
    },
  ]

  return (
    <section aria-labelledby="workflow-tasks-title">
      <PageHeader
        title={<span id="workflow-tasks-title">{intl.formatMessage(m.title)}</span>}
        description={intl.formatMessage(m.lead)}
      />
      <div className="mb-4 flex gap-2">
        {(['blocked', 'open'] as const).map((f) => (
          <Button
            key={f}
            variant={f === filter ? 'default' : 'outline'}
            aria-pressed={f === filter}
            onClick={() => {
              setFilter(f)
            }}
          >
            {intl.formatMessage(f === 'blocked' ? m.filterBlocked : m.filterOpen)}
          </Button>
        ))}
      </div>
      <DataTable
        caption={intl.formatMessage(m.caption)}
        columns={columns}
        rows={list.data?.items ?? []}
        getRowId={(row) => row.taskId}
        state={list.isPending ? 'loading' : list.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void list.refetch() }}
        empty={intl.formatMessage(m.empty)}
      />
      {reassigning ? (
        <ReassignDialog
          task={reassigning}
          onClose={() => {
            setReassigning(undefined)
          }}
          listKey={listOpenTasksQueryKey(query)}
        />
      ) : null}
    </section>
  )
}

function ReassignDialog({
  task,
  onClose,
  listKey,
}: {
  task: AdminTaskResponse
  onClose: () => void
  listKey: unknown[]
}) {
  const intl = useIntl()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const queryClient = useQueryClient()
  const [key] = useState(() => newIdempotencyKey())
  const [picked, setPicked] = useState<Picked>()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const confirm = async () => {
    if (!picked || !reason.trim()) {
      setError(intl.formatMessage(mm.required))
      return
    }
    setBusy(true)
    try {
      await withStepUp(() =>
        reassignTask({
          path: { taskId: task.taskId },
          headers: { 'Idempotency-Key': key },
          body: { newAssigneeId: picked.id, reason: reason.trim() },
          throwOnError: true,
        }),
      )
      toast.show(intl.formatMessage(mm.done), 'success')
      await queryClient.invalidateQueries({ queryKey: listKey })
      onClose()
    } catch (problem) {
      const reasonCode = isProblem(problem) ? problem.reason : undefined
      setError(
        reasonCode && reasonCode in m
          ? intl.formatMessage(m[reasonCode as keyof typeof m])
          : intl.formatMessage(mm.failed),
      )
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
      title={intl.formatMessage(mv.reassignFor, { reference: isolated(task.subject.reference) })}
      description={intl.formatMessage(m.reassignLead)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(mm.cancel)}
          </Button>
          <Button disabled={busy} onClick={() => void confirm()}>
            {intl.formatMessage(m.reassign)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <label htmlFor="reassign-picker" className="text-sm font-medium">
            {intl.formatMessage(m.newAssignee)}
          </label>
          <PeoplePicker id="reassign-picker" kind="user" value={picked} onChange={setPicked} />
        </div>
        <Field label={intl.formatMessage(m.reason)} required error={error}>
          {(p) => (
            <Textarea
              {...p}
              rows={3}
              maxLength={4000}
              value={reason}
              onChange={(e) => {
                setReason(e.target.value)
                setError(undefined)
              }}
            />
          )}
        </Field>
      </div>
    </Dialog>
  )
}
