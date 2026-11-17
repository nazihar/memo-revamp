import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import type {
  BulkRequest,
  BulkResponse,
  InboxItem,
  InboxPage,
  PreferenceView,
  TeamPage,
  TeamTask,
  WatchingPage,
  WatchSettings,
} from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const task = (over: Partial<InboxItem> = {}): InboxItem => ({
  taskId: 'k1',
  assignedAt: '2026-11-10T09:00:00Z',
  kind: 'DECIDE',
  memo: { id: 'm1', reference: 'M-2026-00042', subject: 'Office lease extension', restricted: false },
  round: 1,
  shared: false,
  stepName: 'Approve',
  ...over,
})

const tasks: InboxPage = {
  items: [
    task(),
    task({
      taskId: 'k2',
      memo: { id: 'm2', reference: 'M-2026-00031', subject: 'Branch generator replacement', restricted: false },
      shared: true,
    }),
    task({ taskId: 'k3', memo: { id: 'm3', reference: null, subject: null, restricted: true } }),
  ],
  nextCursor: null,
}

const teamTask = (over: Partial<TeamTask> = {}): TeamTask => ({
  taskId: 't1',
  memo: { id: 'm1', reference: 'M-2026-00042', subject: 'Office lease extension', restricted: false },
  holder: { id: 'p1', name: 'Demo Head of Credit' },
  unit: { id: 'u1', name: 'Credit Division' },
  stepName: 'Approve · step 1',
  assignedAt: '2026-11-10T09:00:00Z',
  dueAt: '2099-11-20T16:00:00Z',
  overdue: false,
  ageWorkingDays: 3,
  ...over,
})

const team: TeamPage = {
  // Not in due order: the page shows the overdue task first.
  items: [
    teamTask(),
    teamTask({
      taskId: 't3',
      memo: { id: 'm9', reference: null, subject: null, restricted: true },
      holder: { id: 'p3', name: 'Demo Credit Analyst' },
      stepName: 'Opinion',
      dueAt: null,
      ageWorkingDays: 1,
    }),
    teamTask({
      taskId: 't2',
      memo: { id: 'm2', reference: 'M-2026-00031', subject: 'Branch generator replacement', restricted: false },
      holder: { id: 'p2', name: 'Demo Credit Officer' },
      stepName: 'Approve · step 2',
      dueAt: '2026-11-12T16:00:00Z',
      overdue: true,
      ageWorkingDays: 6,
    }),
  ],
  nextCursor: null,
  units: [
    { id: 'u1', name: 'Credit Division' },
    { id: 'u2', name: 'Retail Banking' },
  ],
}

/** Every request's URL, to see what the page asked for. */
function recordUrls(path: string) {
  const urls: URL[] = []
  server.events.on('request:start', ({ request }) => {
    const url = new URL(request.url)
    if (url.pathname === path) urls.push(url)
  })
  return urls
}

/** `POST /inbox/bulk`, answering with [respond] and keeping each body. */
function bulkHandler(respond: (body: BulkRequest) => BulkResponse | Response) {
  const bodies: BulkRequest[] = []
  server.use(
    http.post(`${API_ORIGIN}/api/v1/inbox/bulk`, async ({ request }) => {
      const body = (await request.json()) as BulkRequest
      bodies.push(body)
      const answer = respond(body)
      return answer instanceof Response ? answer : HttpResponse.json<BulkResponse>(answer)
    }),
  )
  return bodies
}

const teamHandler = (page: TeamPage = team) =>
  http.get(`${API_ORIGIN}/api/v1/inbox/team`, () => HttpResponse.json<TeamPage>(page))

beforeEach(() => {
  server.events.removeAllListeners()
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    http.get(`${API_ORIGIN}/api/v1/inbox/tasks`, () => HttpResponse.json<InboxPage>(tasks)),
  )
})

describe('my tasks', () => {
  it('claims in bulk with a result per item, and offers no decision', async () => {
    const user = userEvent.setup()
    const bodies = bulkHandler((body) => ({
      results: body.ids.map((id) => ({ id, outcome: id === 'k2' ? 'CHANGED' : 'OK' })),
    }))
    const { container } = renderRoute('/inbox')

    await screen.findByRole('link', { name: /Office lease extension/ }, { timeout: 5000 })
    expect(screen.queryByRole('toolbar', { name: 'Actions on the selected items' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: /^Select .?M-2026-00042.? · Office lease extension$/ }))
    await user.click(screen.getByRole('checkbox', { name: /^Select .?M-2026-00031.? · Branch generator replacement$/ }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Restricted memo' }))

    const bar = screen.getByRole('toolbar', { name: 'Actions on the selected items' })
    expect(within(bar).getByText('3 selected')).toBeInTheDocument()
    expect(
      within(bar)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Claim', 'Release', 'Watch', 'Clear selection'])
    expect(within(bar).queryByRole('button', { name: /Approve|Return|Reject/ })).not.toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(within(bar).getByRole('button', { name: 'Claim' }))
    const status = await screen.findByText('2 claimed · 1 not changed: it was decided or moved meanwhile')
    expect(bodies).toEqual([{ action: 'CLAIM', ids: ['k1', 'k2', 'k3'] }])
    const notDone = within(status.closest('[role="status"]')!).getByRole('list', { name: 'Not done' })
    expect(
      within(notDone)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual([
      expect.stringMatching(
        /^.?M-2026-00031.? · Branch generator replacement: Not changed: it was decided or moved meanwhile$/,
      ),
    ])
    // The selection is cleared after the action.
    expect(screen.queryByRole('toolbar', { name: 'Actions on the selected items' })).not.toBeInTheDocument()
  })

  it('watches the memos of the selected tasks, by memo', async () => {
    const user = userEvent.setup()
    const bodies = bulkHandler((body) => ({ results: body.ids.map((id) => ({ id, outcome: 'OK' })) }))
    renderRoute('/inbox')

    await user.click(
      await screen.findByRole(
        'checkbox',
        { name: /^Select .?M-2026-00042.? · Office lease extension$/ },
        { timeout: 5000 },
      ),
    )
    await user.click(screen.getByRole('checkbox', { name: 'Select all shown' }))
    await user.click(screen.getByRole('button', { name: 'Watch' }))
    expect(await screen.findByText('3 followed')).toBeInTheDocument()
    expect(bodies).toEqual([{ action: 'WATCH', ids: ['m1', 'm2', 'm3'] }])
  })

  it('keeps the due order in the address and asks the server for it', async () => {
    const user = userEvent.setup()
    const urls = recordUrls('/api/v1/inbox/tasks')
    const { router } = renderRoute('/inbox')
    await screen.findByRole('link', { name: /Office lease extension/ }, { timeout: 5000 })
    expect(urls.at(-1)?.searchParams.get('sort')).toBeNull()

    await user.selectOptions(screen.getByLabelText('Sort'), 'Due')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ tab: 'tasks', sort: 'due' })
    })
    await waitFor(() => {
      expect(urls.at(-1)?.searchParams.get('sort')).toBe('due')
    })

    await user.selectOptions(screen.getByLabelText('Sort'), 'Assigned')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ tab: 'tasks' })
    })
  })

  it('opens with the due order from a link', async () => {
    const urls = recordUrls('/api/v1/inbox/tasks')
    renderRoute('/inbox?sort=due')
    await screen.findByRole('link', { name: /Office lease extension/ }, { timeout: 5000 })
    expect(screen.getByLabelText('Sort')).toHaveValue('due')
    expect(urls.map((u) => u.searchParams.get('sort'))).toEqual(['due'])
  })
})

describe('watching', () => {
  const watching: WatchingPage = {
    items: [
      {
        memoId: 'm1',
        reference: 'M-2026-00042',
        subject: 'Office lease extension',
        reason: 'INITIATED',
        since: '2026-11-12T08:00:00Z',
      },
      {
        memoId: 'm2',
        reference: 'M-2026-00031',
        subject: 'Branch generator replacement',
        reason: 'MANUAL',
        since: '2026-11-09T08:00:00Z',
      },
    ],
    nextCursor: null,
  }

  it('lists followed memos with why, and unfollows in bulk', async () => {
    const user = userEvent.setup()
    server.use(http.get(`${API_ORIGIN}/api/v1/inbox/watching`, () => HttpResponse.json<WatchingPage>(watching)))
    const bodies = bulkHandler((body) => ({ results: body.ids.map((id) => ({ id, outcome: 'OK' })) }))
    const { container } = renderRoute('/inbox?tab=watching')

    const link = await screen.findByRole('link', { name: /Office lease extension/ }, { timeout: 5000 })
    expect(link).toHaveAttribute('href', '/memos/m1')
    expect(screen.getByRole('tab', { name: 'Watching' })).toHaveAttribute('aria-selected', 'true')
    expect(within(link).getByText('You started it')).toBeInTheDocument()
    expect(screen.getByText('You follow this')).toBeInTheDocument()
    expect(within(link).getByText(/^Following since /)).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('checkbox', { name: /^Select .?M-2026-00031.? · Branch generator replacement$/ }))
    const bar = screen.getByRole('toolbar', { name: 'Actions on the selected items' })
    expect(
      within(bar)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Unfollow', 'Clear selection'])
    await user.click(within(bar).getByRole('button', { name: 'Unfollow' }))
    expect(await screen.findByText('1 unfollowed')).toBeInTheDocument()
    expect(bodies).toEqual([{ action: 'UNWATCH', ids: ['m2'] }])
  })
})

describe('team', () => {
  it('hides the Team tab from people who oversee no unit', async () => {
    const urls = recordUrls('/api/v1/inbox/team')
    renderRoute('/inbox')
    await screen.findByRole('link', { name: /Office lease extension/ }, { timeout: 5000 })
    await waitFor(() => {
      expect(urls).toHaveLength(1)
    })
    // Let the 403 settle before reading the tabs.
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual([
      'My tasks',
      'Drafts',
      'Sent',
      'Closed',
      'Watching',
    ])
  })

  it('lists the team’s tasks overdue first, restricted memos without subject or number, and filters', async () => {
    const user = userEvent.setup()
    const urls = recordUrls('/api/v1/inbox/team')
    server.use(teamHandler())
    const { container, router } = renderRoute('/inbox?tab=team')

    const tab = await screen.findByRole('tab', { name: 'Team' }, { timeout: 5000 })
    expect(tab).toHaveAttribute('aria-selected', 'true')
    await screen.findByRole('link', { name: /Office lease extension/ })
    const panel = screen.getByRole('tabpanel')
    const rows = within(panel)
      .getAllByRole('listitem')
      .map((li) => li.textContent)
    expect(rows[0]).toMatch(/^Branch generator replacement.*Demo Credit Officer · Approve · step 2.*Overdue since /)
    expect(rows[1]).toMatch(/^Office lease extension.*Due /)
    expect(rows[2]).toMatch(/^Restricted memo.*Demo Credit Analyst · Opinion.*No due time/)
    const overdue = within(panel).getByText(/^Overdue since /)
    // Marked with text and an icon, never colour alone.
    expect(overdue.closest('[data-tone="danger"]')?.querySelector('svg')).not.toBeNull()
    expect(within(panel).getAllByRole('link')).toHaveLength(2)
    expect(screen.getByText('3 tasks · 1 overdue')).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.selectOptions(screen.getByRole('combobox', { name: 'Team' }), 'Retail Banking')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ tab: 'team', unit: 'u2' })
    })
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'Overdue')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ tab: 'team', unit: 'u2', status: 'overdue' })
    })
    await waitFor(() => {
      const last = urls.at(-1)
      expect([last?.searchParams.get('unit'), last?.searchParams.get('status')]).toEqual(['u2', 'overdue'])
    })
  })

  it('reassigns the selected tasks with a required reason, after a step-up, and shows each result', async () => {
    const user = userEvent.setup()
    let steppedUp = false
    server.use(
      teamHandler(),
      http.get(`${API_ORIGIN}/api/v1/directory/search`, () =>
        HttpResponse.json({
          users: [{ person: { id: 'p7', displayName: 'Demo Credit Manager', email: 'manager@bank.example' } }],
          positions: [],
          units: [],
        }),
      ),
      http.post(`${API_ORIGIN}/api/v1/auth/step-up`, () => {
        steppedUp = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const bodies = bulkHandler((body) =>
      steppedUp
        ? { results: body.ids.map((id) => ({ id, outcome: id === 't3' ? 'FORBIDDEN' : 'OK' })) }
        : HttpResponse.json(
            { type: 'urn:memo:problem:step-up-required', status: 401, stepUpWindowSeconds: 300 },
            { status: 401 },
          ),
    )
    renderRoute('/inbox?tab=team')

    await screen.findByRole('link', { name: /Office lease extension/ }, { timeout: 5000 })
    await user.click(screen.getByRole('checkbox', { name: /^Select .?M-2026-00031.? · Branch generator replacement$/ }))
    await user.click(screen.getByRole('checkbox', { name: 'Select Restricted memo' }))
    const bar = screen.getByRole('toolbar', { name: 'Actions on the selected items' })
    expect(
      within(bar)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Reassign…', 'Watch', 'Clear selection'])
    await user.click(within(bar).getByRole('button', { name: 'Reassign…' }))

    const dialog = await screen.findByRole('dialog', { name: 'Reassign the selected tasks' })
    expect(within(dialog).getByText(/^The 2 tasks go to the person you choose/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Reassign' }))
    expect(within(dialog).getByText('Choose the new person.')).toBeInTheDocument()
    expect(within(dialog).getByText('A reason is required.')).toBeInTheDocument()
    expect(bodies).toEqual([])

    await user.type(within(dialog).getByRole('combobox', { name: 'New person' }), 'Demo Cr')
    await user.click(await within(dialog).findByRole('option', { name: /Demo Credit Manager/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Reassign' }))
    expect(within(dialog).getByText('A reason is required.')).toBeInTheDocument()
    expect(bodies).toEqual([])
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Covering while on leave')
    await user.click(within(dialog).getByRole('button', { name: 'Reassign' }))

    const stepUp = await screen.findByRole('dialog', { name: 'Confirm it is you' })
    await user.type(within(stepUp).getByLabelText('Code'), '123456')
    await user.click(within(stepUp).getByRole('button', { name: 'Confirm' }))

    expect(await screen.findByText('1 reassigned · 1 not allowed')).toBeInTheDocument()
    const sent = { action: 'REASSIGN', ids: ['t2', 't3'], to: 'p7', reason: 'Covering while on leave' }
    expect(bodies).toEqual([sent, sent])
    expect(screen.getByText('Restricted memo: Not allowed')).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Reassign the selected tasks' })).not.toBeInTheDocument()
  })
})

describe('notification settings', () => {
  const preferences: PreferenceView[] = [
    { category: 'WORKFLOW', channel: 'IN_APP', enabled: true, mandatory: false },
    { category: 'WORKFLOW', channel: 'EMAIL', enabled: true, mandatory: false },
  ]

  it('follows memos I create or act on by default, emails a daily digest by default, and saves changes', async () => {
    const user = userEvent.setup()
    const saved: WatchSettings[] = []
    server.use(
      http.get(`${API_ORIGIN}/api/v1/me/notification-preferences`, () =>
        HttpResponse.json<PreferenceView[]>(preferences),
      ),
      // A person who never chose: the server answers without values, and the page shows the defaults.
      http.get(`${API_ORIGIN}/api/v1/me/watch-settings`, () => HttpResponse.json<WatchSettings>({})),
      http.put(`${API_ORIGIN}/api/v1/me/watch-settings`, async ({ request }) => {
        const body = (await request.json()) as WatchSettings
        saved.push(body)
        return HttpResponse.json<WatchSettings>(body)
      }),
    )
    const { container } = renderRoute('/me/notifications')

    const auto = await screen.findByRole('switch', { name: 'Follow memos I create or act on' }, { timeout: 5000 })
    expect(auto).toBeChecked()
    const email = screen.getByRole('group', { name: 'Email me about followed memos' })
    expect(within(email).getByRole('radio', { name: 'Daily digest (default)' })).toBeChecked()
    expect(
      within(email)
        .getAllByRole('radio')
        .map((r) => r.closest('label')?.textContent),
    ).toEqual(['Daily digest (default)', 'At once', 'Never'])
    await expectNoA11yViolations(container)

    await user.click(auto)
    await waitFor(() => {
      expect(saved).toEqual([{ autoWatch: false, email: 'DIGEST' }])
    })
    await waitFor(() => {
      expect(auto).not.toBeChecked()
    })
    await user.click(within(email).getByRole('radio', { name: 'At once' }))
    await waitFor(() => {
      expect(saved.at(-1)).toEqual({ autoWatch: false, email: 'IMMEDIATE' })
    })
    expect(within(email).getByRole('radio', { name: 'At once' })).toBeChecked()
  })
})
