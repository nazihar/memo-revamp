import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type {
  AttendanceEventResponse,
  AttendancePersonResponse,
  AttendanceResponse,
  ForumPolicyResponse,
  InvitationsResponse,
  LiveQuorumResponse,
  MeetingResponse,
  MemberAgendaResponse,
  MemberItemResponse,
  QuorumChangeResponse,
  QuorumSeatResponse,
  RecordedAttendanceResponse,
  RepliesResponse,
  ReplyResponse,
} from '@/api/generated/types.gen'
import { getMeetingQuorumSnapshot } from '@/api/generated/sdk.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute, renderWithProviders } from '@/test/render'
import { QuorumOverrideDialog } from './QuorumOverrideDialog'

const API = `${API_ORIGIN}/api/v1`
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

vi.mock('@/features/meetings/time', async (original) => ({
  ...(await original<typeof import('@/features/meetings/time')>()),
  viewerZone: () => 'Asia/Dhaka',
}))

/** BRC 3/2026 in progress on Saturday 12 December 2026, 10:00–13:00 in Dhaka; the reader is its secretary. */
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
  location: 'Board Room, Level 12',
  classificationId: 'l1',
  number: 3,
  label: 'BRC 3/2026',
  status: 'IN_PROGRESS',
  relation: 'OFFICER',
  canManage: true,
  guests: [],
  version: 5,
}

const item = (id: string, number: string, title: string): MemberItemResponse => ({
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
})

const agenda: MemberAgendaResponse = {
  meetingId: 'm1',
  status: 'IN_PROGRESS',
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
      items: [
        item('i1', '1', 'Welcome and apologies'),
        item('i3', '3', 'Minutes of the last meeting'),
        item('i5', '5', 'Term loan, Delta Textiles'),
      ],
    },
  ],
}

const person = (
  userId: string,
  name: string,
  extra: Partial<AttendancePersonResponse> = {},
): AttendancePersonResponse => ({
  userId,
  name,
  capacity: 'MEMBER',
  role: 'MEMBER',
  state: 'EXPECTED',
  joinedLate: false,
  leftEarly: false,
  counted: false,
  version: 0,
  ...extra,
})

const arrival = (id: string, userId: string, at: string, mode: 'IN_PERSON' | 'REMOTE' = 'IN_PERSON') =>
  ({
    id,
    userId,
    kind: 'ARRIVED',
    mode,
    at,
    correction: false,
    voided: false,
    source: 'RECORDED',
    recordedAt: at,
    recordedBy: { userId: 'sec', name: 'K. Secretary' },
  }) satisfies AttendanceEventResponse

const people: AttendancePersonResponse[] = [
  person('u1', 'Dr F. Karim', {
    role: 'CHAIR',
    state: 'PRESENT',
    mode: 'IN_PERSON',
    firstArrivedAt: '2026-12-12T04:02:00Z',
    counted: true,
    version: 1,
  }),
  person('u2', 'Demo Director', { state: 'APOLOGIES', version: 1 }),
  person('u4', 'S. Ali', {
    state: 'PRESENT',
    mode: 'REMOTE',
    firstArrivedAt: '2026-12-12T04:05:00Z',
    counted: true,
    version: 1,
  }),
  person('u5', 'N. Chowdhury', { version: 0 }),
  person('u6', 'R. Das', {
    state: 'PRESENT',
    mode: 'IN_PERSON',
    firstArrivedAt: '2026-12-12T04:40:00Z',
    arrivedDuringItem: 'i3',
    joinedLate: true,
    counted: true,
    version: 1,
  }),
  person('u8', 'M. Hossain', { version: 0 }),
  person('u9', 'A. Rahman', {
    capacity: 'ALTERNATE',
    role: undefined,
    standingInFor: { userId: 'u2', name: 'Demo Director' },
    state: 'PRESENT',
    mode: 'IN_PERSON',
    firstArrivedAt: '2026-12-12T04:02:00Z',
    counted: true,
    version: 1,
  }),
  person('u7', 'J. Begum', {
    capacity: 'GUEST',
    role: undefined,
    state: 'PRESENT',
    mode: 'IN_PERSON',
    firstArrivedAt: '2026-12-12T04:02:00Z',
    version: 1,
  }),
]

const events: AttendanceEventResponse[] = [
  {
    id: 'e0',
    userId: 'u2',
    kind: 'APOLOGIES',
    at: '2026-12-12T04:00:00Z',
    correction: false,
    voided: false,
    source: 'RSVP',
    recordedAt: '2026-12-12T04:00:00Z',
  },
  arrival('e1', 'u1', '2026-12-12T04:02:00Z'),
  arrival('e2', 'u9', '2026-12-12T04:02:00Z'),
  arrival('e3', 'u7', '2026-12-12T04:02:00Z'),
  arrival('e4', 'u4', '2026-12-12T04:05:00Z', 'REMOTE'),
  { ...arrival('e5', 'u6', '2026-12-12T04:40:00Z'), itemId: 'i3' },
]

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

const seats: QuorumSeatResponse[] = [
  seat('s1', 'u1', 'Dr F. Karim', { role: 'CHAIR' }),
  seat('s2', 'u2', 'Demo Director', { filledBy: { userId: 'u9', name: 'A. Rahman' }, byAlternate: true }),
  seat('s4', 'u4', 'S. Ali', { remote: true }),
  seat('s5', 'u5', 'N. Chowdhury', { filledBy: null }),
  seat('s6', 'u6', 'R. Das'),
  seat('s8', 'u8', 'M. Hossain', { filledBy: null }),
  seat('s3', 'u3', 'T. Hasan', { filledBy: null }),
]

const quorate: LiveQuorumResponse = { quorate: true, basis: 7, needed: 4, present: 5, unmet: [], seats }

const register = (extra: Partial<AttendanceResponse> = {}): AttendanceResponse => ({
  meetingId: 'm1',
  status: 'IN_PROGRESS',
  canRecord: true,
  correcting: false,
  itemInProgress: { itemId: 'i3', startedAt: '2026-12-12T04:30:00Z' },
  counts: { inPerson: 4, remote: 1, apologies: 1, expected: 2, left: 0, absent: 0, total: 8 },
  people,
  events,
  quorum: quorate,
  ...extra,
})

const reply = (userId: string, name: string, answer: ReplyResponse['answer']): ReplyResponse => ({
  userId,
  name,
  capacity: 'MEMBER',
  answer,
  alternateAttends: false,
  stale: false,
})

const replies: RepliesResponse = {
  meetingId: 'm1',
  canRecord: true,
  counts: { inPerson: 2, remote: 2, apologies: 1, stale: 0, noAnswer: 0, total: 5 },
  members: { inPerson: 2, remote: 2, apologies: 1, stale: 0, noAnswer: 0, total: 5 },
  people: [
    reply('u1', 'Dr F. Karim', 'IN_PERSON'),
    reply('u4', 'S. Ali', 'REMOTE'),
    reply('u5', 'N. Chowdhury', 'IN_PERSON'),
    reply('u8', 'M. Hossain', 'REMOTE'),
    reply('u2', 'Demo Director', 'APOLOGIES'),
  ],
}

const policy: ForumPolicyResponse = {
  typeId: 't1',
  typeName: 'Board committee',
  version: 2,
  memberAttributes: [{ key: 'independent', label: 'Independent' }],
  outcomes: [],
  quorum: { counts: 'VOTING', basis: 'IN_OFFICE', recusedCount: false, remoteCounts: true, alternatesCount: true },
  quorumBasis: 7,
  quorumNeeded: 4,
  submissionPolicy: {},
  termPolicy: {},
}

interface Sent {
  path: string
  body: unknown
  key: string | null
  ifMatch: string | null
}

interface Setup {
  meeting?: MeetingResponse
  register?: AttendanceResponse
  itemQuorum?: (itemId: string) => LiveQuorumResponse
  changes?: QuorumChangeResponse[]
  /** Answers a write instead of the default (the register changed as asked); undefined: the default. */
  answer?: (sent: Sent) => Response | undefined
}

/** The meeting page's requests, with the register kept as the writes leave it. */
function setup(options: Setup = {}) {
  const sent: Sent[] = []
  const state = { register: options.register ?? register() }
  const write = async (path: string, request: Request, apply: (body: Record<string, unknown>) => Response) => {
    const body = (await request.json()) as Record<string, unknown>
    const s = { path, body, key: request.headers.get('Idempotency-Key'), ifMatch: request.headers.get('If-Match') }
    sent.push(s)
    return options.answer?.(s) ?? apply(body)
  }
  const recorded = (event: AttendanceEventResponse, p: AttendancePersonResponse) =>
    HttpResponse.json<RecordedAttendanceResponse>({ event, person: p, quorum: quorate })
  const change = (userId: string, next: Partial<AttendancePersonResponse>) => {
    const people = (state.register.people ?? []).map((p) =>
      p.userId === userId ? { ...p, ...next, version: (p.version ?? -1) + 1 } : p,
    )
    state.register = { ...state.register, people }
    return people.find((p) => p.userId === userId) ?? person(userId, '')
  }
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([])),
    http.get(`${API}/meetings/m1`, () => HttpResponse.json<MeetingResponse>(options.meeting ?? meeting)),
    http.get(`${API}/meetings/m1/agenda`, () => HttpResponse.json<MemberAgendaResponse>(agenda)),
    http.get(`${API}/forums/f1/policy`, () => HttpResponse.json<ForumPolicyResponse>(policy)),
    http.get(`${API}/meetings/m1/attendance`, () => HttpResponse.json<AttendanceResponse>(state.register)),
    http.get(`${API}/meetings/m1/rsvps`, () => HttpResponse.json<RepliesResponse>(replies)),
    http.get(`${API}/meetings/m1/quorum/changes`, () =>
      HttpResponse.json<QuorumChangeResponse[]>(options.changes ?? []),
    ),
    http.get(`${API}/meetings/m1/quorum`, ({ request }) => {
      const itemId = new URL(request.url).searchParams.get('itemId')
      if (itemId) {
        return HttpResponse.json<LiveQuorumResponse>(options.itemQuorum?.(itemId) ?? { ...quorate, itemId })
      }
      return HttpResponse.json<LiveQuorumResponse>(state.register.quorum ?? quorate)
    }),
    http.post(`${API}/meetings/m1/attendance/events`, ({ request }) =>
      write('/attendance/events', request, (body) => {
        const userId = String(body.userId)
        const kind = body.kind as AttendanceEventResponse['kind']
        const next: Partial<AttendancePersonResponse> =
          kind === 'ARRIVED'
            ? { state: 'PRESENT', mode: body.mode as 'IN_PERSON', firstArrivedAt: '2026-12-12T05:00:00Z' }
            : kind === 'LEFT'
              ? { state: 'LEFT', lastLeftAt: '2026-12-12T05:42:00Z', leftDuringItem: 'i5' }
              : kind === 'STEPPED_OUT'
                ? { steppedOutOf: String(body.itemId) }
                : kind === 'STEPPED_BACK'
                  ? { steppedOutOf: null }
                  : kind === 'APOLOGIES'
                    ? { state: 'APOLOGIES' }
                    : {}
        const p = change(userId, next)
        const event: AttendanceEventResponse = {
          ...arrival(`n${String(sent.length)}`, userId, '2026-12-12T05:00:00Z'),
          kind,
        }
        state.register = { ...state.register, events: [...(state.register.events ?? []), event] }
        return recorded(event, p)
      }),
    ),
    http.post(`${API}/meetings/m1/attendance/arrivals`, ({ request }) =>
      write('/attendance/arrivals', request, (body) => {
        for (const userId of body.userIds as string[]) {
          change(userId, { state: 'PRESENT', mode: body.mode as 'IN_PERSON', firstArrivedAt: '2026-12-12T05:00:00Z' })
        }
        return HttpResponse.json<AttendanceResponse>(state.register)
      }),
    ),
    http.post(`${API}/meetings/m1/attendance/events/:eventId/void`, ({ request, params }) =>
      write(`/attendance/events/${String(params.eventId)}/void`, request, (body) => {
        const target = (state.register.events ?? []).find((e) => e.id === params.eventId)
        const voids: AttendanceEventResponse = {
          ...arrival('v1', target?.userId ?? '', '2026-12-12T05:10:00Z'),
          kind: 'VOIDED',
          voidsEventId: String(params.eventId),
          reason: String(body.reason),
        }
        state.register = {
          ...state.register,
          events: [
            ...(state.register.events ?? []).map((e) => (e.id === params.eventId ? { ...e, voided: true } : e)),
            voids,
          ],
        }
        const p = change(target?.userId ?? '', {})
        return recorded(voids, p)
      }),
    ),
    http.post(`${API}/meetings/m1/progress`, ({ request }) =>
      write('/progress', request, (body) => {
        state.register = {
          ...state.register,
          itemInProgress: { itemId: String(body.itemId), startedAt: '2026-12-12T05:00:00Z' },
        }
        return HttpResponse.json({ itemId: body.itemId, startedAt: '2026-12-12T05:00:00Z' })
      }),
    ),
  )
  return { sent, state }
}

const problem = (status: number, extra: Record<string, unknown>) =>
  HttpResponse.json({ type: 'urn:memo:problem:conflict', status, ...extra }, { status })

/** The register's row of [name]. */
async function rowOf(name: string): Promise<HTMLElement> {
  const header = await screen.findByRole('rowheader', { name })
  const row = header.closest('tr')
  if (!row) throw new Error(`no row for ${name}`)
  return row
}

describe('The attendance register (Phase 4 E05 T8, flow X)', () => {
  it('opens on the register while the meeting runs, with alternates under their member and the people in attendance', async () => {
    setup()
    renderRoute('/meetings/m1')
    expect(partsForPath('/meetings/m1')).toContain('meetingAttendance')
    expect(await screen.findByRole('tab', { name: 'Attendance' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('heading', { name: 'Attendance — BRC 3/2026' })).toBeInTheDocument()
    expect(screen.getByText('Now: item 3 Minutes of the last meeting')).toBeInTheDocument()

    const members = screen.getByRole('table', { name: 'Members' })
    expect(
      within(members)
        .getAllByRole('rowheader')
        .map((h) => h.textContent),
    ).toEqual(['Dr F. Karim', 'Demo Director', 'A. Rahman', 'S. Ali', 'N. Chowdhury', 'R. Das', 'M. Hossain'])
    expect(await rowOf('Dr F. Karim')).toHaveTextContent('In person since 10:02 AM')
    expect(await rowOf('Demo Director')).toHaveTextContent('Apologies (from their reply)')
    expect(await rowOf('Demo Director')).toHaveTextContent('A. Rahman attends in their place')
    expect(await rowOf('A. Rahman')).toHaveTextContent('Alternate for Demo Director')
    expect(await rowOf('A. Rahman')).toHaveTextContent('counts for the number only')
    expect(await rowOf('S. Ali')).toHaveTextContent('Online since 10:05 AM')
    expect(await rowOf('R. Das')).toHaveTextContent('joined during item 3 (late)')
    const others = screen.getByRole('table', { name: 'In attendance' })
    expect(within(others).getByRole('rowheader', { name: 'J. Begum' })).toBeInTheDocument()
    expect(within(others).getByText('Guest')).toBeInTheDocument()
  }, 30_000)

  it('marks everyone who said yes present in the way they said, after asking once', async () => {
    const user = userEvent.setup()
    const { sent } = setup()
    renderRoute('/meetings/m1')
    await user.click(await screen.findByRole('button', { name: 'Mark everyone who said yes as present (2)' }))
    const dialog = await screen.findByRole('dialog', { name: 'Mark everyone who said yes as present' })
    expect(dialog).toHaveTextContent(
      'Mark 2 people present in the way they said (1 in person, 1 online)? You can change anyone afterwards.',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Mark 2 people present' }))
    await waitFor(() => {
      expect(sent).toHaveLength(2)
    })
    expect(sent.map((s) => s.body)).toEqual([
      { mode: 'IN_PERSON', userIds: ['u5'] },
      { mode: 'REMOTE', userIds: ['u8'] },
    ])
    expect(sent[0]?.key).toMatch(UUID)
    expect(sent[1]?.key).not.toBe(sent[0]?.key)
    expect(await screen.findByText('2 people are marked present.')).toBeInTheDocument()
    await waitFor(async () => {
      expect(await rowOf('N. Chowdhury')).toHaveTextContent('In person since 11:00 AM')
    })
    expect(screen.queryByRole('button', { name: /Mark everyone who said yes/ })).toBeNull()
  }, 30_000)

  it('records an arrival with no If-Match before a row exists, and a departure with the row’s version', async () => {
    const user = userEvent.setup()
    const fresh = register({
      people: people.map((p) => (p.userId === 'u5' ? { ...p, version: null } : p)),
    })
    const { sent } = setup({ register: fresh })
    renderRoute('/meetings/m1')
    await user.click(within(await rowOf('N. Chowdhury')).getByRole('button', { name: 'In person: N. Chowdhury' }))
    await waitFor(() => {
      expect(sent).toHaveLength(1)
    })
    expect(sent[0]).toMatchObject({
      path: '/attendance/events',
      ifMatch: null,
      body: { userId: 'u5', kind: 'ARRIVED', mode: 'IN_PERSON' },
    })
    expect(sent[0]?.key).toMatch(UUID)
    // Focus moves to the person's new state, which says where they are.
    const state = await waitFor(async () => {
      const line = within(await rowOf('N. Chowdhury')).getByText(/In person since/)
      expect(line).toHaveFocus()
      return line
    })
    expect(state).toHaveTextContent('In person since 11:00 AM')

    await user.click(within(await rowOf('R. Das')).getByRole('button', { name: 'Left: R. Das' }))
    await waitFor(() => {
      expect(sent).toHaveLength(2)
    })
    expect(sent[1]).toMatchObject({ ifMatch: '"1"', body: { userId: 'u6', kind: 'LEFT' } })
    expect(await rowOf('R. Das')).toHaveTextContent('Left at 11:42 AM, during item 5')
  }, 30_000)

  it('says in words when someone else changed the row (412) and shows the register as it is now', async () => {
    const user = userEvent.setup()
    let first = true
    const { sent, state } = setup({
      answer: (s) => {
        if (!first || s.path !== '/attendance/events') return undefined
        first = false
        // Another secretary recorded the departure meanwhile.
        state.register = {
          ...state.register,
          people: (state.register.people ?? []).map((p) =>
            p.userId === 'u6' ? { ...p, state: 'LEFT', lastLeftAt: '2026-12-12T05:30:00Z', version: 2 } : p,
          ),
        }
        return problem(412, {})
      },
    })
    renderRoute('/meetings/m1')
    await user.click(within(await rowOf('R. Das')).getByRole('button', { name: 'Left: R. Das' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'R. Das’s attendance was changed meanwhile, perhaps by another secretary. It is shown as it is now: try again.',
    )
    expect(sent[0]?.ifMatch).toBe('"1"')
    await waitFor(async () => {
      expect(await rowOf('R. Das')).toHaveTextContent('Left at 11:30 AM')
    })
    // The row now offers what fits: back in person or online, at the new version.
    await user.click(within(await rowOf('R. Das')).getByRole('button', { name: 'Back online: R. Das' }))
    await waitFor(() => {
      expect(sent).toHaveLength(2)
    })
    expect(sent[1]).toMatchObject({ ifMatch: '"2"', body: { kind: 'ARRIVED', mode: 'REMOTE' } })
  }, 30_000)

  it('moves "Now: item n" and records a member stepping out for the item they declared an interest in', async () => {
    const user = userEvent.setup()
    const { sent } = setup({
      itemQuorum: (itemId) =>
        itemId === 'i5'
          ? {
              quorate: false,
              basis: 7,
              needed: 4,
              present: 3,
              itemId,
              unmet: [{ kind: 'COUNT', needed: 4, actual: 3 }],
              seats: seats.map((s) => (s.holder.userId === 'u4' ? { ...s, recused: true } : s)),
            }
          : { ...quorate, itemId },
    })
    renderRoute('/meetings/m1')
    await user.click(await screen.findByRole('button', { name: 'Change the item' }))
    await user.click(await screen.findByRole('menuitemradio', { name: '5 Term loan, Delta Textiles' }))
    await waitFor(() => {
      expect(sent[0]).toMatchObject({ path: '/progress', body: { itemId: 'i5' } })
    })
    expect(sent[0]?.key).toMatch(UUID)
    expect(await screen.findByText('Now: item 5 Term loan, Delta Textiles')).toBeInTheDocument()

    // S. Ali declared an interest in item 5: the row says so and offers stepping out.
    const ali = await rowOf('S. Ali')
    expect(await within(ali).findByText('Declared an interest in this item')).toBeInTheDocument()
    // The banner says why item 5 is not quorate.
    expect(
      await screen.findByText('Item 5 is not quorate: 1 member has declared an interest and does not count for it.'),
    ).toBeInTheDocument()
    await user.click(within(ali).getByRole('button', { name: 'Stepped out for this item: S. Ali' }))
    await waitFor(() => {
      expect(sent[1]).toMatchObject({ body: { userId: 'u4', kind: 'STEPPED_OUT', itemId: 'i5' }, ifMatch: '"1"' })
    })
    expect(await within(await rowOf('S. Ali')).findByText('Stepped out for item 5')).toBeInTheDocument()
    await user.click(within(await rowOf('S. Ali')).getByRole('button', { name: 'Back: S. Ali' }))
    await waitFor(() => {
      expect(sent[2]).toMatchObject({ body: { userId: 'u4', kind: 'STEPPED_BACK', itemId: 'i5' }, ifMatch: '"2"' })
    })
  }, 30_000)

  it('corrects a wrong entry: voided with a reason (required), the right one recorded at the new version', async () => {
    const user = userEvent.setup()
    const { sent } = setup()
    renderRoute('/meetings/m1')
    await user.click(within(await rowOf('R. Das')).getByRole('button', { name: 'More for R. Das' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Correct an entry…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Correct an entry for R. Das' })
    expect(within(dialog).getByRole('combobox', { name: 'Entry' })).toHaveDisplayValue(
      'Arrived in person at 10:40 AM, during item 3',
    )
    // The reason is required: nothing is sent without it, and focus goes to it.
    await user.click(within(dialog).getByRole('button', { name: 'Record the correction' }))
    const what = within(dialog).getByRole('textbox', { name: /What is wrong\?/ })
    expect(await within(dialog).findByText('Say why the register is corrected.')).toBeInTheDocument()
    expect(what).toHaveFocus()
    expect(sent).toHaveLength(0)

    await user.type(what, 'R. Das arrived at 10:20, during item 1')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Record the right entry now' }))
    const time = within(dialog).getByLabelText('At')
    await user.clear(time)
    await user.type(time, '10:20')
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'During item' }), 'i1')
    await user.click(within(dialog).getByRole('button', { name: 'Record the correction' }))
    await waitFor(() => {
      expect(sent).toHaveLength(2)
    })
    expect(sent[0]).toMatchObject({
      path: '/attendance/events/e5/void',
      ifMatch: '"1"',
      body: { reason: 'R. Das arrived at 10:20, during item 1' },
    })
    expect(sent[0]?.key).toMatch(UUID)
    expect(sent[1]).toMatchObject({
      path: '/attendance/events',
      ifMatch: '"2"',
      body: { userId: 'u6', kind: 'ARRIVED', mode: 'IN_PERSON', at: '2026-12-12T04:20:00.000Z', itemId: 'i1' },
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull()
    })

    // The history keeps the wrong entry, struck through, with who voided it and why.
    await user.click(screen.getByRole('button', { name: /^History: arrivals, departures and corrections/ }))
    const struck = await screen.findByText(
      (_, el) => el?.tagName === 'S' && el.textContent.includes('R. Das: Arrived in person at 10:40 AM'),
    )
    expect(struck).toHaveTextContent('Voided:')
    expect(
      screen.getByText(/Voided by K\. Secretary at 11:10 AM: “R\. Das arrived at 10:20, during item 1”/),
    ).toBeInTheDocument()
  }, 30_000)

  it('once held, records a missed entry only as a correction with its reason', async () => {
    const user = userEvent.setup()
    const { sent } = setup({
      meeting: { ...meeting, status: 'HELD' },
      register: register({ status: 'HELD', correcting: true, itemInProgress: null }),
    })
    renderRoute('/meetings/m1')
    await user.click(await screen.findByRole('tab', { name: 'Attendance' }))
    expect(
      await screen.findByText(/The meeting is over: what you record now corrects the register, with a reason/),
    ).toBeInTheDocument()
    // No one-press buttons and no "everyone who said yes" after the meeting.
    expect(within(await rowOf('N. Chowdhury')).queryByRole('button', { name: /In person:/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Mark everyone who said yes/ })).toBeNull()
    await user.click(within(await rowOf('M. Hossain')).getByRole('button', { name: 'More for M. Hossain' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Record a missed entry…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Record a missed entry for M. Hossain' })
    await user.click(within(dialog).getByRole('radio', { name: 'Apologies' }))
    await user.click(within(dialog).getByRole('button', { name: 'Record' }))
    expect(await within(dialog).findByText('Say why the register is corrected.')).toBeInTheDocument()
    expect(sent).toHaveLength(0)
    await user.type(within(dialog).getByRole('textbox', { name: /Why the register is corrected/ }), 'Emailed on 11 Dec')
    await user.click(within(dialog).getByRole('button', { name: 'Record' }))
    await waitFor(() => {
      expect(sent[0]).toMatchObject({ body: { userId: 'u8', kind: 'APOLOGIES', reason: 'Emailed on 11 Dec' } })
    })
  }, 30_000)

  it('is for officers only: a member sees how they are recorded and whether the meeting is quorate', async () => {
    setup({
      meeting: { ...meeting, relation: 'MEMBER', canManage: false },
      register: register({
        canRecord: false,
        people: null,
        events: null,
        mine: person('me', 'Nadia Karim', { state: 'PRESENT', mode: 'REMOTE', version: 1 }),
        quorum: { quorate: false, basis: 7, needed: 4, present: 3, unmet: [] },
      }),
    })
    renderRoute('/meetings/m1')
    const card = await screen.findByRole('region', { name: 'At the meeting' })
    expect(await within(card).findByText('You are recorded as present (online).')).toBeInTheDocument()
    expect(within(card).getByText('The meeting is waiting for quorum.')).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Attendance' })).toBeNull()
    expect(screen.queryByRole('region', { name: 'Quorum' })).toBeNull()
  }, 30_000)

  it('works with the keyboard only: arrivals, the item in progress, a departure and a void with a reason', async () => {
    const user = userEvent.setup()
    const { sent } = setup()
    renderRoute('/meetings/m1')
    // Mark everyone who said yes present.
    const bulk = await screen.findByRole('button', { name: 'Mark everyone who said yes as present (2)' })
    bulk.focus()
    await user.keyboard('{Enter}')
    const confirm = await screen.findByRole('dialog', { name: 'Mark everyone who said yes as present' })
    within(confirm).getByRole('button', { name: 'Mark 2 people present' }).focus()
    await user.keyboard('{Enter}')
    await waitFor(() => {
      expect(sent.filter((s) => s.path === '/attendance/arrivals')).toHaveLength(2)
    })
    // Move to item 5 from the menu with the arrow keys.
    const change = await screen.findByRole('button', { name: 'Change the item' })
    change.focus()
    await user.keyboard('{Enter}')
    await screen.findByRole('menuitemradio', { name: '5 Term loan, Delta Textiles' })
    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{Enter}')
    await waitFor(() => {
      expect(sent.at(-1)).toMatchObject({ path: '/progress', body: { itemId: 'i5' } })
    })
    // A departure.
    within(await rowOf('R. Das'))
      .getByRole('button', { name: 'Left: R. Das' })
      .focus()
    await user.keyboard('{Enter}')
    await waitFor(() => {
      expect(sent.at(-1)).toMatchObject({ body: { userId: 'u6', kind: 'LEFT' } })
    })
    // Void the wrong arrival with a reason.
    within(await rowOf('Dr F. Karim'))
      .getByRole('button', { name: 'More for Dr F. Karim' })
      .focus()
    await user.keyboard('{Enter}')
    await screen.findByRole('menuitem', { name: 'Correct an entry…' })
    await user.keyboard('{End}{Enter}')
    const dialog = await screen.findByRole('dialog', { name: 'Correct an entry for Dr F. Karim' })
    within(dialog)
      .getByRole('textbox', { name: /What is wrong\?/ })
      .focus()
    await user.keyboard('Recorded for the wrong person')
    within(dialog).getByRole('button', { name: 'Record the correction' }).focus()
    await user.keyboard('{Enter}')
    await waitFor(() => {
      expect(sent.at(-1)).toMatchObject({
        path: '/attendance/events/e1/void',
        body: { reason: 'Recorded for the wrong person' },
      })
    })
  }, 40_000)
})

describe('The live quorum (Phase 4 E05 T8, flow Y)', () => {
  it('says the meeting is quorate in words, then announces the quorum lost after a departure', async () => {
    const user = userEvent.setup()
    const { state } = setup({
      answer: (s) => {
        if (s.path !== '/attendance/events') return undefined
        state.register = { ...state.register, quorum: { ...quorate, present: 3, quorate: false } }
        return undefined
      },
      changes: [
        {
          id: 'c1',
          level: 'MEETING',
          kind: 'LOST',
          at: '2026-12-12T05:42:00Z',
          itemId: 'i5',
          needed: 4,
          present: 3,
          unmet: [],
        },
      ],
    })
    renderRoute('/meetings/m1')
    const banner = await screen.findByRole('region', { name: 'Quorum' })
    expect(await within(banner).findByText('Quorate — meeting: 5 of 7 present, 4 needed.')).toBeInTheDocument()
    expect(await within(banner).findByText('Item 3: quorate — 5 of 7 counted, 4 needed.')).toBeInTheDocument()
    const status = within(banner).getByRole('status')
    expect(status).toBeEmptyDOMElement()

    await user.click(within(await rowOf('R. Das')).getByRole('button', { name: 'Left: R. Das' }))
    expect(
      await within(banner).findByText(
        'The meeting is not quorate since 11:42 AM (during item 5): 3 of 7 present, 4 needed.',
      ),
    ).toBeInTheDocument()
    expect(within(banner).getByText('No decision can be recorded until quorum is restored.')).toBeInTheDocument()
    expect(status).toHaveTextContent(
      'Quorum lost: 3 of 7 present, 4 needed. No decision can be recorded until quorum is restored.',
    )
  }, 30_000)

  it('says when the meeting has not been quorate yet', async () => {
    setup({
      register: register({
        quorum: {
          quorate: false,
          basis: 7,
          needed: 4,
          present: 2,
          unmet: [{ kind: 'ROLE', roles: ['CHAIR', 'VICE_CHAIR'] }],
          seats,
        },
      }),
    })
    renderRoute('/meetings/m1')
    const banner = await screen.findByRole('region', { name: 'Quorum' })
    expect(
      await within(banner).findByText('Not quorate — the meeting is waiting for quorum: 2 of 7 present, 4 needed.'),
    ).toBeInTheDocument()
    expect(within(banner).getByText('Needs the chair or a vice-chair present.')).toBeInTheDocument()
  }, 30_000)

  it('shows an item’s quorum: who counts, who does not and why, and that alternates count for the number only', async () => {
    const user = userEvent.setup()
    setup({
      itemQuorum: (itemId) => ({
        quorate: false,
        basis: 7,
        needed: 4,
        present: 2,
        itemId,
        unmet: [
          { kind: 'COUNT', needed: 4, actual: 2 },
          { kind: 'COMPOSITION', attribute: 'independent', needed: 2, actual: 1 },
        ],
        seats: seats.map((s) =>
          s.holder.userId === 'u4'
            ? { ...s, recused: true }
            : s.holder.userId === 'u6'
              ? { ...s, steppedOut: true }
              : s,
        ),
      }),
    })
    renderRoute('/meetings/m1')
    await user.click(await screen.findByRole('button', { name: 'Quorum by item…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Quorum by item' })
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Item' }), 'i5')
    expect(await within(dialog).findByText('Quorum for item 5 Term loan, Delta Textiles')).toBeInTheDocument()
    expect(dialog).toHaveTextContent('Needed: 4 of 7.')
    expect(dialog).toHaveTextContent('Counted (2): Dr F. Karim (chair) and A. Rahman (alternate for Demo Director)')
    expect(dialog).toHaveTextContent(
      'Alternates count for the number of members only, not as chair or as independent members.',
    )
    expect(within(dialog).getByText('S. Ali — declared an interest in this item')).toBeInTheDocument()
    expect(within(dialog).getByText('R. Das — stepped out for this item')).toBeInTheDocument()
    expect(within(dialog).getByText('N. Chowdhury — expected')).toBeInTheDocument()
    expect(dialog).toHaveTextContent('Result: not quorate — 2 of 4 needed.')
    expect(within(dialog).getByText('Needs at least 2 independent members, has 1.')).toBeInTheDocument()
  }, 30_000)

  it('gives an officer recused from the item the numbers only', async () => {
    const user = userEvent.setup()
    setup({
      itemQuorum: (itemId) => ({ quorate: false, basis: 7, needed: 4, present: 3, itemId, unmet: [], seats: null }),
    })
    renderRoute('/meetings/m1')
    await user.click(await screen.findByRole('button', { name: 'Quorum by item…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Quorum by item' })
    expect(
      await within(dialog).findByText(
        '3 counted, 4 needed. You declared an interest in this item, so the names are not shown to you.',
      ),
    ).toBeInTheDocument()
    expect(within(dialog).queryByText(/Dr F\. Karim/)).toBeNull()
    expect(within(dialog).queryByText(/Counted \(/)).toBeNull()
  }, 30_000)

  it('reads right to left in Arabic without accessibility violations', async () => {
    setup()
    const { container } = renderRoute('/meetings/m1', { locale: 'ar' })
    expect(await screen.findByRole('tab', { name: 'الحضور' })).toHaveAttribute('aria-selected', 'true')
    await screen.findByRole('table', { name: 'الأعضاء' })
    expect(await screen.findByRole('region', { name: 'النصاب' })).toHaveTextContent(/النصاب مكتمل — الاجتماع/)
    expect(document.documentElement).toHaveAttribute('dir', 'rtl')
    await expectNoA11yViolations(container)
  }, 30_000)
})

/** The invitations panel with nobody on it: enough for the "Invitations and replies" tab to open. */
const noInvitations = http.get(`${API}/meetings/m1/invitations`, () =>
  HttpResponse.json<InvitationsResponse>({
    meetingId: 'm1',
    canResend: false,
    cancelled: false,
    created: true,
    pending: false,
    people: [],
  }),
)

describe('The meeting page’s tabs', () => {
  it('keeps the tab chosen in the address and across a change of language, without reloading the page', async () => {
    const user = userEvent.setup()
    setup()
    server.use(noInvitations)
    const { router } = renderRoute('/meetings/m1')
    expect(await screen.findByRole('tab', { name: 'Attendance' })).toHaveAttribute('aria-selected', 'true')

    const replies = screen.getByRole('tab', { name: 'Invitations and replies' })
    await user.click(replies)
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ tab: 'replies' })
    })
    expect(replies).toHaveAttribute('aria-selected', 'true')

    await user.click(screen.getByRole('button', { name: /Change language/ }))
    await user.click(await screen.findByRole('menuitemradio', { name: 'العربية' }))
    expect(await screen.findByRole('tab', { name: 'الدعوات والردود' })).toHaveAttribute('aria-selected', 'true')
    // The same tab, translated in place: the page was not replaced by a loading state while Arabic loaded.
    expect(replies).toBeInTheDocument()
    expect(router.state.location.search).toEqual({ tab: 'replies' })

    // The register, the tab the page opens on while the meeting runs, leaves the address.
    await user.click(screen.getByRole('tab', { name: 'الحضور' }))
    await waitFor(() => {
      expect(router.state.location.search).toEqual({})
    })
  }, 30_000)

  it('opens on the tab the address names, and on the first tab for one the viewer does not have', async () => {
    setup()
    const first = renderRoute('/meetings/m1?tab=agenda')
    expect(await screen.findByRole('tab', { name: 'Agenda' })).toHaveAttribute('aria-selected', 'true')
    first.unmount()

    // Before the meeting there is no register: `?tab=attendance` opens the agenda; an unknown tab, the one the page opens on.
    setup({ meeting: { ...meeting, status: 'SCHEDULED' } })
    const scheduled = renderRoute('/meetings/m1?tab=attendance')
    expect(await screen.findByRole('tab', { name: 'Agenda' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('tab', { name: 'Attendance' })).not.toBeInTheDocument()
    scheduled.unmount()

    setup()
    renderRoute('/meetings/m1?tab=minutes')
    expect(await screen.findByRole('tab', { name: 'Attendance' })).toHaveAttribute('aria-selected', 'true')
  }, 30_000)

  it('puts the register’s tables in a positioned scroll box, so a hidden header never widens the page', async () => {
    setup()
    renderRoute('/meetings/m1')
    const members = await screen.findByRole('table', { name: 'Members' })
    const box = members.closest('[data-table-scroll]')
    expect(box).toHaveClass('relative', 'overflow-x-auto')
  }, 30_000)
})

/** The override dialog as E06's decision screen opens it, with [onRecord] standing in for E06's request. */
function Override({ allowed, onRecord }: { allowed: boolean; onRecord: (reason: string) => Promise<unknown> }) {
  const [open, setOpen] = useState(true)
  return open ? (
    <QuorumOverrideDialog
      quorum={{ present: 3, needed: 4 }}
      allowed={allowed}
      onRecord={onRecord}
      onClose={() => {
        setOpen(false)
      }}
    />
  ) : (
    <p>closed</p>
  )
}

function renderOverride(allowed: boolean, onRecord: (reason: string) => Promise<unknown>) {
  server.use(sessionHandler(signedInSession()))
  return renderWithProviders(<Override allowed={allowed} onRecord={onRecord} />)
}

/** E06's request as the dialog sees it: refused by the server with [body]. */
function refusedWith(status: number, body: Record<string, unknown>) {
  server.use(http.get(`${API}/meetings/m1/quorum/snapshots/s1`, () => problem(status, body)))
  return () => getMeetingQuorumSnapshot({ path: { meetingId: 'm1', snapshotId: 's1' }, throwOnError: true })
}

describe('Recording a decision without quorum (Phase 4 E05 T8, flow Y3, for E06)', () => {
  it('asks for a reason, says it is to be ratified and who is told, then sends it', async () => {
    const user = userEvent.setup()
    const onRecord = vi.fn(() => Promise.resolve())
    const { container } = renderOverride(true, onRecord)
    const dialog = await screen.findByRole('dialog', { name: 'Record without quorum' })
    expect(dialog).toHaveTextContent('This item is not quorate: 3 of 4 needed.')
    expect(dialog).toHaveTextContent(
      'The committee’s rules allow a decision without a quorum only with a reason, and it must be ratified at a later quorate meeting.',
    )
    expect(dialog).toHaveTextContent('The chair and the other secretaries will be told.')
    await user.click(within(dialog).getByRole('button', { name: 'Record without quorum' }))
    const reason = within(dialog).getByRole('textbox', { name: /Reason/ })
    expect(
      await within(dialog).findByText('Give the reason for recording this decision without a quorum.'),
    ).toBeInTheDocument()
    expect(reason).toHaveFocus()
    expect(onRecord).not.toHaveBeenCalled()
    await expectNoA11yViolations(container)

    await user.type(reason, 'Urgent: the borrower’s facility expires on 15 December')
    await user.click(within(dialog).getByRole('button', { name: 'Record without quorum' }))
    await waitFor(() => {
      expect(onRecord).toHaveBeenCalledWith('Urgent: the borrower’s facility expires on 15 December')
    })
    expect(await screen.findByText('closed')).toBeInTheDocument()
  }, 20_000)

  it('says no decision can be recorded when the committee type does not allow it, and on OVERRIDE_NOT_ALLOWED', async () => {
    const user = userEvent.setup()
    const refused = renderOverride(false, () => Promise.resolve())
    const notice = await screen.findByRole('alert')
    expect(notice).toHaveTextContent('The committee’s rules do not allow a decision without a quorum.')
    expect(notice).toHaveTextContent('No decision can be recorded: the item is not quorate (3 of 4 needed).')
    expect(screen.queryByRole('button', { name: 'Record without quorum' })).toBeNull()
    refused.unmount()

    // Allowed when the screen opened, refused by the server (the type's pinned version does not allow it).
    renderOverride(true, refusedWith(409, { reason: 'OVERRIDE_NOT_ALLOWED' }))
    const dialog = await screen.findByRole('dialog', { name: 'Record without quorum' })
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Urgent')
    await user.click(within(dialog).getByRole('button', { name: 'Record without quorum' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The committee’s rules do not allow a decision without a quorum.',
    )
    expect(screen.queryByRole('button', { name: 'Record without quorum' })).toBeNull()
  }, 20_000)

  it('puts the server’s REASON_REQUIRED at the field', async () => {
    const user = userEvent.setup()
    renderOverride(
      true,
      refusedWith(422, { errors: [{ field: 'reason', code: 'REASON_REQUIRED', message: 'Give a reason.' }] }),
    )
    const dialog = await screen.findByRole('dialog', { name: 'Record without quorum' })
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), '   x')
    await user.click(within(dialog).getByRole('button', { name: 'Record without quorum' }))
    expect(
      await within(dialog).findByText('Give the reason for recording this decision without a quorum.'),
    ).toBeInTheDocument()
  }, 20_000)
})
