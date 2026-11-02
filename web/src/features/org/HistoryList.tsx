import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { getOrgHistoryOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { OrgHistoryEntry } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { om } from './org-messages'

const actions = defineMessages({
  created: { id: 'org.history.created', defaultMessage: 'Created' },
  updated: { id: 'org.history.updated', defaultMessage: 'Changed' },
  moved: { id: 'org.history.moved', defaultMessage: 'Moved' },
  closed: { id: 'org.history.closed', defaultMessage: 'Closed' },
})

function show(value: unknown): string {
  if (value === null || value === undefined) return '–'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return value.toString()
  return JSON.stringify(value)
}

/** What changed, in a few words: the action verb and the fields that changed (old → new). */
function describe(entry: OrgHistoryEntry, verb: string): string {
  const before = entry.before ?? {}
  const after = entry.after ?? {}
  const changes = Object.keys(after)
    .filter((k) => k in before && JSON.stringify(before[k]) !== JSON.stringify(after[k]))
    .map((k) => `${k}: ${show(before[k])} → ${show(after[k])}`)
  return changes.length > 0 ? `${verb} (${changes.join('; ')})` : verb
}

/**
 * The change history of a unit or position (design E04 §13), read from the audit trail when opened. Shown only to people
 * who may read it (`audit.read`, or managing the object); for everyone else the request answers 403 and the section
 * stays out of the way.
 */
export function HistoryList({ type, id }: { type: 'unit' | 'position'; id: string }) {
  const intl = useIntl()
  const [open, setOpen] = useState(false)
  const history = useQuery({ ...getOrgHistoryOptions({ path: { type, id } }), enabled: open, retry: false })
  if (history.isError && isProblem(history.error) && history.error.status === 403) return null

  const columns: DataTableColumn<OrgHistoryEntry>[] = [
    {
      id: 'when',
      header: intl.formatMessage(om.colWhen),
      cell: ({ row }) => intl.formatDate(row.original.occurredAt, { dateStyle: 'medium', timeStyle: 'short' }),
    },
    {
      id: 'change',
      header: intl.formatMessage(om.colChange),
      cell: ({ row }) => {
        const verb = row.original.action.split('.').pop() ?? ''
        const known = verb in actions ? intl.formatMessage(actions[verb as keyof typeof actions]) : row.original.action
        return describe(row.original, known)
      },
    },
    {
      id: 'by',
      header: intl.formatMessage(om.colBy),
      cell: ({ row }) => row.original.actor ?? intl.formatMessage(om.system),
    },
  ]

  return (
    <details
      className="mt-6 rounded-md border"
      onToggle={(e) => {
        setOpen(e.currentTarget.open)
      }}
    >
      <summary className="cursor-pointer px-4 py-3 text-sm font-medium">{intl.formatMessage(om.history)}</summary>
      {open ? (
        <div className="border-t p-2">
          <DataTable
            caption={intl.formatMessage(om.historyCaption)}
            columns={columns}
            rows={history.data ?? []}
            getRowId={(e) => `${e.occurredAt}-${e.action}`}
            state={history.isPending ? 'loading' : history.isError ? 'error' : 'ready'}
            error={{ onRetry: () => void history.refetch() }}
            empty={intl.formatMessage(om.historyEmpty)}
          />
        </div>
      ) : null}
    </details>
  )
}
