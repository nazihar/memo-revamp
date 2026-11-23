import type { DecisionSearchRequest } from '@/api/generated/types.gen'

/**
 * The decision register's address (Phase 4 E08 T6, flow AS): the filters and the order in the query string, the words
 * in the fragment (`#q=…`, never sent to a server, as the memo search does). The route validates this in the shell's
 * bundle, so this module imports types only.
 */

export const OUTCOMES = [
  'APPROVED',
  'APPROVED_WITH_CONDITIONS',
  'REJECTED',
  'DEFERRED',
  'REFERRED',
  'RETURNED',
  'WITHDRAWN',
  'NOTED',
] as const
export type Outcome = (typeof OUTCOMES)[number]

export const STATES = ['RECORDED', 'NUMBERED', 'RELEASED', 'FINAL', 'VOID'] as const
export type RegisterState = (typeof STATES)[number]

export const FLAGS = ['WITH_CONDITIONS', 'WITH_DISSENT', 'TO_BE_RATIFIED', 'CORRECTED', 'VALIDITY_PASSED'] as const
export type Flag = (typeof FLAGS)[number]

/** The orders a reader can choose; absent, best match with words and newest first without (the server's default). */
export const SORTS = ['newest', 'oldest', 'relevance', 'number'] as const
export type RegisterSort = (typeof SORTS)[number]

export interface RegisterSearch {
  /** One committee (`forumId`). */
  committee?: string
  /** One committee type. */
  type?: string
  /** ISO dates of the meeting, inclusive. */
  from?: string
  to?: string
  outcome?: Outcome
  resolutions?: true
  state?: RegisterState
  memoType?: string
  flag?: Flag[]
  sort?: RegisterSort
}

const ID = /^[\w-]{1,64}$/
const DAY = /^\d{4}-\d{2}-\d{2}$/

/** Whether [day] is an ISO date that exists (`2026-02-30` does not). */
export function isDay(day: unknown): day is string {
  if (typeof day !== 'string' || !DAY.test(day)) return false
  const date = new Date(`${day}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === day
}

function id(value: unknown): string | undefined {
  return typeof value === 'string' && ID.test(value) ? value : undefined
}

function oneOf<T extends string>(values: readonly T[], value: unknown): T | undefined {
  return values.find((v) => v === value)
}

/** Drops keys whose value is undefined, so the address carries only what is set. */
function compact(search: RegisterSearch): RegisterSearch {
  return Object.fromEntries(Object.entries(search).filter(([, v]) => v !== undefined))
}

/**
 * `validateSearch` of `/decisions`: keeps what is well formed and drops the rest. A `q` in the query string is ignored:
 * words never go in a query string (AGENTS.md §3.4).
 */
export function parseRegisterSearch(raw: Record<string, unknown>): RegisterSearch {
  const flags = (Array.isArray(raw.flag) ? raw.flag : [raw.flag])
    .map((f) => oneOf(FLAGS, f))
    .filter((f): f is Flag => f !== undefined)
  return withValidSort(
    compact({
      committee: id(raw.committee),
      type: id(raw.type),
      from: isDay(raw.from) ? raw.from : undefined,
      to: isDay(raw.to) ? raw.to : undefined,
      outcome: oneOf(OUTCOMES, raw.outcome),
      resolutions: raw.resolutions === true || raw.resolutions === 'true' ? true : undefined,
      state: oneOf(STATES, raw.state),
      memoType: id(raw.memoType),
      flag: flags.length > 0 ? FLAGS.filter((f) => flags.includes(f)) : undefined,
      sort: oneOf(SORTS, raw.sort),
    }),
  )
}

/** Number order needs one committee (design §1.4): without one the order goes back to the default. */
export function withValidSort(search: RegisterSearch): RegisterSearch {
  return search.sort === 'number' && !search.committee ? compact({ ...search, sort: undefined }) : search
}

/**
 * The words of a search, from the URL fragment (`#q=BRC%2F2026%2F014`). The fragment is never sent to a server, so the
 * words stay out of access logs, while reload, back/forward and links still work.
 */
export function qOf(hash: string): string {
  return new URLSearchParams(hash.replace(/^#/, '')).get('q')?.trim() ?? ''
}

/** The fragment (without "#") that holds [q]; empty when there are no words. */
export function hashOf(q: string | undefined): string {
  const clean = q?.trim()
  return clean ? `q=${encodeURIComponent(clean)}` : ''
}

export const PAGE_SIZE = 50

const API_SORT: Record<RegisterSort, NonNullable<DecisionSearchRequest['sort']>> = {
  newest: 'NEWEST',
  oldest: 'OLDEST',
  relevance: 'RELEVANCE',
  number: 'NUMBER',
}

/** The filters and the order of [search] and [q] as the API takes them (search and export share them). */
export function filtersBody(search: RegisterSearch, q: string): Omit<DecisionSearchRequest, 'cursor' | 'limit'> {
  const s = withValidSort(search)
  const text = q.trim()
  return {
    ...(text ? { text } : {}),
    ...(s.committee ? { forumIds: [s.committee] } : {}),
    ...(s.type ? { forumTypeIds: [s.type] } : {}),
    ...(s.from ? { from: s.from } : {}),
    ...(s.to ? { to: s.to } : {}),
    ...(s.outcome ? { outcomes: [s.outcome] } : {}),
    ...(s.resolutions ? { resolutionsOnly: true } : {}),
    ...(s.state ? { states: [s.state] } : {}),
    ...(s.memoType ? { memoTypeIds: [s.memoType] } : {}),
    ...(s.flag?.length ? { flags: s.flag } : {}),
    ...(s.sort ? { sort: API_SORT[s.sort] } : {}),
  }
}

/** The body of `POST /decision-register/search`, without the cursor (each page adds its own). */
export function searchBody(search: RegisterSearch, q: string): DecisionSearchRequest {
  return { ...filtersBody(search, q), limit: PAGE_SIZE }
}

/** The order the server uses for [search] and [q]: the chosen one, else best match with words and newest without. */
export function effectiveSort(search: RegisterSearch, q: string): RegisterSort {
  const s = withValidSort(search)
  if (s.sort === 'relevance' && !q.trim()) return 'newest'
  return s.sort ?? (q.trim() ? 'relevance' : 'newest')
}

/** [search] without any filter: the order stays when it still makes sense. */
export function withoutFilters(search: RegisterSearch): RegisterSearch {
  return search.sort && search.sort !== 'number' ? { sort: search.sort } : {}
}

/** Whether any filter is set (the words and the order are not filters). */
export function hasFilters(search: RegisterSearch): boolean {
  return Object.entries(search).some(([key, v]) => key !== 'sort' && v !== undefined)
}

/**
 * Whether [search] or [q] filters on what only a row seen in full carries (design §1.3): words that are not a number,
 * an outcome, a memo type or an exception. Restricted lines are then left out, and the page says so.
 */
export function filtersOnContent(search: RegisterSearch, q: string): boolean {
  return q.trim() !== '' || Boolean(search.outcome) || Boolean(search.memoType) || Boolean(search.flag?.length)
}

/** The whole of one calendar year, for the year facet. */
export function yearRange(year: number): { from: string; to: string } {
  const y = String(year).padStart(4, '0')
  return { from: `${y}-01-01`, to: `${y}-12-31` }
}
