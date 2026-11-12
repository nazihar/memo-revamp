import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { listSubstitutionsOptions, listSubstitutionsQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { applySubstitution, createSubstitution, endSubstitution } from '@/api/generated/sdk.gen'
import type { SubstitutionResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input, Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'

const m = defineMessages({
  title: { id: 'workflows.substitutions.title', defaultMessage: 'Substitutions' },
  lead: {
    id: 'workflows.substitutions.lead',
    defaultMessage:
      'While a substitution is in force, new tasks for the person go to their substitute, who acts for them. Never on a memo the substitute wrote or has an interest in.',
  },
  caption: { id: 'workflows.substitutions.caption', defaultMessage: 'Substitutions, newest first' },
  colPerson: { id: 'workflows.substitutions.col.person', defaultMessage: 'Person' },
  colSubstitute: { id: 'workflows.substitutions.col.substitute', defaultMessage: 'Substitute' },
  colDates: { id: 'workflows.substitutions.col.dates', defaultMessage: 'Dates' },
  colReason: { id: 'workflows.substitutions.col.reason', defaultMessage: 'Reason' },
  colActions: { id: 'workflows.substitutions.col.actions', defaultMessage: 'Actions' },
  empty: { id: 'workflows.substitutions.empty', defaultMessage: 'No substitutions yet.' },
  add: { id: 'workflows.substitutions.add', defaultMessage: 'Add substitution' },
  end: { id: 'workflows.substitutions.end', defaultMessage: 'End today' },
  apply: { id: 'workflows.substitutions.apply', defaultMessage: 'Move open tasks' },
  inForce: { id: 'workflows.substitutions.inForce', defaultMessage: 'In force' },
  planned: { id: 'workflows.substitutions.planned', defaultMessage: 'Planned' },
  ended: { id: 'workflows.substitutions.ended', defaultMessage: 'Ended' },
  person: { id: 'workflows.substitutions.person', defaultMessage: 'Person away' },
  substitute: { id: 'workflows.substitutions.substitute', defaultMessage: 'Substitute' },
  from: { id: 'workflows.substitutions.from', defaultMessage: 'From' },
  to: { id: 'workflows.substitutions.to', defaultMessage: 'Until (not included)' },
  toHint: { id: 'workflows.substitutions.to.hint', defaultMessage: 'Leave empty until someone ends it.' },
  reason: { id: 'workflows.substitutions.reason', defaultMessage: 'Reason' },
  required: { id: 'workflows.substitutions.required', defaultMessage: 'Choose both people and a start date.' },
  OVERLAP: {
    id: 'workflows.substitutions.refused.overlap',
    defaultMessage: 'This person already has a substitute for some of these dates.',
  },
  NOT_IN_FORCE: {
    id: 'workflows.substitutions.refused.notInForce',
    defaultMessage: 'This substitution is not in force today.',
  },
  ENDED: { id: 'workflows.substitutions.refused.ended', defaultMessage: 'This substitution has ended.' },
  failed: { id: 'workflows.substitutions.failed', defaultMessage: 'That did not work. Try again.' },
  cancel: { id: 'workflows.substitutions.cancel', defaultMessage: 'Cancel' },
  save: { id: 'workflows.substitutions.save', defaultMessage: 'Save' },
  done: { id: 'workflows.substitutions.done', defaultMessage: 'Done.' },
})

const mv = defineMessages<{
  dates: { from: string; to: string }
  openEnded: { from: string }
  moved: { count: number }
}>({
  dates: { id: 'workflows.substitutions.dates', defaultMessage: '{from} to {to}' },
  openEnded: { id: 'workflows.substitutions.openEnded', defaultMessage: 'From {from}' },
  moved: {
    id: 'workflows.substitutions.moved',
    defaultMessage: '{count, plural, =0 {No open tasks moved.} one {# open task moved.} other {# open tasks moved.}}',
  },
})

type Phase = 'inForce' | 'planned' | 'ended'

/** Today as a calendar date; the server decides with the tenant's time zone and refuses what is not possible. */
function today(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

function phaseOf(row: SubstitutionResponse, date: string): Phase {
  if (row.validFrom > date) return 'planned'
  return row.validTo && row.validTo <= date ? 'ended' : 'inForce'
}

/**
 * `/admin/workflow/substitutions` (design E04 §4.5, §5): who acts for whom between which dates, for holders of
 * `workflow.tasks.reassign` until Phase 1b brings self-service. Moving a person's open tasks needs a step-up.
 */
export function SubstitutionsPage() {
  const intl = useIntl()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const queryClient = useQueryClient()
  const list = useQuery(listSubstitutionsOptions())
  const [adding, setAdding] = useState(false)
  const date = today()

  const refresh = () => queryClient.invalidateQueries({ queryKey: listSubstitutionsQueryKey() })
  const refusal = (problem: unknown) => {
    const reason = isProblem(problem) ? problem.reason : undefined
    toast.show(intl.formatMessage(reason && reason in m ? m[reason as keyof typeof m] : m.failed), 'error')
  }

  const end = async (row: SubstitutionResponse) => {
    try {
      await endSubstitution({ path: { id: row.id }, throwOnError: true })
      toast.show(intl.formatMessage(m.done), 'success')
      await refresh()
    } catch (problem) {
      refusal(problem)
    }
  }

  const apply = async (row: SubstitutionResponse) => {
    try {
      const result = await withStepUp(() => applySubstitution({ path: { id: row.id }, throwOnError: true }))
      toast.show(intl.formatMessage(mv.moved, { count: result.data.moved }), 'success')
    } catch (problem) {
      refusal(problem)
    }
  }

  const formatDate = (value: string) => intl.formatDate(`${value}T00:00:00`, { dateStyle: 'medium' })
  const columns: DataTableColumn<SubstitutionResponse>[] = [
    { id: 'person', header: intl.formatMessage(m.colPerson), cell: ({ row }) => row.original.principal.name },
    { id: 'substitute', header: intl.formatMessage(m.colSubstitute), cell: ({ row }) => row.original.substitute.name },
    {
      id: 'dates',
      header: intl.formatMessage(m.colDates),
      cell: ({ row }) => {
        const phase = phaseOf(row.original, date)
        const { validFrom, validTo } = row.original
        return (
          <span className="flex flex-wrap items-center gap-2">
            {validTo
              ? intl.formatMessage(mv.dates, { from: formatDate(validFrom), to: formatDate(validTo) })
              : intl.formatMessage(mv.openEnded, { from: formatDate(validFrom) })}
            <Badge tone={phase === 'inForce' ? 'ok' : 'neutral'}>{intl.formatMessage(m[phase])}</Badge>
          </span>
        )
      },
    },
    { id: 'reason', header: intl.formatMessage(m.colReason), cell: ({ row }) => row.original.reason ?? '' },
    {
      id: 'actions',
      header: () => <span className="sr-only">{intl.formatMessage(m.colActions)}</span>,
      cell: ({ row }) => {
        const phase = phaseOf(row.original, date)
        if (phase === 'ended') return null
        return (
          <span className="flex flex-wrap gap-2">
            {phase === 'inForce' ? (
              <Button variant="outline" onClick={() => void apply(row.original)}>
                {intl.formatMessage(m.apply)}
              </Button>
            ) : null}
            <Button variant="outline" onClick={() => void end(row.original)}>
              {intl.formatMessage(m.end)}
            </Button>
          </span>
        )
      },
    },
  ]

  return (
    <section aria-labelledby="substitutions-title">
      <PageHeader
        title={<span id="substitutions-title">{intl.formatMessage(m.title)}</span>}
        description={intl.formatMessage(m.lead)}
        actions={
          <Button
            onClick={() => {
              setAdding(true)
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
        getRowId={(row) => row.id}
        state={list.isPending ? 'loading' : list.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void list.refetch() }}
        empty={intl.formatMessage(m.empty)}
      />
      {adding ? (
        <AddDialog
          defaultFrom={date}
          onClose={() => {
            setAdding(false)
          }}
          onSaved={async () => {
            setAdding(false)
            toast.show(intl.formatMessage(m.done), 'success')
            await refresh()
          }}
        />
      ) : null}
    </section>
  )
}

function AddDialog({
  defaultFrom,
  onClose,
  onSaved,
}: {
  defaultFrom: string
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const intl = useIntl()
  const [principal, setPrincipal] = useState<Picked>()
  const [substitute, setSubstitute] = useState<Picked>()
  const [from, setFrom] = useState(defaultFrom)
  const [to, setTo] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const save = async () => {
    if (!principal || !substitute || !from) {
      setError(intl.formatMessage(m.required))
      return
    }
    setBusy(true)
    try {
      await createSubstitution({
        body: {
          principalId: principal.id,
          substituteId: substitute.id,
          validFrom: from,
          validTo: to || undefined,
          reason: reason.trim() || undefined,
        },
        throwOnError: true,
      })
      await onSaved()
    } catch (problem) {
      const code = isProblem(problem) ? problem.reason : undefined
      setError(
        code && code in m
          ? intl.formatMessage(m[code as keyof typeof m])
          : ((isProblem(problem) ? problem.detail : undefined) ?? intl.formatMessage(m.failed)),
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
      title={intl.formatMessage(m.add)}
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
        <Field label={intl.formatMessage(m.person)} required>
          {(p) => <PeoplePicker id={p.id} kind="user" value={principal} onChange={setPrincipal} />}
        </Field>
        <Field label={intl.formatMessage(m.substitute)} required>
          {(p) => <PeoplePicker id={p.id} kind="user" value={substitute} onChange={setSubstitute} />}
        </Field>
        <div className="flex flex-wrap gap-4">
          <Field label={intl.formatMessage(m.from)} required>
            {(p) => (
              <Input
                {...p}
                type="date"
                value={from}
                onChange={(e) => {
                  setFrom(e.target.value)
                }}
              />
            )}
          </Field>
          <Field label={intl.formatMessage(m.to)} hint={intl.formatMessage(m.toHint)}>
            {(p) => (
              <Input
                {...p}
                type="date"
                min={from}
                value={to}
                onChange={(e) => {
                  setTo(e.target.value)
                }}
              />
            )}
          </Field>
        </div>
        <Field label={intl.formatMessage(m.reason)}>
          {(p) => (
            <Textarea
              {...p}
              rows={2}
              maxLength={2000}
              value={reason}
              onChange={(e) => {
                setReason(e.target.value)
              }}
            />
          )}
        </Field>
      </div>
    </Dialog>
  )
}
