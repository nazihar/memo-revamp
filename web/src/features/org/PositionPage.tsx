import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { getPositionOptions } from '@/api/generated/@tanstack/react-query.gen'
import {
  assignPosition,
  cancelAssignment,
  closePosition,
  createReportingLine,
  updateAssignment,
  updatePosition,
  updateReportingLine,
} from '@/api/generated/sdk.gen'
import type { OrgAssignment, OrgPositionDetail } from '@/api/generated/types.gen'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge, type Tone } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { AlertDialog } from '@/components/ui/dialog'
import { useToast } from '@/components/ui/toast-context'
import { HistoryList } from './HistoryList'
import { DateDialog, PickAndDateDialog, PositionFormDialog } from './OrgDialogs'
import { om, omv, orgProblemMessage, today } from './org-messages'

type Pending =
  | { kind: 'edit' }
  | { kind: 'close' }
  | { kind: 'assign' }
  | { kind: 'line' }
  | { kind: 'end'; assignment: OrgAssignment }
  | { kind: 'cancel'; assignment: OrgAssignment }

function phase(a: OrgAssignment, on: string): { label: keyof typeof om; tone: Tone } {
  if (a.status === 'CANCELLED') return { label: 'cancelled', tone: 'neutral' }
  if (a.validFrom > on) return { label: 'upcoming', tone: 'warn' }
  if (a.validTo && a.validTo <= on) return { label: 'past', tone: 'neutral' }
  return { label: 'current', tone: 'ok' }
}

/** `/org/positions/$positionId` (design E04 §13): who held the position when, whom it reports to, and its history. */
export function PositionPage({ positionId }: { positionId: string }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const detail = useQuery(getPositionOptions({ path: { id: positionId } }))
  const [pending, setPending] = useState<Pending>()
  if (detail.isPending) return <LoadingState />
  if (detail.isError) return <ErrorState onRetry={() => void detail.refetch()} />
  const { position, unit, assignments, reportingLines, canManage } = detail.data
  const now = today()
  const refresh = () => queryClient.invalidateQueries()
  const currentLine = reportingLines.find(
    (l) => l.status === 'ACTIVE' && l.validFrom <= now && (!l.validTo || l.validTo > now),
  )

  const columns: DataTableColumn<OrgAssignment>[] = [
    { id: 'person', header: intl.formatMessage(om.colPerson), cell: ({ row }) => row.original.person.displayName },
    {
      id: 'from',
      header: intl.formatMessage(om.colFrom),
      cell: ({ row }) => intl.formatDate(row.original.validFrom, { dateStyle: 'medium' }),
    },
    {
      id: 'until',
      header: intl.formatMessage(om.colUntil),
      cell: ({ row }) =>
        row.original.validTo
          ? intl.formatDate(row.original.validTo, { dateStyle: 'medium' })
          : intl.formatMessage(om.ongoing),
    },
    {
      id: 'status',
      header: intl.formatMessage(om.colStatus),
      cell: ({ row }) => {
        const p = phase(row.original, now)
        return <Badge tone={p.tone}>{intl.formatMessage(om[p.label])}</Badge>
      },
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{intl.formatMessage(om.colActions)}</span>,
      cell: ({ row }) =>
        canManage && row.original.status === 'ACTIVE' ? (
          <span className="flex flex-wrap justify-end gap-1">
            {!row.original.validTo || row.original.validTo > now ? (
              <Button
                variant="ghost"
                onClick={() => {
                  setPending({ kind: 'end', assignment: row.original })
                }}
              >
                {intl.formatMessage(om.end)}
              </Button>
            ) : null}
            <Button
              variant="ghost"
              onClick={() => {
                setPending({ kind: 'cancel', assignment: row.original })
              }}
            >
              {intl.formatMessage(om.cancelAssignment)}
            </Button>
          </span>
        ) : null,
    },
  ]

  return (
    <section aria-labelledby="position-title">
      <p className="mb-2 text-sm">
        <Link
          to="/org"
          search={{ unit: unit.id }}
          className="text-muted-foreground hover:text-foreground hover:underline"
        >
          {intl.formatMessage(omv.backToUnit, { unit: unit.name })}
        </Link>
      </p>
      <PageHeader
        title={<span id="position-title">{position.title}</span>}
        status={position.head ? <Badge tone="neutral">{intl.formatMessage(om.head)}</Badge> : undefined}
        description={`${intl.formatMessage(om.unit)}: ${unit.name}`}
        actions={
          canManage && !position.validTo ? (
            <>
              <Button
                onClick={() => {
                  setPending({ kind: 'assign' })
                }}
              >
                {intl.formatMessage(om.assign)}
              </Button>
              <Button
                variant="outline"
                onClick={() => {
                  setPending({ kind: 'edit' })
                }}
              >
                {intl.formatMessage(om.edit)}
              </Button>
              <Button
                variant="outline"
                onClick={() => {
                  setPending({ kind: 'close' })
                }}
              >
                {intl.formatMessage(om.close)}
              </Button>
            </>
          ) : undefined
        }
      />
      <div className="mb-6 flex flex-wrap items-center gap-3 rounded-md border px-4 py-3 text-sm">
        <span className="font-medium">{intl.formatMessage(om.reportsTo)}</span>
        {currentLine ? (
          <Link
            to="/org/positions/$positionId"
            params={{ positionId: currentLine.reportsTo.id }}
            className="hover:underline"
          >
            {currentLine.reportsTo.title}
          </Link>
        ) : (
          <span className="text-muted-foreground">{intl.formatMessage(om.reportsToNone)}</span>
        )}
        {canManage && !position.validTo ? (
          <Button
            variant="ghost"
            className="ms-auto"
            onClick={() => {
              setPending({ kind: 'line' })
            }}
          >
            {intl.formatMessage(om.changeLine)}
          </Button>
        ) : null}
      </div>
      <h2 className="mb-2 text-base font-semibold">{intl.formatMessage(om.holders)}</h2>
      <DataTable
        caption={intl.formatMessage(om.holdersCaption)}
        columns={columns}
        rows={assignments}
        getRowId={(a) => a.id}
        state="ready"
        empty={intl.formatMessage(om.noHolders)}
      />
      <HistoryList type="position" id={position.id} />
      {pending ? (
        <PositionActions
          detail={detail.data}
          pending={pending}
          currentLineId={currentLine?.id}
          onClose={() => {
            setPending(undefined)
          }}
          onDone={refresh}
        />
      ) : null}
    </section>
  )
}

function PositionActions({
  detail,
  pending,
  currentLineId,
  onClose,
  onDone,
}: {
  detail: OrgPositionDetail
  pending: Pending
  currentLineId?: string
  onClose: () => void
  onDone: () => Promise<unknown>
}) {
  const intl = useIntl()
  const toast = useToast()
  const { position, reportingLines } = detail
  const [key] = useState(() => ({ 'Idempotency-Key': crypto.randomUUID() }))
  const confirmed = useRef(false)
  const after = async <T,>(call: Promise<T>) => {
    const result = await call
    await onDone()
    return result
  }
  const ifMatch = (version: number) => ({ 'If-Match': `"${String(version)}"` })

  switch (pending.kind) {
    case 'edit':
      return (
        <PositionFormDialog
          title={intl.formatMessage(om.editPosition)}
          initial={{ title: position.title, ...(position.code ? { code: position.code } : {}), head: position.head }}
          onSave={(body) =>
            after(
              updatePosition({
                path: { id: position.id },
                headers: ifMatch(position.version),
                body,
                throwOnError: true,
              }),
            )
          }
          onClose={onClose}
        />
      )
    case 'close':
      return (
        <DateDialog
          title={intl.formatMessage(omv.closeTitle, { name: position.title })}
          description={intl.formatMessage(om.closeLead)}
          label={intl.formatMessage(om.closeDate)}
          submitLabel={intl.formatMessage(om.close)}
          onSave={(validTo) =>
            after(
              closePosition({
                path: { id: position.id },
                headers: ifMatch(position.version),
                body: { validTo },
                throwOnError: true,
              }),
            )
          }
          onClose={onClose}
        />
      )
    case 'assign':
      return (
        <PickAndDateDialog
          title={intl.formatMessage(omv.assignTitle, { position: position.title })}
          kind="user"
          pickLabel={intl.formatMessage(om.person)}
          submitLabel={intl.formatMessage(om.assign)}
          onSave={(person, validFrom) =>
            after(
              assignPosition({
                path: { id: position.id },
                headers: key,
                body: { userId: person.id, validFrom },
                throwOnError: true,
              }),
            )
          }
          onClose={onClose}
        />
      )
    case 'line':
      return (
        <PickAndDateDialog
          title={intl.formatMessage(omv.lineTitle, { position: position.title })}
          kind="position"
          pickLabel={intl.formatMessage(om.linePosition)}
          submitLabel={intl.formatMessage(om.save)}
          onSave={async (target, validFrom) => {
            // The current line ends where the new one starts.
            const current = reportingLines.find((l) => l.id === currentLineId)
            if (current) {
              await updateReportingLine({
                path: { id: current.id },
                headers: ifMatch(current.version),
                body: { validFrom: current.validFrom, validTo: validFrom },
                throwOnError: true,
              })
            }
            return after(
              createReportingLine({
                path: { id: position.id },
                headers: key,
                body: { reportsToPositionId: target.id, validFrom },
                throwOnError: true,
              }),
            )
          }}
          onClose={onClose}
        />
      )
    case 'end':
      return (
        <DateDialog
          title={intl.formatMessage(omv.endTitle, { name: pending.assignment.person.displayName })}
          label={intl.formatMessage(om.endDate)}
          submitLabel={intl.formatMessage(om.end)}
          onSave={(validTo) =>
            after(
              updateAssignment({
                path: { id: pending.assignment.id },
                headers: ifMatch(pending.assignment.version),
                body: { validFrom: pending.assignment.validFrom, validTo },
                throwOnError: true,
              }),
            )
          }
          onClose={onClose}
        />
      )
    case 'cancel':
      return (
        <AlertDialog
          open
          onOpenChange={(open) => {
            if (!open && !confirmed.current) onClose()
          }}
          title={intl.formatMessage(om.cancelAssignmentTitle)}
          description={intl.formatMessage(om.cancelAssignmentLead)}
          confirmLabel={intl.formatMessage(om.cancelAssignment)}
          cancelLabel={intl.formatMessage(om.keep)}
          destructive
          onConfirm={() => {
            confirmed.current = true
            after(
              cancelAssignment({
                path: { id: pending.assignment.id },
                headers: ifMatch(pending.assignment.version),
                throwOnError: true,
              }),
            )
              .then(() => {
                toast.show(intl.formatMessage(om.saved))
              })
              .catch((error: unknown) => {
                toast.show(orgProblemMessage(intl, error), 'error')
              })
              .finally(onClose)
          }}
        />
      )
  }
}
