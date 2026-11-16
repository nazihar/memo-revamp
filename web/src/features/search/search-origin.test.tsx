import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type {
  SavedSearchRequest,
  SavedSearchView,
  SearchParams as SearchBody,
  SearchPageView,
  SearchResultView,
} from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'
import { criteriaOf, filterCount, locationOf, parseSearchParams, searchBody } from './search-params'

const imported: SearchResultView = {
  memoId: 'm1',
  number: 'CR/2016/0108',
  subject: 'Working capital line for Meghna Agro',
  typeId: 't1',
  typeName: 'Credit proposal',
  status: 'APPROVED',
  unitId: 'u1',
  unitName: 'Credit Division',
  updatedAt: '2026-11-16T10:00:00Z',
  snippet: [],
  foundIn: { kind: 'DETAILS' },
  origin: 'IMPORTED',
}
const native: SearchResultView = {
  ...imported,
  memoId: 'm2',
  number: 'CR/2026/0001',
  subject: 'Renewal',
  origin: 'NATIVE',
}
const page: SearchPageView = {
  results: [imported, native],
  count: 2,
  capped: false,
  facets: { status: [], type: [], unit: [] },
  cursor: null,
}

function handlers() {
  const bodies: SearchBody[] = []
  server.use(
    sessionHandler(signedInSession()),
    accessHandler(),
    csrfHandler(),
    http.post(`${API_ORIGIN}/api/v1/search`, async ({ request }) => {
      bodies.push((await request.json()) as SearchBody)
      return HttpResponse.json(page)
    }),
    http.get(`${API_ORIGIN}/api/v1/saved-searches`, () => HttpResponse.json<SavedSearchView[]>([])),
  )
  return bodies
}

describe('the Origin filter and the Imported badge (Phase 3 E06 T9, flow O2)', () => {
  it('keeps the origin in the address, the search body and a saved search, and counts it as a filter', () => {
    expect(parseSearchParams({ origin: 'imported' })).toEqual({ origin: 'imported' })
    expect(parseSearchParams({ origin: 'anything' })).toEqual({})
    expect(searchBody({ origin: 'imported' }, 'credit')).toEqual({
      q: 'credit',
      origin: ['IMPORTED'],
      sort: 'relevance',
      limit: 20,
    })
    expect(searchBody({ origin: 'native' }, 'x').origin).toEqual(['NATIVE'])
    expect(searchBody({}, 'x')).not.toHaveProperty('origin')
    expect(filterCount({ origin: 'native' })).toBe(1)
    expect(criteriaOf({ origin: 'imported' }, 'credit').origins).toEqual(['IMPORTED'])
    expect(criteriaOf({}, 'credit').origins).toEqual([])
    expect(locationOf({ q: 'credit', origins: ['IMPORTED'] }).search).toEqual({ origin: 'imported' })
    expect(locationOf({ q: 'credit', origins: ['IMPORTED', 'NATIVE'] }).search).toEqual({})
  })

  it('shows "Imported" after an imported memo’s number, and filters by origin from the panel', async () => {
    const bodies = handlers()
    const user = userEvent.setup()
    const { container, router } = renderRoute('/search#q=credit')

    const list = await screen.findByRole('list', { name: 'Results' }, { timeout: 5000 })
    const items = within(list).getAllByRole('listitem')
    expect(within(items[0]!).getByText('Imported')).toBeInTheDocument()
    expect(within(items[1]!).queryByText('Imported')).not.toBeInTheDocument()
    await expectNoA11yViolations(container)

    const filters = screen.getByRole('complementary', { name: 'Filters' })
    const origin = within(filters).getByRole('group', { name: 'Origin' })
    expect(within(origin).getByRole('radio', { name: 'All memos' })).toBeChecked()
    await user.click(within(origin).getByRole('radio', { name: 'Imported' }))
    await user.click(within(filters).getByRole('button', { name: 'Apply filters' }))
    await vi.waitFor(() => {
      expect(bodies.at(-1)?.origin).toEqual(['IMPORTED'])
    })
    expect(router.state.location.search).toMatchObject({ origin: 'imported' })
    const chip = screen.getByRole('button', { name: /Origin: Imported/ })
    await user.click(chip)
    await vi.waitFor(() => {
      expect(bodies.at(-1)).not.toHaveProperty('origin')
    })
  }, 20_000)

  it('saves the origin with a search', async () => {
    handlers()
    const saved: SavedSearchRequest[] = []
    server.use(
      http.post(`${API_ORIGIN}/api/v1/saved-searches`, async ({ request }) => {
        saved.push((await request.json()) as SavedSearchRequest)
        return HttpResponse.json({ id: 's1' }, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderRoute('/search?origin=imported#q=credit')
    await user.click(await screen.findByRole('button', { name: 'Save this search' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'Imported credit memos')
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))
    await vi.waitFor(() => {
      expect(saved[0]?.criteria.origins).toEqual(['IMPORTED'])
    })
  }, 20_000)
})
