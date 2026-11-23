import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type {
  MeetingResponse,
  MeetingSummaryResponse,
  MemberAgendaResponse,
  MemberItemResponse,
  MemberSectionResponse,
  MyDeclarationsResponse,
  PublishedRevisionResponse,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import {
  accessHandler,
  API_ORIGIN,
  csrfHandler,
  myMeetingsHandler,
  server,
  sessionHandler,
  signedInSession,
} from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const API = `${API_ORIGIN}/api/v1`
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

// The reader is in London; the meeting is in Dhaka (flow P1: both clocks).
vi.mock('@/features/meetings/time', async (original) => ({
  ...(await original<typeof import('@/features/meetings/time')>()),
  viewerZone: () => 'Europe/London',
}))

// PDF.js does not run in jsdom: the viewer's document loads, or fails with the HTTP status the test says.
const pdf = vi.hoisted(() => ({ urls: [] as string[], status: undefined as number | undefined }))
vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: { workerPort: {} },
  getDocument: ({ url }: { url: string }) => {
    pdf.urls.push(url)
    const status = pdf.status
    return {
      promise: status
        ? Promise.reject(Object.assign(new Error('Unexpected server response'), { status }))
        : Promise.resolve({ numPages: 9, getPage: () => new Promise(() => undefined) }),
      destroy: () => Promise.resolve(),
    }
  },
}))

/** BRC 3/2026 on Saturday 12 December 2026, 10:00–13:00 in Dhaka (04:00–07:00 UTC, so 04:00–07:00 in London). */
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
  location: 'Board Room, Level 12, Head Office',
  joinUrl: 'https://meet.example/abc',
  joinDetails: 'Meeting ID 123 456',
  classificationId: 'l1',
  number: 3,
  label: 'BRC 3/2026',
  status: 'SCHEDULED',
  statusReason: null,
  relation: 'MEMBER',
  canManage: false,
  guests: [{ userId: 'u5', name: 'N. Hossain', note: 'Chief Financial Officer', addedAt: '2026-11-18T09:00:00Z' }],
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

const at = (time: string) => `2026-12-12T${time}:00Z`

const sections: MemberSectionResponse[] = [
  {
    id: 's1',
    title: 'Opening',
    kind: 'STANDARD',
    membersOnly: false,
    items: [
      item('i1', { number: '1', title: 'Welcome and apologies', purpose: 'INFORMATION', startsAt: at('04:00') }),
      item('i3', {
        number: '3',
        title: 'Minutes of the last meeting',
        purpose: 'DECISION',
        startsAt: at('04:10'),
        papers: [
          { id: 'p0', title: 'Minutes of BRC 2/2026', pages: 6, ready: true, opened: true, late: 'NONE', changes: [] },
        ],
      }),
    ],
  },
  {
    id: 's2',
    title: 'For decision',
    kind: 'STANDARD',
    membersOnly: false,
    items: [
      item('i5', {
        number: '5',
        kind: 'SUBMISSION',
        title: 'Term loan, Delta Textiles',
        purpose: 'DECISION',
        startsAt: at('04:25'),
        presenters: [{ name: 'Head of Credit' }],
        late: 'LATE',
        changes: ['PAPER_REPLACED'],
        resolutionSought: 'To approve a term loan of BDT 60 million subject to conditions.',
        papers: [
          {
            id: 'p1',
            title: 'Paper: CR/HO-CR/2026-27/0042',
            pages: 9,
            ready: true,
            opened: false,
            late: 'NONE',
            changes: ['REPLACED'],
          },
          {
            id: 'p2',
            title: 'Security valuation report',
            pages: 5,
            ready: false,
            opened: false,
            late: 'NONE',
            changes: [],
          },
        ],
      }),
      item('i6', {
        number: '6',
        access: 'TITLE',
        title: 'Market risk limits',
        purpose: 'DECISION',
        startsAt: at('04:55'),
      }),
      item('i7', { number: '7', access: 'HIDDEN', title: null, startsAt: at('05:15') }),
      item('ib', { number: null, kind: 'BREAK', title: 'Break', minutes: 10, startsAt: at('05:25') }),
    ],
  },
  {
    id: 's3',
    title: 'Consent items',
    kind: 'CONSENT',
    membersOnly: false,
    items: [
      item('i8', {
        number: '8',
        title: 'Quarterly compliance report',
        purpose: 'INFORMATION',
        startsAt: at('05:35'),
        papers: [
          { id: 'p8', title: 'Compliance report Q3', pages: 12, ready: true, opened: false, late: 'NONE', changes: [] },
        ],
        discussion: { canAsk: true, mine: false, requestedBy: [] },
      }),
      item('i9', {
        number: '9',
        title: 'Committee terms of reference',
        purpose: 'DECISION',
        startsAt: at('05:35'),
        discussion: { canAsk: false, mine: true, requestedBy: [{ id: 'u2', name: 'S. Ali' }] },
      }),
    ],
  },
  {
    id: 's4',
    title: 'Members’ private session',
    kind: 'STANDARD',
    membersOnly: true,
    items: [item('i10', { number: '10', title: 'Chief Risk Officer’s appraisal', purpose: 'DISCUSSION' })],
  },
]

const agenda: MemberAgendaResponse = {
  meetingId: 'm1',
  status: 'SCHEDULED',
  startsAt: meeting.startsAt,
  endsAt: meeting.endsAt,
  timeZone: 'Asia/Dhaka',
  revision: 2,
  latestRevision: 2,
  publishedAt: '2026-12-10T06:00:00Z',
  note: 'Updated credit paper after the valuation',
  preview: false,
  lastSeenRevision: 1,
  sections,
  whatChanged: {
    since: 1,
    count: 2,
    revisions: [
      {
        number: 2,
        publishedAt: '2026-12-10T06:00:00Z',
        note: 'Updated credit paper after the valuation',
        items: [
          {
            id: 'i5',
            number: '5',
            title: 'Term loan, Delta Textiles',
            access: 'FULL',
            removed: false,
            flags: ['PAPER_REPLACED'],
            papers: [{ id: 'p1', title: 'Paper: CR/HO-CR/2026-27/0042', removed: false, flags: ['REPLACED'] }],
          },
          { id: 'i7', number: '7', title: null, access: 'HIDDEN', removed: false, flags: ['ADDED'], papers: [] },
        ],
      },
    ],
  },
}

const revision1: MemberAgendaResponse = {
  ...agenda,
  revision: 1,
  publishedAt: '2026-12-04T06:00:00Z',
  note: null,
  lastSeenRevision: 2,
  whatChanged: null,
  sections: sections.map((s) => ({
    ...s,
    items: s.items.map((i) => ({
      ...i,
      changes: [],
      papers: i.papers.map((p) => ({ ...p, changes: [] })),
      ...(i.discussion ? { discussion: { ...i.discussion, canAsk: false } } : {}),
    })),
  })),
}

const revisions: PublishedRevisionResponse[] = [
  { number: 1, publishedAt: '2026-12-04T06:00:00Z', note: null },
  { number: 2, publishedAt: '2026-12-10T06:00:00Z', note: 'Updated credit paper after the valuation' },
]

interface Calls {
  seen: unknown[]
  agendaQueries: (string | null)[]
}

function handlers(
  calls: Calls = { seen: [], agendaQueries: [] },
  options: { meeting?: MeetingResponse; agenda?: MemberAgendaResponse } = {},
) {
  return [
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    http.get(`${API}/authz/classification-levels`, () =>
      HttpResponse.json([{ id: 'l1', name: 'Confidential', rank: 1 }]),
    ),
    http.get(`${API}/meetings/m1`, () => HttpResponse.json<MeetingResponse>(options.meeting ?? meeting)),
    http.get(`${API}/meetings/m1/agenda`, ({ request }) => {
      const asked = new URL(request.url).searchParams.get('revision')
      calls.agendaQueries.push(asked)
      return HttpResponse.json<MemberAgendaResponse>(asked === '1' ? revision1 : (options.agenda ?? agenda))
    }),
    http.get(`${API}/meetings/m1/agenda/revisions`, () => HttpResponse.json<PublishedRevisionResponse[]>(revisions)),
    http.post(`${API}/meetings/m1/seen`, async ({ request }) => {
      calls.seen.push(await request.json())
      return new HttpResponse(null, { status: 204 })
    }),
    // Declarations (E04 T6) are tested in declarations.test.tsx: here, nothing to review.
    http.get(`${API}/meetings/m1/declarations/mine`, () =>
      HttpResponse.json<MyDeclarationsResponse>({
        meetingId: 'm1',
        latestRevision: 2,
        reviewedRevision: 2,
        declarations: [],
        toReview: [],
      }),
    ),
  ]
}

/** The text of [element] with every kind of space made a plain one (the locale data uses thin and narrow spaces). */
const plain = (element: Element) => element.textContent.replace(/\s+/g, ' ')

/** The agenda item whose heading is [name]. */
const itemNamed = (name: RegExp | string) => {
  const heading = screen.getByRole('heading', { level: 4, name })
  const li = heading.closest('li')
  if (!li) throw new Error('no item')
  return li
}

describe('the meeting page (Phase 4 E03 T8, flow P)', () => {
  it('shows the agenda as the member may see it, with both clocks, and marks the revision seen', async () => {
    const calls: Calls = { seen: [], agendaQueries: [] }
    server.use(...handlers(calls))
    const { container } = renderRoute('/meetings/m1')

    const agendaRegion = await screen.findByRole('region', { name: 'Agenda' })
    expect(partsForPath('/meetings/m1')).toEqual([
      'meetings',
      'meetingPage',
      'meetingReplies',
      'meetingAttendance',
      'declarations',
      'minutes',
      'actions',
      'actionLinks',
      'decisions',
      'actionsOfficer',
    ])
    // Times: the meeting's clock and the reader's own, with the city.
    expect(screen.getByText('Saturday, December 12, 2026')).toBeInTheDocument()
    expect(plain(screen.getByText(/Dhaka time/))).toBe('10:00 AM – 1:00 PM Dhaka time')
    expect(plain(screen.getByText(/your time/))).toBe('(4:00 – 7:00 AM your time, London)')
    expect(screen.getByRole('link', { name: /Join the meeting/ })).toHaveAttribute('href', 'https://meet.example/abc')

    // Sections, purposes in words and times on the meeting's clock.
    expect(
      within(agendaRegion)
        .getAllByRole('heading', { level: 3 })
        .map((h) => h.textContent),
    ).toEqual(['Opening', 'For decision', 'Consent items', 'Members’ private session'])
    expect(plain(itemNamed('1 Welcome and apologies'))).toContain('10:00 AM · For noting')
    const loan = itemNamed('5 Term loan, Delta Textiles')
    expect(plain(loan)).toContain('10:25 AM · For decision')
    expect(loan).toHaveTextContent('Presenter: Head of Credit · Late paper')
    expect(loan).toHaveTextContent('To approve a term loan of BDT 60 million subject to conditions.')
    expect(loan).toHaveTextContent('Changed: paper replaced')
    const papers = within(loan).getByRole('list', { name: 'Papers for 5 Term loan, Delta Textiles' })
    expect(within(papers).getByRole('button', { name: 'Paper: CR/HO-CR/2026-27/0042 · 9 pages' })).toBeInTheDocument()
    expect(papers).toHaveTextContent('Changed: replaced with a new version')
    expect(papers).toHaveTextContent('Being prepared: it can be read shortly.')
    // FULL, TITLE and HIDDEN lines (design §1.9).
    expect(itemNamed('6 Market risk limits — papers restricted')).not.toHaveTextContent('For decision ·')
    // A title-only line has no papers; the reader sees its title, so may declare an interest (E04 T6).
    expect(
      within(itemNamed('6 Market risk limits — papers restricted'))
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Declare an interest'])
    expect(itemNamed('7 Restricted item')).toBeInTheDocument()
    expect(plain(itemNamed('Break'))).toContain('11:25 AM · 10 min')
    // "Opened" ticks.
    expect(itemNamed('3 Minutes of the last meeting')).toHaveTextContent('Opened')
    expect(loan).not.toHaveTextContent('Opened')
    // Consent and members-only sections.
    const consent = screen.getByRole('region', { name: 'Consent items' })
    expect(consent).toHaveTextContent('Taken together without discussion')
    expect(
      within(consent).getByRole('button', { name: 'Ask to discuss this item: 8 Quarterly compliance report' }),
    ).toBeInTheDocument()
    expect(itemNamed('9 Committee terms of reference')).toHaveTextContent(
      'Lifted for discussion at the request of S. Ali.',
    )
    expect(screen.getByRole('region', { name: 'Members’ private session' })).toHaveTextContent('Members only')
    // Members do not see the guests panel; officers do.
    expect(screen.queryByRole('region', { name: 'Guests' })).toBeNull()

    await waitFor(() => {
      expect(calls.seen).toEqual([{ revision: 2 }])
    })
    expect(calls.agendaQueries).toEqual([null])
    await expectNoA11yViolations(container)
  }, 20_000)

  it('says what changed since the reader last looked (P2)', async () => {
    const user = userEvent.setup()
    server.use(...handlers())
    renderRoute('/meetings/m1')

    expect(
      await screen.findByText(
        'The agenda changed on December 10, 2026 (revision 2). “Updated credit paper after the valuation”',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('2 changes since you last looked')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Show changes' }))
    const dialog = await screen.findByRole('dialog', { name: 'Changes since you last looked' })
    expect(within(dialog).getByRole('heading', { name: 'Revision 2, December 10, 2026' })).toBeInTheDocument()
    expect(dialog).toHaveTextContent('“Updated credit paper after the valuation”')
    const lines = within(dialog)
      .getAllByRole('listitem')
      .map((li) => li.firstChild?.textContent)
    expect(lines).toEqual([
      '5 Term loan, Delta Textiles: paper replaced',
      'Paper: CR/HO-CR/2026-27/0042: replaced with a new version',
      '7 Restricted item: added',
    ])
    // The dialog's own Close at its foot (the × in its title bar is also named Close).
    const close = within(dialog).getAllByRole('button', { name: 'Close' }).at(-1)
    if (!close) throw new Error('no Close')
    await user.click(close)
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull()
    })
    expect(screen.getByRole('button', { name: 'Show changes' })).toHaveFocus()
  }, 20_000)

  it('opens a paper in the PDF viewer and ticks it as opened', async () => {
    const user = userEvent.setup()
    pdf.urls = []
    pdf.status = undefined
    server.use(...handlers())
    const { router } = renderRoute('/meetings/m1')

    const paper = await screen.findByRole('button', { name: 'Paper: CR/HO-CR/2026-27/0042 · 9 pages' })
    await user.click(paper)
    const viewer = await screen.findByRole('dialog', { name: 'Paper: CR/HO-CR/2026-27/0042' })
    // The open paper has its own address, pushed onto the history.
    expect(router.state.location.search).toEqual({ paper: 'p1', revision: 2 })
    expect(router.history.length).toBe(2)
    expect(within(viewer).getByRole('button', { name: 'Back to the agenda' })).toBeInTheDocument()
    expect(await within(viewer).findByRole('img', { name: 'Paper: CR/HO-CR/2026-27/0042' })).toBeInTheDocument()
    expect(within(viewer).queryByRole('link', { name: /download/i })).toBeNull()
    expect(pdf.urls).toEqual(['/api/v1/meetings/m1/papers/p1/content?revision=2&kind=PDF'])

    await user.click(within(viewer).getByRole('button', { name: 'Back to the agenda' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull()
    })
    expect(paper).toHaveFocus()
    expect(router.state.location.search).toEqual({})
    expect(router.history.location.pathname).toBe('/meetings/m1')
    expect(itemNamed('5 Term loan, Delta Textiles')).toHaveTextContent('Opened')
  }, 20_000)

  it('closes the paper with the browser’s Back and gives focus back to its button', async () => {
    const user = userEvent.setup()
    pdf.urls = []
    pdf.status = undefined
    server.use(...handlers())
    const { router } = renderRoute('/meetings/m1')

    const paper = await screen.findByRole('button', { name: 'Compliance report Q3 · 12 pages' })
    await user.click(paper)
    await screen.findByRole('dialog', { name: 'Compliance report Q3' })
    expect(router.state.location.search).toEqual({ paper: 'p8', revision: 2 })

    router.history.back()
    await waitFor(() => {
      expect(router.state.location.search).toEqual({})
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull()
    })
    expect(paper).toHaveFocus()
    expect(screen.getByRole('region', { name: 'Agenda' })).toBeInTheDocument()

    // Escape closes it too, back to the same address.
    await user.click(paper)
    await screen.findByRole('dialog', { name: 'Compliance report Q3' })
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(router.state.location.search).toEqual({})
    })
    expect(paper).toHaveFocus()
  }, 20_000)

  it('opens the paper of a pasted link, and closing it stays on the meeting page', async () => {
    const user = userEvent.setup()
    pdf.urls = []
    pdf.status = undefined
    server.use(...handlers())
    const { router } = renderRoute('/meetings/m1?paper=p1&revision=2')

    const viewer = await screen.findByRole('dialog', { name: 'Paper: CR/HO-CR/2026-27/0042' })
    await within(viewer).findByRole('img', { name: 'Paper: CR/HO-CR/2026-27/0042' })
    expect(pdf.urls).toEqual(['/api/v1/meetings/m1/papers/p1/content?revision=2&kind=PDF'])

    await user.click(within(viewer).getByRole('button', { name: 'Back to the agenda' }))
    await waitFor(() => {
      expect(router.state.location.search).toEqual({})
    })
    expect(router.history.location.pathname).toBe('/meetings/m1')
    expect(await screen.findByRole('button', { name: 'Paper: CR/HO-CR/2026-27/0042 · 9 pages' })).toHaveFocus()
  }, 20_000)

  it('shows the agenda with a short message for a paper it does not carry', async () => {
    pdf.urls = []
    server.use(...handlers())
    renderRoute('/meetings/m1?paper=p-unknown')

    expect(await screen.findByText('That paper is not on this agenda, or it is not shown to you.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(itemNamed('5 Term loan, Delta Textiles')).toBeInTheDocument()
    expect(pdf.urls).toEqual([])
  }, 20_000)

  it('says in words when a paper’s PDF is still being prepared (409 PAPER_NOT_READY)', async () => {
    const user = userEvent.setup()
    pdf.urls = []
    pdf.status = 409
    server.use(...handlers())
    renderRoute('/meetings/m1')

    await user.click(await screen.findByRole('button', { name: 'Security valuation report · 5 pages' }))
    const viewer = await screen.findByRole('dialog', { name: 'Security valuation report' })
    expect(
      await within(viewer).findByText('This paper is still being prepared. Try again in a few minutes.'),
    ).toBeInTheDocument()
    expect(pdf.urls).toEqual(['/api/v1/meetings/m1/papers/p2/content?revision=2&kind=PDF'])
    expect(itemNamed('5 Term loan, Delta Textiles')).not.toHaveTextContent('Opened')
    pdf.status = undefined
  }, 20_000)

  it('asks to discuss a consent item with an Idempotency-Key, and withdraws a request', async () => {
    const user = userEvent.setup()
    const keys: (string | null)[] = []
    const withdrawn: string[] = []
    let refuse = true
    const asked: MemberAgendaResponse = {
      ...agenda,
      lastSeenRevision: 2,
      whatChanged: null,
      sections: sections.map((s) =>
        s.id === 's3'
          ? {
              ...s,
              items: s.items.map((i) =>
                i.id === 'i8'
                  ? {
                      ...i,
                      discussion: { canAsk: false, mine: true, requestedBy: [{ id: 'u1', name: 'Demo Director' }] },
                    }
                  : i,
              ),
            }
          : s,
      ),
    }
    server.use(
      ...handlers(),
      http.post(`${API}/meetings/m1/agenda/items/:itemId/discussion-requests`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        if (refuse) {
          refuse = false
          return HttpResponse.json({ status: 503, title: 'Unavailable' }, { status: 503 })
        }
        return HttpResponse.json<MemberAgendaResponse>(asked, { status: 201 })
      }),
      http.post(`${API}/meetings/m1/agenda/items/:itemId/discussion-requests/mine/withdraw`, ({ params }) => {
        withdrawn.push(String(params.itemId))
        return HttpResponse.json({ status: 409, title: 'Conflict', reason: 'WRONG_STATE' }, { status: 409 })
      }),
    )
    renderRoute('/meetings/m1')

    await user.click(
      await screen.findByRole('button', { name: 'Ask to discuss this item: 8 Quarterly compliance report' }),
    )
    const dialog = await screen.findByRole('dialog', { name: 'Ask for item 8 to be discussed separately?' })
    expect(dialog).toHaveTextContent(
      'The secretary and the chair will be told. You can withdraw this until the meeting starts.',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Ask to discuss' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('That did not work. Try again in a moment.')
    await user.click(within(dialog).getByRole('button', { name: 'Ask to discuss' }))
    expect(await screen.findByText('Your request has been sent to the secretary and the chair.')).toBeInTheDocument()
    expect(keys).toHaveLength(2)
    expect(keys[0]).toMatch(UUID)
    expect(keys[1]).toBe(keys[0])
    const report = itemNamed('8 Quarterly compliance report')
    expect(report).toHaveTextContent('Lifted for discussion at the request of Demo Director.')
    expect(within(report).queryByRole('button', { name: /^Ask to discuss/ })).toBeNull()

    await user.click(
      within(itemNamed('9 Committee terms of reference')).getByRole('button', {
        name: 'Withdraw my request: 9 Committee terms of reference',
      }),
    )
    expect(
      await screen.findByText(
        'This cannot be changed now: the meeting has started or the item has moved. The page shows the latest agenda.',
      ),
    ).toBeInTheDocument()
    expect(withdrawn).toEqual(['i9'])
  }, 20_000)

  it('withdraws a request and shows the agenda the server answers', async () => {
    const user = userEvent.setup()
    const after: MemberAgendaResponse = {
      ...agenda,
      whatChanged: null,
      sections: sections.map((s) => ({
        ...s,
        items: s.items.map((i) =>
          i.id === 'i9' ? { ...i, discussion: { canAsk: true, mine: false, requestedBy: [] } } : i,
        ),
      })),
    }
    server.use(
      ...handlers(),
      http.post(`${API}/meetings/m1/agenda/items/i9/discussion-requests/mine/withdraw`, () =>
        HttpResponse.json<MemberAgendaResponse>(after),
      ),
    )
    renderRoute('/meetings/m1')

    await user.click(await screen.findByRole('button', { name: 'Withdraw my request: 9 Committee terms of reference' }))
    expect(await screen.findByText('Your request has been withdrawn.')).toBeInTheDocument()
    const terms = itemNamed('9 Committee terms of reference')
    expect(terms).not.toHaveTextContent('Lifted for discussion')
    expect(
      within(terms).getByRole('button', { name: 'Ask to discuss this item: 9 Committee terms of reference' }),
    ).toBeInTheDocument()
  }, 20_000)

  it('reads an earlier revision without asking or marking it seen again', async () => {
    const user = userEvent.setup()
    pdf.urls = []
    pdf.status = undefined
    const calls: Calls = { seen: [], agendaQueries: [] }
    server.use(...handlers(calls))
    renderRoute('/meetings/m1')

    const picker = await screen.findByRole('combobox', { name: 'Revision of the agenda' })
    await waitFor(() => {
      expect(
        within(picker)
          .getAllByRole('option')
          .map((o) => o.textContent),
      ).toEqual(['Revision 2, December 10, 2026 (the latest)', 'Revision 1, December 4, 2026'])
    })
    await user.selectOptions(picker, '1')
    expect(
      await screen.findByText('You are reading revision 1 of December 4, 2026, an earlier version of the agenda.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Ask to discuss/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Withdraw/ })).toBeNull()
    expect(itemNamed('5 Term loan, Delta Textiles')).not.toHaveTextContent('Changed:')
    await user.click(screen.getByRole('button', { name: 'Paper: CR/HO-CR/2026-27/0042 · 9 pages' }))
    await screen.findByRole('dialog', { name: 'Paper: CR/HO-CR/2026-27/0042' })
    expect(pdf.urls).toEqual(['/api/v1/meetings/m1/papers/p1/content?revision=1&kind=PDF'])
    await user.keyboard('{Escape}')

    await user.click(screen.getByRole('button', { name: 'Read the latest revision' }))
    expect(await screen.findByText('2 changes since you last looked')).toBeInTheDocument()
    expect(calls.agendaQueries).toContain('1')
    expect(calls.seen).toEqual([{ revision: 2 }])
  }, 20_000)

  it('shows a forum.read holder unheaded members-only lines and no papers', async () => {
    const calls: Calls = { seen: [], agendaQueries: [] }
    const reader: MemberAgendaResponse = {
      ...agenda,
      lastSeenRevision: null,
      whatChanged: null,
      sections: [
        {
          id: 's2',
          title: 'For decision',
          kind: 'STANDARD',
          membersOnly: false,
          items: [
            item('i5', { number: '5', access: 'TITLE', title: 'Term loan, Delta Textiles', startsAt: at('04:25') }),
          ],
        },
        {
          id: 's4',
          title: null,
          kind: 'STANDARD',
          membersOnly: true,
          items: [item('i10', { number: '10', access: 'HIDDEN', title: null })],
        },
      ],
    }
    server.use(
      ...handlers(calls, {
        meeting: { ...meeting, relation: 'READER', location: null, joinUrl: null, joinDetails: null, guests: [] },
        agenda: reader,
      }),
    )
    renderRoute('/meetings/m1')

    const agendaRegion = await screen.findByRole('region', { name: 'Agenda' })
    expect(
      within(agendaRegion)
        .getAllByRole('heading', { level: 3 })
        .map((h) => h.textContent),
    ).toEqual(['For decision'])
    expect(itemNamed('5 Term loan, Delta Textiles — papers restricted')).toBeInTheDocument()
    expect(itemNamed('10 Restricted item')).toBeInTheDocument()
    expect(within(agendaRegion).queryByText('Members only')).toBeNull()
    expect(within(agendaRegion).queryByRole('button', { name: /pages|Ask/ })).toBeNull()
    expect(
      screen.getByText(
        'The agenda changed on December 10, 2026 (revision 2). “Updated credit paper after the valuation”',
      ),
    ).toBeInTheDocument()
    await waitFor(() => {
      expect(calls.seen).toEqual([{ revision: 2 }])
    })
  }, 20_000)

  it('shows a recused member the lines of the items they declared an interest in, and why (E04)', async () => {
    const calls: Calls = { seen: [], agendaQueries: [] }
    const declaration = (state: 'RECUSED' | 'AWAITING_RULING') => ({
      id: `d-${state}`,
      meetingId: 'm1',
      itemId: 'i5',
      itemNumber: '5',
      itemTitle: null,
      user: { id: 'u1', name: 'S. Ali' },
      nature: 'My brother-in-law is a director of Delta Textiles',
      intent: state === 'RECUSED' ? ('LEAVE' as const) : ('ASK_CHAIR' as const),
      source: 'SELF' as const,
      how: 'IN_APP' as const,
      declaredAt: '2026-12-01T09:00:00Z',
      atMeeting: false,
      correction: false,
      state,
      version: 0,
    })
    const recused: MemberAgendaResponse = {
      ...agenda,
      lastSeenRevision: null,
      whatChanged: null,
      sections: [
        {
          id: 's2',
          title: 'For decision',
          kind: 'STANDARD',
          membersOnly: false,
          items: [
            item('i5', {
              number: '5',
              access: 'TITLE',
              title: 'Term loan, Delta Textiles',
              recused: true,
              myDeclaration: declaration('RECUSED'),
            }),
            item('i6', {
              number: '6',
              access: 'HIDDEN',
              title: null,
              recused: true,
              myDeclaration: declaration('AWAITING_RULING'),
            }),
          ],
        },
      ],
    }
    server.use(...handlers(calls, { agenda: recused }))
    renderRoute('/meetings/m1')

    expect(
      await screen.findByRole('heading', { level: 4, name: '5 Term loan, Delta Textiles — You declared an interest' }),
    ).toBeInTheDocument()
    expect(
      itemNamed('6 Restricted item — Waiting for the chair’s decision, papers withheld until then'),
    ).toBeInTheDocument()
    // Nothing of the item but its line, why, and "My declaration" (E04 T6).
    expect(
      within(itemNamed(/^5 /))
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['My declaration'])
    expect(itemNamed(/^5 /)).toHaveTextContent(
      'You will be asked to leave the room for this item. Its papers are not shown to you.',
    )
    expect(itemNamed(/^6 /)).toHaveTextContent('You asked the chair to decide whether you may take part.')
    await waitFor(() => {
      expect(calls.seen).toEqual([{ revision: 2 }])
    })
  }, 20_000)

  it('says the agenda is not published yet, shows officers the guests, and marks nothing seen', async () => {
    const calls: Calls = { seen: [], agendaQueries: [] }
    server.use(
      ...handlers(calls, {
        meeting: { ...meeting, relation: 'OFFICER', canManage: true },
        agenda: {
          ...agenda,
          revision: null,
          latestRevision: 0,
          publishedAt: null,
          note: null,
          sections: [],
          whatChanged: null,
          lastSeenRevision: null,
        },
      }),
    )
    renderRoute('/meetings/m1')

    expect(
      await screen.findByText(
        'The agenda has not been published yet. It appears here when the secretary publishes it.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Guests' })).toHaveTextContent('N. Hossain')
    expect(screen.queryByRole('button', { name: 'Print the agenda' })).toBeNull()
    expect(calls.seen).toEqual([])
  }, 20_000)

  it('shows one clock when the reader is in the meeting’s zone', async () => {
    const london = { startsAt: meeting.startsAt, endsAt: meeting.endsAt, timeZone: 'Europe/London' }
    server.use(...handlers(undefined, { meeting: { ...meeting, ...london }, agenda: { ...agenda, ...london } }))
    renderRoute('/meetings/m1')

    expect(plain(await screen.findByText(/London time/))).toBe('4:00 – 7:00 AM London time')
    expect(screen.queryByText(/your time/)).toBeNull()
  }, 20_000)

  it('tells the reader on My meetings that the agenda changed since they last looked (flow N3)', async () => {
    const mine: MeetingSummaryResponse = {
      id: 'm1',
      forumId: 'f1',
      forumName: 'Board Risk Committee',
      forumCode: 'BRC',
      kind: 'ORDINARY',
      startsAt: new Date(Date.now() + 86_400_000).toISOString(),
      endsAt: new Date(Date.now() + 90_000_000).toISOString(),
      timeZone: 'Europe/London',
      localStartsAt: '2026-12-12T10:00:00',
      localEndsAt: '2026-12-12T11:00:00',
      mode: 'ONLINE',
      label: 'BRC 3/2026',
      status: 'SCHEDULED',
      relation: 'MEMBER',
      agendaRevision: 2,
      lastSeenRevision: 1,
      changesSinceSeen: 2,
    }
    server.use(
      ...handlers(),
      myMeetingsHandler([mine, { ...mine, id: 'm2', agendaRevision: 1, lastSeenRevision: 1, changesSinceSeen: 0 }]),
    )
    renderRoute('/me/meetings')

    const cards = await screen.findAllByRole('article')
    expect(cards[0]).toHaveTextContent('Agenda published · 2 changes since you last looked')
    expect(cards[1]).toHaveTextContent('Agenda published')
    expect(cards[1]).not.toHaveTextContent('since you last looked')
  }, 20_000)

  it('reads right to left in Arabic without accessibility violations', async () => {
    server.use(...handlers())
    const { container } = renderRoute('/meetings/m1', { locale: 'ar' })

    const agendaRegion = await screen.findByRole('region', { name: 'جدول الأعمال' })
    expect(document.documentElement).toHaveAttribute('dir', 'rtl')
    expect(within(agendaRegion).getByRole('heading', { level: 4, name: '7 بند مقيّد' })).toBeInTheDocument()
    expect(
      within(agendaRegion).getByRole('button', { name: 'طلب مناقشة هذا البند: 8 Quarterly compliance report' }),
    ).toBeInTheDocument()
    expect(screen.getByText('تغييران منذ آخر اطلاع لك')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 20_000)
})
