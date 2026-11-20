import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type {
  InvitationsResponse,
  InviteeResponse,
  MeetingResponse,
  MeetingSummaryResponse,
  MemberAgendaResponse,
  MyDeclarationsResponse,
  MyRsvpResponse,
  RepliesResponse,
  ReplyResponse,
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
const ME = signedInSession().user?.id ?? ''

vi.mock('@/features/meetings/time', async (original) => ({
  ...(await original<typeof import('@/features/meetings/time')>()),
  viewerZone: () => 'Asia/Dhaka',
}))

/** BRC 3/2026 on Saturday 12 December 2026, 10:00–13:00 in Dhaka; the reader is a member. */
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
  joinUrl: 'https://meet.example/abc',
  classificationId: 'l1',
  number: 3,
  label: 'BRC 3/2026',
  status: 'SCHEDULED',
  relation: 'MEMBER',
  canManage: false,
  guests: [],
  version: 3,
  rsvp: { capacity: 'MEMBER', alternateAttends: false, stale: false, canAnswer: true },
}

const noAnswer: MyRsvpResponse = {
  meetingId: 'm1',
  capacity: 'MEMBER',
  canAnswer: true,
  offered: ['IN_PERSON', 'REMOTE', 'APOLOGIES'],
  replyBy: '2026-12-10',
  alternateAttends: false,
  alternateStandingIn: false,
  stale: false,
  alternate: { userId: 'u9', name: 'A. Rahman' },
}

const agenda: MemberAgendaResponse = {
  meetingId: 'm1',
  status: 'SCHEDULED',
  startsAt: meeting.startsAt,
  endsAt: meeting.endsAt,
  timeZone: 'Asia/Dhaka',
  revision: null,
  latestRevision: 0,
  preview: false,
  sections: [],
}

interface Calls {
  puts: { body: unknown; key: string | null; ifMatch: string | null }[]
  mineReads: number
}

/** The meeting page's requests, with "Will you attend?" answered by [answer] (a response or a problem). */
function handlers(
  calls: Calls,
  options: {
    meeting?: MeetingResponse
    mine?: MyRsvpResponse
    answer?: (body: { answer: string; alternateAttends?: boolean; note?: string | null }) => Response
  } = {},
) {
  let mine = options.mine ?? noAnswer
  return [
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([])),
    http.get(`${API}/meetings/m1`, () => HttpResponse.json<MeetingResponse>(options.meeting ?? meeting)),
    http.get(`${API}/meetings/m1/agenda`, () => HttpResponse.json<MemberAgendaResponse>(agenda)),
    http.get(`${API}/meetings/m1/declarations/mine`, () =>
      HttpResponse.json<MyDeclarationsResponse>({ meetingId: 'm1', latestRevision: 0, declarations: [], toReview: [] }),
    ),
    http.get(`${API}/meetings/m1/rsvp/mine`, () => {
      calls.mineReads++
      return HttpResponse.json<MyRsvpResponse>(mine)
    }),
    http.put(`${API}/meetings/m1/rsvp/mine`, async ({ request }) => {
      const body = (await request.json()) as {
        answer: 'IN_PERSON' | 'REMOTE' | 'APOLOGIES'
        alternateAttends?: boolean
      }
      calls.puts.push({
        body,
        key: request.headers.get('Idempotency-Key'),
        ifMatch: request.headers.get('If-Match'),
      })
      if (options.answer) return options.answer(body)
      mine = {
        ...mine,
        answer: body.answer,
        alternateAttends: body.alternateAttends ?? false,
        alternateStandingIn: body.alternateAttends ?? false,
        answeredAt: '2026-11-12T04:14:00Z',
        version: (mine.version ?? -1) + 1,
        stale: false,
      }
      return HttpResponse.json<MyRsvpResponse>(mine)
    }),
  ]
}

/** Row [i] of [rows], which must exist. */
function rowOf(rows: HTMLElement[], i: number): HTMLElement {
  const row = rows[i]
  if (!row) throw new Error(`no row ${String(i)}`)
  return row
}

const problem = (status: number, extra: Record<string, unknown>) =>
  HttpResponse.json({ type: 'urn:memo:problem:conflict', status, ...extra }, { status })

describe('Will you attend? (Phase 4 E05 T7, flow V)', () => {
  it('answers "Yes, online" with an Idempotency-Key, then changes it to apologies with the alternate (If-Match)', async () => {
    const user = userEvent.setup()
    const calls: Calls = { puts: [], mineReads: 0 }
    server.use(...handlers(calls))
    renderRoute('/meetings/m1')

    const card = await screen.findByRole('region', { name: 'Will you attend?' })
    expect(partsForPath('/meetings/m1')).toContain('meetingReplies')
    expect(await within(card).findByText('Please reply by Thursday, December 10')).toBeInTheDocument()
    const answers = await within(card).findByRole('group', { name: 'Will you attend?' })
    expect(
      within(answers)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Yes, in person', 'Yes, online', 'No, I send my apologies'])
    // "Add to my calendar" is the calendar file, with why the calendar's own buttons do not answer.
    const calendar = within(card).getByRole('link', { name: 'Add to my calendar' })
    expect(calendar).toHaveAttribute('href', '/api/v1/meetings/m1/calendar.ics')
    expect(calendar).toHaveAttribute('download')
    expect(calendar).toHaveAccessibleDescription(/Accept and Decline buttons do not reach the secretary/)

    await user.click(within(answers).getByRole('button', { name: 'Yes, online' }))
    const said = await within(card).findByText(/You said: Yes, online/)
    await waitFor(() => {
      expect(said.closest('p')).toHaveFocus()
    })
    expect(calls.puts).toHaveLength(1)
    expect(calls.puts[0]?.key).toMatch(UUID)
    expect(calls.puts[0]?.ifMatch).toBeNull()
    expect(calls.puts[0]?.body).toMatchObject({ answer: 'REMOTE' })
    expect(within(card).getByRole('status')).toHaveTextContent('Your answer is saved.')

    // Change to apologies: the alternate attends by default, with a note.
    await user.click(within(card).getByRole('button', { name: 'Change my answer' }))
    await user.click(within(card).getByRole('button', { name: 'No, I send my apologies' }))
    const dialog = await screen.findByRole('dialog', { name: 'No, I send my apologies' })
    expect(dialog).toHaveTextContent('Your alternate is A. Rahman.')
    expect(within(dialog).getByRole('radio', { name: 'A. Rahman will attend in my place' })).toBeChecked()
    await user.type(within(dialog).getByRole('textbox', { name: /A note for the secretary/ }), 'Travelling')
    await user.click(within(dialog).getByRole('button', { name: 'Send my apologies' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(calls.puts[1]?.ifMatch).toBe('"0"')
    expect(calls.puts[1]?.key).toMatch(UUID)
    expect(calls.puts[1]?.key).not.toBe(calls.puts[0]?.key)
    expect(calls.puts[1]?.body).toEqual({ answer: 'APOLOGIES', alternateAttends: true, note: 'Travelling' })
    expect(within(card).getByRole('status')).toHaveTextContent(
      'Your apologies are recorded. A. Rahman has been asked to attend in your place and will receive the invitation.',
    )
    expect(card).toHaveTextContent('A. Rahman will attend in your place.')
  }, 20_000)

  it('offers only the answers of an online meeting', async () => {
    const calls: Calls = { puts: [], mineReads: 0 }
    server.use(
      ...handlers(calls, {
        meeting: { ...meeting, mode: 'ONLINE', location: null },
        mine: { ...noAnswer, offered: ['REMOTE', 'APOLOGIES'] },
      }),
    )
    renderRoute('/meetings/m1')
    const answers = await screen.findByRole('group', { name: 'Will you attend?' })
    expect(
      within(answers)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Yes, online', 'No, I send my apologies'])
  }, 20_000)

  it('says the alternate’s refusals in words and keeps the dialog open', async () => {
    const user = userEvent.setup()
    const calls: Calls = { puts: [], mineReads: 0 }
    let reason = 'ALTERNATE_IS_MEMBER'
    server.use(...handlers(calls, { answer: () => problem(409, { reason }) }))
    renderRoute('/meetings/m1')

    await user.click(await screen.findByRole('button', { name: 'No, I send my apologies' }))
    const dialog = await screen.findByRole('dialog', { name: 'No, I send my apologies' })
    await user.click(within(dialog).getByRole('button', { name: 'Send my apologies' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Your alternate is also a member of this committee and cannot attend in your place.',
    )

    reason = 'ALTERNATE_BUSY'
    await user.click(within(dialog).getByRole('button', { name: 'Send my apologies' }))
    await waitFor(() => {
      expect(within(dialog).getByRole('alert')).toHaveTextContent(
        'A. Rahman is already attending in place of another member.',
      )
    })
    // The same request retried keeps its Idempotency-Key.
    expect(calls.puts[1]?.key).toBe(calls.puts[0]?.key)
  }, 20_000)

  it('reloads the answer after a stale If-Match (412) and says so', async () => {
    const user = userEvent.setup()
    const calls: Calls = { puts: [], mineReads: 0 }
    const answered: MyRsvpResponse = {
      ...noAnswer,
      answer: 'IN_PERSON',
      answeredAt: '2026-11-12T04:14:00Z',
      version: 2,
    }
    server.use(...handlers(calls, { mine: answered, answer: () => problem(412, {}) }))
    renderRoute('/meetings/m1')

    const card = await screen.findByRole('region', { name: 'Will you attend?' })
    expect(await within(card).findByText(/You said: Yes, in person/)).toBeInTheDocument()
    await user.click(within(card).getByRole('button', { name: 'Change my answer' }))
    expect(within(card).getByRole('button', { name: 'Yes, in person' })).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => {
      expect(within(card).getByRole('button', { name: 'Yes, in person' })).toHaveFocus()
    })
    const reads = calls.mineReads
    await user.click(within(card).getByRole('button', { name: 'Yes, online' }))
    expect(await within(card).findByRole('alert')).toHaveTextContent(
      'This answer was changed meanwhile, perhaps by the secretary.',
    )
    expect(calls.puts[0]?.ifMatch).toBe('"2"')
    await waitFor(() => {
      expect(calls.mineReads).toBeGreaterThan(reads)
    })
  }, 20_000)

  it('asks again after a change of time, showing the old answer for the old time', async () => {
    const calls: Calls = { puts: [], mineReads: 0 }
    server.use(
      ...handlers(calls, {
        meeting: {
          ...meeting,
          startsAt: '2026-12-12T08:00:00Z',
          endsAt: '2026-12-12T10:00:00Z',
          rsvp: { capacity: 'MEMBER', answer: 'IN_PERSON', alternateAttends: false, stale: true, canAnswer: true },
        },
        mine: {
          ...noAnswer,
          answer: 'IN_PERSON',
          version: 0,
          stale: true,
          forStartsAt: '2026-12-12T04:00:00Z',
          forEndsAt: '2026-12-12T07:00:00Z',
        },
      }),
    )
    renderRoute('/meetings/m1')
    const card = await screen.findByRole('region', { name: 'Will you attend?' })
    const notice = await within(card).findByText(/for the old time/)
    expect(notice.textContent.replace(/\s+/g, ' ')).toBe(
      'You replied “Yes, in person” for the old time (Saturday, December 12 at 10:00 AM).',
    )
    expect(
      within(card)
        .getByText(/The meeting is now on/)
        .textContent.replace(/\s+/g, ' '),
    ).toBe('The meeting is now on Saturday, December 12 at 2:00 PM. Will you attend?')
    expect(within(card).getByRole('group', { name: 'Will you attend?' })).toBeInTheDocument()
  }, 20_000)

  it('answers from a My meetings card, loading the answer first for its version', async () => {
    const user = userEvent.setup()
    const calls: Calls = { puts: [], mineReads: 0 }
    const soon = new Date(Date.now() + 3 * 86_400_000)
    const later = new Date(soon.getTime() + 3 * 3_600_000)
    const summary: MeetingSummaryResponse = {
      id: 'm1',
      forumId: 'f1',
      forumName: 'Board Risk Committee',
      forumCode: 'BRC',
      kind: 'ORDINARY',
      startsAt: soon.toISOString(),
      endsAt: later.toISOString(),
      localStartsAt: soon.toISOString().slice(0, 19),
      localEndsAt: later.toISOString().slice(0, 19),
      timeZone: 'Asia/Dhaka',
      mode: 'IN_PERSON',
      label: 'BRC 3/2026',
      status: 'SCHEDULED',
      relation: 'MEMBER',
      rsvp: { capacity: 'MEMBER', alternateAttends: false, stale: false, canAnswer: true },
    }
    server.use(...handlers(calls), myMeetingsHandler([summary]))
    const { container } = renderRoute('/me/meetings')

    const card = await screen.findByRole('article', { name: /Board Risk Committee/ })
    expect(card).toHaveTextContent('Please reply')
    const answers = within(card).getByRole('group', { name: /Will you attend\? Board Risk Committee/ })
    expect(
      within(answers)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Yes, in person', 'No, I send my apologies'])
    await expectNoA11yViolations(container)
    await user.click(within(answers).getByRole('button', { name: 'Yes, in person' }))
    await waitFor(() => {
      expect(calls.puts).toHaveLength(1)
    })
    expect(calls.mineReads).toBe(1)
    expect(calls.puts[0]?.body).toMatchObject({ answer: 'IN_PERSON' })
    expect(calls.puts[0]?.key).toMatch(UUID)
  }, 20_000)

  it('reads right to left in Arabic without accessibility violations', async () => {
    const calls: Calls = { puts: [], mineReads: 0 }
    server.use(...handlers(calls))
    const { container } = renderRoute('/meetings/m1', { locale: 'ar' })
    const card = await screen.findByRole('region', { name: 'هل ستحضر؟' })
    expect(document.documentElement).toHaveAttribute('dir', 'rtl')
    expect(await within(card).findByRole('button', { name: 'نعم، عبر الإنترنت' })).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'أضِفه إلى تقويمي' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 20_000)
})

// The secretary's panel (flow W)

const reply = (userId: string, name: string, extra: Partial<ReplyResponse> = {}): ReplyResponse => ({
  userId,
  name,
  capacity: 'MEMBER',
  alternateAttends: false,
  stale: false,
  ...extra,
})

const invitee = (userId: string, name: string, extra: Partial<InviteeResponse> = {}): InviteeResponse => ({
  userId,
  name,
  invitable: true,
  guest: false,
  emailOff: false,
  state: 'INVITED',
  lastKind: 'UPDATE',
  lastSequence: 1,
  firstSentAt: '2026-11-12T04:02:00Z',
  lastSentAt: '2026-12-03T09:40:00Z',
  delivery: { state: 'SENT', attempts: 1, sentAt: '2026-12-03T09:40:00Z' },
  ...extra,
})

const people: InviteeResponse[] = [
  invitee('u1', 'Dr F. Karim', {
    reply: reply('u1', 'Dr F. Karim', {
      answer: 'IN_PERSON',
      how: 'IN_APP',
      version: 0,
      note: 'I will join after 11:00',
    }),
  }),
  invitee('u2', 'Demo Director', {
    reply: reply('u2', 'Demo Director', {
      answer: 'APOLOGIES',
      alternateAttends: true,
      alternate: { userId: 'u9', name: 'A. Rahman' },
      how: 'IN_APP',
      version: 1,
    }),
  }),
  invitee('u9', 'A. Rahman', {
    reply: reply('u9', 'A. Rahman', {
      capacity: 'ALTERNATE',
      standingInFor: { userId: 'u2', name: 'Demo Director' },
    }),
  }),
  invitee('u3', 'M. Hossain', {
    delivery: { state: 'FAILED', attempts: 5, failedAt: '2026-12-03T10:00:00Z' },
    reply: reply('u3', 'M. Hossain'),
  }),
  invitee('u4', 'S. Ali', {
    reply: reply('u4', 'S. Ali', {
      answer: 'REMOTE',
      how: 'PHONE',
      howNote: 'called the office',
      recordedBy: { userId: ME, name: 'Nadia Karim' },
      answeredAt: '2026-12-09T05:00:00Z',
      version: 0,
    }),
  }),
  invitee(ME, 'Nadia Karim', { reply: reply(ME, 'Nadia Karim') }),
]

const panel: InvitationsResponse = {
  meetingId: 'm1',
  created: true,
  sequence: 1,
  pending: false,
  cancelled: false,
  canResend: true,
  people,
}

const replies: RepliesResponse = {
  meetingId: 'm1',
  canRecord: true,
  counts: { inPerson: 1, remote: 1, apologies: 1, stale: 0, noAnswer: 3, total: 6 },
  members: { inPerson: 1, remote: 1, apologies: 1, stale: 0, noAnswer: 2, total: 5 },
  people: people.flatMap((p) => (p.reply ? [p.reply] : [])),
}

const officerMeeting: MeetingResponse = {
  ...meeting,
  relation: 'OFFICER',
  canManage: true,
  rsvp: { capacity: 'MEMBER', alternateAttends: false, stale: false, canAnswer: true },
  invitation: { sequence: 1, sentAt: '2026-11-12T04:02:00Z', lastSentAt: '2026-12-03T09:40:00Z', pending: false },
}

interface PanelCalls {
  records: { userId: string; body: unknown; key: string | null; ifMatch: string | null }[]
  resends: { body: unknown; key: string | null }[]
}

function panelHandlers(calls: PanelCalls, resend?: () => Response) {
  let current = panel
  return [
    ...handlers({ puts: [], mineReads: 0 }, { meeting: officerMeeting }),
    http.get(`${API}/meetings/m1/invitations`, () => HttpResponse.json<InvitationsResponse>(current)),
    http.get(`${API}/meetings/m1/rsvps`, () => HttpResponse.json<RepliesResponse>(replies)),
    http.put(`${API}/meetings/m1/rsvps/:userId`, async ({ request, params }) => {
      calls.records.push({
        userId: String(params.userId),
        body: await request.json(),
        key: request.headers.get('Idempotency-Key'),
        ifMatch: request.headers.get('If-Match'),
      })
      return HttpResponse.json<ReplyResponse>(reply(String(params.userId), 'M. Hossain', { answer: 'APOLOGIES' }))
    }),
    http.post(`${API}/meetings/m1/invitations/resend`, async ({ request }) => {
      calls.resends.push({ body: await request.json(), key: request.headers.get('Idempotency-Key') })
      if (resend) {
        // The panel as the server then has it: M. Hossain was sent the invitation 4 minutes ago.
        current = {
          ...panel,
          people: panel.people.map((p) =>
            p.userId === 'u3' ? { ...p, resendFrom: new Date(Date.now() + 6 * 60_000).toISOString() } : p,
          ),
        }
        return resend()
      }
      return HttpResponse.json<InvitationsResponse>(current)
    }),
  ]
}

describe('Invitations and replies (Phase 4 E05 T7, flow W)', () => {
  it('shows each person’s reply, how it came and the invitation, and records an answer given by phone', async () => {
    const user = userEvent.setup()
    const calls: PanelCalls = { records: [], resends: [] }
    server.use(...panelHandlers(calls))
    renderRoute('/meetings/m1')

    await user.click(await screen.findByRole('tab', { name: 'Invitations and replies' }))
    const table = await screen.findByRole('table', { name: 'Invitations and replies' })
    expect(screen.getByText(/^Replies:/)).toHaveTextContent(
      'Replies: 2 yes (1 in person, 1 online) · 1 apology · 3 not yet · 0 for the old time',
    )
    expect(screen.getByText('Members: 2 of 5 said they will attend.')).toBeInTheDocument()
    const rows = within(table).getAllByRole('row').slice(1)
    // The alternate right after the member they stand in for.
    expect(rows.map((r) => within(r).getByRole('rowheader').textContent)).toEqual([
      'Dr F. Karim',
      'Demo Director',
      'A. Rahman',
      'M. Hossain',
      'S. Ali',
      'Nadia Karim',
    ])
    expect(rows[0]).toHaveTextContent('Yes, in person')
    expect(rows[0]).toHaveTextContent('Note: “I will join after 11:00”')
    expect(rows[0]).toHaveTextContent('in memo')
    expect(rows[1]).toHaveTextContent('Apologies — alternate attends')
    expect(rows[2]).toHaveTextContent('Alternate for Demo Director')
    expect(rows[3]).toHaveTextContent('Not yet')
    expect(rows[3]).toHaveTextContent('Not delivered: the update of')
    expect(rows[4]).toHaveTextContent('by phone')
    expect(rows[4]).toHaveTextContent('Recorded by Nadia Karim on')
    expect(rows[4]).toHaveTextContent('“called the office”')
    // One's own answer is given on the meeting page, not recorded.
    await user.click(within(rowOf(rows, 5)).getByRole('button', { name: 'Actions for Nadia Karim' }))
    expect((await screen.findAllByRole('menuitem')).map((i) => i.textContent)).toEqual(['Resend the invitation'])
    await user.keyboard('{Escape}')

    await user.click(within(rowOf(rows, 3)).getByRole('button', { name: 'Actions for M. Hossain' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Record an answer…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Record an answer for M. Hossain' })
    expect(dialog).toHaveTextContent('M. Hossain will be told that you recorded this answer.')
    await user.click(within(dialog).getByRole('radio', { name: 'Apologies' }))
    // "How" is required: nothing is sent without it.
    await user.click(within(dialog).getByRole('button', { name: 'Record the answer' }))
    expect(
      await within(dialog).findByText('Say how they told you: by email, by phone, in person or otherwise.'),
    ).toBeInTheDocument()
    expect(within(dialog).getByRole('radio', { name: 'Email' })).toHaveFocus()
    expect(calls.records).toHaveLength(0)

    await user.click(within(dialog).getByRole('radio', { name: 'Phone' }))
    await user.type(within(dialog).getByRole('textbox', { name: /Note/ }), 'called on 9 Dec')
    await user.click(within(dialog).getByRole('button', { name: 'Record the answer' }))
    await waitFor(() => {
      expect(calls.records).toHaveLength(1)
    })
    expect(calls.records[0]).toMatchObject({
      userId: 'u3',
      ifMatch: null,
      body: { answer: 'APOLOGIES', alternateAttends: false, how: 'PHONE', howNote: 'called on 9 Dec' },
    })
    expect(calls.records[0]?.key).toMatch(UUID)
  }, 30_000)

  it('records over an existing answer with its version as If-Match', async () => {
    const user = userEvent.setup()
    const calls: PanelCalls = { records: [], resends: [] }
    server.use(...panelHandlers(calls))
    renderRoute('/meetings/m1')
    await user.click(await screen.findByRole('tab', { name: 'Invitations and replies' }))
    await user.click(await screen.findByRole('button', { name: 'Actions for S. Ali' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Record an answer…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Record an answer for S. Ali' })
    expect(within(dialog).getByRole('radio', { name: 'Yes, online' })).toBeChecked()
    await user.click(within(dialog).getByRole('radio', { name: 'Email' }))
    await user.click(within(dialog).getByRole('button', { name: 'Record the answer' }))
    await waitFor(() => {
      expect(calls.records).toHaveLength(1)
    })
    expect(calls.records[0]?.ifMatch).toBe('"0"')
  }, 20_000)

  it('resends to those who have not replied, and says when it is too soon in words', async () => {
    const user = userEvent.setup()
    const calls: PanelCalls = { records: [], resends: [] }
    server.use(...panelHandlers(calls, () => problem(409, { reason: 'RESEND_TOO_SOON' })))
    renderRoute('/meetings/m1')
    await user.click(await screen.findByRole('tab', { name: 'Invitations and replies' }))
    await user.click(await screen.findByRole('button', { name: 'Actions for M. Hossain' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Resend the invitation' }))
    const dialog = await screen.findByRole('dialog', { name: 'Resend the invitation to M. Hossain' })
    expect(within(dialog).getByRole('radio', { name: 'M. Hossain only' })).toBeChecked()
    expect(within(dialog).getByRole('radio', { name: 'Everyone who has not replied (3)' })).toBeInTheDocument()
    expect(within(dialog).getByRole('radio', { name: 'Everyone (6)' })).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Resend the invitation' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      /^The invitation was sent to M\. Hossain less than 10 minutes ago\. You can resend it after \d{1,2}:\d{2}/,
    )
    expect(calls.resends[0]?.body).toEqual({ userIds: ['u3'] })
    expect(calls.resends[0]?.key).toMatch(UUID)
  }, 20_000)

  it('resends to everyone who has not replied from "Resend…"', async () => {
    const user = userEvent.setup()
    const calls: PanelCalls = { records: [], resends: [] }
    server.use(...panelHandlers(calls))
    renderRoute('/meetings/m1')
    await user.click(await screen.findByRole('tab', { name: 'Invitations and replies' }))
    await user.click(await screen.findByRole('button', { name: 'Resend…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Resend the invitation' })
    await user.click(within(dialog).getByRole('button', { name: 'Resend the invitation' }))
    await waitFor(() => {
      expect(calls.resends).toHaveLength(1)
    })
    expect(calls.resends[0]?.body).toEqual({ userIds: ['u9', 'u3', ME] })
    expect(await screen.findByText('The invitation was sent again to 3 people.')).toBeInTheDocument()
  }, 20_000)

  it('is for officers only: a member sees no tab', async () => {
    server.use(...handlers({ puts: [], mineReads: 0 }))
    renderRoute('/meetings/m1')
    await screen.findByRole('region', { name: 'Will you attend?' })
    expect(screen.queryByRole('tab', { name: 'Invitations and replies' })).toBeNull()
  }, 20_000)

  it('reads right to left in Arabic without accessibility violations', async () => {
    const user = userEvent.setup()
    server.use(...panelHandlers({ records: [], resends: [] }))
    const { container } = renderRoute('/meetings/m1', { locale: 'ar' })
    await user.click(await screen.findByRole('tab', { name: 'الدعوات والردود' }))
    await screen.findByRole('table', { name: 'الدعوات والردود' })
    expect(document.documentElement).toHaveAttribute('dir', 'rtl')
    await expectNoA11yViolations(container)
  }, 20_000)
})
