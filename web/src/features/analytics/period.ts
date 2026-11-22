import type { IntlShape } from 'react-intl'
import type { PeriodSearch, Preset } from './analytics-params'

const pad = (n: number) => String(n).padStart(2, '0')
const iso = (d: Date) => `${String(d.getFullYear())}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

/**
 * The `from` and `to` a period asks the API for; none for the default (the server's last 12 months in the tenant's
 * zone). Presets end today in the viewer's calendar: "last 2 years" is 2 years to today, both days included.
 */
export function periodQuery(search: PeriodSearch, today: Date = new Date()): { from?: string; to?: string } {
  if (search.period) return presetRange(search.period, today)
  return { ...(search.from ? { from: search.from } : {}), ...(search.to ? { to: search.to } : {}) }
}

export function presetRange(preset: Preset, today: Date = new Date()): { from: string; to: string } {
  const year = today.getFullYear()
  switch (preset) {
    case '2y':
    case '5y': {
      const years = preset === '2y' ? 2 : 5
      return {
        from: iso(new Date(year - years, today.getMonth(), today.getDate() + 1)),
        to: iso(today),
      }
    }
    case 'thisYear':
      return { from: `${String(year)}-01-01`, to: iso(today) }
    case 'lastYear':
      return { from: `${String(year - 1)}-01-01`, to: `${String(year - 1)}-12-31` }
  }
}

/** An ISO date (`2026-11-15`) for people, in the viewer's language; the date has no time zone of its own. */
export function formatDay(
  intl: IntlShape,
  day: string,
  options: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short', year: 'numeric' },
) {
  return intl.formatDate(`${day}T12:00:00Z`, { ...options, timeZone: 'UTC' })
}

/** A share in whole percent (the API's), as the viewer's language writes a percentage. */
export function formatPercent(intl: IntlShape, percent: number) {
  return intl.formatNumber(percent / 100, { style: 'percent', maximumFractionDigits: 0 })
}

/** "2026-Q1" as people say it: "Q1 2026" (Arabic: "الربع 1 2026"), through the message [label]. */
export function quarterParts(quarter: string): { quarter: number; year: number } | undefined {
  const match = /^(\d{4})-Q([1-4])$/.exec(quarter)
  return match ? { year: Number(match[1]), quarter: Number(match[2]) } : undefined
}
