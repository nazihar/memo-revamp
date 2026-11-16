import type { SearchParams as SearchBody, SearchQuery } from '@/api/generated/types.gen'

/** Where a search looks (flow H2 "Search in"); all three unless the reader narrows it. */
export type Source = 'metadata' | 'notes' | 'documents'
export const SOURCES: readonly Source[] = ['metadata', 'notes', 'documents']

/** Where a memo comes from (Phase 3 E06): created here or imported from an earlier system; both unless narrowed. */
export type Origin = 'native' | 'imported'
export const ORIGINS: readonly Origin[] = ['native', 'imported']

/**
 * The Search page's filters and order, in the query string (flow H2), so a reload, a link and back/forward show the same
 * search. Defaults (relevance, all sources) are left out. The words are not here: they are in the fragment (see [qOf]).
 */
export interface SearchParams {
  status?: string[]
  type?: string[]
  unit?: string[]
  /** ISO dates (yyyy-mm-dd), inclusive. */
  from?: string
  to?: string
  amountMin?: number
  amountMax?: number
  in?: Source[]
  origin?: Origin
  sort?: 'newest'
}

const DATE = /^\d{4}-\d{2}-\d{2}$/

function text(value: unknown): string | undefined {
  // The router parses values as JSON, so an id or a value such as 42 may arrive as a number.
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return typeof value === 'string' && value.trim() ? value : undefined
}

function list(value: unknown): string[] | undefined {
  const values = (Array.isArray(value) ? value : [value]).map(text).filter((v): v is string => v !== undefined)
  return values.length > 0 ? [...new Set(values)] : undefined
}

function amount(value: unknown): number | undefined {
  const n = typeof value === 'string' && value.trim() ? Number(value) : value
  return typeof n === 'number' && Number.isFinite(n) && n >= 0 ? n : undefined
}

function sources(value: unknown): Source[] | undefined {
  const picked = SOURCES.filter((s) => list(value)?.includes(s))
  return picked.length > 0 && picked.length < SOURCES.length ? picked : undefined
}

/** Drops keys whose value is undefined, so the URL carries only what is set. */
function compact(params: SearchParams): SearchParams {
  return Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined))
}

/**
 * `validateSearch` of `/search`: keeps what is well formed and drops the rest. A `q` in the query string is ignored:
 * search words never go in a query string (AGENTS.md §3.4, threat model E05 I4).
 */
export function parseSearchParams(raw: Record<string, unknown>): SearchParams {
  const from = text(raw.from)
  const to = text(raw.to)
  return compact({
    status: list(raw.status),
    type: list(raw.type),
    unit: list(raw.unit),
    from: from && DATE.test(from) ? from : undefined,
    to: to && DATE.test(to) ? to : undefined,
    amountMin: amount(raw.amountMin),
    amountMax: amount(raw.amountMax),
    in: sources(raw.in),
    origin: raw.origin === 'native' || raw.origin === 'imported' ? raw.origin : undefined,
    sort: raw.sort === 'newest' ? 'newest' : undefined,
  })
}

/**
 * The words of a search, from the URL fragment (`#q=rent%20schedule`). The fragment is never sent to a server, so the
 * words stay out of access logs, while reload, back/forward and links still work.
 */
export function qOf(hash: string): string | undefined {
  const q = new URLSearchParams(hash.replace(/^#/, '')).get('q')?.trim()
  return q === '' ? undefined : q
}

/** The fragment (without "#") that holds [q]; empty when there are no words. */
export function hashOf(q: string | undefined): string {
  const clean = q?.trim()
  return clean ? `q=${encodeURIComponent(clean)}` : ''
}

export const PAGE_SIZE = 20

/** The body of `POST /search` for [q] and [params], without the cursor (each page adds its own). */
export function searchBody(params: SearchParams, q: string): SearchBody {
  const { origin, ...rest } = params
  const body: SearchBody = {
    q,
    ...(compact(rest) as Omit<SearchParams, 'origin'>),
    ...(origin ? { origin: [origin === 'imported' ? ('IMPORTED' as const) : ('NATIVE' as const)] } : {}),
    sort: params.sort ?? 'relevance',
    limit: PAGE_SIZE,
  }
  return body
}

/** How many filters are set (the "Filters (n)" button); the words and the order are not filters. */
export function filterCount(params: SearchParams): number {
  return (
    (params.status?.length ?? 0) +
    (params.type?.length ?? 0) +
    (params.unit?.length ?? 0) +
    [params.from, params.to, params.amountMin, params.amountMax, params.in, params.origin].filter(
      (v) => v !== undefined,
    ).length
  )
}

/** [params] without any filter: the same order. */
export function withoutFilters(params: SearchParams): SearchParams {
  return compact({ sort: params.sort })
}

/** A saved search's criteria (`SearchQuery`) for the words [q] and [params]. */
export function criteriaOf(params: SearchParams, q: string): SearchQuery {
  return {
    q: q.trim(),
    types: params.type ?? [],
    statuses: params.status ?? [],
    units: params.unit ?? [],
    from: params.from ?? null,
    to: params.to ?? null,
    amountMin: params.amountMin ?? null,
    amountMax: params.amountMax ?? null,
    sources: (params.in ?? SOURCES).map((s) => s.toUpperCase() as 'METADATA' | 'NOTES' | 'DOCUMENTS'),
    origins: params.origin ? [params.origin === 'imported' ? 'IMPORTED' : 'NATIVE'] : [],
    sort: params.sort === 'newest' ? 'NEWEST' : 'RELEVANCE',
  }
}

/** Where the Search page shows a saved search: its filters in the query string and its words in the fragment. */
export function locationOf(criteria: SearchQuery): { search: SearchParams; hash: string } {
  return {
    search: parseSearchParams({
      status: criteria.statuses,
      type: criteria.types,
      unit: criteria.units,
      from: criteria.from ?? undefined,
      to: criteria.to ?? undefined,
      amountMin: criteria.amountMin ?? undefined,
      amountMax: criteria.amountMax ?? undefined,
      in: criteria.sources?.map((s) => s.toLowerCase()),
      origin: criteria.origins?.length === 1 ? criteria.origins[0]?.toLowerCase() : undefined,
      sort: criteria.sort === 'NEWEST' ? 'newest' : undefined,
    }),
    hash: hashOf(criteria.q),
  }
}
