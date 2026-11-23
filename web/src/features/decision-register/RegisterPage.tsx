import { keepPreviousData, useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { FileDown, X } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { getDecisionRegisterScopeOptions } from '@/api/generated/@tanstack/react-query.gen'
import { searchDecisionRegister } from '@/api/generated/sdk.gen'
import type { DecisionFacetsView, DecisionRowView, RegisterScopeView } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Select } from '@/components/ui/form-controls'
import { useTerms, type TermValues } from '@/features/terms/terms'
import { ExportDialog } from './ExportDialog'
import { drm } from './messages'
import { NumberCheck } from './NumberCheck'
import {
  effectiveSort,
  filtersOnContent,
  hasFilters,
  hashOf,
  searchBody,
  SORTS,
  withValidSort,
  yearRange,
  type Outcome,
  type RegisterSearch,
} from './register-params'
import { searchRefusal } from './register-problems'
import { RegisterRow } from './RegisterRow'
import { SearchForm } from './SearchForm'
import { chipsOf, chipWords, outcomeWords, rowFocusId, todayIso, type Chip } from './words'

/** Query key of a register search; the words are in it in memory only, never in a URL. */
const SEARCH_KEY = 'decisionRegisterSearch'

/**
 * The row opened last, so that coming back to the same search puts the reader where they were (flow AS2): the loaded
 * pages are still cached, and focus returns to that row.
 */
let lastOpened: { key: string; decisionId: string } | undefined

const sortLabels = {
  newest: drm.sortNewest,
  oldest: drm.sortOldest,
  relevance: drm.sortRelevance,
  number: drm.sortNumber,
} as const

/**
 * `/decisions` (Phase 4 E08 T6, flow AS): the decision register across the committees the reader may see. The filters
 * are in the query string and the words in the fragment; the search is a POST, so words never reach a server log. Rows
 * the reader may not see in full are restricted lines that say why; rows above their clearance or outside their scope are
 * neither shown nor counted (the server decides).
 */
export function RegisterPage({ search, q }: { search: RegisterSearch; q: string }) {
  const intl = useIntl()
  const terms = useTerms()
  const navigate = useNavigate()
  const scope = useQuery({ ...getDecisionRegisterScopeOptions(), retry: false, staleTime: 60_000 })

  return (
    <section aria-labelledby="register-title" className="flex flex-col gap-4">
      <PageHeader
        className="mb-0"
        title={<span id="register-title">{intl.formatMessage(drm.title)}</span>}
        description={intl.formatMessage(drm.lead, terms)}
      />
      {scope.isPending ? (
        <LoadingState />
      ) : scope.isError ? (
        isProblem(scope.error) && scope.error.status === 403 ? (
          <EmptyState
            title={intl.formatMessage(drm.noScope, terms)}
            description={intl.formatMessage(drm.noScopeHint, terms)}
          />
        ) : (
          <ErrorState onRetry={() => void scope.refetch()} />
        )
      ) : (
        <Register
          search={search}
          q={q}
          scope={scope.data}
          terms={terms}
          go={(next, words) => {
            void navigate({ to: '/decisions', search: withValidSort(next), hash: hashOf(words) })
          }}
        />
      )}
    </section>
  )
}

function Register({
  search,
  q,
  scope,
  terms,
  go,
}: {
  search: RegisterSearch
  q: string
  scope: RegisterScopeView
  terms: TermValues
  go: (next: RegisterSearch, words: string) => void
}) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const countRef = useRef<HTMLHeadingElement>(null)
  /** Where focus goes once the results are drawn: the count after a search, the first new row after "Show more". */
  const focus = useRef<{ to: 'count' } | { to: 'row'; index: number }>(undefined)
  const [exporting, setExporting] = useState(false)
  const today = todayIso()

  const body = searchBody(search, q)
  const key = [SEARCH_KEY, body] as const
  const results = useInfiniteQuery({
    queryKey: key,
    queryFn: async ({ pageParam, signal }) => {
      const page = pageParam ? { ...body, cursor: pageParam } : body
      const { data } = await searchDecisionRegister({ body: page, signal, throwOnError: true })
      return data
    },
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
    staleTime: 60_000,
    gcTime: 30 * 60_000,
    // Refusals (rate limit, timeout, bad input) would only repeat; a lost connection may not.
    retry: (count, error) => count < 1 && !isProblem(error),
  })
  const first = results.data?.pages[0]
  const rows = useMemo(() => results.data?.pages.flatMap((p) => p.rows) ?? [], [results.data])
  const refusal = results.isError ? searchRefusal(results.error) : undefined
  const keyText = JSON.stringify(body)

  const apply = (next: RegisterSearch, words = q) => {
    if (JSON.stringify(searchBody(next, words)) === keyText && !results.isFetching) {
      // The same search again: nothing reloads, so the count takes focus now.
      countRef.current?.focus()
      return
    }
    focus.current = { to: 'count' }
    go(next, words)
  }

  // Focus after a search, a chip or the order (the count, which says what was found), or after "Show more".
  useEffect(() => {
    const pending = focus.current
    if (!pending || results.isFetching) return
    focus.current = undefined
    if (pending.to === 'count') countRef.current?.focus()
    else {
      const row = rows[pending.index]
      if (row) document.getElementById(rowFocusId(row))?.focus()
    }
  }, [results.isFetching, rows, keyText])

  // Coming back to the same search: the row opened last takes focus again (flow AS2).
  useEffect(() => {
    if (lastOpened?.key !== keyText) return
    const target = document.getElementById(`decision-${lastOpened.decisionId}`)
    if (target) {
      target.focus()
      lastOpened = undefined
    }
  }, [keyText, rows.length])

  const chips = chipsOf(intl, search, scope, terms)
  const filtered = hasFilters(search) || q.length > 0
  const sort = effectiveSort(search, q)
  const sorts = SORTS.filter((s) =>
    s === 'relevance' ? q.length > 0 : s === 'number' ? Boolean(search.committee) : true,
  )
  const searchAgain = () => {
    focus.current = { to: 'count' }
    void queryClient.resetQueries({ queryKey: key })
  }

  return (
    <>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 flex-[1_1_40rem]">
          <SearchForm
            key={`${JSON.stringify(search)}|${q}`}
            search={search}
            q={q}
            scope={scope}
            refusal={{ text: refusal?.text, dates: refusal?.dates }}
            onSearch={(next, words) => {
              apply(next, words)
            }}
          />
        </div>
        {scope.canExport ? (
          <Button
            variant="outline"
            className="min-h-11"
            disabled={!first || first.total === 0}
            onClick={() => {
              setExporting(true)
            }}
          >
            <FileDown aria-hidden="true" />
            {intl.formatMessage(drm.export)}
          </Button>
        ) : null}
      </div>

      <div className="flex flex-col gap-3 border-t pt-4">
        <div className="flex flex-wrap items-end gap-3">
          <h2
            ref={countRef}
            tabIndex={-1}
            aria-live="polite"
            aria-atomic="true"
            className="me-auto text-base font-semibold focus-visible:outline-2 focus-visible:outline-ring"
          >
            {first
              ? first.capped
                ? intl.formatMessage(drm.countCapped, { count: intl.formatNumber(first.total) })
                : intl.formatMessage(drm.count, { count: first.total })
              : results.isFetching
                ? intl.formatMessage(drm.searching)
                : null}
          </h2>
          <Field label={intl.formatMessage(drm.sort)} className="w-48">
            {(p) => (
              <Select
                {...p}
                className="min-h-11"
                value={sort}
                onChange={(e) => {
                  const next = SORTS.find((s) => s === e.target.value)
                  if (next) apply({ ...search, sort: next })
                }}
              >
                {sorts.map((s) => (
                  <option key={s} value={s}>
                    {intl.formatMessage(sortLabels[s])}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          {filtered ? (
            <Button
              variant="outline"
              className="min-h-11"
              onClick={() => {
                apply({}, '')
              }}
            >
              {intl.formatMessage(drm.reset)}
            </Button>
          ) : null}
        </div>
        <Chips
          chips={chips}
          onRemove={(chip) => {
            apply(chip.without)
          }}
        />
        {filtersOnContent(search, q) ? (
          <p className="text-sm text-muted-foreground">{intl.formatMessage(drm.restrictedNote)}</p>
        ) : null}
        {refusal?.filters ? (
          <p role="alert" className="rounded-md border border-destructive/40 p-3 text-sm font-medium">
            {intl.formatMessage(refusal.filters, terms)}
          </p>
        ) : null}
        {first ? <Facets facets={first.facets} search={search} q={q} terms={terms} /> : null}
        {search.committee && scope.canCheckNumbers ? (
          <NumberCheck key={search.committee} forumId={search.committee} />
        ) : null}

        <Results
          rows={rows}
          pending={results.isPending}
          stale={results.isPlaceholderData}
          refusal={refusal?.kind}
          filtered={filtered}
          today={today}
          terms={terms}
          onRetry={() => void results.refetch()}
          onSearchAgain={searchAgain}
          onReset={() => {
            apply({}, '')
          }}
          onOpen={(row) => {
            lastOpened = { key: keyText, decisionId: row.decisionId }
          }}
          more={
            results.hasNextPage && !results.isError
              ? () => {
                  focus.current = { to: 'row', index: rows.length }
                  void results.fetchNextPage()
                }
              : undefined
          }
          loadingMore={results.isFetchingNextPage}
        />
      </div>

      {exporting && first ? (
        <ExportDialog
          open
          onClose={() => {
            setExporting(false)
          }}
          search={search}
          q={q}
          total={first.total}
          capped={first.capped}
          facets={first.facets}
          scope={scope}
        />
      ) : null}
    </>
  )
}

/** The filters applied, each a button that removes it (BoardPro's chips); Reset clears them all. */
function Chips({ chips, onRemove }: { chips: Chip[]; onRemove: (chip: Chip) => void }) {
  const intl = useIntl()
  if (chips.length === 0) return null
  return (
    <ul aria-label={intl.formatMessage(drm.activeFilters)} className="flex flex-wrap gap-2">
      {chips.map((chip) => {
        const text = chipWords(intl, chip)
        return (
          <li key={chip.key}>
            <button
              type="button"
              aria-label={intl.formatMessage(drm.removeFilter, { filter: text })}
              onClick={() => {
                onRemove(chip)
              }}
              className="inline-flex min-h-11 items-center gap-1.5 rounded-full border bg-card px-4 text-sm hover:bg-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
            >
              {text}
              <X aria-hidden="true" className="size-4" />
            </button>
          </li>
        )
      })}
    </ul>
  )
}

const FACET_LINK =
  'inline-flex min-h-11 items-center text-primary underline underline-offset-4 hover:decoration-2 focus-visible:outline-2 focus-visible:outline-ring'

/**
 * Counts by committee, outcome and year, each leading to the register narrowed to it. Committee and year counts include
 * restricted lines; outcome counts only rows seen in full (design §1.3), and the page says so.
 */
function Facets({
  facets,
  search,
  q,
  terms,
}: {
  facets: DecisionFacetsView
  search: RegisterSearch
  q: string
  terms: TermValues
}) {
  const intl = useIntl()
  const hash = hashOf(q)
  const value = (label: string, count: number) =>
    intl.formatMessage(drm.facetValue, { label, count: intl.formatNumber(count) })
  const groups = [
    {
      key: 'forums',
      label: intl.formatMessage(drm.byCommittee, terms),
      items: search.committee
        ? []
        : facets.forums.map((f) => ({
            key: f.forumId,
            text: value(f.name, f.count),
            search: { ...search, committee: f.forumId },
          })),
    },
    {
      key: 'outcomes',
      label: intl.formatMessage(drm.byOutcome),
      items: search.outcome
        ? []
        : facets.outcomes.map((o) => ({
            key: o.outcome,
            text: value(outcomeWords(intl, o.outcome), o.count),
            search: { ...search, outcome: o.outcome as Outcome },
          })),
    },
    {
      key: 'years',
      label: intl.formatMessage(drm.byYear),
      items:
        facets.years.length > 1
          ? facets.years.map((y) => ({
              key: String(y.year),
              text: value(intl.formatNumber(y.year, { useGrouping: false }), y.count),
              search: { ...search, ...yearRange(y.year) },
            }))
          : [],
    },
  ].filter((g) => g.items.length > 0)
  if (groups.length === 0) return null
  return (
    <div className="flex flex-col gap-1 text-sm">
      <dl className="flex flex-col gap-1">
        {groups.map((g) => (
          <div key={g.key} className="flex flex-wrap items-center gap-x-3">
            <dt className="font-medium">{g.label}</dt>
            <dd>
              <ul className="flex flex-wrap gap-x-3">
                {g.items.map((item) => (
                  <li key={item.key}>
                    <Link to="/decisions" search={withValidSort(item.search)} hash={hash} className={FACET_LINK}>
                      {item.text}
                    </Link>
                  </li>
                ))}
              </ul>
            </dd>
          </div>
        ))}
      </dl>
      {groups.some((g) => g.key === 'outcomes') ? (
        <p className="text-xs text-muted-foreground">{intl.formatMessage(drm.facetHint)}</p>
      ) : null}
    </div>
  )
}

function Results({
  rows,
  pending,
  stale,
  refusal,
  filtered,
  today,
  terms,
  onRetry,
  onSearchAgain,
  onReset,
  onOpen,
  more,
  loadingMore,
}: {
  rows: DecisionRowView[]
  pending: boolean
  stale: boolean
  refusal?: ReturnType<typeof searchRefusal>['kind']
  filtered: boolean
  today: string
  terms: TermValues
  onRetry: () => void
  onSearchAgain: () => void
  onReset: () => void
  onOpen: (row: DecisionRowView) => void
  more?: () => void
  loadingMore: boolean
}) {
  const intl = useIntl()
  const problem =
    refusal === 'rateLimited' ? (
      <ErrorState title={intl.formatMessage(drm.rateLimited)} onRetry={onRetry} />
    ) : refusal === 'timeout' ? (
      <ErrorState title={intl.formatMessage(drm.timeout)} />
    ) : refusal === 'cursor' ? (
      <div role="alert" className="flex flex-col items-center gap-2 py-6 text-center">
        <p className="font-medium">{intl.formatMessage(drm.cursorInvalid)}</p>
        <Button variant="outline" className="min-h-11" onClick={onSearchAgain}>
          {intl.formatMessage(drm.searchAgain)}
        </Button>
      </div>
    ) : refusal === 'other' || refusal === 'noScope' ? (
      <ErrorState onRetry={onRetry} />
    ) : null
  // A refused first page has nothing to show; a refused next page keeps the rows already shown.
  if (problem && rows.length === 0) return problem
  if (refusal === 'invalid') return null
  if (pending) return <LoadingState />
  if (rows.length === 0) {
    return filtered ? (
      <EmptyState
        title={intl.formatMessage(drm.empty)}
        description={intl.formatMessage(drm.emptyHint)}
        action={
          <Button variant="outline" className="min-h-11" onClick={onReset}>
            {intl.formatMessage(drm.reset)}
          </Button>
        }
      />
    ) : (
      <EmptyState title={intl.formatMessage(drm.emptyAll, terms)} />
    )
  }
  return (
    <>
      <ul
        aria-label={intl.formatMessage(drm.results)}
        aria-busy={stale || undefined}
        className={
          stale ? 'flex flex-col divide-y rounded-md border opacity-60' : 'flex flex-col divide-y rounded-md border'
        }
      >
        {rows.map((row) => (
          <li key={row.decisionId}>
            <RegisterRow row={row} today={today} onOpen={onOpen} />
          </li>
        ))}
      </ul>
      {problem}
      {more ? (
        <div className="flex justify-center">
          <Button variant="outline" className="min-h-11" disabled={loadingMore} onClick={more}>
            {intl.formatMessage(loadingMore ? drm.loadingMore : drm.showMore)}
          </Button>
        </div>
      ) : null}
    </>
  )
}
