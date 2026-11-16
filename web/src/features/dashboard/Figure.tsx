import { useId, useState, type ReactNode } from 'react'
import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { dm } from './dashboard-messages'

/**
 * A dashboard figure (flow K): a `<figure>` whose caption is its title and a one-line summary, the chart, and its data
 * as a table. The table is always in the page for screen readers; "Show as table" makes it visible too.
 */
export function Figure({
  title,
  summary,
  table,
  children,
  className,
}: {
  title: string
  summary: string
  /** The figure's data; absent for a single number. */
  table?: ReactNode
  children: ReactNode
  className?: string
}) {
  const intl = useIntl()
  const id = useId()
  const [shown, setShown] = useState(false)
  return (
    <figure
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-summary`}
      className={cn('flex min-w-0 flex-col gap-3 rounded-md border bg-card p-4', className)}
    >
      <figcaption className="flex flex-col gap-1">
        <h2 id={`${id}-title`} className="text-base font-semibold">
          {title}
        </h2>
        <p id={`${id}-summary`} className="text-sm text-muted-foreground">
          {summary}
        </p>
      </figcaption>
      {children}
      {table ? (
        <>
          <div>
            <Button
              variant={shown ? 'default' : 'outline'}
              aria-pressed={shown}
              aria-controls={`${id}-table`}
              onClick={() => {
                setShown((s) => !s)
              }}
            >
              {intl.formatMessage(dm.showTable)}
            </Button>
          </div>
          <div id={`${id}-table`} className={shown ? 'overflow-x-auto' : 'sr-only'}>
            {table}
          </div>
        </>
      ) : null}
    </figure>
  )
}

/** A plain data table for a figure: a caption, column headers and the first cell of each row as its header. */
export function DataTableOf({
  caption,
  columns,
  rows,
}: {
  caption: string
  columns: string[]
  rows: { key: string; cells: (string | number)[] }[]
}) {
  const intl = useIntl()
  return (
    <table className="w-full text-sm">
      <caption className="sr-only">{caption}</caption>
      <thead>
        <tr className="border-b">
          {columns.map((c, i) => (
            <th key={c} scope="col" className={cn('px-2 py-1.5 font-medium', i === 0 ? 'text-start' : 'text-end')}>
              {c}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.key} className="border-b last:border-b-0">
            {row.cells.map((cell, i) =>
              i === 0 ? (
                <th key={i} scope="row" className="px-2 py-1.5 text-start font-normal">
                  {cell}
                </th>
              ) : (
                <td key={i} className="px-2 py-1.5 text-end tabular-nums">
                  {typeof cell === 'number' ? intl.formatNumber(cell) : cell}
                </td>
              ),
            )}
          </tr>
        ))}
      </tbody>
    </table>
  )
}
