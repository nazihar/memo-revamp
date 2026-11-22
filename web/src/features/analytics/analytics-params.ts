/**
 * The analytics pages' address, apart from the page (Phase 4 E08 T7): the routes validate it in the shell's bundle, so
 * this module imports nothing (no messages, no queries), or they would load with every first page.
 *
 * The period is a preset (`period=2y`) or two dates (`from=2026-01-01&to=2026-12-31`); absent, the server's default,
 * the last 12 months. The overview may narrow to one committee (`committee=<id>`); the attendance table may sort by rate.
 */
export const PRESETS = ['2y', '5y', 'thisYear', 'lastYear'] as const
export type Preset = (typeof PRESETS)[number]

export interface PeriodSearch {
  period?: Preset
  from?: string
  to?: string
}

export interface OverviewSearch extends PeriodSearch {
  committee?: string
}

export interface AttendanceSearch extends PeriodSearch {
  sort?: 'rate'
}

const DAY = /^\d{4}-\d{2}-\d{2}$/
const ID = /^[\w-]{1,64}$/

/** Whether [day] is an ISO date that exists (`2026-02-30` does not). */
export function isDay(day: unknown): day is string {
  if (typeof day !== 'string' || !DAY.test(day)) return false
  const date = new Date(`${day}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === day
}

export function parsePeriodSearch(search: Record<string, unknown>): PeriodSearch {
  const period = PRESETS.find((p) => p === search.period)
  if (period) return { period }
  const from = isDay(search.from) ? search.from : undefined
  const to = isDay(search.to) ? search.to : undefined
  return { ...(from ? { from } : {}), ...(to ? { to } : {}) }
}

export function parseOverviewSearch(search: Record<string, unknown>): OverviewSearch {
  const committee = typeof search.committee === 'string' && ID.test(search.committee) ? search.committee : undefined
  return { ...parsePeriodSearch(search), ...(committee ? { committee } : {}) }
}

export function parseAttendanceSearch(search: Record<string, unknown>): AttendanceSearch {
  return { ...parsePeriodSearch(search), ...(search.sort === 'rate' ? { sort: 'rate' as const } : {}) }
}

/** The period part of an address, to carry from one analytics page to the next. */
export function periodOf(search: PeriodSearch): PeriodSearch {
  if (search.period) return { period: search.period }
  return { ...(search.from ? { from: search.from } : {}), ...(search.to ? { to: search.to } : {}) }
}
