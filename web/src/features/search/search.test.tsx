import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type {
  SavedSearchRequest,
  SavedSearchView,
  SearchParams as SearchBody,
  SearchPageView,
  SearchResultView,
  SuggestionView,
  SuggestRequest,
} from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'
import { forgetRecentSearches } from './recent-searches'
import { hashOf, parseSearchParams, qOf } from './search-params'

const lease: SearchResultView = {
  memoId: 'm1',
  number: 'M-2026-00042',
  subject: 'Office lease extension',
  typeId: 't1',
  typeName: 'General memo',
  status: 'IN_REVIEW',
  unitId: 'u1',
  unitName: 'Credit Division',
  updatedAt: '2026-11-09T10:00:00Z',
  // The text of a note may contain markup-like text; it must show as typed, never as HTML.
  snippet: [
    { text: '…the revised ', match: false },
    { text: 'rent schedule', match: true },
    { text: ' for floors <b>3</b> and 4…', match: false },
  ],
  foundIn: { kind: 'NOTE', name: 'Demo Head of Credit' },
}

const credit: SearchResultView = {
  memoId: 'm2',
  number: 'CR/2026/0001',
  subject: 'Credit line renewal for Meghna Agro',
  typeId: 't2',
  typeName: 'Credit proposal',
  status: 'APPROVED',
  unitId: 'u1',
  unitName: 'Credit Division',
  updatedAt: '2026-11-08T10:00:00Z',
  snippet: [
    { text: '…annex B, ', match: false },
    { text: 'rent schedule', match: true },
    { text: ' attached…', match: false },
  ],
  foundIn: { kind: 'ATTACHMENT', name: 'annex-b.pdf' },
}

const results = (over: Partial<SearchPageView> = {}): SearchPageView => ({
  results: [lease, credit],
  count: 2,
  capped: false,
  facets: {
    status: [
      { value: 'IN_REVIEW', label: 'IN_REVIEW', count: 1 },
      { value: 'APPROVED', label: 'APPROVED', count: 1 },
    ],
    type: [
      { value: 't1', label: 'General memo', count: 1 },
      { value: 't2', label: 'Credit proposal', count: 1 },
    ],
    unit: [{ value: 'u1', label: 'Credit Division', count: 2 }],
  },
  cursor: null,
  ...over,
})

const savedSearch: SavedSearchView = {
  id: 's1',
  name: 'Credit memos this quarter',
  criteria: {
    q: 'credit',
    statuses: ['IN_REVIEW'],
    types: [],
    units: [],
    sources: ['METADATA', 'NOTES', 'DOCUMENTS'],
    sort: 'RELEVANCE',
  },
  version: 3,
  createdAt: '2026-11-01T10:00:00Z',
  updatedAt: '2026-11-01T10:00:00Z',
}

/** `POST /search`, answering [respond] and keeping each request's body. */
function searchHandler(respond: (body: SearchBody) => Response = () => HttpResponse.json(results())) {
  const bodies: SearchBody[] = []
  server.use(
    http.post(`${API_ORIGIN}/api/v1/search`, async ({ request }) => {
      const body = (await request.json()) as SearchBody
      bodies.push(body)
      return respond(body)
    }),
  )
  return bodies
}

/** The body of a search for [q] with [filters]: relevance and 20 a page unless given. */
const sent = (q: string, filters: Partial<SearchBody> = {}): SearchBody => ({
  q,
  sort: 'relevance',
  limit: 20,
  ...filters,
})

const savedHandler = (items: SavedSearchView[] = [savedSearch]) =>
  http.get(`${API_ORIGIN}/api/v1/saved-searches`, () => HttpResponse.json<SavedSearchView[]>(items))

const problem = (status: number, body: Record<string, unknown> = {}) =>
  HttpResponse.json({ type: 'about:blank', title: 'Refused', status, ...body }, { status })

const json = (value: unknown) => encodeURIComponent(JSON.stringify(value))

/** Every URL requested during a test, to show the words never travel in one (threat model E05 I4). */
const requested: string[] = []
const record = ({ request }: { request: Request }) => {
  requested.push(decodeURIComponent(request.url))
}

function expectNoWordsInUrls(...words: string[]) {
  expect(requested.length).toBeGreaterThan(0)
  for (const word of words) expect(requested.filter((url) => url.includes(word))).toEqual([])
}

beforeEach(() => {
  requested.length = 0
  server.events.on('request:start', record)
  forgetRecentSearches()
  server.use(sessionHandler(signedInSession()), accessHandler([]), savedHandler())
})

afterEach(() => {
  server.events.removeListener('request:start', record)
})

function filterPanel() {
  const panel = screen.getByRole('heading', { name: 'Filters' }).closest('aside')
  if (!panel) throw new Error('no filter panel')
  return within(panel)
}

describe('search page', () => {
  it('shows each result with its matches marked as text, where it was found, and a polite count', async () => {
    const bodies = searchHandler()
    const { container, router } = renderRoute('/search#q=rent%20schedule')

    const link = await screen.findByRole('link', { name: 'M-2026-00042 · Office lease extension' }, { timeout: 5000 })
    expect(link).toHaveAttribute('href', '/memos/m1')
    expect(bodies).toEqual([sent('rent schedule')])
    expect(qOf(router.state.location.hash)).toBe('rent schedule')
    expect(screen.getByLabelText('Words to find')).toHaveValue('rent schedule')
    expectNoWordsInUrls('rent', 'schedule')

    const count = screen.getByRole('heading', { name: '2 memos' })
    expect(count.closest('[aria-live="polite"]')).not.toBeNull()

    const marks = container.querySelectorAll('mark')
    expect([...marks].map((m) => m.textContent)).toEqual(['rent schedule', 'rent schedule'])
    // Markup in a snippet is text: no <b> element is made from it.
    expect(screen.getByText(/for floors <b>3<\/b> and 4…/)).toBeInTheDocument()
    expect(container.querySelector('article b')).toBeNull()

    expect(screen.getByText('Found in: note by Demo Head of Credit')).toBeInTheDocument()
    expect(screen.getByText('Found in: attachment “annex-b.pdf”')).toBeInTheDocument()
    expect(screen.getByText(/^Credit Division · General memo · updated /)).toBeInTheDocument()
    const article = link.closest('article')
    if (!article) throw new Error('no article')
    expect(within(article).getByText('In review')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('says "2,000+" when the count reaches the cap', async () => {
    searchHandler(() => HttpResponse.json(results({ count: 2000, capped: true })))
    renderRoute('/search#q=rent')
    expect(await screen.findByRole('heading', { name: '2,000+ memos' }, { timeout: 5000 })).toBeInTheDocument()
    expect(
      screen.getByText('Showing the best matches among the first 2,000. Add words or filters to narrow it.'),
    ).toBeInTheDocument()
  })

  it('puts the filters in the query string and the words in the fragment; back, forward and chips work', async () => {
    const user = userEvent.setup()
    const bodies = searchHandler()
    const { router } = renderRoute('/search#q=rent')
    await screen.findByRole('heading', { name: '2 memos' }, { timeout: 5000 })

    await user.click(filterPanel().getByRole('checkbox', { name: 'In review (1)' }))
    await user.type(filterPanel().getByLabelText('From'), '2026-11-01')
    await user.click(filterPanel().getByRole('checkbox', { name: 'Documents' }))
    await user.click(filterPanel().getByRole('button', { name: 'Apply filters' }))

    await waitFor(() => {
      expect(router.state.location.search).toEqual({
        status: ['IN_REVIEW'],
        from: '2026-11-01',
        in: ['metadata', 'notes'],
      })
    })
    expect(router.state.location.searchStr).not.toContain('rent')
    expect(qOf(router.state.location.hash)).toBe('rent')
    await waitFor(() => {
      expect(bodies.at(-1)).toEqual(
        sent('rent', { status: ['IN_REVIEW'], from: '2026-11-01', in: ['metadata', 'notes'] }),
      )
    })
    expect(screen.getByRole('button', { name: 'Remove filter Status: In review' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove filter Search in: Details and Notes' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Filters (3)' })).toBeInTheDocument()

    router.history.back()
    await waitFor(() => {
      expect(router.state.location.search).toEqual({})
    })
    expect(qOf(router.state.location.hash)).toBe('rent')
    expect(screen.queryByRole('button', { name: /^Remove filter/ })).not.toBeInTheDocument()
    expect(filterPanel().getByRole('checkbox', { name: 'In review (1)' })).not.toBeChecked()

    router.history.forward()
    const chip = await screen.findByRole('button', { name: 'Remove filter Status: In review' })
    expect(filterPanel().getByRole('checkbox', { name: 'In review (1)' })).toBeChecked()
    await user.click(chip)
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ from: '2026-11-01', in: ['metadata', 'notes'] })
    })
    expect(qOf(router.state.location.hash)).toBe('rent')

    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => {
      expect(router.state.location.search).toEqual({})
    })
    expect(qOf(router.state.location.hash)).toBe('rent')
    expectNoWordsInUrls('rent')
  })

  it('orders by newest when asked, keeping the order in the address', async () => {
    const user = userEvent.setup()
    const bodies = searchHandler()
    const { router } = renderRoute('/search#q=rent')
    await screen.findByRole('heading', { name: '2 memos' }, { timeout: 5000 })
    await user.selectOptions(screen.getByLabelText('Sort'), 'Newest')
    await waitFor(() => {
      expect(bodies.at(-1)).toEqual(sent('rent', { sort: 'newest' }))
    })
    expect(router.state.location.search).toEqual({ sort: 'newest' })
    expect(qOf(router.state.location.hash)).toBe('rent')
  })

  it('checks dates and amounts at their fields before searching', async () => {
    const user = userEvent.setup()
    const bodies = searchHandler()
    renderRoute('/search#q=rent')
    await screen.findByRole('heading', { name: '2 memos' }, { timeout: 5000 })
    await user.type(filterPanel().getByLabelText('From'), '2026-11-10')
    await user.type(filterPanel().getByLabelText('To'), '2026-11-01')
    await user.type(filterPanel().getByLabelText('Minimum'), '500')
    await user.type(filterPanel().getByLabelText('Maximum'), '100')
    await user.click(filterPanel().getByRole('button', { name: 'Apply filters' }))
    expect(filterPanel().getByLabelText('To')).toHaveAccessibleDescription('The end date is before the start date.')
    expect(filterPanel().getByLabelText('Maximum')).toHaveAccessibleDescription('The maximum is below the minimum.')
    expect(bodies).toHaveLength(1)
  })

  it('loads more results with the cursor in the body', async () => {
    const user = userEvent.setup()
    const bodies = searchHandler((body) =>
      body.cursor === 'next'
        ? HttpResponse.json(results({ results: [credit], cursor: null }))
        : HttpResponse.json(results({ results: [lease], count: 2, cursor: 'next' })),
    )
    renderRoute('/search#q=rent')
    await screen.findByRole('link', { name: /Office lease extension/ }, { timeout: 5000 })
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    expect(await screen.findByRole('link', { name: /Credit line renewal/ })).toBeInTheDocument()
    expect(bodies).toEqual([sent('rent'), sent('rent', { cursor: 'next' })])
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('says when nothing matches, with Clear filters', async () => {
    const user = userEvent.setup()
    searchHandler(() =>
      HttpResponse.json(results({ results: [], count: 0, facets: { status: [], type: [], unit: [] } })),
    )
    const { router } = renderRoute(`/search?status=${json(['REJECTED'])}#q=rent`)
    expect(
      await screen.findByText(
        'No memos match. Check the spelling, remove filters, or search all of the memo’s text.',
        {},
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '0 memos' })).toBeInTheDocument()
    // The chosen status still shows (and can be unticked) although nothing matches it now.
    expect(filterPanel().getByRole('checkbox', { name: 'Rejected' })).toBeChecked()
    const clear = screen.getAllByRole('button', { name: 'Clear filters' })
    expect(clear).toHaveLength(2)
    await user.click(clear[1]!)
    await waitFor(() => {
      expect(router.state.location.search).toEqual({})
    })
    expect(qOf(router.state.location.hash)).toBe('rent')
  })

  it('says when there were too many searches in a minute', async () => {
    searchHandler(() => problem(429, { type: 'urn:memo:problem:rate-limited' }))
    renderRoute('/search#q=rent')
    expect(
      await screen.findByText('Too many searches in a minute. Try again shortly.', {}, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Too many searches in a minute.')
  })

  it('says when a search took too long', async () => {
    searchHandler(() => problem(503, { reason: 'SEARCH_TIMEOUT' }))
    renderRoute('/search#q=rent')
    expect(
      await screen.findByText('That search took too long. Add words or filters.', {}, { timeout: 5000 }),
    ).toBeInTheDocument()
  })

  it('shows input errors at the field', async () => {
    searchHandler(() =>
      problem(422, {
        type: 'urn:memo:problem:validation',
        errors: [
          { field: 'q', code: 'TOO_MANY_TERMS', message: 'At most 12 words.' },
          { field: 'to', code: 'BEFORE_FROM', message: 'The end is before the start.' },
        ],
      }),
    )
    renderRoute('/search?from=2026-11-10&to=2026-11-01#q=a%20b%20c')
    const query = await screen.findByLabelText('Words to find', {}, { timeout: 5000 })
    await waitFor(() => {
      expect(query).toHaveAccessibleDescription('Too many words. Keep the most telling ones.')
    })
    expect(query).toHaveAttribute('aria-invalid', 'true')
    expect(filterPanel().getByLabelText('To')).toHaveAccessibleDescription('The end date is before the start date.')
    expect(screen.getByText('Check the search words and filters.')).toBeInTheDocument()
  })

  it('searches again with the words typed, keeping the filters', async () => {
    const user = userEvent.setup()
    const bodies = searchHandler()
    const { router } = renderRoute(`/search?status=${json(['IN_REVIEW'])}#q=rent`)
    await screen.findByRole('heading', { name: '2 memos' }, { timeout: 5000 })
    const query = screen.getByLabelText('Words to find')
    await user.clear(query)
    await user.type(query, 'lease/annex & 100%{Enter}')
    await waitFor(() => {
      expect(qOf(router.state.location.hash)).toBe('lease/annex & 100%')
    })
    expect(router.state.location.search).toEqual({ status: ['IN_REVIEW'] })
    await waitFor(() => {
      expect(bodies.at(-1)).toEqual(sent('lease/annex & 100%', { status: ['IN_REVIEW'] }))
    })
    expectNoWordsInUrls('lease', 'annex')
    // "/" typed in a field stays in the field.
    expect(screen.getByRole('combobox', { name: 'Search memos' })).not.toHaveFocus()
  })

  it('asks for words before searching, and ignores words in the query string', async () => {
    const bodies = searchHandler()
    const { router } = renderRoute('/search?q=leaked')
    expect(
      await screen.findByText(
        'Type words from a memo’s details, notes or documents, or a memo number.',
        {},
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()
    // The words are dropped from the address too, by replacing the history entry.
    await waitFor(() => {
      expect(router.state.location.search).toEqual({})
    })
    expect(router.state.location.href).not.toContain('leaked')
    expect(bodies).toHaveLength(0)
    expect(screen.getByLabelText('Words to find')).toHaveValue('')
    expect(screen.queryByRole('button', { name: 'Save this search' })).not.toBeInTheDocument()
  })

  it('keeps only well-formed filters in the query string, and the words in the fragment', () => {
    expect(
      parseSearchParams({
        q: 'leaked',
        status: 'IN_REVIEW',
        in: ['metadata', 'notes', 'documents'],
        sort: 'relevance',
      }),
    ).toEqual({ status: ['IN_REVIEW'] })
    for (const words of ['rent schedule', 'a&b=c', '100% + #2', 'إيجار']) {
      // The router keeps the fragment decoded except for reserved characters (decodeURI); both forms read back.
      expect(qOf(decodeURI(hashOf(words)))).toBe(words)
      expect(qOf(`#${hashOf(words)}`)).toBe(words)
    }
    expect(hashOf('  ')).toBe('')
    expect(qOf('')).toBeUndefined()
  })

  it('reads in Arabic, right to left, with no axe findings', async () => {
    searchHandler()
    const { container } = renderRoute('/search#q=rent', { locale: 'ar' })
    expect(await screen.findByRole('heading', { level: 1, name: 'البحث' }, { timeout: 5000 })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'مذكرتان' })).toBeInTheDocument()
    expect(screen.getByText('وُجد في: ملاحظة كتبها Demo Head of Credit')).toBeInTheDocument()
    expect(document.documentElement).toHaveAttribute('dir', 'rtl')
    await expectNoA11yViolations(container)
  }, 20_000)
})

describe('save this search', () => {
  it('saves the words, filters and order under a name, and shows the limit at the field', async () => {
    const user = userEvent.setup()
    searchHandler()
    const bodies: SavedSearchRequest[] = []
    server.use(
      http.post(`${API_ORIGIN}/api/v1/saved-searches`, async ({ request }) => {
        bodies.push((await request.json()) as SavedSearchRequest)
        return bodies.length === 1
          ? problem(409, { reason: 'SAVED_SEARCH_LIMIT' })
          : HttpResponse.json<SavedSearchView>({ ...savedSearch, id: 's2', name: 'Lease' }, { status: 201 })
      }),
    )
    renderRoute(`/search?status=${json(['IN_REVIEW'])}&sort=%22newest%22&in=${json(['notes'])}#q=rent`)
    await user.click(await screen.findByRole('button', { name: 'Save this search' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Save this search' })
    const name = within(dialog).getByLabelText('Name')
    expect(name).toHaveAccessibleDescription(
      'Only you see your saved searches. Each time you open one, it shows what you may read then.',
    )

    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(name).toHaveAccessibleDescription(expect.stringContaining('Enter a name.'))
    expect(bodies).toHaveLength(0)

    await user.type(name, 'Lease')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => {
      expect(name).toHaveAccessibleDescription(
        expect.stringContaining('You have as many saved searches as you can keep.'),
      )
    })
    expect(bodies[0]).toEqual({
      name: 'Lease',
      criteria: {
        q: 'rent',
        types: [],
        statuses: ['IN_REVIEW'],
        units: [],
        from: null,
        to: null,
        amountMin: null,
        amountMax: null,
        sources: ['NOTES'],
        sort: 'NEWEST',
      },
    })

    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Search saved.')).toBeInTheDocument()
    expect(dialog).not.toBeInTheDocument()
  })
})

describe('saved searches', () => {
  it('lists them; opens one with its criteria; renames with the version; deletes after confirming', async () => {
    const user = userEvent.setup()
    let current = [savedSearch]
    const puts: { ifMatch: string | null; body: SavedSearchRequest }[] = []
    const deletes: (string | null)[] = []
    server.use(
      http.get(`${API_ORIGIN}/api/v1/saved-searches`, () => HttpResponse.json<SavedSearchView[]>(current)),
      http.put(`${API_ORIGIN}/api/v1/saved-searches/s1`, async ({ request }) => {
        const body = (await request.json()) as SavedSearchRequest
        puts.push({ ifMatch: request.headers.get('If-Match'), body })
        current = [{ ...savedSearch, name: body.name, version: 4 }]
        return HttpResponse.json<SavedSearchView>(current[0])
      }),
      http.delete(`${API_ORIGIN}/api/v1/saved-searches/s1`, ({ request }) => {
        deletes.push(request.headers.get('If-Match'))
        current = []
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const { container } = renderRoute('/search/saved')

    const link = await screen.findByRole('link', { name: 'Credit memos this quarter' }, { timeout: 5000 })
    expect(link.getAttribute('href')).toBe(`/search?status=${json(['IN_REVIEW'])}#q=credit`)
    expect(screen.getByText('“credit” · 1 filter')).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('button', { name: 'Rename Credit memos this quarter' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rename saved search' })
    const name = within(dialog).getByLabelText('Name')
    expect(name).toHaveValue('Credit memos this quarter')
    await user.clear(name)
    await user.type(name, 'Credit in review')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('link', { name: 'Credit in review' })).toBeInTheDocument()
    expect(puts).toEqual([{ ifMatch: '"3"', body: { name: 'Credit in review', criteria: savedSearch.criteria } }])

    await user.click(screen.getByRole('button', { name: 'Delete Credit in review' }))
    const confirm = await screen.findByRole('alertdialog', { name: 'Delete “Credit in review”?' })
    await user.click(within(confirm).getByRole('button', { name: 'Delete' }))
    expect(
      await screen.findByText('No saved searches yet. Run a search and choose Save this search.'),
    ).toBeInTheDocument()
    expect(deletes).toEqual(['"4"'])
  })

  it('opening a saved search runs it with its criteria', async () => {
    const user = userEvent.setup()
    const bodies = searchHandler()
    const { router } = renderRoute('/search/saved')
    await user.click(await screen.findByRole('link', { name: 'Credit memos this quarter' }, { timeout: 5000 }))
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ status: ['IN_REVIEW'] })
    })
    expect(qOf(router.state.location.hash)).toBe('credit')
    await waitFor(() => {
      expect(bodies.at(-1)).toEqual(sent('credit', { status: ['IN_REVIEW'] }))
    })
    // Only the /search POST and others; no URL carries the words.
    expect(requested.filter((url) => url.includes('q=credit'))).toEqual([])
  })
})

describe('search box', () => {
  it('"/" focuses it; it lists saved searches, suggests memos while typing, and Enter searches', async () => {
    const user = userEvent.setup()
    const suggested: string[] = []
    server.use(
      http.post(`${API_ORIGIN}/api/v1/search/suggest`, async ({ request }) => {
        suggested.push(((await request.json()) as SuggestRequest).q)
        return HttpResponse.json<SuggestionView[]>([
          { memoId: 'm1', number: 'M-2026-00042', subject: 'Office lease extension' },
        ])
      }),
    )
    const bodies = searchHandler()
    const { router } = renderRoute('/search/saved')
    await screen.findByRole('heading', { level: 1, name: 'Saved searches' }, { timeout: 5000 })

    const box = screen.getByRole('combobox', { name: 'Search memos' })
    await user.keyboard('/')
    expect(box).toHaveFocus()
    expect(box).toHaveValue('')
    const list = await screen.findByRole('listbox', { name: 'Search memos' })
    expect(await within(list).findByRole('option', { name: 'Credit memos this quarter' })).toBeInTheDocument()
    expect(within(list).getByRole('option', { name: 'All saved searches' })).toBeInTheDocument()
    expect(within(list).getByRole('group', { name: 'Saved searches' })).toBeInTheDocument()

    await user.type(box, 're')
    expect(suggested).toEqual([])
    await user.type(box, 'nt sch')
    expect(await within(list).findByRole('option', { name: /Office lease extension/ })).toBeInTheDocument()
    expect(suggested).toEqual(['rent sch'])
    expect(box).toHaveAttribute('aria-expanded', 'true')

    await user.keyboard('{ArrowDown}{ArrowDown}')
    const memo = within(list).getByRole('option', { name: /Office lease extension/ })
    expect(box).toHaveAttribute('aria-activedescendant', memo.id)
    expect(memo).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{Escape}')
    expect(box).toHaveAttribute('aria-expanded', 'false')

    await user.keyboard('{Enter}')
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/search')
    })
    expect(router.state.location.search).toEqual({})
    expect(qOf(router.state.location.hash)).toBe('rent sch')
    await waitFor(() => {
      expect(bodies.at(-1)).toEqual(sent('rent sch'))
    })
    expectNoWordsInUrls('rent')

    // The words just searched are offered again, from memory only.
    await user.clear(box)
    expect(await within(list).findByRole('option', { name: 'rent sch' })).toBeInTheDocument()
    expect(sessionStorage.length).toBe(0)
    expect(Object.keys(localStorage).filter((k) => !['memo.theme', 'memo.locale'].includes(k))).toEqual([])
  })

  it('is not there for visitors who are not signed in', async () => {
    server.use(sessionHandler())
    renderRoute('/status')
    await screen.findByRole('banner')
    expect(screen.queryByRole('combobox', { name: 'Search memos' })).not.toBeInTheDocument()
  })
})
