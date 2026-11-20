import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  AgendaDraftResponse,
  AgendaItemResponse,
  DeclarationResponse,
  MeetingDeclarationRegisterResponse,
  MeetingResponse,
  MemberAgendaResponse,
  MemberResponse,
  MyDeclarationsResponse,
  MyForumResponse,
  Session,
} from '@/api/generated/types.gen'
import {
  accessHandler,
  API_ORIGIN,
  csrfHandler,
  myForumsHandler,
  server,
  sessionHandler,
  signedInSession,
} from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const API = `${API_ORIGIN}/api/v1`
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

const CHAIR = { id: 'u1', name: 'S. Ali' }
const VICE = { id: 'u4', name: 'T. Islam' }
const SECRETARY = { id: 'u9', name: 'Company Secretary' }
const DIRECTOR = { id: 'u2', name: 'Demo Director' }
const KHAN = { id: 'u3', name: 'R. Khan' }
const RAHMAN = { id: 'u5', name: 'N. Rahman' }
const HOSSAIN = { id: 'u6', name: 'K. Hossain' }

/** BRC 3/2026 on Saturday 12 December 2026, 10:00–13:00 in Dhaka. */
const meetingFor = (over: Partial<MeetingResponse> = {}): MeetingResponse => ({
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
  mode: 'IN_PERSON',
  location: 'Board Room',
  classificationId: 'l1',
  number: 3,
  label: 'BRC 3/2026',
  status: 'SCHEDULED',
  relation: 'OFFICER',
  canManage: true,
  guests: [],
  version: 3,
  ...over,
})

const seat = (id: string, person: { id: string; name: string }, role: MemberResponse['role']): MemberResponse => ({
  id,
  kind: 'PERSON',
  person,
  role,
  state: 'IN_OFFICE',
  startsOn: '2026-01-01',
  termNumber: 1,
  version: 0,
  voting: role !== 'SECRETARY',
  attributes: [],
})

const members: MemberResponse[] = [
  seat('s1', CHAIR, 'CHAIR'),
  seat('s2', VICE, 'VICE_CHAIR'),
  seat('s3', SECRETARY, 'SECRETARY'),
  seat('s4', DIRECTOR, 'MEMBER'),
  seat('s5', KHAN, 'MEMBER'),
  seat('s6', RAHMAN, 'MEMBER'),
  seat('s7', HOSSAIN, 'MEMBER'),
]

const declaration = (over: Partial<DeclarationResponse> & Pick<DeclarationResponse, 'id'>): DeclarationResponse => ({
  meetingId: 'm1',
  itemId: 'i5',
  itemNumber: '5',
  itemTitle: 'Term loan, Delta Textiles',
  user: DIRECTOR,
  nature: 'Brother-in-law is a director of Delta Textiles.',
  intent: 'LEAVE',
  source: 'SELF',
  how: 'IN_APP',
  declaredAt: '2026-12-09T08:10:00Z',
  atMeeting: false,
  correction: false,
  state: 'RECUSED',
  version: 1,
  ...over,
})

const byEmail = declaration({
  id: 'd1',
  source: 'ON_BEHALF',
  how: 'EMAIL',
  howNote: 'email of 8 Dec',
  recordedBy: SECRETARY,
})
const fromMemo = declaration({
  id: 'd2',
  itemId: 'i6',
  itemNumber: '6',
  itemTitle: 'Market risk limits',
  user: HOSSAIN,
  source: 'MEMO',
  how: 'SYSTEM',
  nature: 'Interest declared on memo CR/HO-CR/2026-27/0042',
})
const asking = declaration({
  id: 'd3',
  itemId: 'i7',
  itemNumber: '7',
  itemTitle: 'Liquidity stress results',
  user: KHAN,
  intent: 'ASK_CHAIR',
  state: 'AWAITING_RULING',
  nature: 'Holds shares in a bank in the stress set.',
  version: 2,
})
const takingPart = declaration({
  id: 'd4',
  itemId: 'i8',
  itemNumber: '8',
  itemTitle: null,
  user: RAHMAN,
  intent: 'ASK_CHAIR',
  state: 'TAKING_PART',
  nature: 'A cousin works for the counterparty.',
  ruling: { by: CHAIR, outcome: 'TAKE_PART', how: 'IN_APP', at: '2026-12-10T05:00:00Z', note: 'Not material.' },
})
const chairAsks = declaration({
  id: 'd5',
  itemId: 'i9',
  itemNumber: '9',
  itemTitle: 'Treasury mandate',
  user: CHAIR,
  intent: 'ASK_CHAIR',
  state: 'AWAITING_RULING',
  nature: 'Sits on the board of the fund manager.',
  version: 4,
})

function registerOf(over: Partial<MeetingDeclarationRegisterResponse> = {}): MeetingDeclarationRegisterResponse {
  return {
    meetingId: 'm1',
    latestRevision: 2,
    canRemind: true,
    reviews: [
      { user: SECRETARY, officer: true, reviewedRevision: 2, toReview: 0 },
      { user: CHAIR, officer: true, reviewedRevision: 2, toReview: 0 },
      { user: DIRECTOR, officer: false, reviewedRevision: 2, toReview: 0 },
      { user: RAHMAN, officer: false, toReview: 3, remindedRevision: 2 },
      { user: HOSSAIN, officer: false, reviewedRevision: 1, toReview: 1 },
    ],
    items: [
      {
        itemId: 'i5',
        number: '5',
        title: 'Term loan, Delta Textiles',
        recused: false,
        declarations: [byEmail],
        alsoReadsMemo: [DIRECTOR],
      },
      {
        itemId: 'i6',
        number: '6',
        title: 'Market risk limits',
        recused: false,
        declarations: [fromMemo],
        alsoReadsMemo: [],
        quorumWarning: {
          declared: 3,
          basis: 7,
          needed: 4,
          available: 3,
          recusedCount: false,
          unmet: [{ kind: 'COUNT' }],
        },
      },
      {
        itemId: 'i7',
        number: '7',
        title: 'Liquidity stress results',
        recused: false,
        declarations: [asking],
        alsoReadsMemo: [],
      },
      { itemId: 'i8', number: '8', title: null, recused: false, declarations: [takingPart], alsoReadsMemo: [] },
      {
        itemId: 'i9',
        number: '9',
        title: 'Treasury mandate',
        recused: false,
        declarations: [chairAsks],
        alsoReadsMemo: [],
      },
    ],
    ...over,
  }
}

const draftItem = (id: string, number: string, title: string, over: Partial<AgendaItemResponse> = {}) =>
  ({
    id,
    number,
    title,
    kind: 'ITEM',
    late: 'NONE',
    purpose: 'DECISION',
    presenters: [],
    attendees: [],
    titleHidden: false,
    classificationId: 'l1',
    papers: [],
    outOfPack: [],
    subItems: [],
    ...over,
  }) satisfies AgendaItemResponse

const draft: AgendaDraftResponse = {
  meetingId: 'm1',
  started: true,
  currentRevision: 2,
  dirty: false,
  changes: 'ANY',
  canEdit: true,
  canAgree: false,
  chairAgreementRequired: false,
  chairComments: [],
  startsAt: '2026-12-12T04:00:00Z',
  endsAt: '2026-12-12T07:00:00Z',
  timeZone: 'Asia/Dhaka',
  deadlinePassed: false,
  lateMode: 'ALLOW',
  totalMinutes: 60,
  plannedEndsAt: '2026-12-12T05:00:00Z',
  overrun: false,
  traySize: 0,
  state: 'PUBLISHED',
  revisions: [],
  version: 7,
  sections: [
    {
      id: 's1',
      title: 'For decision',
      kind: 'STANDARD',
      membersOnly: false,
      receivesSubmissions: true,
      minutes: 60,
      items: [
        draftItem('i0', '1', 'Break', { kind: 'BREAK' }),
        draftItem('i5', '5', 'Term loan, Delta Textiles', { kind: 'SUBMISSION' }),
        draftItem('i7', '7', 'Liquidity stress results'),
        draftItem('i10', '10', 'Restricted item', { recused: true, title: '', titleHidden: true }),
      ],
    },
  ],
}

const agenda: MemberAgendaResponse = {
  meetingId: 'm1',
  status: 'SCHEDULED',
  startsAt: '2026-12-12T04:00:00Z',
  endsAt: '2026-12-12T07:00:00Z',
  timeZone: 'Asia/Dhaka',
  revision: 2,
  latestRevision: 2,
  publishedAt: '2026-12-10T06:00:00Z',
  preview: false,
  lastSeenRevision: 2,
  sections: [],
}

const mine: MyDeclarationsResponse = {
  meetingId: 'm1',
  latestRevision: 2,
  reviewedRevision: 2,
  declarations: [],
  toReview: [],
}

const sessionOf = (user: { id: string; name: string }): Session => {
  const s = signedInSession()
  return { ...s, ...(s.user ? { user: { ...s.user, id: user.id, displayName: user.name } } : {}) }
}

interface Sent {
  path: string
  body: unknown
  ifMatch: string | null
  key: string | null
}

interface Setup {
  as?: { id: string; name: string }
  meeting?: MeetingResponse
  register?: MeetingDeclarationRegisterResponse
  forums?: MyForumResponse[]
  /** Answers to the writes, in order; a missing answer is a success. */
  answers?: (sent: Sent) => Response | undefined
}

function setup(options: Setup = {}) {
  const sent: Sent[] = []
  const state = { register: options.register ?? registerOf(), registerReads: 0, draftReads: 0 }
  const write = async ({ request, params }: { request: Request; params: Record<string, unknown> }) => {
    const text = await request.text()
    const call: Sent = {
      path: new URL(request.url).pathname.replace('/api/v1', ''),
      body: text ? (JSON.parse(text) as unknown) : undefined,
      ifMatch: request.headers.get('If-Match'),
      key: request.headers.get('Idempotency-Key'),
    }
    sent.push(call)
    const answer = options.answers?.(call)
    if (answer) return answer
    if (call.path.endsWith('/remind')) {
      return HttpResponse.json({ revision: 2, reminded: [RAHMAN, HOSSAIN] })
    }
    const id = typeof params.declarationId === 'string' ? params.declarationId : 'new'
    return HttpResponse.json(declaration({ id }), { status: call.key ? 201 : 200 })
  }
  server.use(
    sessionHandler(sessionOf(options.as ?? SECRETARY)),
    accessHandler([]),
    csrfHandler(),
    myForumsHandler(options.forums ?? []),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([])),
    http.get(`${API}/meetings/m1`, () => HttpResponse.json<MeetingResponse>(options.meeting ?? meetingFor())),
    http.get(`${API}/meetings/m1/agenda`, () => HttpResponse.json<MemberAgendaResponse>(agenda)),
    http.get(`${API}/meetings/m1/agenda/draft`, () => {
      state.draftReads++
      return HttpResponse.json<AgendaDraftResponse>(draft)
    }),
    http.get(`${API}/meetings/m1/declarations/mine`, () => HttpResponse.json<MyDeclarationsResponse>(mine)),
    http.get(`${API}/meetings/m1/declarations`, () => {
      state.registerReads++
      return HttpResponse.json<MeetingDeclarationRegisterResponse>(state.register)
    }),
    http.get(`${API}/forums/f1/members`, () => HttpResponse.json<MemberResponse[]>(members)),
    http.post(`${API}/meetings/m1/seen`, () => new HttpResponse(null, { status: 204 })),
    http.post(`${API}/meetings/m1/declarations/remind`, write),
    http.post(`${API}/meetings/m1/items/:itemId/declarations`, write),
    http.post(`${API}/meetings/m1/declarations/:declarationId/ruling`, write),
    http.post(`${API}/meetings/m1/declarations/:declarationId/withdraw`, write),
  )
  return { sent, state }
}

const problem = (status: number, extra: object) =>
  HttpResponse.json(
    { status, title: 'Refused', ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

/** Opens the meeting's Declarations tab. */
async function openTab(user: ReturnType<typeof userEvent.setup>) {
  renderRoute('/meetings/m1')
  await user.click(await screen.findByRole('tab', { name: 'Declarations' }))
  return screen.findByRole('region', { name: 'Declarations of interest' })
}

/** The item block whose heading is [name]. */
const itemBlock = (name: RegExp | string) => screen.getByRole('region', { name })

describe('managing declarations (Phase 4 E04 T7, flow T)', () => {
  it('shows the secretary the review, the declarations by item, the memo flag and the quorum warning, and reminds', async () => {
    const user = userEvent.setup()
    const { sent, state } = setup()
    const { container } = renderRoute('/meetings/m1')
    await user.click(await screen.findByRole('tab', { name: 'Declarations' }))
    const panel = await screen.findByRole('region', { name: 'Declarations of interest' })

    expect(panel).toHaveTextContent('Revision 2 published')
    expect(panel).toHaveTextContent('Reviewed: 3 of 5')
    expect(panel).toHaveTextContent('Not yet: N. Rahman (reminded) and K. Hossain')

    const loan = itemBlock('Item 5 Term loan, Delta Textiles')
    expect(loan).toHaveTextContent('Demo Director')
    expect(loan).toHaveTextContent('Leaving the room')
    expect(loan).toHaveTextContent(/By email, recorded by Company Secretary, .*\(email of 8 Dec\)/)
    expect(loan).toHaveTextContent('“Brother-in-law is a director of Delta Textiles.”')
    expect(loan).toHaveTextContent(
      'Demo Director also reads the memo through the memo’s own access. A declaration withholds the committee’s papers, not the memo: change its access on the memo.',
    )
    const limits = itemBlock('Item 6 Market risk limits')
    expect(limits).toHaveTextContent('Carried over from the memo')
    expect(limits).toHaveTextContent(
      'Even if every member attends, item 6 cannot be decided: 3 of 7 members have declared an interest and the committee needs 4 (members with an interest do not count).',
    )
    const stress = itemBlock('Item 7 Liquidity stress results')
    expect(stress).toHaveTextContent('Waiting for a decision')
    expect(stress).toHaveTextContent('Waiting for S. Ali to decide.')
    // The secretary records the chair's decision; only the chair decides in the app.
    expect(
      within(stress).getByRole('button', { name: 'Record the chair’s decision on R. Khan’s declaration, item 7' }),
    ).toBeInTheDocument()
    expect(within(stress).queryByRole('button', { name: /^Decide on/ })).toBeNull()
    // A restricted item: "Restricted item"; the chair's own declaration waits for the vice-chair.
    const restricted = itemBlock('Item 8 Restricted item')
    expect(restricted).toHaveTextContent(/S\. Ali decided: may take part, .*\. Note: “Not material\.”/)
    expect(itemBlock('Item 9 Treasury mandate')).toHaveTextContent('Waiting for T. Islam to decide.')
    for (const button of within(panel).getAllByRole('button')) expect(button.className).toMatch(/min-h-11/)
    expect(within(panel).getByRole('link', { name: 'Register of declarations for this committee' })).toHaveAttribute(
      'href',
      '/declarations?forumId=f1',
    )
    await expectNoA11yViolations(container)

    const reads = state.registerReads
    await user.click(within(panel).getByRole('button', { name: 'Remind them now' }))
    expect(await within(panel).findByRole('status')).toHaveTextContent(
      '2 people were reminded: N. Rahman and K. Hossain.',
    )
    expect(sent.map((s) => s.path)).toEqual(['/meetings/m1/declarations/remind'])
    await waitFor(() => {
      expect(state.registerReads).toBeGreaterThan(reads)
    })
  }, 30_000)

  it('records a declaration made by email, with the keyboard, refusals at the field and in words', async () => {
    const user = userEvent.setup()
    let step = 0
    const { sent } = setup({
      answers: (s) => {
        if (!s.path.endsWith('/declarations') || !s.key) return undefined
        step++
        if (step === 1) {
          return problem(422, { errors: [{ field: 'onBehalfOf', code: 'NOT_IN_AUDIENCE', message: 'No' }] })
        }
        if (step === 2) return problem(409, { reason: 'ONE_OPEN', declarationId: 'd9' })
        return undefined
      },
    })
    const panel = await openTab(user)
    const record = within(panel).getByRole('button', { name: 'Record a declaration…' })
    record.focus()
    await user.keyboard('{Enter}')
    const sheet = await screen.findByRole('dialog', { name: 'Record a declaration of interest' })
    expect(sheet).toHaveTextContent('For a declaration someone made to you')

    // Nothing filled in: each field says what to do, and the first gets the focus.
    await user.click(within(sheet).getByRole('button', { name: 'Record' }))
    const member = within(sheet).getByRole('combobox', { name: 'Member' })
    expect(member).toHaveAccessibleDescription('Please choose who made the declaration.')
    expect(member).toHaveFocus()
    expect(sheet).toHaveTextContent('Please say how it was made.')
    expect(sent).toEqual([])

    // The items: the draft's items and submissions the secretary is not recused from, not a break.
    const items = within(within(sheet).getByRole('combobox', { name: 'Item' })).getAllByRole('option')
    expect(items.map((o) => o.textContent)).toEqual([
      'Choose the item',
      '5 Term loan, Delta Textiles',
      '7 Liquidity stress results',
    ])
    await user.selectOptions(member, 'u3')
    await user.selectOptions(within(sheet).getByRole('combobox', { name: 'Item' }), 'i5')
    await user.click(within(sheet).getByRole('textbox', { name: 'Their interest' }))
    await user.keyboard('Owns shares in Delta Textiles.')
    await user.click(within(sheet).getByRole('radio', { name: 'By email' }))
    await user.click(within(sheet).getByRole('textbox', { name: /^Note/ }))
    await user.keyboard('email of 8 Dec')
    expect(within(sheet).getByRole('radio', { name: 'Leave the room for this item' })).toBeChecked()
    await user.click(within(sheet).getByRole('button', { name: 'Record' }))

    // 422 at the field.
    expect(
      await within(sheet).findByText(/A declaration can be recorded only for someone this meeting is for/),
    ).toBeInTheDocument()
    await user.click(within(sheet).getByRole('button', { name: 'Record' }))
    // 409 ONE_OPEN in words; the same request kept its Idempotency-Key.
    expect(await within(sheet).findByRole('alert')).toHaveTextContent(
      'This person already has a declaration on this item. Find it in the list to change or withdraw it.',
    )
    await user.click(within(sheet).getByRole('button', { name: 'Record' }))
    expect(await screen.findByText('Recorded. R. Khan has been told.')).toBeInTheDocument()
    expect(sent).toHaveLength(3)
    expect(sent[0]?.key).toMatch(UUID)
    expect(new Set(sent.map((s) => s.key)).size).toBe(1)
    expect(sent[2]).toMatchObject({
      path: '/meetings/m1/items/i5/declarations',
      body: {
        nature: 'Owns shares in Delta Textiles.',
        intent: 'LEAVE',
        onBehalfOf: 'u3',
        how: 'EMAIL',
        howNote: 'email of 8 Dec',
      },
    })
    await waitFor(() => {
      expect(record).toHaveFocus()
    })
  }, 30_000)

  it('opens the sheet during the meeting with "At the meeting" chosen', async () => {
    const user = userEvent.setup()
    setup({ meeting: meetingFor({ status: 'IN_PROGRESS' }) })
    const panel = await openTab(user)
    await user.click(within(panel).getByRole('button', { name: 'Record a declaration…' }))
    const sheet = await screen.findByRole('dialog', { name: 'Record a declaration of interest' })
    expect(within(sheet).getByRole('radio', { name: 'At the meeting' })).toBeChecked()
    expect(within(sheet).queryByRole('textbox', { name: /Why the record is being corrected/ })).toBeNull()
  }, 30_000)

  it('once the meeting is held, offers only the secretary’s correction, with a reason', async () => {
    const user = userEvent.setup()
    setup({ meeting: meetingFor({ status: 'HELD' }) })
    const panel = await openTab(user)
    // Once held, nothing but the secretary's correction: no decisions, no withdrawals except as a correction.
    expect(within(panel).queryByRole('button', { name: /Record the chair’s decision/ })).toBeNull()
    await user.click(within(panel).getByRole('button', { name: 'Record a declaration…' }))
    const sheet = await screen.findByRole('dialog', { name: 'Record a declaration of interest' })
    expect(sheet).toHaveTextContent('Correcting the record of a held meeting')
    expect(within(sheet).getByRole('textbox', { name: /Why the record is being corrected/ })).toBeInTheDocument()
  }, 30_000)

  it('records the chair’s decision on their behalf, saying how it was given; NO_CHAIR in words', async () => {
    const user = userEvent.setup()
    let refuse = true
    const { sent } = setup({
      answers: (s) => {
        if (!s.path.endsWith('/ruling')) return undefined
        if (refuse) {
          refuse = false
          return problem(409, { reason: 'NO_CHAIR' })
        }
        return HttpResponse.json(declaration({ ...asking, state: 'TAKING_PART', version: 3 }))
      },
    })
    const panel = await openTab(user)
    await user.click(
      within(panel).getByRole('button', { name: 'Record the chair’s decision on R. Khan’s declaration, item 7' }),
    )
    const dialog = await screen.findByRole('dialog', {
      name: 'Record the chair’s decision — R. Khan, item 7 Liquidity stress results',
    })
    expect(dialog).toHaveTextContent('“Holds shares in a bank in the stress set.”')
    expect(dialog).toHaveTextContent('The decision is S. Ali’s.')
    await user.click(within(dialog).getByRole('button', { name: 'Record' }))
    expect(dialog).toHaveTextContent('Please choose the decision.')
    expect(dialog).toHaveTextContent('Please say how it was given.')
    await user.click(within(dialog).getByRole('radio', { name: 'They may take part' }))
    await user.click(within(dialog).getByRole('radio', { name: 'By phone' }))
    await user.click(within(dialog).getByRole('button', { name: 'Record' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'There is no chair or vice-chair in office who is free to decide on this declaration.',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Record' }))
    expect(await screen.findByText('R. Khan may take part. They have been told.')).toBeInTheDocument()
    expect(sent.at(-1)).toEqual({
      path: '/meetings/m1/declarations/d3/ruling',
      body: { outcome: 'TAKE_PART', onBehalf: true, how: 'PHONE' },
      ifMatch: '"2"',
      key: null,
    })
  }, 30_000)

  it('withdraws a declaration with a reason, with If-Match; a stale version in words', async () => {
    const user = userEvent.setup()
    let stale = true
    const { sent } = setup({
      answers: (s) => {
        if (!s.path.endsWith('/withdraw')) return undefined
        if (stale) {
          stale = false
          return problem(412, {})
        }
        return undefined
      },
    })
    const panel = await openTab(user)
    await user.click(within(panel).getByRole('button', { name: 'Withdraw Demo Director’s declaration, item 5' }))
    const dialog = await screen.findByRole('dialog', {
      name: 'Withdraw Demo Director’s declaration — item 5 Term loan, Delta Textiles?',
    })
    expect(dialog).toHaveTextContent(
      'Demo Director, the secretaries and the chair will be told, and the withdrawal will be in the minutes.',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw' }))
    const reason = within(dialog).getByRole('textbox', { name: 'Why is it being withdrawn?' })
    expect(reason).toHaveAccessibleDescription('Please say why you are withdrawing it.')
    await user.type(reason, 'Entered for the wrong item.')
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'This declaration changed in the meantime. The latest is shown: please try again.',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw' }))
    expect(
      await screen.findByText('Demo Director’s declaration has been withdrawn. They have been told.'),
    ).toBeInTheDocument()
    expect(sent.at(-1)).toMatchObject({
      path: '/meetings/m1/declarations/d1/withdraw',
      body: { reason: 'Entered for the wrong item.' },
      ifMatch: '"1"',
    })
  }, 30_000)

  it('lets the chair decide in the app, never on their own declaration', async () => {
    const user = userEvent.setup()
    const { sent } = setup({ as: CHAIR, meeting: meetingFor({ canManage: false }) })
    const panel = await openTab(user)
    const stress = itemBlock('Item 7 Liquidity stress results')
    expect(stress).toHaveTextContent('Waiting for your decision.')
    // Their own declaration waits for the vice-chair: no decision for them, and no "Record" (not a secretary).
    expect(
      within(itemBlock('Item 9 Treasury mandate')).queryByRole('button', { name: /Decide|Record the chair/ }),
    ).toBeNull()
    expect(
      within(itemBlock('Item 8 Restricted item')).getByRole('button', {
        name: 'Change the decision on N. Rahman’s declaration, item 8',
      }),
    ).toBeInTheDocument()

    await user.click(within(stress).getByRole('button', { name: 'Decide on R. Khan’s declaration, item 7' }))
    const dialog = await screen.findByRole('dialog', { name: /^A declaration for .+ — your decision is needed$/ })
    expect(dialog).toHaveTextContent('R. Khan, item 7 Liquidity stress results')
    expect(dialog).toHaveTextContent(
      'R. Khan asks whether they may take part. Until you decide, the papers are withheld from them.',
    )
    const buttons = within(dialog)
      .getAllByRole('button')
      .map((b) => b.textContent)
    expect(buttons).toEqual(expect.arrayContaining(['They should leave the room', 'They may take part']))
    await user.type(within(dialog).getByRole('textbox', { name: /^Note/ }), 'Shareholding below 0.1%.')
    await user.click(within(dialog).getByRole('button', { name: 'They should leave the room' }))
    expect(
      await screen.findByText('R. Khan will leave the room for this item. They have been told.'),
    ).toBeInTheDocument()
    expect(sent).toEqual([
      {
        path: '/meetings/m1/declarations/d3/ruling',
        body: { outcome: 'LEAVE', note: 'Shareholding below 0.1%.' },
        ifMatch: '"2"',
        key: null,
      },
    ])
    expect(panel).toBeInTheDocument()
  }, 30_000)

  it('gives the vice-chair, a member of the meeting, the tab and the decision on the chair’s own declaration', async () => {
    const user = userEvent.setup()
    const { sent } = setup({
      as: VICE,
      meeting: meetingFor({ relation: 'MEMBER', canManage: false }),
      forums: [
        {
          forumId: 'f1',
          code: 'BRC',
          name: 'Board Risk Committee',
          role: 'VICE_CHAIR',
          state: 'IN_OFFICE',
          status: 'ACTIVE',
          startsOn: '2026-01-01',
          termNumber: 1,
          voting: true,
          attributes: [],
          canOpen: true,
        },
      ],
    })
    const panel = await openTab(user)
    expect(within(panel).queryByRole('button', { name: 'Record a declaration…' })).toBeNull()
    expect(within(panel).queryByRole('button', { name: /^Withdraw/ })).toBeNull()
    expect(within(itemBlock('Item 7 Liquidity stress results')).queryByRole('button')).toBeNull()
    const treasury = itemBlock('Item 9 Treasury mandate')
    expect(treasury).toHaveTextContent('Waiting for your decision.')
    await user.click(within(treasury).getByRole('button', { name: 'Decide on S. Ali’s declaration, item 9' }))
    const dialog = await screen.findByRole('dialog', { name: /your decision is needed/ })
    expect(dialog).toHaveTextContent('The chair declared this interest, so the vice-chair decides.')
    await user.click(within(dialog).getByRole('button', { name: 'They may take part' }))
    await waitFor(() => {
      expect(sent).toHaveLength(1)
    })
    expect(sent[0]).toMatchObject({ path: '/meetings/m1/declarations/d5/ruling', body: { outcome: 'TAKE_PART' } })
  }, 30_000)

  it('gives a member without a chair’s seat no Declarations tab', async () => {
    setup({ as: DIRECTOR, meeting: meetingFor({ relation: 'MEMBER', canManage: false }) })
    renderRoute('/meetings/m1')
    expect(await screen.findByRole('heading', { name: 'Agenda' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Declarations' })).toBeNull()
  }, 30_000)

  it('reads right to left in Arabic without accessibility violations', async () => {
    const user = userEvent.setup()
    setup()
    const { container } = renderRoute('/meetings/m1', { locale: 'ar' })
    await user.click(await screen.findByRole('tab', { name: 'الإفصاحات' }))
    const panel = await screen.findByRole('region', { name: 'الإفصاح عن المصالح' })
    expect(document.documentElement).toHaveAttribute('dir', 'rtl')
    expect(panel).toHaveTextContent('نُشرت النسخة 2')
    expect(panel).toHaveTextContent('بانتظار قرار S. Ali.')
    expect(panel).toHaveTextContent(
      'حتى لو حضر جميع الأعضاء، لا يمكن البتّ في البند 6: أفصح 3 من أصل 7 من الأعضاء عن مصلحة، وتحتاج اللجنة إلى 4 (لا يُحتسب الأعضاء ذوو المصلحة).',
    )
    await user.click(within(panel).getByRole('button', { name: 'تسجيل إفصاح…' }))
    expect(await screen.findByRole('dialog', { name: 'تسجيل إفصاح عن مصلحة' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 30_000)
})
