import { cn } from '@/lib/utils'

/*
 * Horizontal bars with their value labels (ADR-0070, flows AU–AW), in plain HTML: one series, one hue (`--chart-1`, the
 * dashboard's validated first series colour), the label in the text colour beside the bar's end, a 4 px rounded end and
 * a light track. Widths are set through React's style property (the CSSOM), which the CSP allows; logical sizes, so the
 * bars grow from the inline start in right-to-left languages too. The bars repeat numbers a table already gives in
 * words, so they are hidden from assistive technology; a bar is never the only place a number is.
 */

const share = (value: number, max: number) => (max <= 0 ? 0 : Math.max(0, Math.min(100, (value / max) * 100)))

/** One bar and its label, for a table cell beside the cell's number; hidden from assistive technology. */
export function DataBar({
  value,
  max = 100,
  label,
  className,
}: {
  value: number
  /** The value of a full bar: 100 for a percentage, the largest value for counts. */
  max?: number
  /** The bar's value label; none when the number sits just beside it in the cell. */
  label?: string
  className?: string
}) {
  return (
    <span aria-hidden="true" data-bar="" className={cn('inline-flex w-full min-w-24 items-center gap-2', className)}>
      <span className="memo-bar-track h-2.5 flex-1">
        <span className="memo-bar block h-full" style={{ inlineSize: `${String(share(value, max))}%` }} />
      </span>
      {label ? <span className="shrink-0 text-xs font-medium tabular-nums text-foreground">{label}</span> : null}
    </span>
  )
}

/**
 * Labelled bars beside a table (a table-first `Figure`'s chart, already hidden there): each row its name, its bar and
 * its value label; a row without a value says so in words instead of a bar.
 */
export function BarList({
  rows,
  max,
}: {
  rows: { key: string; name: string; value: number | null; label: string }[]
  max?: number
}) {
  const top = max ?? Math.max(0, ...rows.map((r) => r.value ?? 0))
  return (
    <ul data-bar-list="" className="flex flex-col gap-2 text-sm">
      {rows.map((row) => (
        <li key={row.key} className="grid grid-cols-[minmax(5rem,auto)_minmax(0,1fr)] items-center gap-3">
          <span className="text-muted-foreground">{row.name}</span>
          {row.value === null ? (
            <span className="text-xs text-muted-foreground">{row.label}</span>
          ) : (
            <DataBar value={row.value} max={top} label={row.label} />
          )}
        </li>
      ))}
    </ul>
  )
}
