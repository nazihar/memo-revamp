import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  ActionReportResponse,
  ActionReportRowResponse,
  MeetingResponse,
  MemberAgendaResponse,
  MemberItemResponse,
  MemoView,
  MyDeclarationsResponse,
  PublishedRevisionResponse,
} from '@/api/generated/types.gen'
import {
  accessHandler,
  API_ORIGIN,
  csrfHandler,
  memoPapersHandler,
  server,
  sessionHandler,
  signedInSession,
} from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'
import { reportCounts } from './report-links'

const API = `${API_ORIGIN}/api/v1`

const row = (
  over: Partial<ActionReportRowResponse> & Pick<ActionReportRowResponse, 'actionId' | 'reference' | 'title'>,
) =>
  ({
    sourceLabel: 'BRC 3/2026, item 7',
    owner: 'Head of Treasury',
    dueDate: '2026-11-28',
    originalDueDate: '2026-11-28',
    dueRevisions: 0,
    rag: { colour: 'GREEN', word: 'ON_TRACK' },
    status: 'IN_PROGRESS',
    ...over,
  }) satisfies ActionReportRowResponse

/** BRC 4/2026's report as an officer reads it (flow AO's drawing): two sections that need the meeting first. */
const report: ActionReportResponse = {
  reportId: 'r2',
  meetingId: 'm4',
  forumId: 'f1',
  committee: 'Board Risk Committee',
  meetingLabel: 'BRC 4/2026',
  previousMeetingLabel: 'BRC 3/2026',
  since: '2026-11-10T04:00:00Z',
  revision: 2,
  revisions: [1, 2],
  itemId: 'i3',
  builtAt: '2026-11-28T10:05:00Z',
  more: 2,
  heldBack: 1,
  summary:
    'Action taken report: 1 to close, 1 overdue, 1 closed since the last meeting, 1 cancelled since the last meeting',
  sections: [
    {
      section: 'TO_CLOSE',
      count: 1,
      corrections: [],
      rows: [
        row({
          actionId: 'a9',
          reference: 'BRC A9/2026',
          title: 'Revise the liquidity contingency plan',
          holder: 'A. Karim',
          status: 'DONE_PENDING',
          rag: { colour: 'BLUE', word: 'TO_VERIFY' },
          doneAt: '2026-12-02T09:00:00Z',
          latestUpdate: 'Plan revised and approved by ALCO on 1 Dec.',
          latestUpdateAt: '2026-12-02T09:00:00Z',
        }),
      ],
    },
    {
      section: 'OVERDUE',
      count: 1,
      corrections: [],
      rows: [
        row({
          actionId: 'a5',
          reference: 'BRC A5/2026',
          title: 'Report the revised collateral valuation',
          dueDate: '2026-10-30',
          originalDueDate: '2026-10-15',
          dueRevisions: 2,
          rag: { colour: 'RED', word: 'OVERDUE' },
        }),
      ],
    },
    { section: 'OPEN', count: 0, corrections: [], rows: [] },
    {
      section: 'CLOSED',
      count: 1,
      corrections: [],
      rows: [
        row({
          actionId: 'a3',
          reference: 'BRC A3/2026',
          title: 'Update the liquidity risk appetite',
          status: 'CLOSED',
          rag: { colour: 'GREY', word: 'CLOSED' },
          closedAt: '2026-11-20T09:00:00Z',
        }),
      ],
    },
    {
      section: 'CANCELLED',
      count: 1,
      corrections: [],
      rows: [
        row({
          actionId: 'a2',
          reference: 'BRC A2/2026',
          title: 'Review the old risk appetite',
          status: 'CANCELLED',
          rag: { colour: 'GREY', word: 'CANCELLED' },
          cancelKind: 'RISK_ACCEPTED',
          cancelReason: 'Superseded by the new risk appetite.',
        }),
      ],
    },
    {
      section: 'CORRECTIONS',
      count: 1,
      corrections: [{ label: 'BRC 2/2026, item 4: the amount read BDT 50 crore', approvedAt: '2026-11-15T09:00:00Z' }],
      rows: [],
    },
  ],
}

function reportHandler(body: ActionReportResponse = report) {
  const asked: (string | null)[] = []
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    http.get(`${API}/meetings/m4/action-report`, ({ request }) => {
      const revision = new URL(request.url).searchParams.get('revision')
      asked.push(revision)
      return HttpResponse.json<ActionReportResponse>(revision === '1' ? { ...body, revision: 1, heldBack: null } : body)
    }),
  )
  return asked
}

describe('the action taken report (Phase 4 E07 T11, flow AO)', () => {
  it('shows the sections that need the meeting first, each reader’s rows in words, and prints as a PDF', async () => {
    reportHandler()
    const { container } = renderRoute('/meetings/m4/action-report')
    expect(
      await screen.findByRole(
        'heading',
        { level: 1, name: 'Action taken report — Board Risk Committee, BRC 4/2026' },
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()
    expect(screen.getByText(/Built when the agenda was published on November 28, 2026 at/)).toHaveTextContent(
      'It does not change.',
    )
    expect(screen.getByText('“Since the last meeting” means since BRC 3/2026 began.')).toBeInTheDocument()
    expect(
      within(screen.getByRole('article'))
        .getAllByRole('heading', { level: 2 })
        .map((h) => h.textContent),
    ).toEqual([
      '1. Proposed to close (1) — the committee is asked to agree',
      '2. Overdue (1)',
      '3. Open (0)',
      '4. Closed since the last meeting, for noting (1)',
      '5. Cancelled since the last meeting, for noting (1)',
      '6. Corrections to earlier minutes (1)',
      'Key',
    ])
    const toClose = screen.getByRole('region', { name: /^1\. Proposed to close/ })
    expect(within(toClose).getByRole('link', { name: 'BRC A9/2026' })).toHaveAttribute('href', '/actions/a9')
    expect(toClose).toHaveTextContent('Done, to be verified')
    expect(toClose).toHaveTextContent(
      'Owner: Head of Treasury (held by A. Karim) · Done Dec 2, 2026 · BRC 3/2026, item 7',
    )
    expect(toClose).toHaveTextContent('Dec 2, 2026: “Plan revised and approved by ALCO on 1 Dec.”')
    const overdue = screen.getByRole('region', { name: '2. Overdue (1)' })
    expect(overdue).toHaveTextContent('due Oct 30, 2026 (originally Oct 15, 2026, revised twice)')
    expect(overdue).toHaveTextContent('Overdue')
    expect(screen.getByRole('region', { name: '3. Open (0)' })).toHaveTextContent('None.')
    const cancelled = screen.getByRole('region', { name: /^5\. Cancelled/ })
    expect(cancelled).toHaveTextContent('Cancelled: risk accepted')
    expect(cancelled).toHaveTextContent('“Superseded by the new risk appetite.”')
    expect(screen.getByRole('region', { name: /^6\. Corrections/ })).toHaveTextContent(
      'BRC 2/2026, item 4: the amount read BDT 50 crore (approved November 15, 2026)',
    )
    expect(screen.getByText('And 2 more open actions: see the committee’s tracker.')).toBeInTheDocument()
    expect(
      screen.getByText('1 action was held back by the secretary. Only officers see this line.'),
    ).toBeInTheDocument()
    const print = screen.getByRole('link', { name: 'Print' })
    expect(print).toHaveAttribute('href', '/api/v1/meetings/m4/action-report/pdf')
    expect(print).toHaveAccessibleDescription('Opens your rows as a PDF, to print or keep.')
    expect(screen.getByRole('link', { name: 'Back to the meeting' })).toHaveAttribute('href', '/meetings/m4')
    await expectNoA11yViolations(container)
  })

  it('opens an earlier revision’s report, unchanged, by its address; a member reads no held-back line', async () => {
    const asked = reportHandler()
    const user = userEvent.setup()
    const { router } = renderRoute('/meetings/m4/action-report')
    const select = await screen.findByRole('combobox', { name: 'Agenda revision' }, { timeout: 5000 })
    expect(
      within(select)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['Revision 2 (latest)', 'Revision 1'])
    await user.selectOptions(select, 'Revision 1')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ revision: 1 })
    })
    await waitFor(() => {
      expect(asked).toContain('1')
    })
    expect(await screen.findByRole('link', { name: 'Print' })).toHaveAttribute(
      'href',
      '/api/v1/meetings/m4/action-report/pdf?revision=1',
    )
    await waitFor(() => {
      expect(screen.queryByText(/held back by the secretary/)).toBeNull()
    })
  })

  it('says there is no report for a reader it is not for (404)', async () => {
    server.use(
      sessionHandler(signedInSession()),
      http.get(`${API}/meetings/m4/action-report`, () => HttpResponse.json({ status: 404 }, { status: 404 })),
    )
    renderRoute('/meetings/m4/action-report')
    expect(
      await screen.findByText(
        'There is no action taken report for this meeting, or it is not for you.',
        {},
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()
  })

  it('reads the report right to left in Arabic', async () => {
    reportHandler()
    const { container } = renderRoute('/meetings/m4/action-report', { locale: 'ar' })
    expect(await screen.findByRole('link', { name: 'اطبع' }, { timeout: 5000 })).toBeInTheDocument()
    expect(document.documentElement.dir).toBe('rtl')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('تقرير الإجراءات المتخذة')
    await expectNoA11yViolations(container)
  })
})

/** BRC 4/2026, scheduled, with its "Matters arising" item linking to the report (through the item-links port). */
const meeting: MeetingResponse = {
  id: 'm4',
  forumId: 'f1',
  forumName: 'Board Risk Committee',
  forumCode: 'BRC',
  typeVersion: 2,
  kind: 'ORDINARY',
  title: null,
  startsAt: '2026-12-12T04:00:00Z',
  endsAt: '2026-12-12T07:00:00Z',
  timeZone: 'Asia/Dhaka',
  localStartsAt: '2026-12-12T10:00:00',
  localEndsAt: '2026-12-12T13:00:00',
  mode: 'IN_PERSON',
  location: 'Board Room',
  classificationId: 'l1',
  number: 4,
  label: 'BRC 4/2026',
  status: 'SCHEDULED',
  statusReason: null,
  relation: 'MEMBER',
  canManage: false,
  guests: [],
  version: 3,
}

const item = (id: string, extra: Partial<MemberItemResponse>): MemberItemResponse => ({
  id,
  access: 'FULL',
  kind: 'ITEM',
  links: [],
  late: 'NONE',
  changes: [],
  papers: [],
  presenters: [],
  subItems: [],
  ...extra,
})

function agendaOf(links: MemberItemResponse['links']): MemberAgendaResponse {
  return {
    meetingId: 'm4',
    status: 'SCHEDULED',
    startsAt: meeting.startsAt,
    endsAt: meeting.endsAt,
    timeZone: 'Asia/Dhaka',
    revision: 2,
    latestRevision: 2,
    publishedAt: '2026-11-28T10:05:00Z',
    note: null,
    preview: false,
    lastSeenRevision: 2,
    whatChanged: null,
    sections: [
      {
        id: 's1',
        title: 'Opening',
        kind: 'STANDARD',
        membersOnly: false,
        items: [
          item('i3', {
            number: '3',
            title: 'Matters arising and actions',
            kind: 'STANDING',
            standingKind: 'MATTERS_ARISING',
            purpose: 'INFORMATION',
            links,
          }),
        ],
      },
    ],
  }
}

function meetingPage(agenda: MemberAgendaResponse) {
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    http.get(`${API}/authz/classification-levels`, () =>
      HttpResponse.json([{ id: 'l1', name: 'Confidential', rank: 1 }]),
    ),
    http.get(`${API}/meetings/m4`, () => HttpResponse.json<MeetingResponse>(meeting)),
    http.get(`${API}/meetings/m4/agenda`, () => HttpResponse.json<MemberAgendaResponse>(agenda)),
    http.get(`${API}/meetings/m4/agenda/revisions`, () =>
      HttpResponse.json<PublishedRevisionResponse[]>([{ number: 2, publishedAt: '2026-11-28T10:05:00Z', note: null }]),
    ),
    http.post(`${API}/meetings/m4/seen`, () => new HttpResponse(null, { status: 204 })),
    http.get(`${API}/meetings/m4/declarations/mine`, () =>
      HttpResponse.json<MyDeclarationsResponse>({
        meetingId: 'm4',
        latestRevision: 2,
        reviewedRevision: 2,
        declarations: [],
        toReview: [],
      }),
    ),
  )
}

describe('the Matters arising item’s link to its report (Phase 4 E07 T11, flow AO)', () => {
  it('says when the report was built and the reader’s counts in their words, with Open the report', async () => {
    meetingPage(
      agendaOf([
        {
          kind: 'ACTION_REPORT',
          meetingId: 'm4',
          state: 'BUILT',
          at: '2026-11-28T10:05:00Z',
          label: 'Action taken report: 2 to close, 3 overdue, 9 open, 4 closed since the last meeting',
        },
      ]),
    )
    const { container } = renderRoute('/meetings/m4')
    expect(
      await screen.findByText('Action taken report (as published on November 28)', {}, { timeout: 8000 }),
    ).toBeInTheDocument()
    expect(
      screen.getByText('2 proposed to close · 3 overdue · 9 open · 4 closed since the last meeting'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the report' })).toHaveAttribute('href', '/meetings/m4/action-report')
    await expectNoA11yViolations(container)
  })

  it('shows no link to a reader the report is not for', async () => {
    meetingPage(agendaOf([]))
    renderRoute('/meetings/m4')
    expect(
      await screen.findByRole('heading', { name: /Matters arising and actions/ }, { timeout: 8000 }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Open the report' })).toBeNull()
  })

  it('reads the counts back from the server’s fixed words, and gives up on any other form', () => {
    expect(reportCounts('Action taken report: 2 to close, 3 overdue, 9 open, 4 closed since the last meeting')).toEqual(
      {
        TO_CLOSE: 2,
        OVERDUE: 3,
        OPEN: 9,
        CLOSED: 4,
      },
    )
    expect(
      reportCounts('Action taken report: 1 cancelled since the last meeting, 2 corrections to earlier minutes'),
    ).toEqual({ CANCELLED: 1, CORRECTIONS: 2 })
    expect(reportCounts('Action taken report: nothing to report')).toEqual({})
    expect(reportCounts('Something else')).toBeUndefined()
    expect(reportCounts('Action taken report: 2 to close, a few late')).toBeUndefined()
  })
})

const memo: MemoView = {
  origin: 'NATIVE',
  followsUp: [],
  id: 'm1',
  reference: null,
  subject: 'Revised liquidity contingency plan',
  type: { id: 't1', name: 'General memo' },
  classification: { id: 'c1', rank: 2, name: 'Confidential' },
  status: 'DRAFT',
  initiator: { id: 'u1', name: 'A. Karim' },
  route: [],
  round: 1,
  holders: [],
  sendBackTargets: [],
  allowedActions: [],
  createdAt: '2026-11-27T08:00:00Z',
  updatedAt: '2026-11-27T08:00:00Z',
  version: 1,
  interests: [],
  details: [],
  staleDetails: [],
  links: [],
  linkedFrom: [],
  canLink: false,
  coauthors: [],
  rendition: 'NONE',
}

function memoPage(view: MemoView) {
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    memoPapersHandler([]),
    http.get(`${API}/memos/m1`, () => HttpResponse.json<MemoView>(view)),
    http.get(`${API}/memos/m1/history`, () => HttpResponse.json([])),
    http.get(`${API}/memos/m1/notes`, () => HttpResponse.json({ entries: [], canWrite: false })),
    http.get(`${API}/memos/m1/approvals`, () => HttpResponse.json([])),
    http.get(`${API}/memos/m1/copies`, () =>
      HttpResponse.json({ copies: [], canEdit: false, canAdd: false, memoVersion: view.version }),
    ),
    http.get(`${API}/memos/m1/shares`, () => HttpResponse.json([])),
  )
}

describe('“Raised from action” on the memo (Phase 4 E07 T11, flow AP)', () => {
  it('links the action for a reader who sees it, and names it in words for one who does not', async () => {
    memoPage({
      ...memo,
      followsUp: [
        {
          kind: 'RAISED_FROM',
          recordType: 'ACTION',
          reference: 'BRC A9/2026',
          owner: 'Board Risk Committee',
          recordId: 'a9',
        },
        {
          kind: 'LINKED_TO',
          recordType: 'ACTION',
          reference: 'MCC A1/2026',
          owner: 'Management Credit Committee',
          recordId: null,
        },
      ],
    })
    const { container } = renderRoute('/memos/m1')
    const list = await screen.findByRole('list', { name: 'Committee actions this memo follows up' }, { timeout: 8000 })
    expect(
      within(list).getByRole('link', { name: 'Raised from action BRC A9/2026 (Board Risk Committee)' }),
    ).toHaveAttribute('href', '/actions/a9')
    expect(
      within(list).getByText('Follows up action MCC A1/2026 (Management Credit Committee)').closest('a'),
    ).toBeNull()
    await expectNoA11yViolations(container)
  }, 20_000)

  it('says nothing on a memo that follows up no action', async () => {
    memoPage(memo)
    renderRoute('/memos/m1')
    expect(await screen.findByRole('heading', { name: 'Revised liquidity contingency plan' }, { timeout: 8000 }))
    expect(screen.queryByRole('list', { name: /actions this memo follows up/ })).toBeNull()
  }, 20_000)
})
