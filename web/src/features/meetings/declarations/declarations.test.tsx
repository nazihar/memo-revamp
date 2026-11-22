import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  DeclarationResponse,
  MeetingResponse,
  MemberAgendaResponse,
  MemberItemResponse,
  MyDeclarationsResponse,
  ReviewItemResponse,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const API = `${API_ORIGIN}/api/v1`
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

/** BRC 3/2026 on Saturday 12 December 2026, 10:00–13:00 in Dhaka. */
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
  mode: 'IN_PERSON',
  location: 'Board Room',
  classificationId: 'l1',
  number: 3,
  label: 'BRC 3/2026',
  status: 'SCHEDULED',
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
  declarations: [],
  ...extra,
})

const at = (time: string) => `2026-12-12T${time}:00Z`

const loanPaper = {
  id: 'p1',
  title: 'Paper: CR/HO-CR/2026-27/0042',
  pages: 9,
  ready: true,
  opened: false,
  late: 'NONE' as const,
  changes: [],
}

const declaration = (extra: Partial<DeclarationResponse> = {}): DeclarationResponse => ({
  id: 'd5',
  meetingId: 'm1',
  itemId: 'i5',
  itemNumber: '5',
  itemTitle: 'Term loan, Delta Textiles',
  user: { id: 'u1', name: 'Demo Director' },
  nature: 'My brother-in-law is a director of Delta Textiles.',
  intent: 'LEAVE',
  source: 'SELF',
  how: 'IN_APP',
  declaredAt: '2026-12-09T08:10:00Z',
  atMeeting: false,
  correction: false,
  state: 'RECUSED',
  version: 3,
  ...extra,
})

/** Item 5 as the agenda shows it: in full, or as the recused line with the reader's declaration (E04 §1.6). */
const loan = (mine?: DeclarationResponse): MemberItemResponse =>
  mine && mine.state !== 'TAKING_PART'
    ? item('i5', {
        number: '5',
        kind: 'SUBMISSION',
        access: 'TITLE',
        title: 'Term loan, Delta Textiles',
        purpose: 'DECISION',
        startsAt: at('04:25'),
        recused: true,
        myDeclaration: mine,
      })
    : item('i5', {
        number: '5',
        kind: 'SUBMISSION',
        title: 'Term loan, Delta Textiles',
        purpose: 'DECISION',
        startsAt: at('04:25'),
        papers: [loanPaper],
        ...(mine ? { myDeclaration: mine } : {}),
      })

function agendaWith(loanItem: MemberItemResponse, more: Partial<MemberAgendaResponse> = {}): MemberAgendaResponse {
  return {
    meetingId: 'm1',
    status: 'SCHEDULED',
    startsAt: meeting.startsAt,
    endsAt: meeting.endsAt,
    timeZone: 'Asia/Dhaka',
    revision: 2,
    latestRevision: 2,
    publishedAt: '2026-12-10T06:00:00Z',
    preview: false,
    lastSeenRevision: 2,
    sections: [
      {
        id: 's1',
        title: 'Opening',
        kind: 'STANDARD',
        membersOnly: false,
        items: [
          item('i1', {
            number: '1',
            kind: 'STANDING',
            standingKind: 'APOLOGIES',
            title: 'Welcome and apologies',
            purpose: 'INFORMATION',
          }),
          item('i2', {
            number: '2',
            kind: 'STANDING',
            standingKind: 'DECLARATIONS',
            title: 'Declarations of interest',
            purpose: 'INFORMATION',
          }),
          item('i3', { number: '3', title: 'Minutes of the last meeting', purpose: 'DECISION' }),
        ],
      },
      {
        id: 's2',
        title: 'For decision',
        kind: 'STANDARD',
        membersOnly: false,
        items: [
          loanItem,
          item('i6', { number: '6', access: 'TITLE', title: 'Market risk limits', purpose: 'DECISION' }),
          item('i7', { number: '7', access: 'HIDDEN', title: null }),
          item('ib', { number: null, kind: 'BREAK', title: 'Break', minutes: 10 }),
        ],
      },
    ],
    ...more,
  }
}

const review = (id: string, number: string, title: string): ReviewItemResponse => ({ itemId: id, number, title })

const mineOf = (extra: Partial<MyDeclarationsResponse> = {}): MyDeclarationsResponse => ({
  meetingId: 'm1',
  latestRevision: 2,
  reviewedRevision: null,
  declarations: [],
  toReview: [
    review('i3', '3', 'Minutes of the last meeting'),
    review('i5', '5', 'Term loan, Delta Textiles'),
    review('i6', '6', 'Market risk limits'),
  ],
  ...extra,
})

interface State {
  agenda: MemberAgendaResponse
  mine: MyDeclarationsResponse
  agendaReads: number
  mineReads: number
}

function handlers(state: State, options: { meeting?: MeetingResponse } = {}) {
  return [
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([])),
    http.get(`${API}/meetings/m1`, () => HttpResponse.json<MeetingResponse>(options.meeting ?? meeting)),
    http.get(`${API}/meetings/m1/agenda`, () => {
      state.agendaReads++
      return HttpResponse.json<MemberAgendaResponse>(state.agenda)
    }),
    http.get(`${API}/meetings/m1/declarations/mine`, () => {
      state.mineReads++
      return HttpResponse.json<MyDeclarationsResponse>(state.mine)
    }),
    http.post(`${API}/meetings/m1/seen`, () => new HttpResponse(null, { status: 204 })),
  ]
}

const plain = (element: Element) => element.textContent.replace(/\s+/g, ' ')

/** The agenda item whose heading is [name]. */
const itemNamed = (name: RegExp | string) => {
  const li = screen.getByRole('heading', { level: 4, name }).closest('li')
  if (!li) throw new Error('no item')
  return li
}

interface Sent {
  keys: (string | null)[]
  ifMatch: (string | null)[]
  bodies: unknown[]
}

const sent = (): Sent => ({ keys: [], ifMatch: [], bodies: [] })

describe('declaring an interest on the meeting page (Phase 4 E04 T6, flow S)', () => {
  it('declares an interest, leaving the room, with an Idempotency-Key, and shows the recused line', async () => {
    const user = userEvent.setup()
    const state: State = { agenda: agendaWith(loan()), mine: mineOf(), agendaReads: 0, mineReads: 0 }
    const calls = sent()
    let refuse = true
    server.use(
      ...handlers(state),
      http.post(`${API}/meetings/m1/items/:itemId/declarations`, async ({ request, params }) => {
        calls.keys.push(request.headers.get('Idempotency-Key'))
        calls.bodies.push({ item: params.itemId, ...((await request.json()) as object) })
        if (refuse) {
          refuse = false
          return HttpResponse.json({ status: 503, title: 'Unavailable' }, { status: 503 })
        }
        const made = declaration()
        state.agenda = agendaWith(loan(made))
        state.mine = mineOf({ declarations: [made] })
        return HttpResponse.json<DeclarationResponse>(made, { status: 201, headers: { ETag: '"3"' } })
      }),
    )
    const { container } = renderRoute('/meetings/m1')

    expect(partsForPath('/meetings/m1')).toEqual([
      'meetings',
      'meetingPage',
      'meetingReplies',
      'meetingAttendance',
      'declarations',
      'minutes',
      'decisions',
    ])
    const declare = await screen.findByRole('button', { name: 'Declare an interest: 5 Term loan, Delta Textiles' })
    await user.click(declare)
    const sheet = await screen.findByRole('dialog', { name: 'Declare an interest — 5 Term loan, Delta Textiles' })
    expect(sheet).toHaveTextContent('Tell the committee about any interest that could affect')
    expect(
      within(sheet).getByRole('radio', { name: /I will leave the room for this item\. \(recommended\)/ }),
    ).toBeChecked()
    expect(within(sheet).getByRole('radio', { name: /I ask the chair to decide/ })).not.toBeChecked()
    // 44 px targets on every button of the sheet.
    for (const button of within(sheet).getAllByRole('button')) expect(button.className).toMatch(/min-h-11|size-11/)

    // Nothing said: the field says what to do, and nothing is sent.
    await user.click(within(sheet).getByRole('button', { name: 'Declare' }))
    const nature = within(sheet).getByRole('textbox', { name: 'What is your interest?' })
    expect(nature).toHaveAccessibleDescription(/Please say what your interest is\./)
    expect(nature).toHaveFocus()
    expect(calls.bodies).toEqual([])

    await user.type(nature, 'My brother-in-law is a director of Delta Textiles.')
    await expectNoA11yViolations(container)
    await user.click(within(sheet).getByRole('button', { name: 'Declare' }))
    expect(await within(sheet).findByRole('alert')).toHaveTextContent('That did not work. Try again in a moment.')
    await user.click(within(sheet).getByRole('button', { name: 'Declare' }))

    expect(
      await screen.findByText(
        'Thank you. The secretary and the chair have been told. You will not see this item’s papers.',
      ),
    ).toBeInTheDocument()
    expect(calls.keys).toHaveLength(2)
    expect(calls.keys[0]).toMatch(UUID)
    expect(calls.keys[1]).toBe(calls.keys[0])
    expect(calls.bodies[1]).toEqual({
      item: 'i5',
      nature: 'My brother-in-law is a director of Delta Textiles.',
      intent: 'LEAVE',
    })

    // The recused line: number, time, purpose and title, why, and "My declaration" (which keeps the focus).
    const line = await screen.findByRole('heading', {
      level: 4,
      name: '5 Term loan, Delta Textiles — You declared an interest',
    })
    const li = line.closest('li') as HTMLElement
    expect(li).toHaveTextContent('You will be asked to leave the room for this item. Its papers are not shown to you.')
    expect(within(li).queryByRole('button', { name: /Paper:/ })).toBeNull()
    const myDeclaration = within(li).getByRole('button', { name: 'My declaration: 5 Term loan, Delta Textiles' })
    await waitFor(() => {
      expect(myDeclaration).toHaveFocus()
    })
    expect(screen.queryByRole('dialog')).toBeNull()
  }, 30_000)

  it('asks the chair to decide, and the line says the papers are withheld until the chair decides', async () => {
    const user = userEvent.setup()
    const state: State = { agenda: agendaWith(loan()), mine: mineOf(), agendaReads: 0, mineReads: 0 }
    const calls = sent()
    server.use(
      ...handlers(state),
      http.post(`${API}/meetings/m1/items/i5/declarations`, async ({ request }) => {
        calls.keys.push(request.headers.get('Idempotency-Key'))
        calls.bodies.push(await request.json())
        const made = declaration({ intent: 'ASK_CHAIR', state: 'AWAITING_RULING', version: 0 })
        state.agenda = agendaWith(loan(made))
        state.mine = mineOf({ declarations: [made] })
        return HttpResponse.json<DeclarationResponse>(made, { status: 201 })
      }),
    )
    renderRoute('/meetings/m1')

    await user.click(await screen.findByRole('button', { name: 'Declare an interest: 5 Term loan, Delta Textiles' }))
    const sheet = await screen.findByRole('dialog', { name: /^Declare an interest/ })
    await user.type(within(sheet).getByRole('textbox'), 'I hold shares in Delta Textiles.')
    await user.click(within(sheet).getByRole('radio', { name: /I ask the chair to decide/ }))
    await user.click(within(sheet).getByRole('button', { name: 'Declare' }))

    expect(
      await screen.findByText(
        'Thank you. The chair has been asked to decide. Until then, you will not see this item’s papers.',
      ),
    ).toBeInTheDocument()
    expect(calls.bodies).toEqual([{ nature: 'I hold shares in Delta Textiles.', intent: 'ASK_CHAIR' }])
    expect(calls.keys[0]).toMatch(UUID)
    const li = (
      await screen.findByRole('heading', {
        level: 4,
        name: '5 Term loan, Delta Textiles — Waiting for the chair’s decision, papers withheld until then',
      })
    ).closest('li') as HTMLElement
    expect(li).toHaveTextContent('You asked the chair to decide whether you may take part.')
    expect(li).not.toHaveTextContent('You will be asked to leave the room')
    expect(within(li).getByRole('button', { name: 'My declaration: 5 Term loan, Delta Textiles' })).toBeInTheDocument()
  }, 30_000)

  it('says ONE_OPEN and a refused nature in words, in the sheet', async () => {
    const user = userEvent.setup()
    const state: State = { agenda: agendaWith(loan()), mine: mineOf(), agendaReads: 0, mineReads: 0 }
    const answers = [
      HttpResponse.json(
        {
          type: 'urn:memo:problem:validation',
          status: 422,
          title: 'Some fields need attention.',
          errors: [{ field: 'nature', code: 'LENGTH', message: 'At most 1000 characters.' }],
        },
        { status: 422 },
      ),
      HttpResponse.json(
        { type: 'urn:memo:problem:conflict', status: 409, title: 'Conflict', reason: 'ONE_OPEN', declarationId: 'd5' },
        { status: 409 },
      ),
    ]
    const keys: (string | null)[] = []
    server.use(
      ...handlers(state),
      http.post(`${API}/meetings/m1/items/i5/declarations`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return answers.shift()
      }),
    )
    renderRoute('/meetings/m1')

    await user.click(await screen.findByRole('button', { name: 'Declare an interest: 5 Term loan, Delta Textiles' }))
    const sheet = await screen.findByRole('dialog', { name: /^Declare an interest/ })
    const nature = within(sheet).getByRole('textbox')
    await user.type(nature, 'A long story.')
    await user.click(within(sheet).getByRole('button', { name: 'Declare' }))
    await waitFor(() => {
      expect(nature).toHaveAccessibleDescription(/Please shorten this to 1,000 characters or fewer\./)
    })
    expect(nature).toHaveAttribute('aria-invalid', 'true')

    // A changed request is a new request: it gets a new Idempotency-Key.
    await user.type(nature, ' Shorter.')
    const reads = state.agendaReads
    await user.click(within(sheet).getByRole('button', { name: 'Declare' }))
    expect(await within(sheet).findByRole('alert')).toHaveTextContent(
      'You have already declared an interest in this item. Use “My declaration” on the item to change or withdraw it.',
    )
    expect(keys).toHaveLength(2)
    expect(keys[1]).not.toBe(keys[0])
    // The agenda is read again, so "My declaration" appears once the server says so.
    await waitFor(() => {
      expect(state.agendaReads).toBeGreaterThan(reads)
    })
  }, 30_000)

  it('changes the intent and withdraws with If-Match and a reason (S4)', async () => {
    const user = userEvent.setup()
    const recused = declaration()
    const state: State = {
      agenda: agendaWith(loan(recused)),
      mine: mineOf({ declarations: [recused], reviewedRevision: 2, toReview: [] }),
      agendaReads: 0,
      mineReads: 0,
    }
    const calls = sent()
    let frozen = false
    server.use(
      ...handlers(state),
      http.post(`${API}/meetings/m1/declarations/d5/intent`, async ({ request }) => {
        calls.ifMatch.push(request.headers.get('If-Match'))
        calls.bodies.push(await request.json())
        const changed = declaration({ intent: 'ASK_CHAIR', state: 'AWAITING_RULING', version: 4 })
        state.agenda = agendaWith(loan(changed))
        state.mine = { ...state.mine, declarations: [changed] }
        return HttpResponse.json<DeclarationResponse>(changed, { headers: { ETag: '"4"' } })
      }),
      http.post(`${API}/meetings/m1/declarations/d5/withdraw`, async ({ request }) => {
        calls.ifMatch.push(request.headers.get('If-Match'))
        calls.bodies.push(await request.json())
        if (frozen) {
          return HttpResponse.json(
            { type: 'urn:memo:problem:conflict', status: 409, title: 'Conflict', reason: 'FROZEN' },
            { status: 409 },
          )
        }
        const withdrawn = declaration({ intent: 'ASK_CHAIR', state: 'WITHDRAWN', version: 5 })
        state.agenda = agendaWith(loan())
        state.mine = { ...state.mine, declarations: [withdrawn] }
        return HttpResponse.json<DeclarationResponse>(withdrawn)
      }),
    )
    renderRoute('/meetings/m1')

    // The panel lists the member's declarations; the line opens the same sheet.
    const panel = await screen.findByRole('region', { name: 'Declarations of interest' })
    expect(panel).toHaveTextContent('You confirmed your declarations for revision 2 of the agenda.')
    expect(panel).toHaveTextContent('You — will leave the room for item 5 Term loan, Delta Textiles')
    const opener = within(itemNamed(/^5 Term loan/)).getByRole('button', {
      name: 'My declaration: 5 Term loan, Delta Textiles',
    })
    await user.click(opener)
    let sheet = await screen.findByRole('dialog', { name: 'Your declaration — 5 Term loan, Delta Textiles' })
    expect(plain(sheet)).toMatch(/Declared on Dec 9, 2026, 2:10 PM, by you in the app\./)
    expect(sheet).toHaveTextContent('“My brother-in-law is a director of Delta Textiles.”')
    expect(sheet).toHaveTextContent('You chose: I will leave the room for this item.')
    for (const button of within(sheet).getAllByRole('button')) expect(button.className).toMatch(/min-h-11|size-11/)

    await user.click(within(sheet).getByRole('button', { name: 'Ask the chair to decide instead' }))
    expect(
      await screen.findByText('The chair has been asked to decide. Until then, the papers stay withheld from you.'),
    ).toBeInTheDocument()
    expect(calls.ifMatch).toEqual(['"3"'])
    expect(calls.bodies).toEqual([{ intent: 'ASK_CHAIR' }])
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull()
    })
    await waitFor(() => {
      expect(opener).toHaveFocus()
    })
    await screen.findByRole('heading', { level: 4, name: /Waiting for the chair’s decision/ })

    // Withdraw: a reason is required; a refusal is said in words; then it goes, with the new version.
    await user.click(opener)
    sheet = await screen.findByRole('dialog', { name: /^Your declaration/ })
    expect(sheet).toHaveTextContent('Waiting for the chair’s decision. The papers are withheld until then.')
    expect(within(sheet).getByRole('button', { name: 'I will leave the room instead' })).toBeInTheDocument()
    await user.click(within(sheet).getByRole('button', { name: 'Withdraw this declaration…' }))
    const confirm = await screen.findByRole('dialog', {
      name: 'Withdraw your declaration — 5 Term loan, Delta Textiles?',
    })
    expect(confirm).toHaveTextContent(
      'The secretary and the chair will be told, and the withdrawal will be in the minutes.',
    )
    await user.click(within(confirm).getByRole('button', { name: 'Withdraw' }))
    const reason = within(confirm).getByRole('textbox', { name: 'Why are you withdrawing it?' })
    expect(reason).toHaveAccessibleDescription(/Please say why you are withdrawing it\./)
    expect(calls.bodies).toHaveLength(1)
    await user.type(reason, 'The loan is to a different Delta company.')
    frozen = true
    await user.click(within(confirm).getByRole('button', { name: 'Withdraw' }))
    expect(await within(confirm).findByRole('alert')).toHaveTextContent(
      'Declarations for this meeting can no longer be changed here. Ask the secretary.',
    )
    frozen = false
    await user.click(within(confirm).getByRole('button', { name: 'Withdraw' }))
    expect(
      await screen.findByText('Your declaration has been withdrawn. The secretary and the chair have been told.'),
    ).toBeInTheDocument()
    expect(calls.ifMatch).toEqual(['"3"', '"4"', '"4"'])
    expect(calls.bodies.at(-1)).toEqual({ reason: 'The loan is to a different Delta company.' })
    // The item is back in full, with "Declare an interest" in the same place, still focused.
    const again = await within(await waitFor(() => itemNamed('5 Term loan, Delta Textiles'))).findByRole('button', {
      name: 'Declare an interest: 5 Term loan, Delta Textiles',
    })
    await waitFor(() => {
      expect(again).toHaveFocus()
    })
  }, 30_000)

  it('asks only about items added since the last confirmation, declares from the list, and confirms (S3)', async () => {
    const user = userEvent.setup()
    const earlier = declaration({ id: 'd3', itemId: 'i3', itemNumber: '3', itemTitle: 'Minutes of the last meeting' })
    const state: State = {
      agenda: agendaWith(loan()),
      mine: mineOf({
        reviewedRevision: 1,
        declarations: [earlier],
        toReview: [review('i5', '5', 'Term loan, Delta Textiles'), review('i6', '6', 'Market risk limits')],
      }),
      agendaReads: 0,
      mineReads: 0,
    }
    const reviewed: unknown[] = []
    server.use(
      ...handlers(state),
      http.post(`${API}/meetings/m1/items/i6/declarations`, () => {
        const made = declaration({ id: 'd6', itemId: 'i6', itemNumber: '6', itemTitle: 'Market risk limits' })
        state.mine = { ...state.mine, declarations: [earlier, made] }
        return HttpResponse.json<DeclarationResponse>(made, { status: 201 })
      }),
      http.post(`${API}/meetings/m1/declarations/review`, async ({ request }) => {
        const body = (await request.json()) as { revision: number }
        reviewed.push(body)
        if (body.revision !== 3) {
          // Revision 3 was published meanwhile, adding item 3a.
          state.mine = {
            ...state.mine,
            latestRevision: 3,
            toReview: [...state.mine.toReview, review('i3a', '3a', 'Liquidity stress results')],
          }
          return HttpResponse.json(
            { type: 'urn:memo:problem:conflict', status: 409, title: 'Conflict', reason: 'STALE_REVISION' },
            { status: 409 },
          )
        }
        state.mine = { ...state.mine, reviewedRevision: 3, toReview: [] }
        return HttpResponse.json<MyDeclarationsResponse>(state.mine)
      }),
    )
    renderRoute('/meetings/m1')

    const panel = await screen.findByRole('region', { name: 'Declarations of interest' })
    expect(panel).toHaveTextContent('Please read the agenda and tell us about any interest.')
    expect(panel).toHaveTextContent('2 new items to review')
    await user.click(within(panel).getByRole('button', { name: 'Review and declare' }))
    const sheet = await screen.findByRole('dialog', {
      name: 'Review the agenda for December 12 — declarations of interest',
    })
    expect(sheet).toHaveTextContent('Only the items added since you confirmed revision 1 are listed.')
    const list = within(sheet).getByRole('list', { name: 'Items to review' })
    expect(
      within(list)
        .getAllByRole('listitem')
        .map((li) => li.firstChild?.textContent),
    ).toEqual(['5 Term loan, Delta Textiles', '6 Market risk limits'])

    // "I have an interest" opens the declaration sheet over the list; the row then says what was declared.
    await user.click(within(list).getByRole('button', { name: 'I have an interest: 6 Market risk limits' }))
    const declare = await screen.findByRole('dialog', { name: 'Declare an interest — 6 Market risk limits' })
    await user.type(within(declare).getByRole('textbox'), 'Non-executive director of a counterparty.')
    await user.click(within(declare).getByRole('button', { name: 'Declare' }))
    const declared = await within(list).findByRole('button', {
      name: 'Declared — leaving the room: 6 Market risk limits',
    })
    await waitFor(() => {
      expect(declared).toHaveFocus()
    })
    expect(screen.getByRole('dialog', { name: /^Review the agenda/ })).toBeInTheDocument()

    // The agenda changed meanwhile: said in words, the new item listed, and the next confirmation is for revision 3.
    await user.click(within(sheet).getByRole('button', { name: 'I have declared my interests in this agenda' }))
    expect(await within(sheet).findByRole('alert')).toHaveTextContent(
      'The agenda changed while you were reviewing it. The new items are now listed: look at them, then confirm again.',
    )
    expect(await within(list).findByText('3a Liquidity stress results')).toBeInTheDocument()
    await user.click(within(sheet).getByRole('button', { name: 'I have declared my interests in this agenda' }))
    expect(await screen.findByText('Thank you. Your review of the agenda is recorded.')).toBeInTheDocument()
    expect(reviewed).toEqual([{ revision: 2 }, { revision: 3 }])
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull()
    })
    const after = screen.getByRole('region', { name: 'Declarations of interest' })
    expect(after).toHaveTextContent('You confirmed your declarations for revision 3 of the agenda.')
    expect(after).not.toHaveTextContent('to review')
    // "Review and declare" has gone with the items to review: focus goes to the panel's heading.
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 2, name: 'Declarations of interest' })).toHaveFocus()
    })
  }, 30_000)

  it('lists who declared under the Declarations item, never the nature, and offers Declare only where it may', async () => {
    const taking = declaration({
      id: 'd3',
      itemId: 'i3',
      itemNumber: '3',
      itemTitle: 'Minutes of the last meeting',
      intent: 'ASK_CHAIR',
      state: 'TAKING_PART',
      ruling: { outcome: 'TAKE_PART', at: '2026-12-10T05:00:00Z', how: 'IN_APP', by: { id: 'u9', name: 'Chair' } },
    })
    const agenda = agendaWith({
      ...loan(),
      declarations: [
        { id: 'x1', user: { id: 'u2', name: 'S. Ali' }, state: 'RECUSED', intent: 'LEAVE' },
        { id: 'x2', user: { id: 'u3', name: 'R. Khan' }, state: 'AWAITING_RULING', intent: 'ASK_CHAIR' },
      ],
    })
    const opening = agenda.sections[0]
    if (!opening) throw new Error('no section')
    opening.items = opening.items.map((i) => (i.id === 'i3' ? { ...i, myDeclaration: taking } : i))
    const state: State = { agenda, mine: mineOf({ declarations: [taking] }), agendaReads: 0, mineReads: 0 }
    server.use(...handlers(state))
    const { container } = renderRoute('/meetings/m1')

    const standing = await screen.findByRole('list', { name: 'Declarations made so far' })
    expect(
      within(standing)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual([
      'You — declared an interest in item 3 and may take part',
      'S. Ali — will leave the room for item 5',
      'R. Khan — has asked the chair to decide on item 5',
    ])
    expect(itemNamed('2 Declarations of interest')).toContainElement(standing)
    // A ruling to take part: the item in full, why, and "My declaration".
    const minutes = itemNamed('3 Minutes of the last meeting')
    expect(minutes).toHaveTextContent('You declared an interest. The chair decided you may take part.')
    expect(within(minutes).getByRole('button', { name: 'My declaration: 3 Minutes of the last meeting' })).toBeVisible()
    // Declare where the member sees the title (in full, or by title only); never on "Restricted item", a break or a
    // standing item.
    expect(
      within(itemNamed('6 Market risk limits — papers restricted')).getByRole('button', {
        name: 'Declare an interest: 6 Market risk limits',
      }),
    ).toBeInTheDocument()
    expect(within(itemNamed('7 Restricted item')).queryByRole('button')).toBeNull()
    expect(within(itemNamed('Break')).queryByRole('button')).toBeNull()
    expect(within(itemNamed('1 Welcome and apologies')).queryByRole('button')).toBeNull()
    expect(document.body).not.toHaveTextContent('brother-in-law')
    await expectNoA11yViolations(container)
  }, 30_000)

  it('offers nothing to declare to a forum.read holder outside the audience, or once the meeting is held', async () => {
    const state: State = { agenda: agendaWith(loan()), mine: mineOf(), agendaReads: 0, mineReads: 0 }
    server.use(...handlers(state, { meeting: { ...meeting, relation: 'READER' } }))
    const first = renderRoute('/meetings/m1')
    expect(await screen.findByRole('heading', { level: 4, name: '5 Term loan, Delta Textiles' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Declare an interest/ })).toBeNull()
    expect(screen.queryByRole('region', { name: 'Declarations of interest' })).toBeNull()
    expect(state.mineReads).toBe(0)
    first.unmount()

    const held: State = {
      agenda: agendaWith(loan(), { status: 'HELD' }),
      mine: mineOf(),
      agendaReads: 0,
      mineReads: 0,
    }
    server.use(...handlers(held, { meeting: { ...meeting, status: 'HELD' } }))
    renderRoute('/meetings/m1')
    expect(await screen.findByRole('heading', { level: 4, name: '5 Term loan, Delta Textiles' })).toBeInTheDocument()
    await waitFor(() => {
      expect(held.mineReads).toBe(1)
    })
    expect(screen.queryByRole('button', { name: /^Declare an interest/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Review and declare' })).toBeNull()
  }, 30_000)

  it('reads right to left in Arabic without accessibility violations, with the sheet open', async () => {
    const user = userEvent.setup()
    const recused = declaration({ id: 'd6', itemId: 'i6', itemNumber: '6', itemTitle: 'Market risk limits' })
    const agenda = agendaWith(loan())
    const decision = agenda.sections[1]
    if (!decision) throw new Error('no section')
    decision.items = decision.items.map((i) => (i.id === 'i6' ? { ...i, recused: true, myDeclaration: recused } : i))
    const state: State = { agenda, mine: mineOf({ declarations: [recused] }), agendaReads: 0, mineReads: 0 }
    server.use(...handlers(state))
    const { container } = renderRoute('/meetings/m1', { locale: 'ar' })

    const panel = await screen.findByRole('region', { name: 'الإفصاح عن المصالح' })
    expect(document.documentElement).toHaveAttribute('dir', 'rtl')
    expect(panel).toHaveTextContent('3 بنود للمراجعة')
    expect(itemNamed(/^6 Market risk limits/)).toHaveTextContent(
      'سيُطلب منك مغادرة القاعة أثناء هذا البند، ولا تُعرض عليك أوراقه.',
    )
    await user.click(screen.getByRole('button', { name: 'الإفصاح عن مصلحة: 5 Term loan, Delta Textiles' }))
    const sheet = await screen.findByRole('dialog', { name: 'الإفصاح عن مصلحة — 5 Term loan, Delta Textiles' })
    expect(within(sheet).getByRole('radio', { name: /سأغادر القاعة أثناء هذا البند\. \(موصى به\)/ })).toBeChecked()
    await expectNoA11yViolations(container)
  }, 30_000)
})
