import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import type {
  AnalyticsSummaryResponse,
  CommitteeDetailResponse,
  CommitteeSummaryResponse,
  MemberAttendanceResponse,
  MyAccess,
  MyForumResponse,
} from '@/api/generated/types.gen'
import { navigationGroups } from '@/app/navigation'
import { holdsCommitteeSeat, viewerOf } from '@/features/auth/session'
import { API_ORIGIN, myForumsHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'
import { presetRange } from './period'
import { sortMembers } from './words'

const BRC = '01900000-0000-7000-8000-00000000b0c1'
const MCC = '01900000-0000-7000-8000-00000000b0c2'

const brc: CommitteeSummaryResponse = {
  forumId: BRC,
  code: 'BRC',
  name: 'Board Risk Committee',
  shortName: 'BRC',
  access: 'FULL',
  attendance: { heldMeetings: 12, expected: 60, attended: 55, ratePercent: 92 },
  actions: {
    overdue: 3,
    overdue1To30: 1,
    overdue31To90: 1,
    overdueOver90: 1,
    overdueRevised: 2,
    closed: 18,
    closedByOriginalDate: 12,
    closedByAgreedDate: 16,
    closedByOriginalPercent: 67,
    closedByAgreedPercent: 89,
  },
  timeToDecision: {
    decisions: 24,
    medianDays: 18,
    p90Days: 41,
    medianWorkingDays: 13,
    p90WorkingDays: 29,
    enough: true,
  },
  deferrals: { decisions: 27, deferred: 3, deferredPercent: 11, deferredTwice: 1 },
  papers: { meetings: 12, withDeadline: 12, onTime: 10, onTimePercent: 83, lateItems: 2, latePapers: 4 },
}

const mcc: CommitteeSummaryResponse = {
  ...brc,
  forumId: MCC,
  code: 'MCC',
  name: 'Management Credit Committee',
  shortName: 'MCC',
  access: 'MEMBER',
  attendance: { heldMeetings: 0, expected: 0, attended: 0, ratePercent: null },
  timeToDecision: {
    decisions: 3,
    medianDays: null,
    p90Days: null,
    medianWorkingDays: null,
    p90WorkingDays: null,
    enough: false,
  },
  papers: { meetings: 0, withDeadline: 0, onTime: 0, onTimePercent: null, lateItems: null, latePapers: null },
}

const summary: AnalyticsSummaryResponse = { from: '2025-11-23', to: '2026-11-22', committees: [brc, mcc] }

const member = (over: Partial<MemberAttendanceResponse>): MemberAttendanceResponse => ({
  userId: 'u1',
  name: 'Dr F. Karim',
  role: 'CHAIR',
  seatFrom: '2025-11-23',
  seatTo: '2026-11-22',
  expected: 12,
  attended: 12,
  inPerson: 11,
  remote: 1,
  joinedLate: 0,
  leftEarly: 0,
  apologies: 0,
  absent: 0,
  byAlternate: 0,
  ratePercent: 100,
  meetings: [],
  ...over,
})

const haque = member({
  userId: 'u2',
  name: 'Ms N. Haque',
  role: 'MEMBER',
  attended: 10,
  inPerson: 7,
  remote: 3,
  apologies: 1,
  absent: 1,
  byAlternate: 1,
  ratePercent: 83,
  meetings: [
    {
      meetingId: 'm1',
      date: '2026-01-15',
      attendance: 'IN_PERSON',
      byAlternate: false,
      joinedLate: true,
      leftEarly: false,
    },
    {
      meetingId: 'm2',
      date: '2026-02-12',
      attendance: 'APOLOGIES',
      byAlternate: true,
      joinedLate: false,
      leftEarly: false,
    },
    {
      meetingId: 'm3',
      date: '2026-03-12',
      attendance: 'ABSENT',
      byAlternate: false,
      joinedLate: false,
      leftEarly: false,
    },
  ],
})

const ahmed = member({
  userId: 'u3',
  name: 'Mr R. Ahmed',
  role: 'MEMBER',
  seatFrom: '2026-07-01',
  expected: 6,
  attended: 6,
  inPerson: 6,
  remote: 0,
  ratePercent: 100,
})

const detail: CommitteeDetailResponse = {
  forumId: BRC,
  code: 'BRC',
  name: 'Board Risk Committee',
  shortName: 'BRC',
  access: 'FULL',
  from: '2025-11-23',
  to: '2026-11-22',
  attendance: {
    summary: brc.attendance,
    members: [haque, member({}), ahmed],
    ownRowOnly: false,
    membersOmitted: false,
  },
  actions: {
    summary: brc.actions,
    owners: [
      {
        ownerUserId: null,
        ownerPositionId: 'p1',
        holderUserId: 'h1',
        ownerName: 'Head of Treasury',
        holderName: 'A. Karim',
        overdue: 2,
        days1To30: 1,
        days31To90: 1,
        over90: 0,
        revised: 1,
      },
    ],
  },
  timeToDecision: {
    overall: { decisions: 46, medianDays: 18, p90Days: 41, medianWorkingDays: 13, p90WorkingDays: 29, enough: true },
    byQuarter: [
      {
        quarter: '2026-Q1',
        timing: { decisions: 11, medianDays: 21, p90Days: 44, medianWorkingDays: 15, p90WorkingDays: 31, enough: true },
      },
      {
        quarter: '2026-Q2',
        timing: { decisions: 3, medianDays: 16, p90Days: 20, medianWorkingDays: 12, p90WorkingDays: 14, enough: false },
      },
    ],
    byMemoType: [
      {
        memoTypeId: 't1',
        memoTypeName: 'Credit proposal',
        timing: { decisions: 19, medianDays: 22, p90Days: 40, medianWorkingDays: 16, p90WorkingDays: 28, enough: true },
      },
    ],
  },
  deferrals: {
    overall: { decisions: 46, deferred: 5, deferredPercent: 11, deferredTwice: 1 },
    byQuarter: [
      { quarter: '2026-Q1', deferrals: { decisions: 11, deferred: 2, deferredPercent: 18, deferredTwice: 0 } },
    ],
  },
  papers: {
    summary: brc.papers,
    meetings: [
      {
        meetingId: 'm1',
        label: 'BRC 1/2026',
        date: '2026-01-15',
        onTime: true,
        lateItems: 0,
        latePapers: 0,
        expected: 5,
        attended: 5,
      },
      {
        meetingId: 'm2',
        label: null,
        date: '2026-02-12',
        onTime: false,
        lateItems: 1,
        latePapers: 2,
        expected: 5,
        attended: 4,
      },
    ],
  },
}

/** `GET /analytics/committees`, keeping each request's query. */
function summaryHandler(answer: AnalyticsSummaryResponse | number = summary) {
  const queries: URLSearchParams[] = []
  server.use(
    http.get(`${API_ORIGIN}/api/v1/analytics/committees`, ({ request }) => {
      queries.push(new URL(request.url).searchParams)
      if (typeof answer === 'number') {
        return HttpResponse.json(
          {
            type: 'urn:memo:problem:validation',
            title: 'Refused',
            status: answer,
            errors: answer === 422 ? [{ field: 'from', code: 'RANGE', message: 'A period of at most 10 years.' }] : [],
          },
          { status: answer },
        )
      }
      return HttpResponse.json<AnalyticsSummaryResponse>(answer)
    }),
  )
  return queries
}

function detailHandler(answer: CommitteeDetailResponse = detail) {
  server.use(
    http.get(`${API_ORIGIN}/api/v1/analytics/committees/:forumId`, ({ params }) =>
      params.forumId === answer.forumId
        ? HttpResponse.json<CommitteeDetailResponse>(answer)
        : HttpResponse.json({ title: 'Not found', status: 404 }, { status: 404 }),
    ),
  )
}

const rows = (table: HTMLElement) =>
  within(table)
    .getAllByRole('row')
    .map((r) => r.textContent)

const figure = (name: string) => screen.getByRole('figure', { name })

beforeEach(() => {
  server.use(sessionHandler(signedInSession()))
})

describe('committee analytics overview (flow AU)', () => {
  it('says each committee in a sentence first, then shows its table, with bars hidden beside the words', async () => {
    const queries = summaryHandler()
    const { container } = renderRoute('/analytics/committees')

    const block = await screen.findByRole('figure', { name: 'Board Risk Committee' }, { timeout: 5000 })
    expect(queries[0]?.has('from')).toBe(false)
    expect(screen.getByText('Showing Nov 23, 2025 – Nov 22, 2026')).toBeInTheDocument()
    expect(
      within(block).getByText('92% attendance at 12 meetings · 3 actions overdue · decisions took a median of 18 days'),
    ).toBeInTheDocument()
    expect(within(block).getByText('Based on 27 decisions you can see in full.')).toBeInTheDocument()

    // The table is the figure: visible, not behind a button; no "Show as table".
    const table = within(block).getByRole('table', { name: 'Board Risk Committee' })
    expect(table.closest('.sr-only')).toBeNull()
    expect(within(block).queryByRole('button', { name: 'Show as table' })).not.toBeInTheDocument()
    expect(rows(table)).toEqual([
      'MeasureValueDetail',
      'Attendance92%at 12 meetings',
      'Actions overdue now31 over 90 days; 2 with their date changed',
      'Actions closed on time12 of 1867% by the original date; 16 by the agreed date',
      'Time to decision18 daysmedian; 9 in 10 within 41 days (24 decisions)',
      'Items deferred3 of 2711%; 1 item deferred twice',
      'Papers on time10 of 1283% of meetings published by the papers deadline; 2 late items, 4 late papers',
    ])
    // Horizontal bars for the shares, out of the accessibility tree: the words carry every number.
    const bars = block.querySelectorAll('[data-bar]')
    expect(bars).toHaveLength(4)
    bars.forEach((bar) => {
      expect(bar).toHaveAttribute('aria-hidden', 'true')
    })
    expect(block.querySelectorAll('svg.recharts-surface')).toHaveLength(0)

    // Every number leads to its list (design §2.6).
    expect(within(table).getByRole('link', { name: '92%' })).toHaveAttribute(
      'href',
      `/analytics/committees/${BRC}/attendance`,
    )
    expect(within(table).getByRole('link', { name: '3' })).toHaveAttribute(
      'href',
      `/analytics/committees/${BRC}#actions`,
    )
    expect(within(table).getByRole('link', { name: '18 days' })).toHaveAttribute(
      'href',
      `/analytics/committees/${BRC}#timing`,
    )
    expect(within(table).getByRole('link', { name: '3 of 27' })).toHaveAttribute(
      'href',
      `/analytics/committees/${BRC}#deferrals`,
    )
    expect(within(table).getByRole('link', { name: '10 of 12' })).toHaveAttribute(
      'href',
      `/analytics/committees/${BRC}#papers`,
    )
    expect(within(block).getByRole('link', { name: 'Committee detail' })).toHaveAttribute(
      'href',
      `/analytics/committees/${BRC}`,
    )

    // A member's block: no late-paper counts, too few decisions said in words.
    const credit = figure('Management Credit Committee')
    expect(
      within(credit).getByText('no meetings held · 3 actions overdue · Too few decisions to show a typical time'),
    ).toBeInTheDocument()
    expect(within(credit).getByText('No meeting had a papers deadline.')).toBeInTheDocument()
    expect(within(credit).queryByText(/late papers/)).not.toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('narrows to one committee and changes the period, keeping both in the address', async () => {
    const user = userEvent.setup()
    const queries = summaryHandler()
    const { router } = renderRoute('/analytics/committees')
    const committee = await screen.findByRole('combobox', { name: 'Committee' }, { timeout: 5000 })
    await user.selectOptions(committee, 'Management Credit Committee')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ committee: MCC })
    })
    expect(screen.queryByRole('figure', { name: 'Board Risk Committee' })).not.toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox', { name: 'Period' }), 'Last 2 years')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ period: '2y', committee: MCC })
    })
    const range = presetRange('2y')
    await waitFor(() => {
      expect([queries.at(-1)?.get('from'), queries.at(-1)?.get('to')]).toEqual([range.from, range.to])
    })

    await user.selectOptions(screen.getByRole('combobox', { name: 'Period' }), 'Choose dates')
    await user.type(screen.getByLabelText('From'), '2026-01-01')
    await user.type(screen.getByLabelText('To'), '2026-12-31')
    await user.click(screen.getByRole('button', { name: 'Show' }))
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ from: '2026-01-01', to: '2026-12-31', committee: MCC })
    })
  })

  it('says who analytics are for, and why a period is refused', async () => {
    summaryHandler(403)
    renderRoute('/analytics/committees')
    expect(
      await screen.findByText('Analytics are for committee members, officers and oversight.', {}, { timeout: 5000 }),
    ).toBeInTheDocument()
  })

  it('says a period longer than ten years is refused', async () => {
    summaryHandler(422)
    renderRoute('/analytics/committees?from=2000-01-01')
    expect(
      await screen.findByText('A period can be at most 10 years. Choose a shorter one.', {}, { timeout: 5000 }),
    ).toBeInTheDocument()
  })
})

describe("a committee's detail (flow AW)", () => {
  it('shows each figure with its sentence, its basis, its table and its link', async () => {
    const user = userEvent.setup()
    detailHandler()
    const { container } = renderRoute(`/analytics/committees/${BRC}`)

    const timing = await screen.findByRole('figure', { name: 'Time from submission to decision' }, { timeout: 5000 })
    expect(screen.getByRole('heading', { level: 1, name: 'Board Risk Committee' })).toBeInTheDocument()
    expect(timing).toHaveAttribute('id', 'timing')
    expect(within(timing).getByText('Decisions took a median of 18 days; 9 in 10 within 41 days.')).toBeInTheDocument()
    expect(within(timing).getByText('Based on 46 decisions you can see in full.')).toBeInTheDocument()
    const quarters = within(timing).getByRole('table', { name: 'Time from submission to decision' })
    expect(rows(quarters)).toEqual([
      'QuarterDecisionsMedian9 in 10 within',
      'Q1 20261121 days44 days',
      'Q2 20263Too few decisions to show a typical timeToo few decisions to show a typical time',
    ])
    expect(within(timing).getByText('By memo type: Credit proposal: 22 days (19)')).toBeInTheDocument()
    // The bars beside the table are hidden; they never hold a number the table does not.
    const chart = timing.querySelector('[data-figure-chart]')
    expect(chart).toHaveAttribute('aria-hidden', 'true')
    expect(chart?.textContent).toContain('21 days')

    const working = within(timing).getByRole('button', { name: 'Count working days' })
    expect(working).toHaveAttribute('aria-pressed', 'false')
    await user.click(working)
    expect(working).toHaveAttribute('aria-pressed', 'true')
    expect(within(timing).getByText('Decisions took a median of 13 days; 9 in 10 within 29 days.')).toBeInTheDocument()
    expect(rows(quarters)[1]).toBe('Q1 20261115 days31 days')

    expect(
      within(figure('Items deferred')).getByText('Items deferred: 5 of 46 (11%); 1 item deferred twice.'),
    ).toBeInTheDocument()
    // The decisions behind both figures open in the register (design §2.6, E08 T6).
    expect(within(timing).getByRole('link', { name: 'Open these decisions' })).toHaveAttribute(
      'href',
      `/decisions?committee=${BRC}&from=2025-11-23&to=2026-11-22`,
    )
    expect(within(figure('Items deferred')).getByRole('link', { name: 'Open deferred items' })).toHaveAttribute(
      'href',
      `/decisions?committee=${BRC}&from=2025-11-23&to=2026-11-22&outcome=DEFERRED`,
    )

    const actions = figure('Actions overdue by owner')
    expect(actions).toHaveAttribute('id', 'actions')
    expect(within(actions).getByText('3 actions are overdue; 1 for over 90 days.')).toBeInTheDocument()
    expect(
      within(actions).getByText('18 closed in the period: 12 by the original date, 16 by the agreed date.'),
    ).toBeInTheDocument()
    expect(rows(within(actions).getByRole('table'))).toEqual([
      'OwnerOverdue1–30 days31–90 daysOver 90 daysDate changed',
      'Head of Treasury (A. Karim)21101',
    ])

    // Papers: each colour has its word; each meeting links to its page; late counts for officers.
    const papers = figure('Papers on time')
    expect(within(papers).getByText('Papers on time: 10 of 12 meetings with a deadline.')).toBeInTheDocument()
    expect(within(papers).getByText('4 late papers and 2 late items in the period.')).toBeInTheDocument()
    const table = within(papers).getByRole('table')
    expect(rows(table)).toEqual([
      'MeetingDateAgenda and papersLate itemsLate papersAttendance',
      'BRC 1/2026Jan 15, 2026On time005 of 5',
      'Meeting of Feb 12, 2026Feb 12, 2026Late124 of 5',
    ])
    expect(within(table).getByRole('link', { name: 'BRC 1/2026' })).toHaveAttribute('href', '/meetings/m1')
    expect(within(papers).getByRole('link', { name: 'Meetings of this committee' })).toHaveAttribute(
      'href',
      `/committees/${BRC}?tab=meetings`,
    )
    await expectNoA11yViolations(container)
  })

  it('leaves late counts out for a member', async () => {
    detailHandler({
      ...detail,
      access: 'MEMBER',
      attendance: { ...detail.attendance, members: [haque], ownRowOnly: true },
      papers: {
        summary: { ...brc.papers, lateItems: null, latePapers: null },
        meetings: detail.papers.meetings.map((m) => ({ ...m, lateItems: null, latePapers: null })),
      },
    })
    renderRoute(`/analytics/committees/${BRC}`)
    const papers = await screen.findByRole('figure', { name: 'Papers on time' }, { timeout: 5000 })
    expect(rows(within(papers).getByRole('table'))[0]).toBe('MeetingDateAgenda and papersAttendance')
    expect(within(papers).queryByText(/late papers/)).not.toBeInTheDocument()
  })

  it('says a committee outside the viewer’s analytics is not theirs', async () => {
    detailHandler()
    renderRoute('/analytics/committees/someone-else')
    expect(
      await screen.findByText(
        'This committee is not in your analytics. Choose one from the overview.',
        {},
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()
  })
})

describe('attendance per member (flow AV)', () => {
  it('lists every seat holder by name, with alternates in words, sorting by rate on request', async () => {
    const user = userEvent.setup()
    detailHandler()
    const { container, router } = renderRoute(`/analytics/committees/${BRC}/attendance`)

    const table = await screen.findByRole('table', { name: 'Board Risk Committee — attendance' }, { timeout: 5000 })
    expect(screen.getByText('12 meetings held. Overall attendance 92%.')).toBeInTheDocument()
    expect(rows(table)).toEqual([
      'MemberRoleExpectedAttendedOf which remoteApologiesAbsentRate',
      'Dr F. KarimChair1212100100%',
      'Mr R. AhmedMember6 (from Jul 1)6000100%',
      'Ms N. HaqueMember121031 (1 by alternate)183%',
    ])
    expect(
      screen.getByText(
        '“By alternate”: an alternate attended in the member’s place; this is not counted as the member’s attendance.',
      ),
    ).toBeInTheDocument()
    const headers = within(table).getAllByRole('columnheader')
    expect(headers[0]).toHaveAttribute('aria-sort', 'ascending')
    expect(headers[7]).toHaveAttribute('aria-sort', 'none')
    table.querySelectorAll('[data-bar]').forEach((bar) => {
      expect(bar).toHaveAttribute('aria-hidden', 'true')
    })
    expect(within(table).getByRole('link', { name: 'Ms N. Haque' })).toHaveAttribute(
      'href',
      `/analytics/committees/${BRC}/attendance/u2`,
    )
    expect(screen.getByRole('button', { name: 'Export for the annual report' })).toBeEnabled()

    await user.click(within(headers[7]!).getByRole('button', { name: 'Rate' }))
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ sort: 'rate' })
    })
    expect(within(table).getAllByRole('columnheader')[7]).toHaveAttribute('aria-sort', 'descending')
    await expectNoA11yViolations(container)
  })

  it('shows a member their own row and the committee’s total, without an export', async () => {
    detailHandler({
      ...detail,
      access: 'MEMBER',
      attendance: { ...detail.attendance, members: [haque], ownRowOnly: true },
    })
    renderRoute(`/analytics/committees/${BRC}/attendance`)
    expect(
      await screen.findByText(
        'Your attendance: 10 of 12 meetings (83%). The committee’s overall attendance: 92%.',
        {},
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()
    expect(rows(screen.getByRole('table'))).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Export for the annual report' })).not.toBeInTheDocument()
  })

  it('leaves member rows out above five years and says so', async () => {
    detailHandler({ ...detail, attendance: { ...detail.attendance, members: [], membersOmitted: true } })
    renderRoute(`/analytics/committees/${BRC}/attendance?period=5y`)
    expect(
      await screen.findByText(/Attendance per member covers at most 5 years/, {}, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Export for the annual report' })).not.toBeInTheDocument()
  })

  it("lists a member's meetings with their attendance in words, each linking to its meeting", async () => {
    detailHandler()
    const { container } = renderRoute(`/analytics/committees/${BRC}/attendance/u2`)
    const table = await screen.findByRole('table', { name: 'Meetings and attendance' }, { timeout: 5000 })
    expect(screen.getByRole('heading', { level: 1, name: 'Ms N. Haque' })).toBeInTheDocument()
    expect(screen.getByText('Attended 10 of 12 meetings (83%).')).toBeInTheDocument()
    expect(rows(table)).toEqual([
      'MeetingDateAttendance',
      'BRC 1/2026Jan 15, 2026Attended in person (joined late)',
      'Meeting of Feb 12, 2026Feb 12, 2026Apologies (represented by their alternate)',
      'Meeting of Mar 12, 2026Mar 12, 2026Absent',
    ])
    expect(within(table).getByRole('link', { name: 'BRC 1/2026' })).toHaveAttribute('href', '/meetings/m1')
    await expectNoA11yViolations(container)
  })
})

describe('in Arabic', () => {
  it('reads the overview and the attendance table right to left, with Arabic words', async () => {
    summaryHandler()
    const { container } = renderRoute('/analytics/committees', { locale: 'ar' })
    const block = await screen.findByRole('figure', { name: 'Board Risk Committee' }, { timeout: 5000 })
    expect(document.documentElement.dir).toBe('rtl')
    expect(within(block).getByRole('columnheader', { name: 'المقياس' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('تحليلات')
    await expectNoA11yViolations(container)
  })

  it('reads a committee’s detail in Arabic', async () => {
    detailHandler()
    const { container } = renderRoute(`/analytics/committees/${BRC}`, { locale: 'ar' })
    expect(
      await screen.findByRole('figure', { name: 'المدة من التقديم إلى القرار' }, { timeout: 5000 }),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })
})

describe('the analytics menu entry', () => {
  const forum = (over: Partial<MyForumResponse>): MyForumResponse => ({
    attributes: [],
    canOpen: true,
    code: 'BRC',
    forumId: BRC,
    name: 'Board Risk Committee',
    role: 'MEMBER',
    startsOn: '2026-01-01',
    state: 'IN_OFFICE',
    status: 'ACTIVE',
    termNumber: 1,
    voting: true,
    ...over,
  })
  const access = (keys: string[]): MyAccess => ({
    permissions: keys.map((key) => ({ key, tenantWide: true, scopeUnitIds: [] })),
    clearance: { rank: 0, name: 'Public' },
  })
  const entries = (viewer: ReturnType<typeof viewerOf>) =>
    navigationGroups(viewer).flatMap((g) => g.items.map((i) => i.id))

  it('shows to members and officers in office and to oversight, not to observers or former members', () => {
    const session = signedInSession()
    expect(holdsCommitteeSeat([forum({ role: 'SECRETARY' })])).toBe(true)
    expect(holdsCommitteeSeat([forum({ role: 'OBSERVER' }), forum({ role: 'STANDING_INVITEE' })])).toBe(false)
    expect(holdsCommitteeSeat([forum({ state: 'TERM_ENDED' })])).toBe(false)
    expect(entries(viewerOf(session, access([]), [forum({})]))).toContain('analytics')
    expect(entries(viewerOf(session, access(['forum.read']), []))).toContain('analytics')
    expect(entries(viewerOf(session, access([]), [forum({ role: 'OBSERVER' })]))).not.toContain('analytics')
    expect(entries(viewerOf(undefined, access(['forum.read']), [forum({})]))).not.toContain('analytics')
  })

  it('appears in the menu for a member, from the committees they sit on', async () => {
    summaryHandler()
    server.use(myForumsHandler([forum({})]))
    renderRoute('/analytics/committees')
    const nav = (await screen.findAllByRole('navigation', { name: 'Main navigation' }, { timeout: 5000 }))[0]
    expect(await within(nav!).findByRole('link', { name: 'Committee analytics' }, { timeout: 5000 })).toHaveAttribute(
      'href',
      '/analytics/committees',
    )
  })
})

describe('sorting members', () => {
  it('sorts by name, or by rate with the name after, never ranking', () => {
    const list = [haque, member({}), ahmed]
    expect(sortMembers(list, undefined, 'en').map((m) => m.name)).toEqual(['Dr F. Karim', 'Mr R. Ahmed', 'Ms N. Haque'])
    expect(sortMembers(list, 'rate', 'en').map((m) => m.name)).toEqual(['Dr F. Karim', 'Mr R. Ahmed', 'Ms N. Haque'])
    expect(sortMembers([member({ ratePercent: null, name: 'Z' }), haque], 'rate', 'en').map((m) => m.name)).toEqual([
      'Ms N. Haque',
      'Z',
    ])
  })
})
