import { screen, waitFor, within } from '@testing-library/react'
import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from '@tanstack/react-router'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  DecisionRowView,
  DecisionSearchRequest,
  DecisionSearchView,
  MyForumResponse,
  RegisterScopeView,
  ResolutionResponse,
  ResolutionCheckResponse,
} from '@/api/generated/types.gen'
import { navigationGroups } from '@/app/navigation'
import { viewerOf } from '@/features/auth/session'
import { partsForPath } from '@/i18n/catalogue-parts'
import {
  accessHandler,
  API_ORIGIN,
  csrfHandler,
  myForumsHandler,
  server,
  sessionHandler,
  signedInSession,
} from '@/test/msw'
import { expectNoA11yViolations, renderRoute, renderWithProviders } from '@/test/render'
import { InTheRegisterLink } from './links'
import { parseRegisterSearch, searchBody } from './register-params'

const API = `${API_ORIGIN}/api/v1`
const BRC = '01900000-0000-7000-8000-00000000b0c1'
const MCC = '01900000-0000-7000-8000-00000000b0c2'

const scope: RegisterScopeView = {
  committees: [
    {
      id: BRC,
      name: 'Board Risk Committee',
      shortName: 'BRC',
      typeId: 't1',
      officer: true,
      member: false,
      dissolved: false,
    },
    {
      id: MCC,
      name: 'Management Credit Committee',
      shortName: 'MCC',
      typeId: 't2',
      officer: false,
      member: true,
      dissolved: false,
    },
  ],
  committeeTypes: [
    { id: 't1', name: 'Board committee' },
    { id: 't2', name: 'Management committee' },
  ],
  outcomes: [
    'APPROVED',
    'APPROVED_WITH_CONDITIONS',
    'REJECTED',
    'DEFERRED',
    'REFERRED',
    'RETURNED',
    'WITHDRAWN',
    'NOTED',
  ],
  states: ['RECORDED', 'NUMBERED', 'RELEASED', 'FINAL', 'VOID'],
  flags: ['WITH_CONDITIONS', 'WITH_DISSENT', 'TO_BE_RATIFIED', 'CORRECTED', 'VALIDITY_PASSED'],
  memoTypes: [{ id: 'mt1', name: 'Credit proposal' }],
  canExport: true,
  canCheckNumbers: true,
}

const full: DecisionRowView = {
  level: 'FULL',
  decisionId: 'd13',
  forumId: BRC,
  forumName: 'Board Risk Committee',
  meetingId: 'm3',
  meetingLabel: 'BRC 3/2026',
  meetingDate: '2026-12-12',
  itemId: 'i4',
  itemNumber: '4',
  state: 'FINAL',
  finalAt: '2027-01-09T10:00:00Z',
  resolutionId: 'r13',
  resolutionNumber: 'BRC/2026/013',
  resolutionState: 'IN_FORCE',
  title: 'Liquidity contingency plan, annual review',
  outcomeKind: 'APPROVED_WITH_CONDITIONS',
  outcomeLabel: 'Approved with conditions',
  resolution: true,
  text: 'It was RESOLVED that the plan be approved.',
  conditions: ['Board sign-off by March.', 'Quarterly test.'],
  hasDissent: true,
  corrected: false,
  ratificationRequired: false,
  validUntil: '2027-03-10',
  decidedAt: '2026-12-12T11:00:00Z',
  memoId: 'memo31',
  memoNumber: 'POL-2026-00031',
  memoTypeId: 'mt1',
  memoTypeName: 'Policy approval',
}

const line: DecisionRowView = {
  level: 'LINE',
  reason: 'RECUSED',
  decisionId: 'd14',
  forumId: BRC,
  forumName: 'Board Risk Committee',
  meetingId: 'm3',
  meetingLabel: 'BRC 3/2026',
  meetingDate: '2026-12-12',
  itemId: 'i5',
  itemNumber: '5',
  state: 'NUMBERED',
  resolutionId: 'r14',
  resolutionNumber: 'BRC/2026/014',
  resolutionState: 'IN_FORCE',
}

const noted: DecisionRowView = {
  level: 'FULL',
  decisionId: 'd20',
  forumId: MCC,
  forumName: 'Management Credit Committee',
  meetingId: 'm7',
  meetingLabel: 'MCC 7/2026',
  meetingDate: '2026-11-02',
  itemId: 'i9',
  itemNumber: '2',
  state: 'RECORDED',
  title: 'Quarterly credit portfolio report',
  outcomeKind: 'NOTED',
  outcomeLabel: 'Noted',
  resolution: false,
}

const restrictedOther: DecisionRowView = {
  ...line,
  decisionId: 'd15',
  reason: 'RESTRICTED',
  itemNumber: '6',
  resolutionNumber: null,
}

const page = (rows: DecisionRowView[], over: Partial<DecisionSearchView> = {}): DecisionSearchView => ({
  rows,
  total: rows.length,
  capped: false,
  facets: {
    forums: [
      { forumId: BRC, name: 'Board Risk Committee', count: 2 },
      { forumId: MCC, name: 'Management Credit Committee', count: 1 },
    ],
    outcomes: [
      { outcome: 'APPROVED_WITH_CONDITIONS', count: 1 },
      { outcome: 'NOTED', count: 1 },
    ],
    years: [
      { year: 2026, count: 3 },
      { year: 2025, count: 0 },
    ],
  },
  nextCursor: null,
  ...over,
})

type Answer = DecisionSearchView | { status: number; body: unknown }

/** `POST /decision-register/search`, keeping each body; [answer] decides per body (the first page by default). */
function searchHandler(answer: (body: DecisionSearchRequest) => Answer = () => page([full, line, noted])) {
  const bodies: DecisionSearchRequest[] = []
  server.use(
    http.post(`${API}/decision-register/search`, async ({ request }) => {
      const body = (await request.json()) as DecisionSearchRequest
      bodies.push(body)
      const a = answer(body)
      if ('status' in a) return HttpResponse.json(a.body as object, { status: a.status })
      return HttpResponse.json<DecisionSearchView>(a)
    }),
  )
  return bodies
}

function scopeHandler(answer: RegisterScopeView | number = scope) {
  server.use(
    http.get(`${API}/decision-register/scope`, () =>
      typeof answer === 'number'
        ? HttpResponse.json({ title: 'Forbidden', status: answer }, { status: answer })
        : HttpResponse.json<RegisterScopeView>(answer),
    ),
  )
}

const problem = (status: number, errors: { field: string; code: string }[] = []) => ({
  status,
  body: {
    type: status === 429 ? 'urn:memo:problem:rate-limited' : 'urn:memo:problem:validation',
    title: 'Refused',
    status,
    errors: errors.map((e) => ({ ...e, message: 'refused' })),
  },
})

const seat: MyForumResponse = {
  forumId: BRC,
  code: 'BRC',
  name: 'Board Risk Committee',
  role: 'SECRETARY',
  state: 'IN_OFFICE',
  status: 'ACTIVE',
  startsOn: '2025-01-01',
  termNumber: 1,
  voting: true,
  canOpen: true,
  attributes: [],
}

const results = () => screen.findByRole('list', { name: 'Decisions' }, { timeout: 5000 })
const count = (text: string | RegExp) => screen.findByRole('heading', { level: 2, name: text }, { timeout: 5000 })

const created = vi.fn(() => 'blob:register')

beforeEach(() => {
  server.use(sessionHandler(signedInSession()), csrfHandler())
  Object.defineProperty(URL, 'createObjectURL', { value: created, configurable: true })
  Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true })
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  created.mockClear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('the decision register (Phase 4 E08 T6, flow AS)', () => {
  it('lists decisions newest first, in full or as restricted lines that say why, with links to the meeting and memo', async () => {
    scopeHandler()
    server.use(myForumsHandler([seat]))
    const bodies = searchHandler()
    const { container } = renderRoute('/decisions')

    expect(partsForPath('/decisions')).toEqual(['decisionRegister'])
    expect(await screen.findByRole('heading', { level: 1, name: 'Decisions' }, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByText('Search decisions and resolutions of the committees you can see.')).toBeInTheDocument()
    // In the menu for a seat in office.
    expect(
      (await screen.findAllByRole('link', { name: 'Decisions' })).some((l) => l.getAttribute('href') === '/decisions'),
    ).toBe(true)
    await count('Showing 3 decisions')
    // No words: newest first, every committee, page of 50.
    expect(bodies[0]).toEqual({ limit: 50 })

    const list = await results()
    const [first, second, third] = within(list).getAllByRole('article')
    // In full: title (to the meeting where it was decided), number and its state, outcome, exceptions, state, memo.
    const open = within(first!).getByRole('link', { name: 'Liquidity contingency plan, annual review' })
    // A resolution opens its own page; the meeting and the memo keep their links.
    expect(open).toHaveAttribute('href', '/resolutions/r13')
    expect(within(first!).getByRole('link', { name: 'Meeting BRC 3/2026' })).toHaveAttribute('href', '/meetings/m3')
    expect(first).toHaveTextContent('Resolution BRC/2026/013In force')
    expect(first).toHaveTextContent('Approved with conditions')
    expect(first).toHaveTextContent('2 conditions · with dissent · valid until Mar 10, 2027')
    expect(first).toHaveTextContent(
      'Dec 12, 2026 · BRC 3/2026, item 4 · Board Risk Committee · Minutes approved Jan 9, 2027',
    )
    expect(within(first!).getByRole('link', { name: 'Memo POL-2026-00031' })).toHaveAttribute('href', '/memos/memo31')

    // A restricted line: number, meeting, item and why, in words; never a title, an outcome or a link.
    expect(second).toHaveAccessibleName('Restricted item')
    expect(second).toHaveTextContent('Resolution BRC/2026/014In force')
    expect(second).toHaveTextContent('You declared an interest in this item.')
    expect(second).toHaveTextContent('BRC 3/2026, item 5')
    expect(within(second!).queryAllByRole('link')).toHaveLength(0)

    // States in words; a decision without a number says so.
    expect(third).toHaveTextContent('No resolution number')
    expect(third).toHaveTextContent('Recorded at the meeting')
    expect(third).toHaveTextContent('Noted')
    // A decision without a number opens the meeting where it was taken, with no second link to the same place.
    expect(within(third!).getByRole('link', { name: 'Quarterly credit portfolio report' })).toHaveAttribute(
      'href',
      '/meetings/m7',
    )
    expect(within(third!).queryByRole('link', { name: /^Meeting / })).not.toBeInTheDocument()

    // Facets lead to the register narrowed to them; outcome counts are of rows seen in full.
    const facets = screen.getByText('By committee').closest('dl') as HTMLElement
    expect(within(facets).getByRole('link', { name: 'Management Credit Committee (1)' })).toHaveAttribute(
      'href',
      `/decisions?committee=${MCC}`,
    )
    expect(within(facets).getByRole('link', { name: 'Noted (1)' })).toHaveAttribute('href', '/decisions?outcome=NOTED')
    expect(within(facets).getByRole('link', { name: '2025 (0)' })).toHaveAttribute(
      'href',
      '/decisions?from=2025-01-01&to=2025-12-31',
    )
    expect(screen.getByText('Outcome counts include only decisions you can see in full.')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('applies the filters with Search, shows each as a chip that removes it, and resets them all', async () => {
    const user = userEvent.setup()
    scopeHandler()
    const bodies = searchHandler((body) => (body.outcomes ? page([full]) : page([full, line, noted])))
    const { router } = renderRoute('/decisions')
    await count('Showing 3 decisions')

    await user.selectOptions(screen.getByRole('combobox', { name: 'Committee' }), 'Board Risk Committee')
    await user.type(screen.getByLabelText('From'), '2026-01-01')
    await user.type(screen.getByLabelText('To'), '2026-12-31')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Outcome' }), 'Approved with conditions')
    await user.click(screen.getByRole('checkbox', { name: 'Resolutions only' }))
    await user.click(screen.getByRole('button', { name: 'More filters' }))
    expect(screen.getByRole('button', { name: 'More filters' })).toHaveAttribute('aria-expanded', 'true')
    await user.click(screen.getByRole('checkbox', { name: 'With dissent' }))
    await user.selectOptions(screen.getByRole('combobox', { name: 'Memo type' }), 'Credit proposal')
    await user.selectOptions(screen.getByRole('combobox', { name: 'State' }), 'Minutes approved')
    // Nothing is searched until Search.
    expect(bodies).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Search' }))

    const heading = await count('Showing 1 decision')
    expect(bodies.at(-1)).toEqual({
      forumIds: [BRC],
      from: '2026-01-01',
      to: '2026-12-31',
      outcomes: ['APPROVED_WITH_CONDITIONS'],
      resolutionsOnly: true,
      states: ['FINAL'],
      memoTypeIds: ['mt1'],
      flags: ['WITH_DISSENT'],
      limit: 50,
    })
    // Focus moves to the count, which says what was found (and is announced).
    await waitFor(() => {
      expect(heading).toHaveFocus()
    })
    expect(heading).toHaveAttribute('aria-live', 'polite')
    expect(router.state.location.search).toMatchObject({ committee: BRC, outcome: 'APPROVED_WITH_CONDITIONS' })

    const chips = screen.getByRole('list', { name: 'Active filters' })
    expect(
      within(chips)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual([
      'Committee: Board Risk Committee',
      'From: Jan 1, 2026',
      'To: Dec 31, 2026',
      'Outcome: Approved with conditions',
      'Resolutions only',
      'With dissent',
      'Memo type: Credit proposal',
      'State: Minutes approved',
    ])
    expect(
      screen.getByText(
        'Restricted items are not matched by words, an outcome, a memo type or an exception. A resolution number still finds them.',
      ),
    ).toBeInTheDocument()
    // Number order is offered once one committee is chosen.
    expect(
      within(screen.getByRole('combobox', { name: 'Order' })).getByRole('option', { name: 'Number order' }),
    ).toBeInTheDocument()

    await user.click(within(chips).getByRole('button', { name: 'Remove filter Outcome: Approved with conditions' }))
    await count('Showing 3 decisions')
    expect(bodies.at(-1)?.outcomes).toBeUndefined()
    expect(bodies.at(-1)?.forumIds).toEqual([BRC])

    // Reset goes back to the whole register (the first search, still in the cache).
    await user.click(screen.getByRole('button', { name: 'Reset' }))
    await waitFor(() => {
      expect(router.state.location.search).toEqual({})
    })
    await count('Showing 3 decisions')
    expect(screen.queryByRole('list', { name: 'Active filters' })).not.toBeInTheDocument()
  })

  it('says an end date before the start at the field, without searching', async () => {
    const user = userEvent.setup()
    scopeHandler()
    const bodies = searchHandler()
    renderRoute('/decisions')
    await count('Showing 3 decisions')
    await user.type(screen.getByLabelText('From'), '2026-06-01')
    await user.type(screen.getByLabelText('To'), '2026-01-01')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(screen.getByLabelText('To')).toHaveAccessibleDescription('The end date must be on or after the start date.')
    expect(bodies).toHaveLength(1)
  })

  it('finds a resolution by its number, keeping the words in the address fragment only, and checks the numbers', async () => {
    const user = userEvent.setup()
    scopeHandler()
    const bodies = searchHandler((body) => (body.text ? page([line]) : page([full, line, noted])))
    const checks: string[] = []
    server.use(
      http.get(`${API}/forums/:forumId/resolutions/check`, ({ params }) => {
        checks.push(String(params.forumId))
        return HttpResponse.json<ResolutionCheckResponse>({
          forumId: BRC,
          period: '2026',
          firstNumber: 1,
          lastNumber: 16,
          inForce: 15,
          void: 1,
          missing: [],
          missingCount: 0,
          duplicates: [],
          outOfRange: [],
          complete: true,
        })
      }),
    )
    const { router } = renderRoute(`/decisions?committee=${BRC}`)
    await count('Showing 3 decisions')

    await user.type(
      screen.getByRole('searchbox', { name: 'Words, a resolution number or a memo number' }),
      'BRC/2026/014',
    )
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await count('Showing 1 decision')
    expect(bodies.at(-1)).toEqual({ text: 'BRC/2026/014', forumIds: [BRC], limit: 50 })
    // The words are in the fragment, never in the query string.
    expect(router.state.location.hash).toBe('q=BRC%2F2026%2F014')
    expect(router.state.location.search).toEqual({ committee: BRC })
    // The restricted line is found by its number.
    expect(within(await results()).getByRole('article')).toHaveTextContent('Resolution BRC/2026/014')
    // With words, the order may be the best match.
    expect(screen.getByRole('combobox', { name: 'Order' })).toHaveValue('relevance')

    await user.click(screen.getByRole('button', { name: 'Check the numbers' }))
    expect(
      await screen.findByText('2026: resolutions 1 to 16 are all accounted for (15 in force, 1 void).'),
    ).toBeInTheDocument()
    expect(checks).toEqual([BRC])
  })

  it('pages by cursor and moves focus to the first new decision', async () => {
    const user = userEvent.setup()
    scopeHandler()
    const bodies = searchHandler((body) =>
      body.cursor === 'c1'
        ? page([restrictedOther], { total: 4 })
        : page([full, line, noted], { total: 4, nextCursor: 'c1' }),
    )
    renderRoute('/decisions')
    await count('Showing 4 decisions')
    await user.click(screen.getByRole('button', { name: 'Show more decisions' }))
    await waitFor(() => {
      expect(within(screen.getByRole('list', { name: 'Decisions' })).getAllByRole('article')).toHaveLength(4)
    })
    expect(bodies.at(-1)).toEqual({ limit: 50, cursor: 'c1' })
    const added = within(screen.getByRole('list', { name: 'Decisions' })).getAllByRole('article')[3]!
    expect(added).toHaveTextContent('This item is restricted to named members.')
    await waitFor(() => {
      expect(added).toHaveFocus()
    })
    expect(screen.queryByRole('button', { name: 'Show more decisions' })).not.toBeInTheDocument()
  })

  it('says when nothing matches, and when nothing has been recorded yet', async () => {
    scopeHandler()
    searchHandler(() => page([], { facets: { forums: [], outcomes: [], years: [] } }))
    const filtered = renderRoute('/decisions?outcome=DEFERRED')
    expect(await screen.findByText('No decisions match.', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByText('Try a wider date range or fewer filters.')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Reset' }).length).toBeGreaterThan(0)
    filtered.unmount()

    renderRoute('/decisions')
    expect(
      await screen.findByText('No decision has been recorded in your committees yet.', {}, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reset' })).not.toBeInTheDocument()
  })

  it('explains the register to someone without a scope', async () => {
    scopeHandler(403)
    searchHandler(() => problem(403))
    renderRoute('/decisions')
    expect(
      await screen.findByText('You are not a member or officer of any committee.', {}, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.getByText('The register is for committee members, officers and oversight.')).toBeInTheDocument()
    expect(screen.queryByRole('search', { name: 'Decisions' })).not.toBeInTheDocument()
  })

  it('says each refusal in words, where it belongs', async () => {
    const user = userEvent.setup()
    scopeHandler()
    let next: Answer = problem(422, [{ field: 'text', code: 'TOO_MANY_TERMS' }])
    searchHandler((body) => (body.text || body.forumIds ? next : page([full])))
    renderRoute('/decisions')
    await count('Showing 1 decision')
    const words = screen.getByRole('searchbox', { name: 'Words, a resolution number or a memo number' })

    await user.type(words, 'one two three four five six seven eight nine ten eleven twelve thirteen')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => {
      expect(
        screen.getByRole('searchbox', { name: 'Words, a resolution number or a memo number' }),
      ).toHaveAccessibleDescription(/Use at most 12 words\./)
    })

    next = problem(429)
    await user.type(screen.getByRole('searchbox', { name: /Words/ }), ' again')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(
      await screen.findByText('You have searched many times in the last minute. Wait a moment, then try again.'),
    ).toBeInTheDocument()

    next = problem(422, [{ field: 'forumIds', code: 'FORUM_OUT_OF_SCOPE' }])
    await user.clear(screen.getByRole('searchbox', { name: /Words/ }))
    await user.selectOptions(screen.getByRole('combobox', { name: 'Committee' }), 'Management Credit Committee')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This committee is not in your register. Choose one from the list, or reset the filters.',
    )
  })

  it('keeps a well-formed address and drops the rest, and never takes words from the query string', () => {
    expect(
      parseRegisterSearch({
        committee: BRC,
        outcome: 'NOPE',
        flag: ['WITH_DISSENT', 'BAD', 'CORRECTED'],
        from: '2026-02-30',
        to: '2026-12-31',
        sort: 'number',
        q: 'secret',
        resolutions: 'true',
      }),
    ).toEqual({
      committee: BRC,
      flag: ['WITH_DISSENT', 'CORRECTED'],
      to: '2026-12-31',
      sort: 'number',
      resolutions: true,
    })
    // Number order needs one committee.
    expect(parseRegisterSearch({ sort: 'number' })).toEqual({})
    expect(searchBody({ sort: 'oldest', type: 't1' }, '  liquidity ')).toEqual({
      text: 'liquidity',
      forumTypeIds: ['t1'],
      sort: 'OLDEST',
      limit: 50,
    })
  })

  it('reads right to left in Arabic, with no axe violations', async () => {
    scopeHandler()
    searchHandler()
    const { container } = renderRoute('/decisions', { locale: 'ar' })
    expect(await screen.findByRole('heading', { level: 1, name: 'القرارات' }, { timeout: 5000 })).toBeInTheDocument()
    await screen.findByRole('heading', { level: 2, name: 'تُعرض 3 قرارات' }, { timeout: 5000 })
    expect(document.documentElement.dir).toBe('rtl')
    expect(screen.getByText('أفصحت عن مصلحة في هذا البند.')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })
})

describe('exporting for auditors (flow AT)', () => {
  function exportHandler(answer: () => Response) {
    const bodies: unknown[] = []
    server.use(
      http.post(`${API}/decision-register/export`, async ({ request }) => {
        bodies.push(await request.json())
        return answer()
      }),
    )
    return bodies
  }

  it('shows the count, the filters in words and the formats, and downloads the spreadsheet', async () => {
    const user = userEvent.setup()
    scopeHandler()
    searchHandler(() => page([full, line]))
    const bodies = exportHandler(
      () =>
        new HttpResponse('xlsx', {
          headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
        }),
    )
    renderRoute(`/decisions?committee=${BRC}&from=2026-01-01&to=2026-12-31&flag=WITH_DISSENT`)
    await count('Showing 2 decisions')

    await user.click(screen.getByRole('button', { name: 'Export…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Export decisions' })
    expect(dialog).toHaveTextContent('2 decisions match your filters:')
    expect(dialog).toHaveTextContent('Board Risk Committee · Jan 1, 2026 – Dec 31, 2026 · With dissent')
    expect(within(dialog).getByRole('radio', { name: 'Spreadsheet (.xlsx)' })).toBeChecked()
    expect(within(dialog).getByRole('radio', { name: 'Spreadsheet (.xlsx)' })).toHaveAccessibleDescription(
      'For sorting and checking, up to 20,000 rows.',
    )
    expect(within(dialog).getByRole('radio', { name: 'PDF' })).toHaveAccessibleDescription(
      'For filing, up to 2,000 rows.',
    )
    expect(dialog).toHaveTextContent('Restricted items show their number only. Each export is recorded.')
    await expectNoA11yViolations(dialog)

    await user.click(within(dialog).getByRole('button', { name: 'Download' }))
    await waitFor(() => {
      expect(created).toHaveBeenCalledTimes(1)
    })
    expect(bodies).toEqual([
      { forumIds: [BRC], from: '2026-01-01', to: '2026-12-31', flags: ['WITH_DISSENT'], format: 'XLSX' },
    ])
    expect(await screen.findByText('The register has been downloaded.')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Export decisions' })).not.toBeInTheDocument()
    })
  })

  it('keeps Download disabled when a PDF cannot hold the rows, and says the server’s refusals in words', async () => {
    const user = userEvent.setup()
    scopeHandler()
    searchHandler(() => page([full], { total: 2412 }))
    let answer = () =>
      HttpResponse.json(
        { type: 'urn:memo:problem:rate-limited', title: 'Too many', status: 429 },
        { status: 429, headers: { 'Retry-After': '600' } },
      )
    exportHandler(() => answer())
    renderRoute('/decisions')
    await count('Showing 2,412 decisions')
    await user.click(screen.getByRole('button', { name: 'Export…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Export decisions' })

    await user.click(within(dialog).getByRole('radio', { name: 'PDF' }))
    expect(dialog).toHaveTextContent(
      'This is 2,412 decisions; a PDF holds up to 2,000. Narrow the dates or choose a spreadsheet.',
    )
    expect(within(dialog).getByRole('button', { name: 'Download' })).toBeDisabled()

    await user.click(within(dialog).getByRole('radio', { name: 'Spreadsheet (.xlsx)' }))
    await user.click(within(dialog).getByRole('button', { name: 'Download' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      /^You have exported 5 times in the last hour\. Please try again at .+\.$/,
    )

    answer = () =>
      HttpResponse.json(
        {
          type: 'urn:memo:problem:validation',
          title: 'Too many rows',
          status: 422,
          errors: [{ field: 'format', code: 'TOO_MANY_ROWS', message: 'narrow' }],
          moreThan: 20000,
          maximum: 20000,
        },
        { status: 422 },
      )
    await user.click(within(dialog).getByRole('button', { name: 'Download' }))
    await waitFor(() => {
      expect(within(dialog).getByRole('alert')).toHaveTextContent(
        'This is more than 20,000 decisions; the spreadsheet holds up to 20,000. Narrow the dates or the filters.',
      )
    })
    expect(created).not.toHaveBeenCalled()
  })

  it('offers no export to a member', async () => {
    scopeHandler({ ...scope, canExport: false, canCheckNumbers: false })
    searchHandler()
    renderRoute(`/decisions?committee=${MCC}`)
    await count('Showing 3 decisions')
    expect(screen.queryByRole('button', { name: 'Export…' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Check the numbers' })).not.toBeInTheDocument()
  })
})

describe('the register in the menu', () => {
  it('is offered to seats in office and to oversight, not to others', () => {
    const ids = (forums: MyForumResponse[], permissions: string[] = []) =>
      navigationGroups(
        viewerOf(
          signedInSession(),
          {
            permissions: permissions.map((key) => ({ key, tenantWide: true, scopeUnitIds: [] })),
            clearance: { rank: 0, name: 'Public' },
          },
          forums,
        ),
      ).flatMap((g) => g.items.map((i) => i.id))
    expect(ids([seat])).toContain('decisions')
    expect(ids([], ['forum.read'])).toContain('decisions')
    expect(ids([{ ...seat, role: 'OBSERVER' }])).not.toContain('decisions')
    expect(ids([])).not.toContain('decisions')
  })
})

describe('In the register (design §1.6)', () => {
  function renderLink() {
    const root = createRootRoute({
      component: () => <InTheRegisterLink forumId={BRC} resolutionNumber="BRC/2026/014" named />,
    })
    const router = createRouter({ routeTree: root, history: createMemoryHistory({ initialEntries: ['/'] }) })
    return renderWithProviders(<RouterProvider router={router as never} />)
  }

  it('opens the register filtered to the committee, with the resolution number as the words', async () => {
    scopeHandler()
    renderLink()
    const link = await screen.findByRole('link', { name: 'Resolution BRC/2026/014 in the register' })
    expect(link).toHaveAttribute('href', `/decisions?committee=${BRC}#q=BRC%2F2026%2F014`)
  })

  it('is not offered to someone whose register does not hold the committee, or who has none', async () => {
    for (const answer of [{ ...scope, committees: scope.committees.filter((c) => c.id !== BRC) }, 403] as const) {
      let asked = false
      server.use(
        http.get(`${API}/decision-register/scope`, () => {
          asked = true
          return typeof answer === 'number'
            ? HttpResponse.json({ title: 'Forbidden', status: answer }, { status: answer })
            : HttpResponse.json<RegisterScopeView>(answer)
        }),
      )
      const view = renderLink()
      await waitFor(() => {
        expect(asked).toBe(true)
      })
      await new Promise((resolve) => setTimeout(resolve, 50))
      expect(screen.queryByRole('link')).not.toBeInTheDocument()
      view.unmount()
    }
  })

  it('stands on the resolution page for a reader of the register', async () => {
    scopeHandler()
    server.use(
      accessHandler([]),
      http.get(`${API}/resolutions/r14`, () =>
        HttpResponse.json<ResolutionResponse>({
          id: 'r14',
          decisionId: 'd14',
          forumId: BRC,
          forumName: 'Board Risk Committee',
          full: true,
          number: 'BRC/2026/014',
          numberedAt: '2026-12-12T08:00:00Z',
          state: 'IN_FORCE',
          meetingId: 'm3',
          meetingLabel: 'BRC 3/2026',
          meetingDate: '2026-12-12',
          itemNumber: '5',
          itemTitle: 'Term loan, Delta Textiles',
          outcome: 'APPROVED',
          outcomeLabel: 'Approved',
          conditions: [],
          mayIssueExtract: false,
          viewerRecused: false,
        }),
      ),
      http.get(`${API}/resolutions/r14/extracts`, () => HttpResponse.json([])),
    )
    const { container } = renderRoute('/resolutions/r14')
    expect(partsForPath('/resolutions/r14')).toEqual(expect.arrayContaining(['resolutions', 'registerLinks']))
    expect(await screen.findByRole('link', { name: 'In the register' }, { timeout: 5000 })).toHaveAttribute(
      'href',
      `/decisions?committee=${BRC}#q=BRC%2F2026%2F014`,
    )
    await expectNoA11yViolations(container)
  })
})
