import { useId, useState, type ReactNode } from 'react'
import { useIntl } from 'react-intl'
import { TableScroll } from '@/components/page/TableScroll'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { dm } from './dashboard-messages'

/**
 * A figure (flow K; Phase 4 E08 flows AU–AW): a `<figure>` whose caption is its title and a one-line summary, then the
 * chart and its data as a table.
 *
 * - **Chart first** (the default, the Approvals dashboard): the table is always in the page for screen readers, and
 *   "Show as table" makes it visible too.
 * - **Table first** (`tableFirst`, committee analytics, ADR-0070): the summary sentence, the table visible by default,
 *   and the chart beside it (below it on narrow screens). The chart repeats the table, so it is hidden from the
 *   accessibility tree: a screen reader reads the sentence and the table, never the bars as well. Draw horizontal bars
 *   with value labels (`analytics/bars`), never lines, pies or donuts.
 *
 * [basis] says what the figure counts ("Based on 46 decisions you can see in full"); [link] leads to the list behind
 * the figure's numbers (design E08 §2.6). Both sit inside the figure, so they are read with it.
 */
export function Figure({
  title,
  summary,
  table,
  children,
  className,
  tableFirst = false,
  basis,
  controls,
  notes,
  link,
  headingLevel = 2,
  id: anchor,
}: {
  title: string
  summary: string
  /** The figure's data; absent for a single number. */
  table?: ReactNode
  children?: ReactNode
  className?: string
  /** The table is the figure: visible by default, with the chart beside it, hidden from assistive technology. */
  tableFirst?: boolean
  /** What the figure counts, under the summary. */
  basis?: ReactNode
  /** Controls that change how the table reads (a toggle), between the caption and the table. */
  controls?: ReactNode
  /** Words after the table (a breakdown in one line). */
  notes?: ReactNode
  /** The list behind the figure's numbers. */
  link?: ReactNode
  /** The title's heading level: 2 on a page of figures, 3 inside a titled section. */
  headingLevel?: 2 | 3
  /** The figure's element id, for a link to it from another page (`#timing`). */
  id?: string
}) {
  const intl = useIntl()
  const id = useId()
  const [shown, setShown] = useState(false)
  const Heading = headingLevel === 3 ? 'h3' : 'h2'
  return (
    <figure
      id={anchor}
      tabIndex={anchor ? -1 : undefined}
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-summary`}
      className={cn(
        'flex min-w-0 flex-col gap-3 rounded-md border bg-card p-4',
        anchor && 'scroll-mt-20 focus:outline-none',
        className,
      )}
    >
      <figcaption className="flex flex-col gap-1">
        <Heading id={`${id}-title`} className="text-base font-semibold">
          {title}
        </Heading>
        <p id={`${id}-summary`} className={tableFirst ? 'text-sm' : 'text-sm text-muted-foreground'}>
          {summary}
        </p>
        {basis ? <p className="text-sm text-muted-foreground">{basis}</p> : null}
      </figcaption>
      {controls}
      {tableFirst ? (
        <div className={cn('grid min-w-0 gap-4', children ? 'xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]' : undefined)}>
          {table ? <TableScroll>{table}</TableScroll> : null}
          {children ? (
            <div aria-hidden="true" data-figure-chart="" className="min-w-0">
              {children}
            </div>
          ) : null}
        </div>
      ) : (
        <>
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
              <div id={`${id}-table`} className={shown ? 'relative overflow-x-auto' : 'sr-only'}>
                {table}
              </div>
            </>
          ) : null}
        </>
      )}
      {notes}
      {link ? <div className="flex flex-wrap gap-x-5 gap-y-2 text-sm font-medium">{link}</div> : null}
    </figure>
  )
}

/** A plain data table for a figure: a caption, column headers and the first cell of each row as its header. */
export function DataTableOf({
  caption,
  columns,
  rows,
  captionVisible = false,
}: {
  caption: string
  columns: string[]
  rows: { key: string; cells: (ReactNode | number)[] }[]
  /** Show the caption (a table-first figure whose table is not named by the figure's title alone). */
  captionVisible?: boolean
}) {
  const intl = useIntl()
  return (
    <table className="w-full text-sm">
      <caption className={captionVisible ? 'mb-1 text-start text-sm font-medium' : 'sr-only'}>{caption}</caption>
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
