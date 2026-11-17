import { useCallback, useState } from 'react'
import { useIntl } from 'react-intl'
import { Bar, BarChart, CartesianGrid, LabelList, Line, LineChart, Tooltip, XAxis, YAxis } from 'recharts'
import { useLocale } from '@/i18n/LocaleProvider'
import { directionOf } from '@/i18n/locales'

/*
 * Charts of the Approvals dashboard (flow K) on Recharts (research E05 R11). Recharts draws SVG and sets styles through
 * the CSSOM only (React style props, `element.style`), which the CSP allows: no inline style attribute, no <style>
 * element, no eval (checked in e2e/smoke/dashboard.spec.ts). Colours come from the stylesheet (`.memo-chart` in
 * index.css): series wear `currentColor`, set by the series class. Animations are off (reduced motion, stable screenshots).
 * Every chart is keyboard reachable (Recharts' accessibility layer: Tab to the chart, arrows move between points) and
 * named by its figure's summary; the figure's table carries the same data. In right-to-left languages the age and
 * time axes run right to left (reversed, the value axis on the right); the horizontal bar charts keep their bars growing
 * from the left, as their value labels sit at the bar's end. The SVG itself is laid out left to right, so SVG text
 * anchors keep their meaning; the text inside still follows the bidirectional algorithm.
 */

/** The width of the chart's box, followed with a ResizeObserver; 0 until measured (and in jsdom). */
function useWidth() {
  const [width, setWidth] = useState(0)
  const ref = useCallback((el: HTMLDivElement | null) => {
    if (!el || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      setWidth(Math.floor(el.clientWidth))
    })
    observer.observe(el)
    return () => {
      observer.disconnect()
    }
  }, [])
  return [ref, width] as const
}

function useRtl() {
  return directionOf(useLocale().locale) === 'rtl'
}

interface TooltipEntry {
  name?: string | number
  value?: unknown
  dataKey?: unknown
}

/** The hover and keyboard tooltip: the point's label and each series' value, in the card's colours. */
function TooltipBox({
  active,
  payload,
  label,
}: {
  active?: boolean
  payload?: readonly TooltipEntry[]
  label?: unknown
}) {
  const intl = useIntl()
  const rtl = useRtl()
  if (!active || !payload?.length) return null
  return (
    <div
      dir={rtl ? 'rtl' : 'ltr'}
      className="rounded-md border bg-card px-3 py-2 text-xs text-card-foreground shadow-md"
    >
      {typeof label === 'string' || typeof label === 'number' ? <p className="mb-1 font-medium">{label}</p> : null}
      <ul>
        {payload.map((entry) => (
          <li key={String(entry.dataKey)} className="flex justify-between gap-3">
            <span>{entry.name}</span>
            <span className="font-medium tabular-nums">
              {typeof entry.value === 'number' ? intl.formatNumber(entry.value) : String(entry.value)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** A legend in the page's text colours, each series with its marker shape (never colour alone). */
export function ChartLegend({
  items,
}: {
  items: { label: string; series: 1 | 2; marker: 'circle' | 'square'; line: boolean }[]
}) {
  return (
    <ul className="memo-chart flex flex-wrap gap-4 text-xs text-muted-foreground">
      {items.map((item) => (
        <li key={item.label} className="flex items-center gap-1.5">
          <svg aria-hidden="true" viewBox="0 0 24 10" className={`series-${String(item.series)} h-2.5 w-6`}>
            {item.line ? (
              <line
                x1="0"
                y1="5"
                x2="24"
                y2="5"
                stroke="currentColor"
                strokeWidth="2"
                strokeDasharray={item.series === 2 ? '6 4' : undefined}
              />
            ) : null}
            {item.marker === 'circle' ? (
              <circle cx="12" cy="5" r="4" fill="currentColor" />
            ) : (
              <rect x="8" y="1" width="8" height="8" fill="currentColor" />
            )}
          </svg>
          {item.label}
        </li>
      ))}
    </ul>
  )
}

/** Pending tasks per age bucket: one series, labelled on each bar. */
export function AgeChart({
  data,
  title,
  seriesName,
}: {
  data: { label: string; count: number }[]
  title: string
  seriesName: string
}) {
  const [ref, width] = useWidth()
  const rtl = useRtl()
  return (
    <div ref={ref} dir="ltr" className="memo-chart h-56 w-full">
      {width > 0 ? (
        <BarChart
          width={width}
          height={224}
          data={data}
          title={title}
          margin={{ top: 24, right: 8, left: 8, bottom: 0 }}
        >
          <CartesianGrid vertical={false} />
          <XAxis dataKey="label" tickLine={false} reversed={rtl} />
          <YAxis allowDecimals={false} width={36} orientation={rtl ? 'right' : 'left'} />
          <Tooltip content={TooltipBox} cursor={false} />
          <Bar
            dataKey="count"
            name={seriesName}
            className="series-1"
            fill="currentColor"
            radius={[4, 4, 0, 0]}
            maxBarSize={56}
            isAnimationActive={false}
          >
            <LabelList dataKey="count" position="top" />
          </Bar>
        </BarChart>
      ) : null}
    </div>
  )
}

/** Pending tasks per person: horizontal bars, each labelled "5 (2 overdue)". */
export function PersonChart({
  data,
  title,
  seriesName,
}: {
  data: { name: string; pending: number; label: string }[]
  title: string
  seriesName: string
}) {
  const [ref, width] = useWidth()
  const height = data.length * 36 + 32
  return (
    <div ref={ref} dir="ltr" className="memo-chart w-full">
      {width > 0 ? (
        <BarChart
          layout="vertical"
          width={width}
          height={height}
          data={data}
          title={title}
          margin={{ top: 8, right: 112, left: 8, bottom: 0 }}
        >
          <CartesianGrid horizontal={false} />
          <XAxis type="number" allowDecimals={false} />
          <YAxis type="category" dataKey="name" width={140} tickLine={false} />
          <Tooltip content={TooltipBox} cursor={false} />
          <Bar
            dataKey="pending"
            name={seriesName}
            className="series-1"
            fill="currentColor"
            radius={[0, 4, 4, 0]}
            maxBarSize={24}
            isAnimationActive={false}
          >
            <LabelList dataKey="label" position="right" />
          </Bar>
        </BarChart>
      ) : null}
    </div>
  )
}

/** Median and P90 days per memo type: two bars per type, each labelled with its series and value. */
export function CycleChart({
  data,
  title,
  medianName,
  p90Name,
}: {
  data: { name: string; median: number; p90: number; medianLabel: string; p90Label: string }[]
  title: string
  medianName: string
  p90Name: string
}) {
  const [ref, width] = useWidth()
  const height = data.length * 64 + 32
  return (
    <div ref={ref} dir="ltr" className="memo-chart w-full">
      {width > 0 ? (
        <BarChart
          layout="vertical"
          width={width}
          height={height}
          data={data}
          title={title}
          barGap={2}
          margin={{ top: 8, right: 96, left: 8, bottom: 0 }}
        >
          <CartesianGrid horizontal={false} />
          <XAxis type="number" />
          <YAxis type="category" dataKey="name" width={140} tickLine={false} />
          <Tooltip content={TooltipBox} cursor={false} />
          <Bar
            dataKey="median"
            name={medianName}
            className="series-1"
            fill="currentColor"
            radius={[0, 4, 4, 0]}
            maxBarSize={20}
            isAnimationActive={false}
          >
            <LabelList dataKey="medianLabel" position="right" />
          </Bar>
          <Bar
            dataKey="p90"
            name={p90Name}
            className="series-2"
            fill="currentColor"
            radius={[0, 4, 4, 0]}
            maxBarSize={20}
            isAnimationActive={false}
          >
            <LabelList dataKey="p90Label" position="right" />
          </Bar>
        </BarChart>
      ) : null}
    </div>
  )
}

/** A square marker for the second line, so the two series differ in shape as well as colour and dash. */
function SquareDot({ cx, cy }: { cx?: number; cy?: number }) {
  if (cx == null || cy == null) return null
  return <rect x={cx - 4} y={cy - 4} width={8} height={8} className="series-2" fill="currentColor" />
}

/** Created vs completed per week: a solid line with round markers and a dashed line with square ones. */
export function ThroughputChart({
  data,
  title,
  createdName,
  completedName,
}: {
  data: { label: string; created: number; completed: number }[]
  title: string
  createdName: string
  completedName: string
}) {
  const [ref, width] = useWidth()
  const rtl = useRtl()
  return (
    <div ref={ref} dir="ltr" className="memo-chart h-64 w-full">
      {width > 0 ? (
        <LineChart
          width={width}
          height={256}
          data={data}
          title={title}
          margin={{ top: 16, right: 16, left: 16, bottom: 0 }}
        >
          <CartesianGrid vertical={false} />
          <XAxis dataKey="label" tickLine={false} reversed={rtl} interval="preserveStartEnd" minTickGap={16} />
          <YAxis allowDecimals={false} width={36} orientation={rtl ? 'right' : 'left'} />
          <Tooltip content={TooltipBox} />
          <Line
            dataKey="created"
            name={createdName}
            className="series-1"
            stroke="currentColor"
            strokeWidth={2}
            dot={{ r: 4, fill: 'currentColor', strokeWidth: 0, className: 'series-1' }}
            activeDot={{ r: 6, fill: 'currentColor', className: 'series-1' }}
            isAnimationActive={false}
          />
          <Line
            dataKey="completed"
            name={completedName}
            className="series-2"
            stroke="currentColor"
            strokeWidth={2}
            strokeDasharray="6 4"
            dot={SquareDot}
            activeDot={{ r: 6, fill: 'currentColor', className: 'series-2' }}
            isAnimationActive={false}
          />
        </LineChart>
      ) : null}
    </div>
  )
}
