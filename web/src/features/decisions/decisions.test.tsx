import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type {
  AttendanceResponse,
  DecisionRequest,
  DecisionResponse,
  ForumPolicyResponse,
  ItemNoteResponse,
  LiveQuorumResponse,
  MeetingResponse,
  MemberAgendaResponse,
  MemberItemResponse,
  QuorumSeatResponse,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const API = `${API_ORIGIN}/api/v1`

vi.mock('@/features/meetings/time', async (original) => ({
  ...(await original<typeof import('@/features/meetings/time')>()),
  viewerZone: () => 'Asia/Dhaka',
}))

/** BRC 3/2026 in progress on 12 December 2026, 10:00–13:00 in Dhaka; the reader is its secretary. */
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
  status: 'IN_PROGRESS',
  relation: 'OFFICER',
  canManage: true,
  guests: [],
  version: 5,
}

const item = (
  id: string,
  number: string,
  title: string,
  extra: Partial<MemberItemResponse> = {},
): MemberItemResponse => ({
  id,
  number,
  title,
  kind: 'ITEM',
  access: 'FULL',
  late: 'NONE',
  changes: [],
  papers: [],
  presenters: [],
  subItems: [],
  links: [],
  ...extra,
})

const agenda = (
  status: MemberAgendaResponse['status'] = 'IN_PROGRESS',
  items?: MemberItemResponse[],
): MemberAgendaResponse => ({
  meetingId: 'm1',
  status,
  startsAt: meeting.startsAt,
  endsAt: meeting.endsAt,
  timeZone: 'Asia/Dhaka',
  revision: 1,
  latestRevision: 1,
  preview: false,
  sections: [
    {
      id: 's1',
      kind: 'STANDARD',
      membersOnly: false,
      title: 'For decision',
      items: items ?? [
        item('i3', '3', 'Minutes of the last meeting', { purpose: 'INFORMATION' }),
        item('i5', '5', 'Term loan, Delta Textiles', {
          kind: 'SUBMISSION',
          purpose: 'DECISION',
          resolutionSought: 'To approve a term loan of BDT 60 million to Delta Textiles.',
        }),
        item('i6', '6', 'Working capital, Karnaphuli', { kind: 'SUBMISSION', purpose: 'DECISION' }),
      ],
    },
    {
      id: 's2',
      kind: 'CONSENT',
      membersOnly: false,
      title: 'Consent items',
      items: [
        item('i7', '7', 'Compliance report Q3', { purpose: 'INFORMATION' }),
        item('i8', '8', 'Terms of reference', {
          purpose: 'DECISION',
          discussion: { canAsk: false, mine: false, requestedBy: [{ id: 'u4', name: 'S. Ali' }] },
        }),
      ],
    },
  ],
})

const policy: ForumPolicyResponse = {
  typeId: 't1',
  typeName: 'Board committee',
  version: 2,
  memberAttributes: [],
  outcomes: [
    { kind: 'APPROVED', label: 'Approved', resolution: true },
    { kind: 'APPROVED_WITH_CONDITIONS', label: 'Approved with conditions', resolution: true },
    { kind: 'REJECTED', label: 'Rejected', resolution: true },
    { kind: 'DEFERRED', label: 'Deferred', resolution: false },
    { kind: 'NOTED', label: 'Noted', resolution: false },
  ],
  quorum: { counts: 'VOTING', basis: 'IN_OFFICE', recusedCount: false, remoteCounts: true, alternatesCount: true },
  quorumBasis: 7,
  quorumNeeded: 4,
  quorumOverride: 'WITH_REASON',
  memoResumes: 'AT_MEETING_END',
  submissionPolicy: {},
  termPolicy: {},
}

const seat = (memberId: string, userId: string, name: string, extra: Partial<QuorumSeatResponse> = {}) =>
  ({
    memberId,
    holder: { userId, name },
    role: 'MEMBER',
    voting: true,
    filledBy: { userId, name },
    byAlternate: false,
    remote: false,
    recused: false,
    steppedOut: false,
    ...extra,
  }) satisfies QuorumSeatResponse

/** Item 5: five of seven present, Demo Director declared an interest, so four may vote. */
const seats: QuorumSeatResponse[] = [
  seat('s1', 'u1', 'Dr F. Karim', { role: 'CHAIR' }),
  seat('s2', 'u2', 'Demo Director', { recused: true }),
  seat('s4', 'u4', 'S. Ali'),
  seat('s6', 'u6', 'R. Das'),
  seat('s8', 'u8', 'M. Hossain'),
  seat('s5', 'u5', 'N. Chowdhury', { filledBy: null }),
  seat('s3', 'u3', 'T. Hasan', { filledBy: null }),
]
const quorate: LiveQuorumResponse = { quorate: true, basis: 7, needed: 4, present: 5, unmet: [], seats }
const notQuorate: LiveQuorumResponse = {
  quorate: false,
  basis: 7,
  needed: 4,
  present: 3,
  unmet: [],
  seats: seats.map((s) => (s.memberId === 's6' || s.memberId === 's8' ? { ...s, filledBy: null } : s)),
}

const register: AttendanceResponse = {
  meetingId: 'm1',
  status: 'IN_PROGRESS',
  canRecord: true,
  correcting: false,
  itemInProgress: { itemId: 'i5', startedAt: '2026-12-12T05:00:00Z' },
  counts: { inPerson: 5, remote: 0, apologies: 0, expected: 2, left: 0, absent: 0, total: 7 },
  people: [],
  events: [],
  quorum: quorate,
}

const labels: Record<string, string> = {
  APPROVED: 'Approved',
  APPROVED_WITH_CONDITIONS: 'Approved with conditions',
  REJECTED: 'Rejected',
  DEFERRED: 'Deferred',
  NOTED: 'Noted',
}

const decision = (itemId: string, extra: Partial<DecisionResponse> = {}): DecisionResponse => ({
  id: `d-${itemId}`,
  itemId,
  itemNumber: itemId.slice(1),
  outcome: 'APPROVED_WITH_CONDITIONS',
  outcomeLabel: 'Approved with conditions',
  resolution: true,
  text: 'It was RESOLVED that the term loan be approved.',
  conditions: ['Security perfected before disbursement'],
  decidedAt: '2026-12-12T05:00:00Z',
  seq: 1,
  state: 'RECORDED',
  toBeRatified: false,
  ratificationRequired: false,
  viewerRecused: false,
  version: 3,
  vote: { mode: 'VOTE', votesFor: 3, votesAgainst: 1, votesAbstain: 0 },
  dissent: [{ person: { id: 'u4', name: 'S. Ali' }, reason: 'Sector outlook' }],
  abstentions: [],
  tookNoPart: [{ id: 'u2', name: 'Demo Director' }],
  recordedBy: { id: 'sec', name: 'K. Secretary' },
  recordedAt: '2026-12-12T05:10:00Z',
  ...extra,
})

interface Sent {
  path: string
  body: Record<string, unknown>
  key: string | null
  ifMatch: string | null
}

interface Setup {
  meeting?: MeetingResponse
  agenda?: MemberAgendaResponse
  decisions?: DecisionResponse[]
  quorum?: LiveQuorumResponse
  policy?: ForumPolicyResponse
  /** Answers a write instead of the default; undefined: the default. */
  answer?: (sent: Sent, count: number) => Response | undefined
  notes?: ItemNoteResponse
}

const problem = (status: number, extra: Record<string, unknown>) =>
  HttpResponse.json({ type: 'urn:memo:problem:conflict', status, ...extra }, { status })

function setup(options: Setup = {}) {
  const sent: Sent[] = []
  const state = { decisions: options.decisions ?? [] }
  const write = async (path: string, request: Request) => {
    const body = (await request.json()) as Record<string, unknown>
    const s = { path, body, key: request.headers.get('Idempotency-Key'), ifMatch: request.headers.get('If-Match') }
    sent.push(s)
    return { s, answer: options.answer?.(s, sent.length) }
  }
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([])),
    http.get(`${API}/meetings/m1`, () => HttpResponse.json<MeetingResponse>(options.meeting ?? meeting)),
    http.get(`${API}/meetings/m1/agenda`, () => HttpResponse.json<MemberAgendaResponse>(options.agenda ?? agenda())),
    http.get(`${API}/forums/f1/policy`, () => HttpResponse.json<ForumPolicyResponse>(options.policy ?? policy)),
    http.get(`${API}/meetings/m1/attendance`, () => HttpResponse.json<AttendanceResponse>(register)),
    http.get(`${API}/meetings/m1/quorum/changes`, () => HttpResponse.json([])),
    http.get(`${API}/meetings/m1/quorum`, () => HttpResponse.json<LiveQuorumResponse>(options.quorum ?? quorate)),
    http.get(`${API}/meetings/m1/decisions`, () => HttpResponse.json({ meetingId: 'm1', decisions: state.decisions })),
    http.get(`${API}/forums/f1/decisions/to-be-ratified`, () => HttpResponse.json([])),
    http.get(`${API}/meetings/m1/minutes`, () => problem(404, { type: 'about:blank' })),
    http.get(`${API}/meetings/m1/declarations/mine`, () =>
      HttpResponse.json({ meetingId: 'm1', declarations: [], canDeclare: false }),
    ),
    http.get(`${API}/meetings/m1/items/:itemId/notes`, ({ params }) =>
      HttpResponse.json<ItemNoteResponse>(options.notes ?? { itemId: String(params.itemId), seq: 0, text: '' }),
    ),
    http.put(`${API}/meetings/m1/items/:itemId/notes`, async ({ request, params }) => {
      const { s, answer } = await write(`/items/${String(params.itemId)}/notes`, request)
      return (
        answer ??
        HttpResponse.json<ItemNoteResponse>({
          itemId: String(params.itemId),
          seq: 1,
          text: String(s.body.text),
          savedAt: '2026-12-12T05:12:00Z',
          savedBy: { id: 'sec', name: 'K. Secretary' },
        })
      )
    }),
    http.get(`${API}/meetings/m1/items/:itemId/decision/entries`, ({ params }) =>
      HttpResponse.json({
        decisionId: `d-${String(params.itemId)}`,
        itemId: String(params.itemId),
        entries: [
          {
            seq: 1,
            outcome: 'APPROVED',
            outcomeLabel: 'Approved',
            resolution: true,
            correction: false,
            conditions: [],
            decidedAt: '2026-12-12T05:00:00Z',
            recordedAt: '2026-12-12T05:10:00Z',
            recordedBy: { id: 'sec', name: 'K. Secretary' },
            ratificationRequired: false,
            snapshotId: 'q1',
            vote: { mode: 'CONSENSUS' },
            dissent: [],
            abstentions: [],
            tookNoPart: [],
          },
          {
            seq: 2,
            outcome: 'APPROVED_WITH_CONDITIONS',
            outcomeLabel: 'Approved with conditions',
            resolution: true,
            correction: true,
            reason: 'Condition wording agreed with the chair',
            conditions: ['Security perfected before disbursement'],
            decidedAt: '2026-12-12T05:00:00Z',
            recordedAt: '2026-12-14T08:00:00Z',
            recordedBy: { id: 'sec', name: 'K. Secretary' },
            ratificationRequired: false,
            snapshotId: 'q1',
            vote: { mode: 'CONSENSUS' },
            dissent: [],
            abstentions: [],
            tookNoPart: [],
          },
        ],
      }),
    ),
    http.put(`${API}/meetings/m1/items/:itemId/decision`, async ({ request, params }) => {
      const { s, answer } = await write(`/items/${String(params.itemId)}/decision`, request)
      if (answer) return answer
      const body = s.body as unknown as DecisionRequest
      const recorded = decision(String(params.itemId), {
        outcome: body.outcome,
        outcomeLabel: labels[body.outcome] ?? body.outcome,
        resolution: body.outcome !== 'NOTED' && body.outcome !== 'DEFERRED',
        text: body.text ?? null,
        conditions: body.conditions ?? [],
        toBeRatified: body.override != null,
        version: 4,
      })
      state.decisions = [...state.decisions.filter((d) => d.itemId !== recorded.itemId), recorded]
      return HttpResponse.json<DecisionResponse>(recorded)
    }),
    http.post(`${API}/meetings/m1/sections/:sectionId/decision`, async ({ request, params }) => {
      const { answer } = await write(`/sections/${String(params.sectionId)}/decision`, request)
      if (answer) return answer
      state.decisions = [
        ...state.decisions,
        decision('i7', { outcome: 'NOTED', outcomeLabel: 'Noted', resolution: false, consentBatchId: 'b1' }),
      ]
      return HttpResponse.json({ meetingId: 'm1', decisions: state.decisions })
    }),
  )
  return { sent, state }
}

async function panel() {
  return screen.findByRole('region', { name: 'Item 5 — Term loan, Delta Textiles' }, { timeout: 5000 })
}

describe('Recording decisions (Phase 4 E06 T10, flow AA)', () => {
  it('records "Approved with conditions" with a condition, a vote and a named dissent from the people present', async () => {
    const user = userEvent.setup()
    const { sent } = setup()
    const { container } = renderRoute('/meetings/m1?tab=decisions')
    expect(partsForPath('/meetings/m1')).toContain('decisions')
    expect(await screen.findByRole('tab', { name: 'Decisions' }, { timeout: 5000 })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    const p = await panel()
    expect(screen.getByText('Now: Item 5 — Term loan, Delta Textiles')).toBeInTheDocument()
    expect(
      await within(p).findByText(
        'Quorate for Item 5: 5 of 7 voting members present, 4 needed (Demo Director declared an interest).',
      ),
    ).toBeInTheDocument()
    expect(within(p).getByText('Took no part: Demo Director (declared an interest).')).toBeInTheDocument()
    // The resolution sought fills the text.
    expect(within(p).getByLabelText(/^Resolution \(as it will be minuted\)/)).toHaveValue(
      'To approve a term loan of BDT 60 million to Delta Textiles.',
    )

    await user.click(within(p).getByRole('radio', { name: 'Approved with conditions' }))
    await user.type(within(p).getByRole('textbox', { name: 'Condition 1' }), 'Security perfected before disbursement')
    await user.click(within(p).getByRole('button', { name: 'Record a vote (optional)' }))
    expect(within(p).getByText('4 voting members present and able to vote.')).toBeInTheDocument()
    await user.type(within(p).getByRole('textbox', { name: 'For' }), '3')
    await user.type(within(p).getByRole('textbox', { name: 'Against' }), '1')
    await user.click(within(p).getByRole('button', { name: 'Dissent and abstentions (optional)' }))
    // Demo Director declared an interest: not offered.
    expect(within(p).queryByRole('group', { name: 'Demo Director' })).not.toBeInTheDocument()
    const ali = within(p).getByRole('group', { name: 'S. Ali' })
    await user.click(within(ali).getByRole('radio', { name: 'Dissented' }))
    await user.type(within(p).getByLabelText('Reason they asked to be recorded (optional)'), 'Sector outlook')
    await expectNoA11yViolations(container)
    await user.click(within(p).getByRole('button', { name: 'Record the decision' }))

    await waitFor(() => {
      expect(sent.filter((s) => s.path === '/items/i5/decision')).toHaveLength(1)
    })
    const s = sent.find((x) => x.path === '/items/i5/decision')
    expect(s?.key).toMatch(/^[0-9a-f-]{36}$/)
    expect(s?.ifMatch).toBeNull()
    expect(s?.body).toMatchObject({
      outcome: 'APPROVED_WITH_CONDITIONS',
      text: 'To approve a term loan of BDT 60 million to Delta Textiles.',
      conditions: ['Security perfected before disbursement'],
      voteMode: 'VOTE',
      votesFor: 3,
      votesAgainst: 1,
      votesAbstain: 0,
      dissent: [{ userId: 'u4', reason: 'Sector outlook' }],
      abstentions: [],
    })
    expect(s?.body).not.toHaveProperty('tookNoPart')
    const nav = screen.getByRole('navigation', { name: 'Agenda' })
    expect(
      await within(nav).findByText('Approved with conditions — numbered when the meeting ends'),
    ).toBeInTheDocument()
    expect(await within(p).findByRole('button', { name: 'Correct' })).toBeInTheDocument()
    expect(within(p).getByText('S. Ali asked that their dissent be recorded: Sector outlook')).toBeInTheDocument()
  }, 30_000)

  it('says in words what cannot be sent: no outcome, too many votes, a recused person named, a stale decision', async () => {
    const user = userEvent.setup()
    const { sent } = setup({
      answer: (s) =>
        s.path === '/items/i5/decision'
          ? problem(422, {
              type: 'urn:memo:problem:validation',
              errors: [{ field: 'dissent/0/userId', code: 'RECUSED_NAMED', message: 'Recused.' }],
            })
          : undefined,
    })
    renderRoute('/meetings/m1?tab=decisions')
    const p = await panel()
    await user.click(await within(p).findByRole('button', { name: 'Record the decision' }))
    expect(await within(p).findByText('Choose the decision.')).toBeInTheDocument()
    expect(sent).toHaveLength(0)

    await user.click(within(p).getByRole('radio', { name: 'Approved' }))
    await user.click(within(p).getByRole('button', { name: 'Record a vote (optional)' }))
    await user.type(within(p).getByRole('textbox', { name: 'For' }), '4')
    await user.type(within(p).getByRole('textbox', { name: 'Against' }), '1')
    await user.click(within(p).getByRole('button', { name: 'Record the decision' }))
    expect(
      await within(p).findByText('There are only 4 voting members present who may vote on this item.'),
    ).toBeInTheDocument()
    expect(sent).toHaveLength(0)

    await user.clear(within(p).getByRole('textbox', { name: 'For' }))
    await user.type(within(p).getByRole('textbox', { name: 'For' }), '3')
    await user.click(within(p).getByRole('button', { name: 'Dissent and abstentions (optional)' }))
    await user.click(within(within(p).getByRole('group', { name: 'S. Ali' })).getByRole('radio', { name: 'Dissented' }))
    await user.click(within(p).getByRole('button', { name: 'Record the decision' }))
    expect(
      await within(p).findByText(
        'This person declared an interest in the item and took no part, so they cannot be named.',
      ),
    ).toBeInTheDocument()
    expect(sent).toHaveLength(1)
  }, 30_000)

  it('not quorate: says so, and records without a quorum through the override dialog with a reason', async () => {
    const user = userEvent.setup()
    const { sent } = setup({ quorum: notQuorate })
    renderRoute('/meetings/m1?tab=decisions')
    const p = await panel()
    expect(
      await within(p).findByText('No decision can be recorded: the item is not quorate (3 of 4 needed).'),
    ).toBeInTheDocument()
    expect(within(p).queryByRole('button', { name: 'Record the decision' })).not.toBeInTheDocument()
    await user.click(within(p).getByRole('radio', { name: 'Approved' }))
    await user.click(within(p).getByRole('button', { name: 'Record without a quorum…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Record without quorum' })
    await user.type(within(dialog).getByRole('textbox'), 'Urgent liquidity need')
    await user.click(within(dialog).getByRole('button', { name: /^Record/ }))
    await waitFor(() => {
      expect(sent).toHaveLength(1)
    })
    expect(sent[0]?.body).toMatchObject({ outcome: 'APPROVED', override: { reason: 'Urgent liquidity need' } })
    expect(
      await within(p).findByText('Decided without a quorum, to be ratified at a later quorate meeting.'),
    ).toBeInTheDocument()
  }, 30_000)

  it('the committee type allows no override: the dialog says why no decision can be recorded (OVERRIDE_NOT_ALLOWED)', async () => {
    const user = userEvent.setup()
    setup({
      quorum: notQuorate,
      policy: { ...policy, version: 1 },
      answer: () => problem(409, { reason: 'OVERRIDE_NOT_ALLOWED' }),
    })
    renderRoute('/meetings/m1?tab=decisions')
    const p = await panel()
    await user.click(await within(p).findByRole('radio', { name: 'Noted' }))
    await user.click(within(p).getByRole('button', { name: 'Record without a quorum…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Record without quorum' })
    await user.type(within(dialog).getByRole('textbox'), 'We must')
    await user.click(within(dialog).getByRole('button', { name: /^Record/ }))
    expect(
      await screen.findByText('The committee’s rules do not allow a decision without a quorum.'),
    ).toBeInTheDocument()
  }, 30_000)

  it('corrects a released decision: the outcome stays, a reason is asked, the version goes as If-Match; FROZEN and MINUTES_IN_REVIEW in words', async () => {
    const user = userEvent.setup()
    const { sent } = setup({
      meeting: { ...meeting, status: 'HELD' },
      agenda: agenda('HELD'),
      decisions: [decision('i5', { state: 'RELEASED', resolutionNumber: 'BRC/2026/014', resolutionId: 'r14' })],
      answer: (s, n) =>
        s.path !== '/items/i5/decision'
          ? undefined
          : n === 1
            ? problem(409, { reason: 'MINUTES_IN_REVIEW' })
            : n === 2
              ? undefined
              : problem(409, { reason: 'FROZEN' }),
    })
    renderRoute('/meetings/m1?tab=decisions')
    const p = await panel()
    expect(await within(p).findByText('APPROVED WITH CONDITIONS — Resolution BRC/2026/014')).toBeInTheDocument()
    await user.click(within(p).getByRole('button', { name: 'History' }))
    expect(
      await within(p).findByText(/^Corrected .+ by K\. Secretary: Condition wording agreed with the chair$/),
    ).toBeInTheDocument()
    await user.click(within(p).getByRole('button', { name: 'Correct' }))
    expect(within(p).getByRole('radio', { name: 'Approved' })).toBeDisabled()
    expect(within(p).getByText(/^The memo has already moved on with this outcome\./)).toBeInTheDocument()
    expect(within(p).getByLabelText(/^When was this item taken\?/)).toHaveValue('11:00')

    await user.click(within(p).getByRole('button', { name: 'Correct the decision' }))
    expect(await within(p).findByText('Give a reason.')).toBeInTheDocument()
    await user.type(within(p).getByLabelText(/^Why are you correcting it\?/), 'Condition wording agreed with the chair')
    await user.click(within(p).getByRole('button', { name: 'Correct the decision' }))
    expect(
      (
        await within(p).findAllByText(
          'The minutes are with the chair or the members. Correcting this decision sends them back to the draft.',
        )
      ).length,
    ).toBeGreaterThan(0)
    await user.click(within(p).getByRole('button', { name: 'Correct the decision' }))
    await waitFor(() => {
      expect(sent).toHaveLength(2)
    })
    expect(sent[0]?.ifMatch).toBe('"3"')
    expect(sent[0]?.body).not.toHaveProperty('returnMinutesToDraft')
    expect(sent[1]?.body).toMatchObject({
      outcome: 'APPROVED_WITH_CONDITIONS',
      reason: 'Condition wording agreed with the chair',
      returnMinutesToDraft: true,
    })
    expect(sent[1]?.body).not.toHaveProperty('decidedAt')
  }, 30_000)

  it('decides the consent items together: the lifted item is decided on its own', async () => {
    const user = userEvent.setup()
    const { sent } = setup()
    renderRoute('/meetings/m1?tab=decisions')
    await panel()
    await user.click(await screen.findByRole('button', { name: 'Decide the consent items together' }))
    const dialog = await screen.findByRole('dialog', { name: 'Decide the consent items together' })
    expect(within(dialog).getByText('Item 7 will be Noted.')).toBeInTheDocument()
    expect(within(dialog).getByText('Item 8 was lifted for discussion and is decided on its own.')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Decide together' }))
    await waitFor(() => {
      expect(sent.map((s) => s.path)).toEqual(['/sections/s2/decision'])
    })
    expect(await screen.findByText('The consent items are decided.')).toBeInTheDocument()
  }, 30_000)

  it('saves the notes when the box is left, with If-Match on the version read; another secretary’s newer notes win', async () => {
    const user = userEvent.setup()
    const { sent } = setup({
      answer: (s, n) => (s.path.endsWith('/notes') && n === 2 ? problem(412, { type: 'about:blank' }) : undefined),
    })
    renderRoute('/meetings/m1?tab=decisions')
    const p = await panel()
    const notes = await within(p).findByLabelText('Notes for the minutes (only secretaries see these)')
    await user.type(notes, 'CFO explained the security cover')
    await user.tab()
    await waitFor(() => {
      expect(sent).toHaveLength(1)
    })
    expect(sent[0]).toMatchObject({
      path: '/items/i5/notes',
      ifMatch: '"0"',
      body: { text: 'CFO explained the security cover' },
    })
    expect(await within(p).findByText(/^Saved at /)).toBeInTheDocument()
    await user.type(notes, '.')
    await user.tab()
    expect(
      await within(p).findByText(
        'Another secretary changed these notes. Their version is shown; your text was not saved.',
      ),
    ).toBeInTheDocument()
    expect(sent[1]?.ifMatch).toBe('"1"')
  }, 30_000)

  it('a recused secretary records nothing on the item (RECUSED) and reads no notes', async () => {
    const user = userEvent.setup()
    const recusedAgenda = agenda('IN_PROGRESS', [
      item('i5', '5', 'Term loan, Delta Textiles', { kind: 'SUBMISSION', purpose: 'DECISION', recused: true }),
      item('i6', '6', 'Working capital, Karnaphuli', { kind: 'SUBMISSION', purpose: 'DECISION' }),
    ])
    const { sent } = setup({ agenda: recusedAgenda, answer: () => problem(403, { reason: 'RECUSED' }) })
    renderRoute('/meetings/m1?tab=decisions')
    const p = await panel()
    expect(
      within(p).getByText(
        'You declared an interest in this item, so you cannot record its decision or read its notes. Another secretary records it.',
      ),
    ).toBeInTheDocument()
    expect(within(p).queryByRole('button', { name: 'Record the decision' })).not.toBeInTheDocument()
    expect(within(p).queryByLabelText(/^Notes for the minutes/)).not.toBeInTheDocument()
    // Item 6: the server's 403 RECUSED is said in words.
    await user.click(screen.getByRole('button', { name: /^Item 6 — Working capital/ }))
    const six = await screen.findByRole('region', { name: 'Item 6 — Working capital, Karnaphuli' })
    expect(within(six).getByRole('heading', { name: 'Item 6 — Working capital, Karnaphuli' })).toHaveFocus()
    await user.click(within(six).getByRole('radio', { name: 'Noted' }))
    await user.click(within(six).getByRole('button', { name: 'Record the decision' }))
    expect(
      await within(six).findByText('You declared an interest in this item, so you cannot record its decision.'),
    ).toBeInTheDocument()
    expect(sent).toHaveLength(1)
  }, 30_000)

  it('Mark the meeting as held says what will happen: the resolutions, the memos that move on, an item without a decision', async () => {
    const user = userEvent.setup()
    setup({
      decisions: [decision('i5'), decision('i3', { outcome: 'NOTED', outcomeLabel: 'Noted', resolution: false })],
    })
    renderRoute('/meetings/m1?tab=decisions')
    await panel()
    await user.click(screen.getByRole('button', { name: /^Actions for / }))
    await user.click(await screen.findByRole('menuitem', { name: /Mark as held|held/i }))
    const dialog = await screen.findByRole('dialog')
    expect(
      await within(dialog).findByText('2 decisions are recorded. 1 is a resolution and will be numbered (5).'),
    ).toBeInTheDocument()
    expect(within(dialog).getByText('1 memo will move on now: item 5 (Approved with conditions).')).toBeInTheDocument()
    expect(
      within(dialog).getByText('Item 6 has no decision. Its memo will keep waiting until you record one.'),
    ).toBeInTheDocument()
    expect(
      within(dialog).getByText('After this, decisions you record will reach their memos at once.'),
    ).toBeInTheDocument()
  }, 30_000)

  it('in Arabic, right to left, the decision panel has no serious axe findings', async () => {
    setup({ decisions: [decision('i5')] })
    const { container } = renderRoute('/meetings/m1?tab=decisions', { locale: 'ar' })
    expect(await screen.findByRole('tab', { name: 'القرارات' }, { timeout: 8000 })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'تصحيح' })).toBeInTheDocument()
    expect(document.documentElement.dir).toBe('rtl')
    await expectNoA11yViolations(container)
  }, 30_000)
})

describe('Decisions as members see them (flow AB)', () => {
  it('a member reads the decision in full; a recused member reads the recused line without the vote or names', async () => {
    const member = { ...meeting, relation: 'MEMBER' as const, canManage: false, status: 'HELD' as const }
    setup({
      meeting: member,
      agenda: agenda('HELD', [
        item('i5', '5', 'Term loan, Delta Textiles', { kind: 'SUBMISSION', purpose: 'DECISION', recused: true }),
        item('i6', '6', 'Working capital, Karnaphuli', { kind: 'SUBMISSION', purpose: 'DECISION' }),
      ]),
      decisions: [
        decision('i5', {
          viewerRecused: true,
          resolutionNumber: 'BRC/2026/014',
          resolutionId: 'r14',
          vote: null,
          dissent: null,
          abstentions: null,
          tookNoPart: null,
          recordedBy: null,
          recordedAt: null,
        }),
        decision('i6', { resolutionNumber: 'BRC/2026/015', resolutionId: 'r15' }),
      ],
    })
    const { container } = renderRoute('/meetings/m1')
    expect(
      await screen.findByText('APPROVED WITH CONDITIONS — Resolution BRC/2026/015', {}, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Decisions' })).not.toBeInTheDocument()
    expect(screen.getByText('Vote: for 3, against 1.')).toBeInTheDocument()
    expect(screen.getByText('Took no part: Demo Director.')).toBeInTheDocument()
    expect(screen.getByText('You declared an interest in this item and took no part.')).toBeInTheDocument()
    expect(screen.getByText('APPROVED WITH CONDITIONS — Resolution BRC/2026/014')).toBeInTheDocument()
    expect(screen.getAllByText('Vote: for 3, against 1.')).toHaveLength(1)
    expect(screen.getAllByRole('link', { name: 'Open the resolution' })[0]).toHaveAttribute('href', '/resolutions/r14')
    await expectNoA11yViolations(container)
  }, 30_000)
})
