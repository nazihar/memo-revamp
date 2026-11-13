import { useState } from 'react'
import { useIntl } from 'react-intl'
import { migrateWorkflowInstances, previewWorkflowMigration } from '@/api/generated/sdk.gen'
import type { MigrationItem, MigrationResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { newIdempotencyKey } from '@/lib/idempotency'
import { migrationReasons, wm, wmv } from './workflow-messages'

const MAX_BATCH = 100

/**
 * Moving running memos to the published version (design E03 §4.7): the preview lists the memos on other versions and
 * why some cannot move; the reviewer selects up to 100 and moves them after a step-up. The answer shows each memo as it
 * is afterwards, so a repeated request shows the same outcome.
 */
export function DefinitionMigration({ definitionKey }: { definitionKey: string }) {
  const intl = useIntl()
  const toast = useToast()
  const withStepUp = useWithStepUp()
  const [plan, setPlan] = useState<MigrationResponse>()
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [key, setKey] = useState(() => newIdempotencyKey())
  const [busy, setBusy] = useState(false)
  const path = { key: definitionKey }

  const preview = async () => {
    setBusy(true)
    try {
      const result = await previewWorkflowMigration({ path, body: {}, throwOnError: true })
      setPlan(result.data)
      setSelected(
        new Set(
          result.data.items
            .filter((i) => i.migratable)
            .slice(0, MAX_BATCH)
            .map((i) => i.instanceId),
        ),
      )
    } catch (problem) {
      toast.show((isProblem(problem) ? problem.detail : undefined) ?? intl.formatMessage(wm.failed), 'error')
    } finally {
      setBusy(false)
    }
  }

  const migrate = async () => {
    setBusy(true)
    try {
      const result = await withStepUp(() =>
        migrateWorkflowInstances({
          path,
          headers: { 'Idempotency-Key': key },
          body: { instanceIds: [...selected] },
          throwOnError: true,
        }),
      )
      const moved = result.data.items.filter((i) => i.migrated).length
      toast.show(intl.formatMessage(wmv.migrated, { count: moved }), 'success')
      setKey(newIdempotencyKey())
      await preview()
    } catch (problem) {
      toast.show((isProblem(problem) ? problem.detail : undefined) ?? intl.formatMessage(wm.failed), 'error')
    } finally {
      setBusy(false)
    }
  }

  const columns: DataTableColumn<MigrationItem>[] = [
    {
      id: 'select',
      header: () => <span className="sr-only">{intl.formatMessage(wm.colCan)}</span>,
      cell: ({ row }) => {
        const item = row.original
        const label = item.subject.reference ?? intl.formatMessage(wm.restricted)
        return item.migratable ? (
          <Checkbox
            aria-label={intl.formatMessage(wmv.selectMemo, { reference: label })}
            checked={selected.has(item.instanceId)}
            onChange={(e) => {
              const next = new Set(selected)
              if (e.target.checked && next.size < MAX_BATCH) next.add(item.instanceId)
              else next.delete(item.instanceId)
              setSelected(next)
            }}
          />
        ) : null
      },
    },
    {
      id: 'memo',
      header: intl.formatMessage(wm.colMemo),
      cell: ({ row }) => {
        const { subject } = row.original
        return subject.restricted
          ? `${subject.reference ?? ''} · ${intl.formatMessage(wm.restricted)}`
          : `${subject.reference ?? ''} · ${subject.title ?? ''}`
      },
    },
    {
      id: 'from',
      header: intl.formatMessage(wm.colFrom),
      cell: ({ row }) =>
        row.original.fromVersion ? intl.formatMessage(wmv.version, { number: row.original.fromVersion }) : '',
    },
    { id: 'at', header: intl.formatMessage(wm.colAt), cell: ({ row }) => row.original.openSteps.join(', ') },
    {
      id: 'can',
      header: intl.formatMessage(wm.colCan),
      cell: ({ row }) => {
        const { migrated, migratable, reason } = row.original
        if (migrated) return intl.formatMessage(wm.moved)
        if (migratable) return intl.formatMessage(wm.yes)
        const message = reason ? migrationReasons[reason as keyof typeof migrationReasons] : undefined
        return message ? intl.formatMessage(message) : (reason ?? '')
      },
    },
  ]

  return (
    <section aria-labelledby="migration-title" className="rounded-md border p-4">
      <h2 id="migration-title" className="text-base font-semibold">
        {intl.formatMessage(wm.migration)}
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">{intl.formatMessage(wm.migrationLead)}</p>
      <div className="mb-3 flex flex-wrap gap-2">
        <Button variant="outline" disabled={busy} onClick={() => void preview()}>
          {intl.formatMessage(wm.preview)}
        </Button>
        {plan ? (
          <Button disabled={busy || selected.size === 0} onClick={() => void migrate()}>
            {intl.formatMessage(wm.migrate)}
          </Button>
        ) : null}
      </div>
      {plan ? (
        plan.items.length === 0 ? (
          <p className="text-sm text-muted-foreground">{intl.formatMessage(wm.nothingToMove)}</p>
        ) : (
          <DataTable
            caption={intl.formatMessage(wm.migrationCaption)}
            columns={columns}
            rows={plan.items}
            getRowId={(row) => row.instanceId}
            state="ready"
          />
        )
      ) : null}
    </section>
  )
}
