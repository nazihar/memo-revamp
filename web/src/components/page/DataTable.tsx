import {
  flexRender,
  functionalUpdate,
  useTable,
  type RowData,
  type RowSelectionState,
  type SortingState,
} from '@tanstack/react-table'
import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { defineMessages, useIntl, type NoMessageValues } from 'react-intl'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/form-controls'
import { cn } from '@/lib/utils'
import { ErrorState, LoadingState } from './States'
import { dataTableFeatures, type DataTableColumn, type DataTableFeatures } from './data-table-features'

const messages = defineMessages<{
  selectAll: NoMessageValues
  selectRow: { label: string }
  sortBy: { column: string }
  previous: NoMessageValues
  next: NoMessageValues
  pagination: NoMessageValues
}>({
  selectAll: { id: 'table.selectAll', defaultMessage: 'Select all rows' },
  selectRow: { id: 'table.selectRow', defaultMessage: 'Select row {label}' },
  sortBy: { id: 'table.sortBy', defaultMessage: 'Sort by {column}' },
  previous: { id: 'table.previous', defaultMessage: 'Previous' },
  next: { id: 'table.next', defaultMessage: 'Next' },
  pagination: { id: 'table.pagination', defaultMessage: 'Pagination' },
})

/** Cursor pagination state supplied by the caller (AGENTS.md §4: keyset, never page numbers). */
export interface CursorPaging {
  hasPrevious: boolean
  hasNext: boolean
  onPrevious: () => void
  onNext: () => void
  summary?: ReactNode
}

export type { DataTableColumn } from './data-table-features'

export interface DataTableProps<T extends RowData> {
  caption: string
  columns: DataTableColumn<T>[]
  rows: T[]
  getRowId: (row: T) => string
  /** Label of a row for "Select row …" (screen readers). Selection is off when absent. */
  rowLabel?: (row: T) => string
  /** Server-side sorting: the table reports the sort, the caller refetches. */
  sorting?: SortingState
  onSortingChange?: (sorting: SortingState) => void
  selection?: RowSelectionState
  onSelectionChange?: (selection: RowSelectionState) => void
  paging?: CursorPaging
  state?: 'ready' | 'loading' | 'error'
  error?: { title?: string; correlationId?: string; onRetry?: () => void }
  empty?: ReactNode
}

/**
 * List template table (E01 design §3) on TanStack Table v9: server-side sorting announced with `aria-sort`, optional
 * row selection with an accessible "select all", cursor pagination, and empty, loading and error states. Wide tables
 * scroll inside their own box so the page never scrolls sideways.
 */
export function DataTable<T extends RowData>({
  caption,
  columns,
  rows,
  getRowId,
  rowLabel,
  sorting = [],
  onSortingChange,
  selection,
  onSelectionChange,
  paging,
  state = 'ready',
  error,
  empty,
}: DataTableProps<T>) {
  const intl = useIntl()
  const [localSelection, setLocalSelection] = useState<RowSelectionState>({})
  const rowSelection = selection ?? localSelection
  const selectable = rowLabel !== undefined
  const table = useTable<DataTableFeatures, T>({
    features: dataTableFeatures,
    data: rows,
    columns,
    getRowId: (row) => getRowId(row),
    manualSorting: true,
    enableRowSelection: selectable,
    state: { sorting, rowSelection },
    onSortingChange: (updater) => onSortingChange?.(functionalUpdate(updater, sorting)),
    onRowSelectionChange: (updater) => {
      const next = functionalUpdate(updater, rowSelection)
      if (onSelectionChange) onSelectionChange(next)
      else setLocalSelection(next)
    },
  })

  const body = () => {
    if (state === 'loading') return <LoadingState />
    if (state === 'error') return <ErrorState {...error} />
    if (rows.length === 0) return empty ?? null
    return null
  }
  const placeholder = body()

  return (
    <div className="flex flex-col gap-3">
      <div className="overflow-x-auto rounded-lg border bg-card">
        <table className="w-full min-w-[40rem] border-collapse text-sm">
          <caption className="sr-only">{caption}</caption>
          <thead>
            {table.getHeaderGroups().map((group) => (
              <tr key={group.id} className="border-b text-xs font-semibold text-muted-foreground">
                {selectable ? (
                  <th scope="col" className="w-11 px-3 py-2.5 text-start">
                    <Checkbox
                      aria-label={intl.formatMessage(messages.selectAll)}
                      checked={table.getIsAllRowsSelected()}
                      ref={(el) => {
                        if (el) el.indeterminate = table.getIsSomeRowsSelected()
                      }}
                      onChange={table.getToggleAllRowsSelectedHandler()}
                    />
                  </th>
                ) : null}
                {group.headers.map((header) => {
                  const sort = header.column.getIsSorted()
                  const canSort = header.column.getCanSort() && onSortingChange !== undefined
                  const label = flexRender(header.column.columnDef.header, header.getContext())
                  return (
                    <th
                      key={header.id}
                      scope="col"
                      aria-sort={
                        sort === 'asc' ? 'ascending' : sort === 'desc' ? 'descending' : canSort ? 'none' : undefined
                      }
                      className="px-3 py-2.5 text-start"
                    >
                      {canSort ? (
                        <button
                          type="button"
                          onClick={header.column.getToggleSortingHandler()}
                          aria-label={intl.formatMessage(messages.sortBy, {
                            column:
                              typeof header.column.columnDef.header === 'string'
                                ? header.column.columnDef.header
                                : header.id,
                          })}
                          className="-mx-1 inline-flex min-h-8 items-center gap-1 rounded px-1 hover:text-foreground"
                        >
                          {label}
                          {sort === 'asc' ? (
                            <ArrowUp aria-hidden="true" className="size-3.5" />
                          ) : sort === 'desc' ? (
                            <ArrowDown aria-hidden="true" className="size-3.5" />
                          ) : (
                            <ArrowUpDown aria-hidden="true" className="size-3.5 opacity-60" />
                          )}
                        </button>
                      ) : (
                        label
                      )}
                    </th>
                  )
                })}
              </tr>
            ))}
          </thead>
          {placeholder === null ? (
            <tbody>
              {table.getRowModel().rows.map((row) => (
                <tr
                  key={row.id}
                  data-selected={row.getIsSelected() || undefined}
                  className="border-b last:border-b-0 data-[selected]:bg-accent/60"
                >
                  {selectable ? (
                    <td className="px-3 py-2">
                      <Checkbox
                        aria-label={intl.formatMessage(messages.selectRow, { label: rowLabel(row.original) })}
                        checked={row.getIsSelected()}
                        onChange={row.getToggleSelectedHandler()}
                      />
                    </td>
                  ) : null}
                  {row.getAllCells().map((cell) => (
                    <td key={cell.id} className="px-3 py-2 align-middle">
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          ) : null}
        </table>
        {placeholder}
      </div>
      {paging ? <CursorPagination {...paging} /> : null}
    </div>
  )
}

export function CursorPagination({ hasPrevious, hasNext, onPrevious, onNext, summary }: CursorPaging) {
  const intl = useIntl()
  return (
    <nav
      aria-label={intl.formatMessage(messages.pagination)}
      className={cn('flex flex-wrap items-center gap-2 text-sm text-muted-foreground')}
    >
      <span className="flex-1">{summary}</span>
      <Button variant="outline" disabled={!hasPrevious} onClick={onPrevious}>
        {intl.formatMessage(messages.previous)}
      </Button>
      <Button variant="outline" disabled={!hasNext} onClick={onNext}>
        {intl.formatMessage(messages.next)}
      </Button>
    </nav>
  )
}
