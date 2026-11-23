import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  AgendaDraftResponse,
  AgendaItemResponse,
  MeetingPageResponse,
  MeetingResponse,
  MemberAgendaResponse,
  MemberResponse,
  PublishPreviewResponse,
  SubmissionResponse,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'
import { moveBody } from './draft'

const API = `${API_ORIGIN}/api/v1`
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

/** BRC on Saturday 12 December 2026, 10:00–13:00 in Dhaka (04:00–07:00 UTC). */
const meeting: MeetingResponse = {
  id: 'm1',
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
  mode: 'HYBRID',
  location: 'Board Room',
  joinUrl: 'https://meet.example/abc',
  joinDetails: null,
  classificationId: 'l1',
  number: null,
  label: null,
  status: 'SCHEDULED',
  statusReason: null,
  relation: 'OFFICER',
  canManage: true,
  guests: [{ userId: 'u7', name: 'Chief Financial Officer', addedAt: '2026-11-01T00:00:00Z' }],
  version: 3,
}

const levels = [
  { id: 'l0', name: 'Internal', rank: 0, version: 0 },
  { id: 'l1', name: 'Confidential', rank: 1, version: 0 },
  { id: 'l2', name: 'Strictly confidential', rank: 2, version: 0 },
]

const members: MemberResponse[] = [
  {
    id: 's1',
    kind: 'PERSON',
    person: { id: 'u1', name: 'S. Ali' },
    role: 'CHAIR',
    state: 'IN_OFFICE',
    startsOn: '2026-01-01',
    termNumber: 1,
    version: 0,
    voting: true,
    attributes: [],
  },
  {
    id: 's2',
    kind: 'PERSON',
    person: { id: 'u2', name: 'Demo Director' },
    role: 'MEMBER',
    state: 'IN_OFFICE',
    startsOn: '2026-01-01',
    termNumber: 1,
    version: 0,
    voting: true,
    attributes: [],
  },
  {
    id: 's3',
    kind: 'PERSON',
    person: { id: 'u3', name: 'R. Khan' },
    role: 'MEMBER',
    state: 'IN_OFFICE',
    startsOn: '2026-01-01',
    termNumber: 1,
    version: 0,
    voting: true,
    attributes: [],
  },
]

const item = (over: Partial<AgendaItemResponse> & Pick<AgendaItemResponse, 'id' | 'title'>): AgendaItemResponse => ({
  kind: 'ITEM',
  late: 'NONE',
  purpose: 'INFORMATION',
  presenters: [],
  attendees: [],
  titleHidden: false,
  classificationId: 'l1',
  papers: [],
  outOfPack: [],
  subItems: [],
  number: null,
  minutes: null,
  ...over,
})

const welcome = item({
  id: 'i1',
  title: 'Welcome and apologies',
  kind: 'STANDING',
  standingKind: 'APOLOGIES',
  number: '1',
  minutes: 5,
  localStartsAt: '2026-12-12T10:00:00',
})
const declarations = item({
  id: 'i2',
  title: 'Declarations of interest',
  kind: 'STANDING',
  standingKind: 'DECLARATIONS',
  number: '2',
  minutes: 5,
  localStartsAt: '2026-12-12T10:05:00',
})
const termLoan = item({
  id: 'i3',
  title: 'Term loan, Delta Textiles',
  kind: 'SUBMISSION',
  submissionId: 'sub1',
  memoId: 'memo1',
  memoReference: 'CR/HO-CR/2026-27/0042',
  purpose: 'DECISION',
  number: '3',
  minutes: 30,
  localStartsAt: '2026-12-12T10:10:00',
  presenters: [{ userId: 'u3', name: 'R. Khan' }],
  attendees: [{ id: 'u3', name: 'R. Khan' }],
  resolutionSought: 'To approve a term loan',
  papers: [
    {
      id: 'p1',
      source: 'MEMO_DOCUMENT',
      fileId: 'file1',
      title: 'Paper: CR/HO-CR/2026-27/0042',
      classificationId: 'l1',
      late: 'NONE',
      pages: 9,
    },
    {
      id: 'p2',
      source: 'MEMO_ATTACHMENT',
      fileId: 'file2',
      title: 'Security valuation report',
      classificationId: 'l1',
      late: 'NONE',
      pages: 5,
    },
  ],
  outOfPack: [{ fileId: 'file3', source: 'MEMO_ATTACHMENT', title: 'Customer KYC file', classificationId: 'l1' }],
})
const marketRisk = item({
  id: 'i4',
  title: 'Market risk limits',
  purpose: 'DECISION',
  number: '4',
  minutes: 20,
  localStartsAt: '2026-12-12T10:40:00',
  restrictedTo: [
    { id: 'u1', name: 'S. Ali' },
    { id: 'u2', name: 'Demo Director' },
  ],
  titleHidden: true,
  subItems: [item({ id: 'i5', title: 'VaR back-testing', number: '4.1' })],
})
const compliance = item({ id: 'i6', title: 'Quarterly compliance report', number: '5' })

function draftOf(over: Partial<AgendaDraftResponse> = {}): AgendaDraftResponse {
  return {
    meetingId: 'm1',
    started: true,
    origin: 'TEMPLATE',
    currentRevision: 1,
    dirty: true,
    changes: 'ANY',
    canEdit: true,
    canAgree: false,
    chairAgreementRequired: true,
    chairAgreement: {
      by: { id: 'u1', name: 'S. Ali' },
      at: '2026-12-04T08:00:00Z',
      onBehalfBy: { id: 'u9', name: 'Company Secretary' },
      note: 'By email on 4 Dec',
    },
    chairComments: [],
    startsAt: meeting.startsAt,
    endsAt: meeting.endsAt,
    timeZone: 'Asia/Dhaka',
    paperDeadline: '2026-12-05',
    deadlinePassed: false,
    lateMode: 'CHAIR_APPROVAL',
    totalMinutes: 60,
    plannedEndsAt: '2026-12-12T05:00:00Z',
    overrun: false,
    traySize: 1,
    state: 'PUBLISHED',
    revisions: [
      {
        number: 1,
        publishedAt: '2026-12-04T10:00:00Z',
        notified: true,
        publishedBy: { id: 'u9', name: 'Company Secretary' },
      },
    ],
    sections: [
      {
        id: 'sOpen',
        title: 'Opening',
        kind: 'STANDARD',
        membersOnly: false,
        receivesSubmissions: false,
        minutes: 10,
        items: [welcome, declarations],
      },
      {
        id: 'sDecide',
        title: 'For decision',
        kind: 'STANDARD',
        membersOnly: false,
        receivesSubmissions: true,
        minutes: 50,
        items: [termLoan, marketRisk],
      },
      {
        id: 'sConsent',
        title: 'Consent items',
        kind: 'CONSENT',
        membersOnly: false,
        receivesSubmissions: false,
        minutes: 0,
        items: [compliance],
      },
    ],
    version: 7,
    ...over,
  }
}

const ready: SubmissionResponse = {
  id: 'sub9',
  forumId: 'f1',
  forumName: 'Board Risk Committee',
  status: 'ACCEPTED',
  purpose: 'DECISION',
  subjectId: 'memo9',
  subjectType: 'memo',
  memo: { id: 'memo9', reference: 'CR/HO-CR/2026-27/0051', subject: 'Working capital, Orion' },
  presenter: { id: 'u3', name: 'R. Khan' },
  submittedAt: '2026-11-10T00:00:00Z',
  submittedBy: { id: 'u5', name: 'Credit Officer' },
  triagedAt: '2026-11-18T00:00:00Z',
  version: 2,
  waits: false,
}

interface Call {
  method: string
  path: string
  body: unknown
  ifMatch: string | null
  key: string | null
}

interface Options {
  draft?: AgendaDraftResponse
  /** The answer to a change: the draft, or a problem with its status. */
  answer?: (call: Call) => AgendaDraftResponse | { status: number; problem: object }
  preview?: Partial<PublishPreviewResponse>
}

function setup(options: Options = {}) {
  const calls: Call[] = []
  const state = { draft: options.draft ?? draftOf(), draftReads: 0 }
  const record = async (request: Request): Promise<Call> => {
    const url = new URL(request.url)
    const text = await request.text()
    const call: Call = {
      method: request.method,
      path: url.pathname.replace('/api/v1', ''),
      body: text ? (JSON.parse(text) as unknown) : undefined,
      ifMatch: request.headers.get('If-Match'),
      key: request.headers.get('Idempotency-Key'),
    }
    calls.push(call)
    return call
  }
  const change = async ({ request }: { request: Request }) => {
    const call = await record(request)
    const answer = options.answer?.(call) ?? { ...state.draft, version: state.draft.version + 1 }
    if ('problem' in answer) return HttpResponse.json(answer.problem, { status: answer.status })
    state.draft = answer
    return HttpResponse.json<AgendaDraftResponse>(answer)
  }
  server.use(
    sessionHandler(signedInSession(false)),
    accessHandler([]),
    csrfHandler(),
    http.get(`${API}/meetings/:id`, () => HttpResponse.json<MeetingResponse>(meeting)),
    http.get(`${API}/meetings/:id/agenda/draft`, () => {
      state.draftReads += 1
      return HttpResponse.json<AgendaDraftResponse>(state.draft)
    }),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json(levels)),
    http.get(`${API}/forums/:id/members`, () => HttpResponse.json<MemberResponse[]>(members)),
    http.get(`${API}/forums/:id/submissions/ready`, () => HttpResponse.json<SubmissionResponse[]>([ready])),
    http.get(`${API}/forums/:id/meetings`, () =>
      HttpResponse.json<MeetingPageResponse>({
        items: [
          {
            ...meeting,
            id: 'm0',
            label: 'BRC 2/2026',
            startsAt: '2026-10-10T04:00:00Z',
            endsAt: '2026-10-10T07:00:00Z',
            status: 'HELD',
          },
        ],
        nextCursor: null,
      }),
    ),
    http.get(`${API}/meetings/:id/agenda/publish-preview`, () =>
      HttpResponse.json<PublishPreviewResponse>({
        meetingId: 'm1',
        revision: 2,
        started: true,
        noteRequired: true,
        meetingNotScheduled: false,
        chairAgreementMissing: false,
        nothingToPublish: false,
        unreadablePapers: [],
        awaitingChair: [],
        changes: [
          { itemId: 'i5', title: 'VaR back-testing', number: '4.1', flags: ['ADDED'], removed: false },
          {
            itemId: 'i3',
            title: 'Term loan, Delta Textiles',
            number: '3',
            previousNumber: '4',
            flags: ['MOVED', 'PAPER_REPLACED'],
            removed: false,
          },
        ],
        version: state.draft.version,
        ...options.preview,
      }),
    ),
    http.get(`${API}/meetings/:id/agenda/preview`, ({ request }) =>
      HttpResponse.json<MemberAgendaResponse>({
        meetingId: 'm1',
        preview: true,
        revision: null,
        latestRevision: 1,
        startsAt: meeting.startsAt,
        endsAt: meeting.endsAt,
        timeZone: 'Asia/Dhaka',
        status: 'SCHEDULED',
        sections: [
          {
            id: 'sDecide',
            title: 'For decision',
            kind: 'STANDARD',
            membersOnly: false,
            items: [
              {
                id: 'i4',
                access: new URL(request.url).searchParams.get('as') === 'u3' ? 'HIDDEN' : 'FULL',
                number: '4',
                title: new URL(request.url).searchParams.get('as') === 'u3' ? null : 'Market risk limits',
                kind: 'ITEM',
                late: 'NONE',
                changes: [],
                papers: [],
                presenters: [],
                subItems: [],
                links: [],
              },
            ],
          },
        ],
      }),
    ),
    http.post(`${API}/meetings/:id/agenda`, change),
    http.post(`${API}/meetings/:id/agenda/*`, change),
    http.patch(`${API}/meetings/:id/agenda/*`, change),
  )
  return { calls, state }
}

const last = (calls: Call[]) => calls.at(-1)

describe('the agenda builder (Phase 4 E03 T7, flow O)', () => {
  it('starts from the committee’s standard agenda or a copy of an earlier meeting', async () => {
    const user = userEvent.setup()
    const { calls } = setup({
      draft: draftOf({
        started: false,
        sections: [],
        currentRevision: 0,
        state: 'DRAFT',
        revisions: [],
        chairAgreement: null,
        template: {
          chairAgreement: true,
          sections: [
            { title: 'Opening', items: [{ title: 'Welcome and apologies' }] },
            { title: 'For decision', receivesSubmissions: true },
          ],
        },
      }),
      answer: () => draftOf({ currentRevision: 0, revisions: [], state: 'DRAFT' }),
    })
    renderRoute('/meetings/m1/agenda')

    expect(
      await screen.findByRole('group', { name: 'How would you like to start?' }, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.getByText('Opening · Welcome and apologies · For decision')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: /Copy an earlier meeting/ }))
    expect(screen.getByRole('combobox', { name: 'Meeting to copy' })).toHaveDisplayValue(/BRC 2\/2026/)
    await user.click(screen.getByRole('radio', { name: /standard agenda/ }))
    await user.click(screen.getByRole('button', { name: 'Start' }))

    await waitFor(() => {
      expect(last(calls)).toMatchObject({ method: 'POST', path: '/meetings/m1/agenda', body: { from: 'TEMPLATE' } })
    })
    expect(last(calls)?.key).toMatch(UUID)
    expect(await screen.findByRole('heading', { name: 'For decision' })).toBeInTheDocument()
    expect(partsForPath('/meetings/m1/agenda')).toEqual([
      'meetings',
      'agendaBuilder',
      'meetingPage',
      'meetingReplies',
      'meetingAttendance',
      'declarations',
      'actions',
      'actionLinks',
      'decisions',
      'actionsOfficer',
    ])
    expect(partsForPath('/meetings/m1')).toEqual([
      'meetings',
      'meetingPage',
      'meetingReplies',
      'meetingAttendance',
      'declarations',
      'actions',
      'actionLinks',
      'decisions',
      'actionsOfficer',
    ])
    expect(partsForPath('/admin/committee-types/t1')).toContain('agendaBuilder')
  })

  it('copies an earlier meeting', async () => {
    const user = userEvent.setup()
    const { calls } = setup({ draft: draftOf({ started: false, sections: [], template: { sections: [] } }) })
    renderRoute('/meetings/m1/agenda')
    await user.click(await screen.findByRole('radio', { name: /Copy an earlier meeting/ }, { timeout: 5000 }))
    await user.click(screen.getByRole('button', { name: 'Start' }))
    await waitFor(() => {
      expect(last(calls)?.body).toEqual({ from: 'MEETING', meetingId: 'm0' })
    })
  })

  it('shows numbers, times, lateness, restrictions, the deadline, the chair’s agreement and the total', async () => {
    setup()
    const { container } = renderRoute('/meetings/m1/agenda')

    const decide = await screen.findByRole('list', { name: 'For decision' }, { timeout: 5000 })
    const loan = within(decide).getByText('Term loan, Delta Textiles').closest('li') as HTMLElement
    expect(loan).toHaveTextContent('3')
    expect(loan).toHaveTextContent('For decision')
    expect(loan).toHaveTextContent('Presenter: R. Khan')
    expect(loan).toHaveTextContent('2 papers (14 pages)')
    expect(loan).toHaveTextContent('CR/HO-CR/2026-27/0042')
    const risk = within(decide).getByText('Market risk limits').closest('li') as HTMLElement
    expect(risk).toHaveTextContent('Only 2 people can see this · title hidden from others')
    expect(within(risk).getByRole('list', { name: 'Sub-items of Market risk limits' })).toHaveTextContent('4.1')
    expect(screen.getByText('Papers due Dec 5, 2026')).toBeInTheDocument()
    expect(
      screen.getByText(/Chair’s agreement: S\. Ali, .* \(recorded by Company Secretary: By email on 4 Dec\)/),
    ).toBeInTheDocument()
    expect(screen.getByText(/Total 1 hr · ends 11:00 AM · planned end 1:00 PM/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Publish revision 2' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Drag to move: Term loan, Delta Textiles' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Ready for an agenda (1)' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('says when the agenda runs past the planned end', async () => {
    setup({ draft: draftOf({ overrun: true, totalMinutes: 200, plannedEndsAt: '2026-12-12T07:20:00Z' }) })
    renderRoute('/meetings/m1/agenda')
    expect(
      await screen.findByText(/ends 1:20 PM, 20 minutes after the planned end/, {}, { timeout: 5000 }),
    ).toBeInTheDocument()
  })

  it('adds a section, an item and a sub-item with If-Match and an Idempotency-Key', async () => {
    const user = userEvent.setup()
    const { calls } = setup()
    renderRoute('/meetings/m1/agenda')

    await user.click(await screen.findByRole('button', { name: 'Add section' }, { timeout: 5000 }))
    let dialog = await screen.findByRole('dialog', { name: 'Add section' })
    await user.type(within(dialog).getByRole('textbox', { name: /Section title/ }), 'For noting')
    await user.click(within(dialog).getByRole('button', { name: 'Add section' }))
    await waitFor(() => {
      expect(last(calls)).toMatchObject({
        path: '/meetings/m1/agenda/sections',
        body: { title: 'For noting', kind: 'STANDARD', membersOnly: false, receivesSubmissions: false },
        ifMatch: '"7"',
      })
    })
    expect(last(calls)?.key).toMatch(UUID)

    await user.click(screen.getByRole('button', { name: 'Add item' }))
    dialog = await screen.findByRole('dialog', { name: 'Add item' })
    await user.type(within(dialog).getByRole('textbox', { name: /Title on the agenda/ }), 'Liquidity stress results')
    await user.selectOptions(within(dialog).getByRole('combobox', { name: /Purpose/ }), 'DECISION')
    await user.type(within(dialog).getByRole('spinbutton', { name: /Time in minutes/ }), '15')
    await user.click(within(dialog).getByRole('button', { name: 'Add' }))
    await waitFor(() => {
      expect(last(calls)).toMatchObject({
        path: '/meetings/m1/agenda/items',
        body: {
          kind: 'ITEM',
          title: 'Liquidity stress results',
          sectionId: 'sDecide',
          purpose: 'DECISION',
          minutes: 15,
        },
        ifMatch: '"8"',
      })
    })

    await user.click(screen.getByRole('button', { name: 'Actions for Term loan, Delta Textiles' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Add a sub-item…' }))
    dialog = await screen.findByRole('dialog', { name: 'Add a sub-item to Term loan, Delta Textiles' })
    expect(within(dialog).getByText('A sub-item has no time of its own.')).toBeInTheDocument()
    await user.type(within(dialog).getByRole('textbox', { name: /Title on the agenda/ }), 'Security cover')
    await user.click(within(dialog).getByRole('button', { name: 'Add' }))
    await waitFor(() => {
      expect(last(calls)?.body).toMatchObject({ kind: 'ITEM', parentId: 'i3', sectionId: null, minutes: null })
    })
  }, 30_000)

  it('moves items with the Move menu, says where they went and keeps focus on the row', async () => {
    const user = userEvent.setup()
    const { calls } = setup({
      answer: (call) => {
        const body = call.body as { itemId: string }
        if (body.itemId === 'i1') {
          return draftOf({
            version: 8,
            sections: draftOf().sections.map((s) =>
              s.id === 'sOpen'
                ? {
                    ...s,
                    items: [
                      { ...declarations, number: '1' },
                      { ...welcome, number: '2' },
                    ],
                  }
                : s,
            ),
          })
        }
        return draftOf({ version: 8 })
      },
    })
    renderRoute('/meetings/m1/agenda')

    await user.click(
      await screen.findByRole('button', { name: 'Actions for Welcome and apologies' }, { timeout: 5000 }),
    )
    expect(screen.getByRole('menuitem', { name: 'Move up' })).toHaveAttribute('aria-disabled', 'true')
    await user.click(screen.getByRole('menuitem', { name: 'Move down' }))
    await waitFor(() => {
      expect(last(calls)).toMatchObject({
        path: '/meetings/m1/agenda/moves',
        body: { itemId: 'i1', toSectionId: 'sOpen', toParentId: null, toPosition: 1 },
        ifMatch: '"7"',
      })
    })
    expect(
      await screen.findByText('Item 2, Welcome and apologies, moved to position 2 in Opening.'),
    ).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Actions for Welcome and apologies' })).toHaveFocus()
    })

    // The last item of a section moves down into the next section.
    await user.click(screen.getByRole('button', { name: 'Actions for Market risk limits' }))
    await user.click(screen.getByRole('menuitem', { name: 'Move down' }))
    await waitFor(() => {
      expect(last(calls)?.body).toEqual({ itemId: 'i4', toSectionId: 'sConsent', toParentId: null, toPosition: 0 })
    })

    await user.click(screen.getByRole('button', { name: 'Actions for Market risk limits' }))
    expect(screen.getByRole('menuitem', { name: 'Make a sub-item of the item above' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    await user.keyboard('{Escape}')
    await user.click(screen.getByRole('button', { name: 'Actions for Quarterly compliance report' }))
    await user.click(screen.getByRole('menuitem', { name: 'Move to section…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Move Quarterly compliance report to another section' })
    await user.selectOptions(within(dialog).getByRole('combobox', { name: /Section/ }), 'sDecide')
    await user.click(within(dialog).getByRole('radio', { name: 'At the start' }))
    await user.click(within(dialog).getByRole('button', { name: 'Move' }))
    await waitFor(() => {
      expect(last(calls)?.body).toEqual({ itemId: 'i6', toSectionId: 'sDecide', toParentId: null, toPosition: 0 })
    })

    await user.click(screen.getByRole('button', { name: 'Actions for VaR back-testing' }))
    await user.click(screen.getByRole('menuitem', { name: 'Make an item again' }))
    await waitFor(() => {
      expect(last(calls)?.body).toEqual({ itemId: 'i5', toSectionId: 'sDecide', toParentId: null, toPosition: 2 })
    })

    await user.click(screen.getByRole('button', { name: 'Actions for Declarations of interest' }))
    await user.click(screen.getByRole('menuitem', { name: 'Make a sub-item of the item above' }))
    await waitFor(() => {
      expect(last(calls)?.body).toEqual({ itemId: 'i2', toSectionId: 'sOpen', toParentId: 'i1', toPosition: 0 })
    })

    await user.click(screen.getByRole('button', { name: 'Actions for Term loan, Delta Textiles' }))
    await user.click(screen.getByRole('menuitem', { name: 'Move to position…' }))
    const position = await screen.findByRole('dialog', { name: 'Move Term loan, Delta Textiles to a position' })
    const field = within(position).getByRole('spinbutton', { name: /Position/ })
    await user.clear(field)
    await user.type(field, '2')
    await user.click(within(position).getByRole('button', { name: 'Move' }))
    await waitFor(() => {
      expect(last(calls)?.body).toEqual({ itemId: 'i3', toSectionId: 'sDecide', toParentId: null, toPosition: 1 })
    })
  }, 30_000)

  it('reloads the draft and says so when someone else changed it (412), and says a refusal in words', async () => {
    const user = userEvent.setup()
    let refusal: { status: number; problem: object } = {
      status: 412,
      problem: { status: 412, title: 'Precondition failed' },
    }
    const { state } = setup({ answer: () => refusal })
    renderRoute('/meetings/m1/agenda')

    await user.click(
      await screen.findByRole('button', { name: 'Actions for Welcome and apologies' }, { timeout: 5000 }),
    )
    const reads = state.draftReads
    await user.click(screen.getByRole('menuitem', { name: 'Move down' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Someone changed the agenda after you opened it.')
    await waitFor(() => {
      expect(state.draftReads).toBeGreaterThan(reads)
    })

    refusal = { status: 409, problem: { status: 409, reason: 'ONE_LEVEL', title: 'Conflict' } }
    await user.click(screen.getByRole('button', { name: 'Actions for Declarations of interest' }))
    await user.click(screen.getByRole('menuitem', { name: 'Make a sub-item of the item above' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Items have one level of sub-items')
  })

  it('places a paper from the tray on time, and explains the late rule first once papers are late', async () => {
    const user = userEvent.setup()
    const { calls } = setup()
    renderRoute('/meetings/m1/agenda')

    await user.click(
      await screen.findByRole(
        'button',
        { name: 'Add to agenda: CR/HO-CR/2026-27/0051 Working capital, Orion' },
        { timeout: 5000 },
      ),
    )
    await waitFor(() => {
      expect(last(calls)).toMatchObject({
        path: '/meetings/m1/agenda/items',
        body: { kind: 'SUBMISSION', submissionId: 'sub9', sectionId: null, position: null },
        ifMatch: '"7"',
      })
    })
    expect(last(calls)?.key).toMatch(UUID)
  })

  it('sends a late paper to the chair after saying so, and refuses it in words under BLOCK', async () => {
    const user = userEvent.setup()
    const { calls } = setup({ draft: draftOf({ deadlinePassed: true, lateMode: 'CHAIR_APPROVAL' }) })
    renderRoute('/meetings/m1/agenda')

    await user.click(await screen.findByRole('button', { name: /^Add to agenda: / }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: /Add CR\/HO-CR\/2026-27\/0051/ })
    expect(dialog).toHaveTextContent('This goes to the chair for agreement first')
    const before = calls.length
    await user.click(within(dialog).getByRole('button', { name: 'Add to agenda' }))
    await waitFor(() => {
      expect(calls.length).toBe(before + 1)
    })
    expect(last(calls)?.body).toMatchObject({ kind: 'SUBMISSION', submissionId: 'sub9' })
  })

  it('does not place a late paper when the committee does not take them', async () => {
    const user = userEvent.setup()
    const { calls } = setup({ draft: draftOf({ deadlinePassed: true, lateMode: 'BLOCK' }) })
    renderRoute('/meetings/m1/agenda')
    await user.click(await screen.findByRole('button', { name: /^Add to agenda: / }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: /Add CR\/HO-CR\/2026-27\/0051/ })
    expect(dialog).toHaveTextContent('Late papers are not accepted for this committee')
    const closes = within(dialog).getAllByRole('button', { name: 'Close' })
    await user.click(closes[closes.length - 1]!)
    expect(calls.filter((c) => c.method !== 'GET')).toEqual([])
  })

  it('edits an item’s details, presenters and who can see it, and its papers, in the side sheet', async () => {
    const user = userEvent.setup()
    const { calls } = setup()
    renderRoute('/meetings/m1/agenda')

    await user.click(
      await screen.findByRole('button', { name: 'Actions for Term loan, Delta Textiles' }, { timeout: 5000 }),
    )
    await user.click(screen.getByRole('menuitem', { name: 'Edit…' }))
    const sheet = await screen.findByRole('dialog', { name: 'Item 3 · Term loan, Delta Textiles' })
    expect(within(sheet).getByText('From the queue: CR/HO-CR/2026-27/0042')).toBeInTheDocument()
    const minutes = within(sheet).getByRole('spinbutton', { name: /Time in minutes/ })
    await user.clear(minutes)
    await user.type(minutes, '25')
    await user.type(within(sheet).getByRole('textbox', { name: 'Or type a name' }), 'Head of Credit')
    await user.click(within(sheet).getByRole('button', { name: 'Add the name' }))
    await user.click(within(sheet).getByRole('button', { name: 'Save' }))
    await waitFor(() => {
      expect(last(calls)).toMatchObject({
        method: 'PATCH',
        path: '/meetings/m1/agenda/items/i3',
        ifMatch: '"7"',
        body: {
          title: 'Term loan, Delta Textiles',
          purpose: 'DECISION',
          minutes: 25,
          resolutionSought: 'To approve a term loan',
          presenters: [{ userId: 'u3' }, { name: 'Head of Credit' }],
          attendees: ['u3'],
          restrictedTo: null,
          titleHidden: false,
          classificationId: 'l1',
        },
      })
    })

    await user.click(within(sheet).getByRole('tab', { name: 'Who can see this' }))
    await user.click(within(sheet).getByRole('radio', { name: 'Only these people' }))
    await user.click(within(sheet).getByRole('button', { name: 'Save' }))
    expect(within(sheet).getByText('Choose at least one person, or let everyone see it.')).toBeInTheDocument()
    await user.click(within(sheet).getByRole('checkbox', { name: 'S. Ali' }))
    await user.click(within(sheet).getByRole('checkbox', { name: 'Chief Financial Officer' }))
    await user.click(within(sheet).getByRole('checkbox', { name: /Hide the title/ }))
    await user.selectOptions(within(sheet).getByRole('combobox', { name: 'Classification' }), 'l2')
    expect(within(sheet).getByRole('combobox', { name: 'Classification' })).not.toHaveTextContent('Internal')
    await user.click(within(sheet).getByRole('button', { name: 'Save' }))
    await waitFor(() => {
      expect(last(calls)?.body).toMatchObject({
        restrictedTo: ['u1', 'u7'],
        titleHidden: true,
        classificationId: 'l2',
        minutes: 30,
      })
    })

    await user.click(within(sheet).getByRole('tab', { name: 'Papers' }))
    const papers = within(sheet).getByRole('list', { name: 'Papers' })
    expect(papers).toHaveTextContent('Paper: CR/HO-CR/2026-27/0042')
    expect(papers).toHaveTextContent('memo document · 9 pages')
    await user.click(within(sheet).getByRole('button', { name: 'Put back in the pack' }))
    await waitFor(() => {
      expect(last(calls)).toMatchObject({ path: '/meetings/m1/agenda/items/i3/papers', body: { fileId: 'file3' } })
    })
    await user.click(within(sheet).getByRole('button', { name: 'Actions for the paper Security valuation report' }))
    await user.click(screen.getByRole('menuitem', { name: 'Remove from the pack' }))
    const confirm = await screen.findByRole('alertdialog', { name: 'Remove Security valuation report from the pack?' })
    await user.click(within(confirm).getByRole('button', { name: 'Remove from the pack' }))
    await waitFor(() => {
      expect(last(calls)).toMatchObject({
        path: '/meetings/m1/agenda/papers/p2/remove',
        ifMatch: expect.stringMatching(/^"\d+"$/) as unknown,
      })
    })
  }, 30_000)

  it('publishes with the change summary and a note from revision 2', async () => {
    const user = userEvent.setup()
    const { calls } = setup({ answer: () => draftOf({ currentRevision: 2, dirty: false, version: 8 }) })
    renderRoute('/meetings/m1/agenda')

    await user.click(await screen.findByRole('button', { name: 'Publish revision 2' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Publish revision 2 of the agenda' })
    const changes = await within(dialog).findByRole('list')
    expect(changes).toHaveTextContent('Added: Item 4.1 VaR back-testing')
    expect(changes).toHaveTextContent('Moved, Paper replaced: Item 3 Term loan, Delta Textiles (was 4)')
    expect(within(dialog).getByRole('checkbox', { name: /Tell the members/ })).toBeChecked()

    await user.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(within(dialog).getByText('Say in a few words what changed; the members read it.')).toBeInTheDocument()
    expect(calls.filter((c) => c.path.endsWith('/publish'))).toEqual([])

    await user.type(within(dialog).getByRole('textbox', { name: /Note to members/ }), 'Updated credit paper')
    await user.click(within(dialog).getByRole('checkbox', { name: /Tell the members/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Publish' }))
    await waitFor(() => {
      expect(last(calls)).toMatchObject({
        path: '/meetings/m1/agenda/publish',
        body: { note: 'Updated credit paper', notify: false },
        ifMatch: '"7"',
      })
    })
    expect(last(calls)?.key).toMatch(UUID)
    expect(await screen.findAllByText('Revision 2 of the agenda is published.')).not.toHaveLength(0)
  })

  it('says what stops a publication, and the server’s refusals, in words', async () => {
    const user = userEvent.setup()
    setup({
      preview: { nothingToPublish: true, unreadablePapers: ['p2'], noteRequired: false },
      answer: () => ({ status: 409, problem: { status: 409, reason: 'CHAIR_AGREEMENT_REQUIRED' } }),
    })
    renderRoute('/meetings/m1/agenda')
    await user.click(await screen.findByRole('button', { name: 'Publish revision 2' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Publish revision 2 of the agenda' })
    expect(
      await within(dialog).findByText(
        'A paper cannot be shown as a PDF: Security valuation report. Replace or remove it, then publish.',
      ),
    ).toBeInTheDocument()
    expect(
      within(dialog).getByText('Nothing changed since the last revision, so there is nothing to publish.'),
    ).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('A paper cannot be shown as a PDF')
  })

  it('previews the draft as a member would see it', async () => {
    const user = userEvent.setup()
    setup()
    renderRoute('/meetings/m1/agenda')
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Preview as' }, { timeout: 5000 }), 'u3')
    await user.click(screen.getByRole('button', { name: 'Preview' }))
    const dialog = await screen.findByRole('dialog', { name: 'Preview as R. Khan' })
    expect(dialog).toHaveTextContent('This is what R. Khan would see if you published now.')
    expect(await within(dialog).findByText('4 Restricted item')).toBeInTheDocument()
  })
})

describe('focus after a removal (Phase 4 E03 T9 follow-up)', () => {
  /** The draft with the term loan's papers [keep] only, or without item [itemId]. */
  function draftWith({ keep, itemId }: { keep?: string[]; itemId?: string }) {
    const base = draftOf()
    return draftOf({
      version: 8,
      sections: base.sections.map((s) => ({
        ...s,
        items: s.items
          .filter((i) => i.id !== itemId)
          .map((i) => (i.id === 'i3' && keep ? { ...i, papers: i.papers.filter((p) => keep.includes(p.id)) } : i)),
      })),
    })
  }

  async function removePaper(user: ReturnType<typeof userEvent.setup>, title: string) {
    const sheet = await screen.findByRole('dialog', { name: 'Item 3 · Term loan, Delta Textiles' })
    await user.click(within(sheet).getByRole('button', { name: `Actions for the paper ${title}` }))
    await user.click(screen.getByRole('menuitem', { name: 'Remove from the pack' }))
    const confirm = await screen.findByRole('alertdialog', { name: `Remove ${title} from the pack?` })
    await user.click(within(confirm).getByRole('button', { name: 'Remove from the pack' }))
    return sheet
  }

  it('moves focus to the paper before when the last one is removed, and to the papers line when none is left', async () => {
    const user = userEvent.setup()
    setup({ answer: (call) => draftWith({ keep: call.path.endsWith('/p2/remove') ? ['p1'] : [] }) })
    renderRoute('/meetings/m1/agenda')
    await user.click(
      await screen.findByRole('button', { name: 'Actions for Term loan, Delta Textiles' }, { timeout: 15_000 }),
    )
    await user.click(screen.getByRole('menuitem', { name: 'Papers…' }))

    const sheet = await removePaper(user, 'Security valuation report')
    await waitFor(() => {
      expect(
        within(sheet).getByRole('button', { name: 'Actions for the paper Paper: CR/HO-CR/2026-27/0042' }),
      ).toHaveFocus()
    })

    await removePaper(user, 'Paper: CR/HO-CR/2026-27/0042')
    await waitFor(() => {
      expect(within(sheet).getByText('Papers in the order members see them.')).toHaveFocus()
    })
  }, 30_000)

  it('moves focus to the next item’s menu when an item is removed from the agenda', async () => {
    const user = userEvent.setup()
    setup({ answer: () => draftWith({ itemId: 'i2' }) })
    renderRoute('/meetings/m1/agenda')
    await user.click(
      await screen.findByRole('button', { name: 'Actions for Declarations of interest' }, { timeout: 15_000 }),
    )
    await user.click(screen.getByRole('menuitem', { name: 'Remove from agenda' }))
    const confirm = await screen.findByRole('alertdialog', { name: 'Remove Declarations of interest from the agenda?' })
    await user.click(within(confirm).getByRole('button', { name: 'Remove' }))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Actions for Term loan, Delta Textiles' })).toHaveFocus()
    })
  }, 30_000)
})

describe('the chair’s side (flow Q)', () => {
  const waiting = item({
    id: 'i8',
    title: 'Liquidity stress results',
    kind: 'SUBMISSION',
    memoReference: 'RSK/2026/0009',
    purpose: 'DECISION',
    late: 'AWAITING_CHAIR',
  })
  // `decides`: the server says the viewer decides the item's late requests (E04 §1.7: the chair, or the vice-chair when
  // the chair is recused from it).
  const firstDraft = (over: Partial<AgendaDraftResponse> = {}, decides = false) =>
    draftOf({
      currentRevision: 0,
      state: 'DRAFT',
      revisions: [],
      chairAgreement: null,
      deadlinePassed: true,
      sections: draftOf().sections.map((s) =>
        s.id === 'sDecide' ? { ...s, items: [...s.items, { ...waiting, decides }] } : s,
      ),
      ...over,
    })

  it('offers the chair no decision on a late paper the server does not say they decide', async () => {
    setup({ draft: firstDraft({ canEdit: false, canAgree: true }) })
    renderRoute('/meetings/m1/agenda')

    expect(
      await screen.findByText('You read the draft agenda. Only the secretaries change it.', {}, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Late papers: your agreement is needed' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Not this meeting' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record the chair’s answer…' })).not.toBeInTheDocument()
  })

  it('lets the chair read the draft, comment, agree it and decide a late paper', async () => {
    const user = userEvent.setup()
    const { calls } = setup({ draft: firstDraft({ canEdit: false, canAgree: true }, true) })
    renderRoute('/meetings/m1/agenda')

    expect(
      await screen.findByText('You read the draft agenda. Only the secretaries change it.', {}, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Actions for / })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Drag to move/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Publish/ })).not.toBeInTheDocument()

    await user.type(screen.getByRole('textbox', { name: /Comment to the secretary/ }), 'Move the credit item up')
    await user.click(screen.getByRole('button', { name: 'Send comments' }))
    await waitFor(() => {
      expect(last(calls)).toMatchObject({
        path: '/meetings/m1/agenda/chair-comments',
        body: { text: 'Move the credit item up' },
      })
    })
    expect(last(calls)?.key).toMatch(UUID)

    await user.click(screen.getByRole('button', { name: 'Agree the agenda' }))
    const confirm = await screen.findByRole('alertdialog', { name: 'Agree the agenda?' })
    await user.click(within(confirm).getByRole('button', { name: 'Agree the agenda' }))
    await waitFor(() => {
      expect(last(calls)).toMatchObject({
        path: '/meetings/m1/agenda/chair-agreement',
        body: {},
        ifMatch: expect.stringMatching(/^"\d+"$/) as unknown,
      })
    })

    const late = screen.getByRole('region', { name: 'Late papers: your agreement is needed' })
    expect(late).toHaveTextContent('Liquidity stress results')
    expect(late).toHaveTextContent('Papers were due on Dec 5, 2026.')
    await user.click(within(late).getByRole('button', { name: 'Not this meeting' }))
    const decline = await screen.findByRole('alertdialog', { name: 'Not this meeting: Liquidity stress results?' })
    expect(decline).toHaveTextContent('goes back to Ready for an agenda')
    await user.click(within(decline).getByRole('button', { name: 'Not this meeting' }))
    await waitFor(() => {
      expect(last(calls)).toMatchObject({
        path: '/meetings/m1/agenda/items/i8/late-decision',
        body: { agree: false, onBehalf: false },
      })
    })
  })

  it('lets a secretary record the chair’s agreement and answer, with how it was given', async () => {
    const user = userEvent.setup()
    const { calls } = setup({ draft: firstDraft() })
    renderRoute('/meetings/m1/agenda')

    expect(await screen.findByText('Waiting for the chair’s agreement', {}, { timeout: 5000 })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Record the chair’s agreement…' }))
    let dialog = await screen.findByRole('dialog', { name: 'Record the chair’s agreement' })
    await user.click(within(dialog).getByRole('button', { name: 'Record the agreement' }))
    expect(within(dialog).getByText('Say when and how the chair gave it.')).toBeInTheDocument()
    await user.type(within(dialog).getByRole('textbox', { name: /When and how/ }), 'By email on 4 Dec')
    await user.click(within(dialog).getByRole('button', { name: 'Record the agreement' }))
    await waitFor(() => {
      expect(last(calls)).toMatchObject({
        path: '/meetings/m1/agenda/chair-agreement',
        body: { onBehalf: true, note: 'By email on 4 Dec' },
        ifMatch: '"7"',
      })
    })

    const late = screen.getByRole('region', { name: 'Late papers waiting for the chair' })
    await user.click(within(late).getByRole('button', { name: 'Record the chair’s answer…' }))
    dialog = await screen.findByRole('dialog', { name: 'The chair’s answer: Liquidity stress results' })
    await user.type(within(dialog).getByRole('textbox', { name: /When and how/ }), 'In person on 9 Dec')
    await user.click(within(dialog).getByRole('button', { name: 'Record the answer' }))
    await waitFor(() => {
      expect(last(calls)).toMatchObject({
        path: '/meetings/m1/agenda/items/i8/late-decision',
        body: { agree: true, onBehalf: true, note: 'In person on 9 Dec' },
      })
    })
  })
})

describe('the Move menu’s commands', () => {
  it('only offers moves the agenda allows (ONE_LEVEL)', () => {
    const d = draftOf()
    expect(moveBody(d, 'i1', 'up')).toBeUndefined()
    expect(moveBody(d, 'i3', 'up')).toEqual({ itemId: 'i3', toSectionId: 'sOpen', toParentId: null, toPosition: 2 })
    expect(moveBody(d, 'i4', 'indent')).toBeUndefined()
    expect(moveBody(d, 'i3', 'indent')).toBeUndefined()
    expect(moveBody(d, 'i5', 'indent')).toBeUndefined()
    expect(moveBody(d, 'i5', 'down')).toBeUndefined()
    expect(moveBody(d, 'i5', 'outdent')).toEqual({
      itemId: 'i5',
      toSectionId: 'sDecide',
      toParentId: null,
      toPosition: 2,
    })
    expect(moveBody(d, 'i6', 'down')).toBeUndefined()
  })
})

describe('the agenda builder in Arabic', () => {
  it('reads right to left with every text translated', async () => {
    const user = userEvent.setup()
    setup()
    const { container } = renderRoute('/meetings/m1/agenda', { locale: 'ar' })
    expect(await screen.findByRole('heading', { name: 'For decision' }, { timeout: 5000 })).toBeInTheDocument()
    expect(document.documentElement.dir).toBe('rtl')
    expect(screen.getByRole('button', { name: 'نشر المراجعة 2' })).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('button', { name: 'إجراءات Term loan, Delta Textiles' }))
    await user.click(screen.getByRole('menuitem', { name: 'تعديل…' }))
    const sheet = await screen.findByRole('dialog', { name: /Term loan, Delta Textiles/ })
    await expectNoA11yViolations(sheet)
  }, 20_000)
})
