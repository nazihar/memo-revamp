import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApprovalsDashboard, MyAccess, OrgUnit, TeamPage } from '@/api/generated/types.gen'
import { API_ORIGIN, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'
import { periodStart } from './scope'

const weeks = Array.from({ length: 12 }, (_, i) => {
  const day = new Date(Date.UTC(2026, 7, 24 + i * 7))
  return { week: day.toISOString().slice(0, 10), created: 5 + (i % 3), completed: 4 + (i % 2) }
})

const mine: ApprovalsDashboard = {
  scope: 'me',
  unit: null,
  from: '2026-08-24',
  to: '2026-11-15',
  pendingByAge: [
    { bucket: '0-2', count: 41 },
    { bucket: '3-5', count: 18 },
    { bucket: '6-10', count: 6 },
    { bucket: '10+', count: 2 },
  ],
  overdue: 7,
  pendingByPerson: [],
  cycleTimes: [
    { type: { id: 't1', name: 'General memo' }, decided: 30, medianDays: 1.4, p90Days: 4 },
    { type: { id: 't2', name: 'Credit proposal' }, decided: 12, medianDays: 3.25, p90Days: 9.5 },
  ],
  throughput: weeks,
}

const credit: ApprovalsDashboard = {
  ...mine,
  scope: 'unit:u1',
  unit: { id: 'u1', name: 'Credit Division' },
  pendingByPerson: [
    { person: { id: 'p2', name: 'Demo Credit Officer' }, pending: 3, overdue: 1 },
    { person: { id: 'p1', name: 'Demo Head of Credit' }, pending: 5, overdue: 0 },
  ],
}

const team: TeamPage = { items: [], nextCursor: null, units: [{ id: 'u1', name: 'Credit Division' }] }

const treasury: OrgUnit = {
  id: 'u5',
  code: 'TRS',
  name: 'Treasury',
  legalEntityId: 'le1',
  validFrom: '2026-01-01',
  version: 0,
}

/** `GET /me/access` with `memo.dashboard.read` for [units] (none: not held). */
const accessWith = (units: string[]) =>
  http.get(`${API_ORIGIN}/api/v1/me/access`, () =>
    HttpResponse.json<MyAccess>({
      permissions: [
        { key: 'directory.read', tenantWide: true, scopeUnitIds: [] },
        ...(units.length > 0 ? [{ key: 'memo.dashboard.read', tenantWide: false, scopeUnitIds: units }] : []),
      ],
      clearance: { rank: 0, name: 'Public' },
    }),
  )

/** `GET /dashboards/approvals`, answering per scope and keeping each request's query. */
function dashboardHandler(byScope: Record<string, ApprovalsDashboard | number>) {
  const queries: URLSearchParams[] = []
  server.use(
    http.get(`${API_ORIGIN}/api/v1/dashboards/approvals`, ({ request }) => {
      const query = new URL(request.url).searchParams
      queries.push(query)
      const answer = byScope[query.get('scope') ?? 'me']
      if (answer === undefined || typeof answer === 'number') {
        const status = answer ?? 403
        return HttpResponse.json({ type: 'about:blank', title: 'Forbidden', status }, { status })
      }
      return HttpResponse.json<ApprovalsDashboard>(answer)
    }),
  )
  return queries
}

function figure(name: string) {
  return screen.getByRole('figure', { name })
}

beforeEach(() => {
  server.use(sessionHandler(signedInSession()), accessWith([]))
})

describe('approvals dashboard', () => {
  it('shows each figure with a one-line summary and its data as a table, for screen readers by default', async () => {
    const user = userEvent.setup()
    const queries = dashboardHandler({ me: mine })
    const { container } = renderRoute('/dashboards/approvals')

    // The file's first render loads the route tree and Recharts: on a loaded runner that alone can take over 5 s.
    expect(
      await screen.findByText('Showing: Me, Aug 24, 2026 – Nov 15, 2026', {}, { timeout: 10_000 }),
    ).toBeInTheDocument()
    expect([queries[0]?.get('scope'), queries[0]?.get('from')]).toEqual(['me', periodStart(12)])
    expect(screen.getByRole('combobox', { name: 'Scope' })).toHaveValue('me')
    expect(screen.getByRole('combobox', { name: 'Period' })).toHaveValue('12')

    expect(screen.getAllByRole('figure').map((f) => f.querySelector('h2')?.textContent)).toEqual([
      'Pending by age',
      'Overdue',
      'Cycle time by memo type',
      'Throughput per week',
    ])

    const age = figure('Pending by age')
    expect(within(age).getByText('67 tasks are pending; 8 have waited more than 5 working days.')).toBeInTheDocument()
    const ageTable = within(age).getByRole('table', { name: 'Pending by age' })
    expect(
      within(ageTable)
        .getAllByRole('row')
        .map((r) => r.textContent),
    ).toEqual(['Waiting (working days)Tasks', '0–2 days41', '3–5 days18', '6–10 days6', 'Over 10 days2'])
    // In the page for screen readers, visually hidden until asked for.
    expect(ageTable.parentElement).toHaveClass('sr-only')
    const toggle = within(age).getByRole('button', { name: 'Show as table' })
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-pressed', 'true')
    expect(ageTable.parentElement).not.toHaveClass('sr-only')

    const overdue = figure('Overdue')
    expect(within(overdue).getByText('7 tasks are past their due time.')).toBeInTheDocument()
    expect(within(overdue).getByText('7 tasks')).toBeInTheDocument()
    expect(within(overdue).getByRole('link', { name: 'View list' })).toHaveAttribute(
      'href',
      '/inbox?tab=tasks&sort=due',
    )

    const cycle = figure('Cycle time by memo type')
    expect(
      within(cycle).getByText('Memos of 2 types were decided; the longest median is Credit proposal, at 3.3 days.'),
    ).toBeInTheDocument()
    const cycleTable = within(cycle).getByRole('table', { name: 'Cycle time by memo type' })
    expect(
      within(cycleTable)
        .getAllByRole('row')
        .map((r) => r.textContent),
    ).toEqual(['Memo typeDecidedMedian (days)P90 (days)', 'General memo301.44', 'Credit proposal123.39.5'])
    // Series are told apart by more than colour: the legend names them with their markers.
    expect(
      within(cycle)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['Median', 'P90'])

    const throughput = figure('Throughput per week')
    expect(within(throughput).getByText('72 memos created and 54 completed in 12 weeks.')).toBeInTheDocument()
    expect(within(throughput).getAllByRole('row')).toHaveLength(13)
    expect(
      within(throughput)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['Created', 'Completed'])

    expect(screen.queryByRole('figure', { name: 'Pending by person' })).not.toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('offers the units the viewer oversees and those of a dashboard grant; a unit adds pending by person', async () => {
    const user = userEvent.setup()
    server.use(
      accessWith(['u5']),
      http.get(`${API_ORIGIN}/api/v1/inbox/team`, () => HttpResponse.json<TeamPage>(team)),
      http.get(`${API_ORIGIN}/api/v1/org/units`, () => HttpResponse.json<OrgUnit[]>([treasury])),
    )
    const queries = dashboardHandler({ me: mine, 'unit:u1': credit })
    const { container, router } = renderRoute('/dashboards/approvals')

    const scope = await screen.findByRole('combobox', { name: 'Scope' }, { timeout: 5000 })
    await waitFor(() => {
      expect(
        within(scope)
          .getAllByRole('option')
          .map((o) => o.textContent),
      ).toEqual(['Me', 'Credit Division', 'Treasury'])
    })
    await user.selectOptions(scope, 'Credit Division')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ scope: 'unit:u1' })
    })
    expect(await screen.findByText(/^Showing: Credit Division, /)).toBeInTheDocument()
    expect(queries.at(-1)?.get('scope')).toBe('unit:u1')

    const people = figure('Pending by person')
    expect(
      within(people).getByText('2 people have pending tasks; the most has Demo Head of Credit, with 5.'),
    ).toBeInTheDocument()
    expect(
      within(within(people).getByRole('table', { name: 'Pending by person' }))
        .getAllByRole('row')
        .map((r) => r.textContent),
    ).toEqual(['PersonPendingOverdue', 'Demo Head of Credit50', 'Demo Credit Officer31'])
    expect(within(figure('Overdue')).getByRole('link', { name: 'View list' })).toHaveAttribute(
      'href',
      '/inbox?tab=team&unit=u1&status=overdue',
    )
    await expectNoA11yViolations(container)
  })

  it('says clearly when a unit is outside the viewer’s scope, and goes back to their own figures', async () => {
    const user = userEvent.setup()
    dashboardHandler({ me: mine, 'unit:u9': 403 })
    const { router } = renderRoute('/dashboards/approvals?scope=unit:u9')

    expect(
      await screen.findByText(
        'You may not see the figures of this unit. Choose Me or a unit you oversee.',
        {},
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Show my figures' }))
    await waitFor(() => {
      expect(router.state.location.search).toEqual({})
    })
    expect(await screen.findByText(/^Showing: Me, /)).toBeInTheDocument()
  })

  describe('charts', () => {
    // jsdom has no layout: give the chart boxes a width, as a browser would.
    beforeEach(() => {
      globalThis.ResizeObserver = class {
        constructor(private readonly callback: () => void) {}
        observe() {
          this.callback()
        }
        unobserve() {
          // nothing to stop
        }
        disconnect() {
          // nothing to stop
        }
      } as unknown as typeof ResizeObserver
      vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(640)
    })
    afterEach(() => {
      Reflect.deleteProperty(globalThis, 'ResizeObserver')
    })

    it('draws each chart as keyboard-reachable SVG named by its summary, with values labelled on the marks', async () => {
      dashboardHandler({ me: mine })
      renderRoute('/dashboards/approvals')
      await screen.findByText(/^Showing: Me, /, {}, { timeout: 5000 })

      const age = figure('Pending by age')
      const surface = await waitFor(() => {
        const svg = age.querySelector('svg.recharts-surface')
        expect(svg).not.toBeNull()
        return svg as SVGElement
      })
      expect(surface).toHaveAttribute('tabindex', '0')
      expect(surface.querySelector('title')?.textContent).toBe(
        '67 tasks are pending; 8 have waited more than 5 working days.',
      )
      expect([...age.querySelectorAll('.recharts-label-list text')].map((t) => t.textContent)).toEqual([
        '41',
        '18',
        '6',
        '2',
      ])
      expect(figure('Throughput per week').querySelectorAll('.recharts-line')).toHaveLength(2)
      expect(figure('Cycle time by memo type').querySelector('svg.recharts-surface')).not.toBeNull()
      // No inline style attribute or <style> element is written by the charts (the CSP forbids both; e2e checks it too).
      expect(document.querySelectorAll('style')).toHaveLength(0)
    })
  })

  it('changes the period, keeping it in the address', async () => {
    const user = userEvent.setup()
    const queries = dashboardHandler({ me: mine })
    const { router } = renderRoute('/dashboards/approvals')
    const period = await screen.findByRole('combobox', { name: 'Period' }, { timeout: 5000 })
    expect(
      within(period)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['Last 4 weeks', 'Last 12 weeks', 'Last 26 weeks'])
    await user.selectOptions(period, 'Last 4 weeks')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ weeks: 4 })
    })
    await waitFor(() => {
      expect(queries.at(-1)?.get('from')).toBe(periodStart(4))
    })
  })
})
