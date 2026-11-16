import { keepPreviousData, useInfiniteQuery, useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Bookmark, BookmarkPlus, SlidersHorizontal, X } from 'lucide-react'
import { Fragment, useEffect, useId, useState, type SyntheticEvent } from 'react'
import { useIntl, type IntlShape, type MessageDescriptor } from 'react-intl'
import { search } from '@/api/generated/sdk.gen'
import type { FacetView, SearchFacetsView, SearchPageView, SearchResultView } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Sheet } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select } from '@/components/ui/form-controls'
import { useSession } from '@/features/auth/session'
import { statusLabels } from '@/features/memo/memo-messages'
import { statusTone, type MemoStatus } from '@/features/memo/memo-status'
import { rememberSearch } from './recent-searches'
import { SaveSearchDialog } from './SaveSearchDialog'
import { sm, smv } from './search-messages'
import {
  filterCount,
  hashOf,
  searchBody,
  SOURCES,
  withoutFilters,
  type SearchParams,
  type Source,
} from './search-params'
import { searchFailure, type FieldErrors } from './search-problems'

const sourceLabels: Record<Source, MessageDescriptor> = {
  metadata: sm.sourceMetadata,
  notes: sm.sourceNotes,
  documents: sm.sourceDocuments,
}

type Facet = 'status' | 'type' | 'unit'
const FACETS: Facet[] = ['status', 'type', 'unit']
const facetLabels: Record<Facet, MessageDescriptor> = { status: sm.facetStatus, type: sm.facetType, unit: sm.facetUnit }

/** Names of type and unit ids seen in this session's results, so a chip still has a name when no memo matches. */
type Labels = ReadonlyMap<string, string>

/** Query key of a search; the words are in it in memory only, never in a URL. */
const SEARCH_KEY = 'memoSearch'

function statusLabel(intl: IntlShape, status: string): string {
  return status in statusLabels ? intl.formatMessage(statusLabels[status as MemoStatus]) : status
}

function valueLabel(intl: IntlShape, facet: Facet, value: string, labels: Labels): string {
  return facet === 'status' ? statusLabel(intl, value) : (labels.get(value) ?? value)
}

function dateLabel(intl: IntlShape, date: string): string {
  return intl.formatDate(`${date}T00:00:00Z`, { dateStyle: 'medium', timeZone: 'UTC' })
}

/**
 * `/search` (flow H2): the filters live in the query string and the words in the fragment, and the search itself is a
 * POST, so the words never reach a server log or a query string. The results show where each memo matched, with the
 * matches marked. Only memos the reader may read are found or counted (the server decides).
 */
export function SearchPage({ params, q = '' }: { params: SearchParams; q?: string }) {
  const intl = useIntl()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const session = useSession()
  const userId = session.data?.user?.id
  const [saving, setSaving] = useState(false)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const go = (next: SearchParams, words = q) => void navigate({ to: '/search', search: next, hash: hashOf(words) })

  useEffect(() => {
    if (q && userId) rememberSearch(userId, q)
  }, [q, userId])

  const body = searchBody(params, q)
  const results = useInfiniteQuery({
    queryKey: [SEARCH_KEY, body],
    queryFn: async ({ pageParam, signal }) => {
      const page = pageParam ? { ...body, cursor: pageParam } : body
      const { data } = await search({ body: page, signal, throwOnError: true })
      return data
    },
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.cursor ?? undefined,
    enabled: q.length > 0,
    placeholderData: keepPreviousData,
    // Refusals (rate limit, timeout, bad input) would only repeat; a lost connection may not.
    retry: (count, error) => count < 1 && !isProblem(error),
  })
  const first = results.data?.pages[0]
  const items = results.data?.pages.flatMap((p) => p.results) ?? []
  const failure = results.isError ? searchFailure(results.error) : undefined
  const fieldErrors: FieldErrors = failure?.kind === 'invalid' ? failure.fields : {}

  const labels = new Map<string, string>()
  for (const [, data] of queryClient.getQueriesData<InfiniteData<SearchPageView>>({ queryKey: [SEARCH_KEY] })) {
    for (const p of data?.pages ?? [])
      for (const f of [...p.facets.type, ...p.facets.unit]) labels.set(f.value, f.label)
  }

  const count = filterCount(params)
  const panel = (onApplied?: () => void) => (
    <FilterPanel
      key={JSON.stringify(params)}
      params={params}
      facets={first?.facets}
      labels={labels}
      errors={fieldErrors}
      onApply={(next) => {
        go(next)
        onApplied?.()
      }}
    />
  )

  return (
    <section aria-labelledby="search-title">
      <PageHeader
        title={<span id="search-title">{intl.formatMessage(sm.title)}</span>}
        actions={
          <>
            <Button variant="outline" asChild>
              <Link to="/search/saved">
                <Bookmark aria-hidden="true" />
                {intl.formatMessage(sm.savedLink)}
              </Link>
            </Button>
            {q ? (
              <Button
                onClick={() => {
                  setSaving(true)
                }}
              >
                <BookmarkPlus aria-hidden="true" />
                {intl.formatMessage(sm.saveThis)}
              </Button>
            ) : null}
          </>
        }
      />
      <QueryForm
        key={q}
        initial={q}
        error={fieldErrors.q}
        onSearch={(text) => {
          go(params, text)
        }}
      />
      {q ? (
        <div className="mt-5 grid gap-6 lg:grid-cols-[16rem_minmax(0,1fr)]">
          <aside aria-labelledby="search-filters" className="hidden lg:block">
            <h2 id="search-filters" className="mb-3 text-base font-semibold">
              {intl.formatMessage(sm.filters)}
            </h2>
            {panel()}
          </aside>
          <div className="flex min-w-0 flex-col gap-3">
            <div className="flex flex-wrap items-center gap-2">
              <div aria-live="polite" aria-atomic="true" className="me-2">
                {first ? (
                  <h2 className="text-base font-semibold">
                    {first.capped
                      ? intl.formatMessage(smv.countCapped, { count: first.count })
                      : intl.formatMessage(smv.count, { count: first.count })}
                  </h2>
                ) : results.isFetching ? (
                  <h2 className="text-base font-semibold">{intl.formatMessage(sm.searching)}</h2>
                ) : null}
              </div>
              <Chips
                params={params}
                labels={labels}
                onChange={go}
                onClear={() => {
                  go(withoutFilters(params))
                }}
              />
            </div>
            {first?.capped ? (
              <p className="text-sm text-muted-foreground">
                {intl.formatMessage(smv.cappedHint, { count: first.count })}
              </p>
            ) : null}
            <div className="flex flex-wrap items-end gap-3">
              <Button
                variant="outline"
                className="lg:hidden"
                aria-haspopup="dialog"
                onClick={() => {
                  setFiltersOpen(true)
                }}
              >
                <SlidersHorizontal aria-hidden="true" />
                {intl.formatMessage(smv.filtersButton, { count })}
              </Button>
              <Field label={intl.formatMessage(sm.sort)} className="ms-auto w-44">
                {(p) => (
                  <Select
                    {...p}
                    value={params.sort ?? 'relevance'}
                    onChange={(e) => {
                      go({ ...params, sort: e.target.value === 'newest' ? 'newest' : undefined })
                    }}
                  >
                    <option value="relevance">{intl.formatMessage(sm.sortRelevance)}</option>
                    <option value="newest">{intl.formatMessage(sm.sortNewest)}</option>
                  </Select>
                )}
              </Field>
            </div>
            <Results
              items={items}
              pending={results.isPending}
              stale={results.isPlaceholderData}
              failure={failure?.kind}
              filtered={count > 0}
              onRetry={() => void results.refetch()}
              onClear={() => {
                go(withoutFilters(params))
              }}
              more={results.hasNextPage ? () => void results.fetchNextPage() : undefined}
              loadingMore={results.isFetchingNextPage}
            />
          </div>
        </div>
      ) : (
        <EmptyState title={intl.formatMessage(sm.prompt)} />
      )}
      <Sheet open={filtersOpen} onOpenChange={setFiltersOpen} label={intl.formatMessage(sm.filters)}>
        {filtersOpen ? (
          <div className="flex flex-col gap-3 p-4">
            <h2 className="text-base font-semibold">{intl.formatMessage(sm.filters)}</h2>
            {panel(() => {
              setFiltersOpen(false)
            })}
          </div>
        ) : null}
      </Sheet>
      {saving ? (
        <SaveSearchDialog
          params={params}
          q={q}
          onClose={() => {
            setSaving(false)
          }}
        />
      ) : null}
    </section>
  )
}

function QueryForm({
  initial,
  error,
  onSearch,
}: {
  initial: string
  error?: MessageDescriptor
  onSearch: (text: string) => void
}) {
  const intl = useIntl()
  const id = useId()
  const [text, setText] = useState(initial)
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    if (text.trim()) onSearch(text.trim())
  }
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-1.5">
      <label htmlFor={id} className="sr-only">
        {intl.formatMessage(sm.queryLabel)}
      </label>
      <div className="flex gap-2">
        <Input
          id={id}
          type="search"
          name="q"
          value={text}
          autoComplete="off"
          enterKeyHint="search"
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-error` : undefined}
          className="max-w-2xl"
          onChange={(e) => {
            setText(e.target.value)
          }}
        />
        <Button type="submit">{intl.formatMessage(sm.submit)}</Button>
      </div>
      {error ? (
        <p id={`${id}-error`} className="text-xs font-medium text-destructive">
          {intl.formatMessage(error)}
        </p>
      ) : null}
    </form>
  )
}

/** The active filters, each removable, and Clear filters. */
function Chips({
  params,
  labels,
  onChange,
  onClear,
}: {
  params: SearchParams
  labels: Labels
  onChange: (next: SearchParams) => void
  onClear: () => void
}) {
  const intl = useIntl()
  const chips: { key: string; name: string; value: string; without: SearchParams }[] = []
  for (const facet of FACETS) {
    for (const value of params[facet] ?? []) {
      const rest = params[facet]?.filter((v) => v !== value)
      chips.push({
        key: `${facet}:${value}`,
        name: intl.formatMessage(facetLabels[facet]),
        value: valueLabel(intl, facet, value, labels),
        without: { ...params, [facet]: rest?.length ? rest : undefined },
      })
    }
  }
  const single = (key: 'from' | 'to' | 'amountMin' | 'amountMax', name: MessageDescriptor, value: string) => {
    chips.push({ key, name: intl.formatMessage(name), value, without: { ...params, [key]: undefined } })
  }
  if (params.from) single('from', sm.from, dateLabel(intl, params.from))
  if (params.to) single('to', sm.to, dateLabel(intl, params.to))
  if (params.amountMin !== undefined) single('amountMin', sm.amountMin, intl.formatNumber(params.amountMin))
  if (params.amountMax !== undefined) single('amountMax', sm.amountMax, intl.formatNumber(params.amountMax))
  if (params.in) {
    chips.push({
      key: 'in',
      name: intl.formatMessage(sm.searchIn),
      value: intl.formatList(params.in.map((s) => intl.formatMessage(sourceLabels[s]))),
      without: { ...params, in: undefined },
    })
  }
  if (chips.length === 0) return null
  return (
    <>
      <ul aria-label={intl.formatMessage(sm.activeFilters)} className="flex flex-wrap gap-2">
        {chips.map((chip) => {
          const text = intl.formatMessage(smv.chip, { name: chip.name, value: chip.value })
          return (
            <li key={chip.key}>
              <button
                type="button"
                aria-label={intl.formatMessage(smv.removeFilter, { filter: text })}
                onClick={() => {
                  onChange(chip.without)
                }}
                className="inline-flex min-h-8 items-center gap-1.5 rounded-full border bg-card px-3 text-xs hover:bg-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
              >
                {text}
                <X aria-hidden="true" className="size-3.5" />
              </button>
            </li>
          )
        })}
      </ul>
      <Button variant="link" onClick={onClear}>
        {intl.formatMessage(sm.clearFilters)}
      </Button>
    </>
  )
}

interface Draft {
  status: string[]
  type: string[]
  unit: string[]
  from: string
  to: string
  amountMin: string
  amountMax: string
  in: Source[]
}

function draftOf(params: SearchParams): Draft {
  return {
    status: params.status ?? [],
    type: params.type ?? [],
    unit: params.unit ?? [],
    from: params.from ?? '',
    to: params.to ?? '',
    amountMin: params.amountMin === undefined ? '' : String(params.amountMin),
    amountMax: params.amountMax === undefined ? '' : String(params.amountMax),
    in: params.in ?? [...SOURCES],
  }
}

function amountOf(value: string): number | undefined {
  const n = value.trim() ? Number(value) : NaN
  return Number.isFinite(n) && n >= 0 ? n : undefined
}

/** The same checks the server makes, so a plain mistake is shown at its field without a round trip. */
function checkDraft(draft: Draft): FieldErrors {
  const errors: FieldErrors = {}
  if (draft.from && draft.to && draft.to < draft.from) errors.to = sm.toBeforeFrom
  if (draft.amountMin.trim() && amountOf(draft.amountMin) === undefined) errors.amountMin = sm.badFilter
  if (draft.amountMax.trim() && amountOf(draft.amountMax) === undefined) errors.amountMax = sm.badFilter
  const min = amountOf(draft.amountMin)
  const max = amountOf(draft.amountMax)
  if (min !== undefined && max !== undefined && max < min) errors.amountMax = sm.maxBelowMin
  if (draft.in.length === 0) errors.in = sm.inRequired
  return errors
}

/** The filter panel (flow H2): facets with counts from the results, dates, amounts and where to look; Apply filters. */
function FilterPanel({
  params,
  facets,
  labels,
  errors,
  onApply,
}: {
  params: SearchParams
  facets?: SearchFacetsView
  labels: Labels
  errors: FieldErrors
  onApply: (next: SearchParams) => void
}) {
  const intl = useIntl()
  const [draft, setDraft] = useState(() => draftOf(params))
  const [local, setLocal] = useState<FieldErrors>({})
  const shown = Object.keys(local).length > 0 ? local : errors
  const message = (field: keyof FieldErrors) => {
    const m = shown[field]
    return m ? intl.formatMessage(m) : undefined
  }
  const toggle = <K extends 'status' | 'type' | 'unit' | 'in'>(key: K, value: Draft[K][number], on: boolean) => {
    setDraft((d) => ({
      ...d,
      [key]: on ? [...(d[key] as string[]), value] : (d[key] as string[]).filter((v) => v !== value),
    }))
  }
  const apply = (e: SyntheticEvent) => {
    e.preventDefault()
    const problems = checkDraft(draft)
    setLocal(problems)
    if (Object.keys(problems).length > 0) return
    onApply({
      sort: params.sort,
      status: draft.status.length ? draft.status : undefined,
      type: draft.type.length ? draft.type : undefined,
      unit: draft.unit.length ? draft.unit : undefined,
      from: draft.from || undefined,
      to: draft.to || undefined,
      amountMin: amountOf(draft.amountMin),
      amountMax: amountOf(draft.amountMax),
      in: draft.in.length < SOURCES.length ? SOURCES.filter((s) => draft.in.includes(s)) : undefined,
    })
  }

  return (
    <form noValidate onSubmit={apply} className="flex flex-col gap-5">
      {shown.filters ? (
        <p role="alert" className="text-xs font-medium text-destructive">
          {intl.formatMessage(shown.filters)}
        </p>
      ) : null}
      {FACETS.map((facet) => {
        const options = facetOptions(facets?.[facet] ?? [], draft[facet])
        if (options.length === 0) return null
        return (
          <Group key={facet} legend={intl.formatMessage(facetLabels[facet])} error={message(facet)}>
            {options.map((o) => (
              <CheckRow
                key={o.value}
                checked={draft[facet].includes(o.value)}
                onChange={(on) => {
                  toggle(facet, o.value, on)
                }}
              >
                {o.count === undefined
                  ? valueLabel(intl, facet, o.value, labels)
                  : intl.formatMessage(smv.facetOption, {
                      label: facet === 'status' ? statusLabel(intl, o.value) : o.label,
                      count: o.count,
                    })}
              </CheckRow>
            ))}
          </Group>
        )
      })}
      <Group legend={intl.formatMessage(sm.date)}>
        <Field label={intl.formatMessage(sm.from)} error={message('from')}>
          {(p) => (
            <Input
              {...p}
              type="date"
              name="from"
              value={draft.from}
              onChange={(e) => {
                setDraft((d) => ({ ...d, from: e.target.value }))
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(sm.to)} error={message('to')}>
          {(p) => (
            <Input
              {...p}
              type="date"
              name="to"
              value={draft.to}
              onChange={(e) => {
                setDraft((d) => ({ ...d, to: e.target.value }))
              }}
            />
          )}
        </Field>
      </Group>
      <Group legend={intl.formatMessage(sm.amount)}>
        <Field label={intl.formatMessage(sm.amountMin)} error={message('amountMin')}>
          {(p) => (
            <Input
              {...p}
              type="number"
              name="amountMin"
              inputMode="decimal"
              min={0}
              value={draft.amountMin}
              onChange={(e) => {
                setDraft((d) => ({ ...d, amountMin: e.target.value }))
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(sm.amountMax)} error={message('amountMax')}>
          {(p) => (
            <Input
              {...p}
              type="number"
              name="amountMax"
              inputMode="decimal"
              min={0}
              value={draft.amountMax}
              onChange={(e) => {
                setDraft((d) => ({ ...d, amountMax: e.target.value }))
              }}
            />
          )}
        </Field>
      </Group>
      <Group legend={intl.formatMessage(sm.searchIn)} error={message('in')}>
        {SOURCES.map((source) => (
          <CheckRow
            key={source}
            checked={draft.in.includes(source)}
            onChange={(on) => {
              toggle('in', source, on)
            }}
          >
            {intl.formatMessage(sourceLabels[source])}
          </CheckRow>
        ))}
      </Group>
      <Button type="submit">{intl.formatMessage(sm.apply)}</Button>
    </form>
  )
}

/** The facet's values with their counts, plus values chosen earlier that no longer match anything (no count). */
function facetOptions(values: FacetView[], chosen: string[]): { value: string; label: string; count?: number }[] {
  const listed = new Set(values.map((v) => v.value))
  return [...values, ...chosen.filter((c) => !listed.has(c)).map((value) => ({ value, label: value }))]
}

function Group({ legend, error, children }: { legend: string; error?: string; children: React.ReactNode }) {
  const id = useId()
  return (
    <fieldset aria-describedby={error ? id : undefined} className="flex min-w-0 flex-col gap-2">
      <legend className="mb-1 text-sm font-semibold">{legend}</legend>
      {children}
      {error ? (
        <p id={id} className="text-xs font-medium text-destructive">
          {error}
        </p>
      ) : null}
    </fieldset>
  )
}

function CheckRow({
  checked,
  onChange,
  children,
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  children: React.ReactNode
}) {
  return (
    <label className="flex min-h-8 items-center gap-2 text-sm">
      <Checkbox
        checked={checked}
        onChange={(e) => {
          onChange(e.target.checked)
        }}
      />
      <span className="min-w-0 break-words">{children}</span>
    </label>
  )
}

function Results({
  items,
  pending,
  stale,
  failure,
  filtered,
  onRetry,
  onClear,
  more,
  loadingMore,
}: {
  items: SearchResultView[]
  pending: boolean
  stale: boolean
  failure?: 'rateLimited' | 'timeout' | 'invalid' | 'other'
  filtered: boolean
  onRetry: () => void
  onClear: () => void
  more?: () => void
  loadingMore: boolean
}) {
  const intl = useIntl()
  if (failure === 'rateLimited') return <ErrorState title={intl.formatMessage(sm.rateLimited)} onRetry={onRetry} />
  if (failure === 'timeout') return <ErrorState title={intl.formatMessage(sm.timeout)} />
  if (failure === 'invalid') return <ErrorState title={intl.formatMessage(sm.invalid)} />
  if (failure === 'other') return <ErrorState onRetry={onRetry} />
  if (pending) return <LoadingState />
  if (items.length === 0) {
    return (
      <EmptyState
        title={intl.formatMessage(sm.empty)}
        action={
          filtered ? (
            <Button variant="outline" onClick={onClear}>
              {intl.formatMessage(sm.clearFilters)}
            </Button>
          ) : undefined
        }
      />
    )
  }
  return (
    <>
      <ul
        aria-label={intl.formatMessage(sm.results)}
        aria-busy={stale || undefined}
        className={
          stale ? 'flex flex-col divide-y rounded-md border opacity-60' : 'flex flex-col divide-y rounded-md border'
        }
      >
        {items.map((item) => (
          <li key={item.memoId}>
            <ResultRow item={item} />
          </li>
        ))}
      </ul>
      {more ? (
        <div className="flex justify-center">
          <Button variant="outline" disabled={loadingMore} onClick={more}>
            {intl.formatMessage(sm.loadMore)}
          </Button>
        </div>
      ) : null}
    </>
  )
}

function foundIn(intl: IntlShape, item: SearchResultView): string {
  const name = item.foundIn.name ?? undefined
  switch (item.foundIn.kind) {
    case 'DETAILS':
      return intl.formatMessage(sm.foundDetails)
    case 'NUMBER':
      return intl.formatMessage(sm.foundNumber)
    case 'NOTE':
      return name ? intl.formatMessage(smv.foundNote, { name }) : intl.formatMessage(sm.foundNoteAnyone)
    case 'DOCUMENT':
      return intl.formatMessage(sm.foundDocument)
    case 'ATTACHMENT':
      return name ? intl.formatMessage(smv.foundAttachment, { name }) : intl.formatMessage(sm.foundAttachmentAny)
  }
}

function ResultRow({ item }: { item: SearchResultView }) {
  const intl = useIntl()
  const updated = intl.formatMessage(smv.updated, {
    date: intl.formatDate(item.updatedAt, { dateStyle: 'medium' }),
  })
  return (
    <article className="flex flex-col gap-1 px-4 py-3">
      <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-1">
        <h3 className="min-w-0 flex-[1_1_16rem] font-medium">
          <Link
            to="/memos/$memoId"
            params={{ memoId: item.memoId }}
            className="text-primary underline-offset-4 hover:underline"
          >
            {[item.number, item.subject].filter(Boolean).join(' · ')}
          </Link>
        </h3>
        <Badge tone={item.status in statusTone ? statusTone[item.status as MemoStatus] : 'neutral'}>
          {statusLabel(intl, item.status)}
        </Badge>
      </div>
      <p className="text-xs text-muted-foreground">
        {[item.unitName, item.typeName, updated].filter(Boolean).join(' · ')}
      </p>
      {item.snippet.length > 0 ? (
        <p className="text-sm">
          {/* Each part is text; the server marks matches with a flag, never with HTML (design E05 §3). */}
          {item.snippet.map((part, i) =>
            part.match ? (
              <mark key={i} className="rounded-sm bg-status-warn-bg px-0.5 text-status-warn-fg">
                {part.text}
              </mark>
            ) : (
              <Fragment key={i}>{part.text}</Fragment>
            ),
          )}
        </p>
      ) : null}
      <p className="text-xs text-muted-foreground">{foundIn(intl, item)}</p>
    </article>
  )
}
