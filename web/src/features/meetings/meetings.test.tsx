import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type {
  ForumPolicyResponse,
  ForumResponse,
  MeetingPageResponse,
  MeetingRequest,
  MeetingResponse,
  MeetingSummaryResponse,
  MemberAgendaResponse,
  MemberResponse,
  MyForumResponse,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import {
  accessHandler,
  API_ORIGIN,
  csrfHandler,
  myForumsHandler,
  myMeetingsHandler,
  server,
  sessionHandler,
  signedInSession,
} from '@/test/msw'
import { expectNoA11yViolations, renderRoute, renderWithProviders } from '@/test/render'
import { TimeZonePicker } from './TimeZonePicker'

const API = `${API_ORIGIN}/api/v1`
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

const brc: ForumResponse = {
  id: 'f1',
  key: 'board-risk',
  code: 'BRC',
  codeLocked: false,
  name: 'Board Risk Committee',
  shortName: 'Risk',
  typeId: 't1',
  typeName: 'Board committee',
  status: 'ACTIVE',
  chair: { id: 'u1', name: 'S. Ali' },
  secretaries: [{ id: 'u9', name: 'Company Secretary' }],
  memberCount: 4,
  termWarnings: 0,
  classificationId: 'l1',
  canManageMembers: true,
  canManage: false,
  version: 3,
}

const policy: ForumPolicyResponse = {
  typeId: 't1',
  typeName: 'Board committee',
  version: 2,
  memberAttributes: [],
  submissionPolicy: { deadlineWorkingDays: 5, lateMode: 'CHAIR_APPROVAL', triageWorkingDays: 5 },
  quorum: {
    counts: 'VOTING',
    basis: 'IN_OFFICE',
    threshold: { kind: 'MAJORITY' },
    required: [],
    composition: [],
    alternatesCount: true,
    remoteCounts: true,
    recusedCount: false,
  },
  outcomes: [],
  termPolicy: { reminderDays: [] },
  quorumBasis: 3,
  quorumNeeded: 2,
}

const levels = [
  { id: 'l0', name: 'Internal', rank: 0 },
  { id: 'l1', name: 'Confidential', rank: 1 },
  { id: 'l2', name: 'Strictly confidential', rank: 2 },
]

/** BRC 3/2026 on Saturday 12 December 2026, 10:00–13:00 in Dhaka (04:00–07:00 UTC). */
const scheduled: MeetingResponse = {
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
  relation: 'OFFICER',
  canManage: true,
  guests: [],
  version: 3,
}

const summary = (m: MeetingResponse): MeetingSummaryResponse => ({
  id: m.id,
  forumId: m.forumId,
  forumName: m.forumName,
  forumCode: m.forumCode,
  kind: m.kind,
  title: m.title,
  startsAt: m.startsAt,
  endsAt: m.endsAt,
  timeZone: m.timeZone,
  localStartsAt: m.localStartsAt,
  localEndsAt: m.localEndsAt,
  mode: m.mode,
  location: m.location,
  joinUrl: m.joinUrl,
  label: m.label,
  status: m.status,
  relation: m.relation,
})

const draft: MeetingResponse = {
  ...scheduled,
  id: 'm2',
  startsAt: '2027-01-14T04:00:00Z',
  endsAt: '2027-01-14T06:00:00Z',
  localStartsAt: '2027-01-14T10:00:00',
  localEndsAt: '2027-01-14T12:00:00',
  mode: 'IN_PERSON',
  joinUrl: null,
  joinDetails: null,
  number: null,
  label: null,
  status: 'DRAFT',
  version: 0,
}

const cancelled: MeetingResponse = {
  ...draft,
  id: 'm3',
  title: 'Strategy day',
  kind: 'SPECIAL',
  status: 'CANCELLED',
  statusReason: 'Chair unavailable',
}

interface Seen {
  listQueries: URLSearchParams[]
}

function handlers(forum: ForumResponse = brc, seen: Seen = { listQueries: [] }) {
  return [
    sessionHandler(signedInSession(true)),
    accessHandler(['forums.manage']),
    csrfHandler(),
    http.get(`${API}/forums/:id`, () => HttpResponse.json<ForumResponse>(forum)),
    http.get(`${API}/forums/:id/members`, () => HttpResponse.json<MemberResponse[]>([])),
    http.get(`${API}/forums/:id/policy`, () => HttpResponse.json<ForumPolicyResponse>(policy)),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json(levels)),
    http.get(`${API}/forums/:id/meetings`, ({ request }) => {
      const query = new URL(request.url).searchParams
      seen.listQueries.push(query)
      const statuses = query.getAll('status')
      const all = forum.canManageMembers ? [scheduled, draft, cancelled] : [scheduled, cancelled]
      return HttpResponse.json<MeetingPageResponse>({
        items: all.filter((m) => statuses.includes(m.status)).map(summary),
        nextCursor: null,
      })
    }),
    // The agenda (T8) before it is published.
    http.get(`${API}/meetings/:id/agenda`, ({ params }) =>
      HttpResponse.json<MemberAgendaResponse>({
        meetingId: String(params.id),
        status: 'SCHEDULED',
        startsAt: scheduled.startsAt,
        endsAt: scheduled.endsAt,
        timeZone: scheduled.timeZone,
        latestRevision: 0,
        preview: false,
        sections: [],
      }),
    ),
    http.get(`${API}/meetings/:id`, ({ params }) =>
      HttpResponse.json<MeetingResponse>([scheduled, draft, cancelled].find((m) => m.id === params.id) ?? scheduled),
    ),
  ]
}

/** The text of [element] with every kind of space made a plain one (the locale data uses thin and narrow spaces). */
const plain = (element: Element) => element.textContent.replace(/\s+/g, ' ')

describe('the Meetings tab (Phase 4 E03 T6, flow N1)', () => {
  it('lists upcoming meetings with times in the meeting’s zone, how they are held and their state', async () => {
    const user = userEvent.setup()
    const seen: Seen = { listQueries: [] }
    server.use(...handlers(brc, seen))
    const { container } = renderRoute('/committees/f1?tab=meetings')

    expect(await screen.findByRole('tab', { name: 'Meetings', selected: true }, { timeout: 5000 })).toBeInTheDocument()
    const table = await screen.findByRole('table', { name: 'Meetings of Board Risk Committee' })
    const brc3 = (await within(table).findByRole('link', { name: 'BRC 3/2026' })).closest('tr') as HTMLElement
    expect(brc3).toHaveTextContent('Sat, Dec 12, 2026')
    expect(plain(brc3)).toMatch(/10:00 AM – 1:00 PM Dhaka time/)
    expect(within(brc3).getByText('Hybrid')).toBeInTheDocument()
    expect(within(brc3).getByText('Scheduled')).toBeInTheDocument()
    expect(within(brc3).getByRole('link', { name: 'BRC 3/2026' })).toHaveAttribute('href', '/meetings/m1')
    const row2 = within(table).getByRole('link', { name: 'Board Risk Committee' }).closest('tr') as HTMLElement
    expect(within(row2).getByText('Draft')).toBeInTheDocument()
    expect(within(row2).getByText('In person')).toBeInTheDocument()
    expect(seen.listQueries[0]?.getAll('status')).toEqual(['DRAFT', 'SCHEDULED', 'IN_PROGRESS'])
    expect(screen.getByRole('button', { name: 'Schedule a meeting' })).toBeInTheDocument()
    expect(partsForPath('/committees/f1')).toContain('meetings')
    expect(partsForPath('/')).toEqual(['meetings'])
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('tab', { name: 'Cancelled' }))
    const cancelledRow = (await screen.findByRole('link', { name: 'Strategy day' })).closest('tr') as HTMLElement
    expect(within(cancelledRow).getByText('Cancelled')).toBeInTheDocument()
    expect(within(cancelledRow).getByText('Special meeting')).toBeInTheDocument()
    expect(seen.listQueries.at(-1)?.getAll('status')).toEqual(['CANCELLED'])

    await user.click(screen.getByRole('tab', { name: 'Past' }))
    expect(await screen.findByText('No meetings were held in this period.')).toBeInTheDocument()
    expect(seen.listQueries.at(-1)?.getAll('status')).toEqual(['HELD', 'MINUTES_APPROVED', 'CLOSED'])
    expect(seen.listQueries.at(-1)?.get('from')).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    const yearAgo = seen.listQueries.at(-1)?.get('from') ?? ''
    await user.click(screen.getByRole('button', { name: 'Show earlier meetings' }))
    await vi.waitFor(() => {
      expect(seen.listQueries.at(-1)?.get('from')).toBe(`${String(Number(yearAgo.slice(0, 4)) - 1)}${yearAgo.slice(4)}`)
    })
  }, 20_000)

  it('offers members neither scheduling nor the meeting menu', async () => {
    server.use(...handlers({ ...brc, canManageMembers: false }))
    renderRoute('/committees/f1?tab=meetings')

    const table = await screen.findByRole('table', { name: 'Meetings of Board Risk Committee' }, { timeout: 5000 })
    expect(await within(table).findByRole('link', { name: 'BRC 3/2026' })).toBeInTheDocument()
    expect(within(table).queryByRole('link', { name: 'Board Risk Committee' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Schedule a meeting' })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Actions for/ })).toBeNull()
  })
})

describe('scheduling a meeting (flow N2)', () => {
  /** Fills the sheet's date and times and picks Dhaka in the time-zone picker. */
  async function fillTimes(user: ReturnType<typeof userEvent.setup>, sheet: HTMLElement) {
    await user.type(within(sheet).getByLabelText(/^Date/), '2026-12-12')
    await user.type(within(sheet).getByLabelText(/^From/), '10:00')
    await user.type(within(sheet).getByLabelText(/^To/), '13:00')
    await user.type(within(sheet).getByRole('combobox', { name: /^Time zone/ }), 'Dhaka')
    await user.click(await within(sheet).findByRole('option', { name: /^Dhaka \(GMT\+6\)/ }))
  }

  it('creates the meeting with an Idempotency-Key and schedules it with If-Match', async () => {
    const user = userEvent.setup()
    const bodies: MeetingRequest[] = []
    const keys: (string | null)[] = []
    const ifMatch: (string | null)[] = []
    server.use(
      ...handlers(),
      http.post(`${API}/forums/f1/meetings`, async ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        bodies.push((await request.json()) as MeetingRequest)
        return HttpResponse.json<MeetingResponse>({ ...draft, id: 'm9', version: 0 }, { status: 201 })
      }),
      http.post(`${API}/meetings/m9/schedule`, ({ request }) => {
        ifMatch.push(request.headers.get('If-Match'))
        return HttpResponse.json<MeetingResponse>({ ...draft, id: 'm9', status: 'SCHEDULED', version: 1 })
      }),
    )
    renderRoute('/committees/f1?tab=meetings')

    await user.click(await screen.findByRole('button', { name: 'Schedule a meeting' }, { timeout: 5000 }))
    const sheet = await screen.findByRole('dialog', { name: 'New meeting of Board Risk Committee' })
    expect(within(sheet).getByRole('combobox', { name: /^Time zone/ })).toHaveValue('Your organisation’s time zone')
    await within(sheet).findByRole('option', { name: 'Confidential' })
    expect(within(sheet).getByRole('combobox', { name: 'Classification' })).toHaveValue('')
    expect(within(sheet).queryByRole('option', { name: 'Internal' })).toBeNull()
    await fillTimes(user, sheet)
    await user.click(within(sheet).getByRole('radio', { name: 'Both (hybrid)' }))
    await user.type(within(sheet).getByRole('textbox', { name: /^Where/ }), 'Board Room')
    await user.type(within(sheet).getByRole('textbox', { name: /^Join link/ }), 'https://meet.example/abc')
    await user.click(within(sheet).getByRole('button', { name: 'Schedule' }))

    await vi.waitFor(() => {
      expect(ifMatch).toEqual(['"0"'])
    })
    expect(bodies).toEqual([
      {
        kind: 'ORDINARY',
        startsAt: '2026-12-12T10:00:00',
        endsAt: '2026-12-12T13:00:00',
        timeZone: 'Asia/Dhaka',
        mode: 'HYBRID',
        location: 'Board Room',
        joinUrl: 'https://meet.example/abc',
        joinDetails: null,
      },
    ])
    expect(keys[0]).toMatch(UUID)
    expect(await screen.findByText('Meeting scheduled. The members can see it now.')).toBeInTheDocument()
  }, 20_000)

  it('saves a draft with guests and the organisation’s zone, without scheduling it', async () => {
    const user = userEvent.setup()
    const bodies: MeetingRequest[] = []
    const guests: unknown[] = []
    let schedules = 0
    server.use(
      ...handlers(),
      http.get(`${API}/directory/search`, () =>
        HttpResponse.json({
          users: [{ person: { id: 'u7', displayName: 'Farah Begum', email: 'farah@bank.example' } }],
          positions: [],
          units: [],
        }),
      ),
      http.post(`${API}/forums/f1/meetings`, async ({ request }) => {
        bodies.push((await request.json()) as MeetingRequest)
        return HttpResponse.json<MeetingResponse>({ ...draft, id: 'm9' }, { status: 201 })
      }),
      http.post(`${API}/meetings/m9/guests`, async ({ request }) => {
        guests.push(await request.json())
        return HttpResponse.json<MeetingResponse>({ ...draft, id: 'm9' })
      }),
      http.post(`${API}/meetings/m9/schedule`, () => {
        schedules += 1
        return HttpResponse.json<MeetingResponse>(draft)
      }),
    )
    renderRoute('/committees/f1?tab=meetings')

    await user.click(await screen.findByRole('button', { name: 'Schedule a meeting' }, { timeout: 5000 }))
    const sheet = await screen.findByRole('dialog', { name: 'New meeting of Board Risk Committee' })
    await user.click(within(sheet).getByRole('radio', { name: 'Special' }))
    await user.type(within(sheet).getByRole('textbox', { name: /^Title/ }), 'Strategy day')
    await user.type(within(sheet).getByLabelText(/^Date/), '2027-01-14')
    await user.type(within(sheet).getByLabelText(/^From/), '10:00')
    await user.type(within(sheet).getByLabelText(/^To/), '12:00')
    await user.type(within(sheet).getByRole('textbox', { name: /^Where/ }), 'Head Office')
    await user.selectOptions(within(sheet).getByRole('combobox', { name: 'Classification' }), 'Strictly confidential')
    await user.type(within(sheet).getByRole('combobox', { name: /^Guests/ }), 'Fa')
    await user.click(await within(sheet).findByRole('option', { name: /Farah Begum/ }))
    expect(within(sheet).getByRole('list', { name: 'Guests' })).toHaveTextContent('Farah Begum')
    await user.click(within(sheet).getByRole('button', { name: 'Save as draft' }))

    expect(await screen.findByText('Saved as a draft.')).toBeInTheDocument()
    expect(bodies).toEqual([
      {
        kind: 'SPECIAL',
        title: 'Strategy day',
        startsAt: '2027-01-14T10:00:00',
        endsAt: '2027-01-14T12:00:00',
        mode: 'IN_PERSON',
        location: 'Head Office',
        joinUrl: null,
        joinDetails: null,
        classificationId: 'l2',
      },
    ])
    expect(guests).toEqual([{ userId: 'u7' }])
    expect(schedules).toBe(0)
  }, 20_000)

  it('says in words at the time that a time in a clock change does not exist', async () => {
    const user = userEvent.setup()
    server.use(
      ...handlers(),
      http.post(`${API}/forums/f1/meetings`, () =>
        HttpResponse.json(
          {
            type: 'urn:memo:problem:validation',
            status: 422,
            title: 'Some fields need attention.',
            errors: [
              {
                field: 'startsAt',
                code: 'UNKNOWN_LOCAL_TIME',
                message: 'This time does not exist on that day (the clocks change).',
              },
              { field: 'joinUrl', code: 'LINK_CREDENTIALS', message: 'Leave the user name and password out.' },
            ],
          },
          { status: 422 },
        ),
      ),
    )
    renderRoute('/committees/f1?tab=meetings')

    await user.click(await screen.findByRole('button', { name: 'Schedule a meeting' }, { timeout: 5000 }))
    const sheet = await screen.findByRole('dialog', { name: 'New meeting of Board Risk Committee' })
    await user.click(within(sheet).getByRole('button', { name: 'Save as draft' }))
    expect(within(sheet).getByLabelText(/^Date/)).toHaveAccessibleDescription('Fill this in.')

    await user.type(within(sheet).getByLabelText(/^Date/), '2027-03-28')
    await user.type(within(sheet).getByLabelText(/^From/), '01:30')
    await user.type(within(sheet).getByLabelText(/^To/), '03:00')
    await user.click(within(sheet).getByRole('radio', { name: 'Online' }))
    await user.type(within(sheet).getByRole('textbox', { name: /^Join link/ }), 'https://me:pw@meet.example/abc')
    await user.click(within(sheet).getByRole('button', { name: 'Save as draft' }))

    const from = within(sheet).getByLabelText(/^From/)
    await vi.waitFor(() => {
      expect(from).toHaveAccessibleDescription(
        'This time does not exist on that day because the clocks change. Choose another time.',
      )
    })
    expect(from).toHaveAttribute('aria-invalid', 'true')
    expect(within(sheet).getByRole('textbox', { name: /^Join link/ })).toHaveAccessibleDescription(
      /Leave the user name and password out of the link\./,
    )
    expect(screen.getByRole('dialog', { name: 'New meeting of Board Risk Committee' })).toBeInTheDocument()
  }, 20_000)
})

describe('the meeting’s page (flow N2: reschedule, cancel, guests)', () => {
  it('shows the meeting in brief and reschedules it with a reason and If-Match', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    const ifMatch: (string | null)[] = []
    server.use(
      ...handlers(),
      http.post(`${API}/meetings/m1/reschedule`, async ({ request }) => {
        ifMatch.push(request.headers.get('If-Match'))
        bodies.push(await request.json())
        return HttpResponse.json<MeetingResponse>({ ...scheduled, version: 4 })
      }),
    )
    const { container } = renderRoute('/meetings/m1')

    const title = await screen.findByRole('heading', { level: 1 }, { timeout: 5000 })
    expect(title).toHaveTextContent('Board Risk Committee — BRC 3/2026')
    expect(screen.getByText('Saturday, December 12, 2026')).toBeInTheDocument()
    expect(screen.getByText('Board Room, Level 12, Head Office')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Join the meeting/ })).toHaveAttribute('href', 'https://meet.example/abc')
    expect(await screen.findByText('Confidential')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to Board Risk Committee' })).toHaveAttribute(
      'href',
      '/committees/f1?tab=meetings',
    )
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('button', { name: 'Actions for Board Risk Committee — BRC 3/2026' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Reschedule…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reschedule BRC 3/2026' })
    expect(within(dialog).getByLabelText(/^Date/)).toHaveValue('2026-12-12')
    expect(within(dialog).getByLabelText(/^From/)).toHaveValue('10:00')
    expect(within(dialog).getByRole('combobox', { name: /^Time zone/ })).toHaveValue('Dhaka (GMT+6)')
    const date = within(dialog).getByLabelText(/^Date/)
    await user.clear(date)
    await user.type(date, '2026-12-15')
    await user.click(within(dialog).getByRole('button', { name: 'Reschedule' }))
    expect(within(dialog).getByRole('textbox', { name: /^Reason/ })).toHaveAccessibleDescription(/Give a reason\./)
    expect(bodies).toEqual([])

    await user.type(within(dialog).getByRole('textbox', { name: /^Reason/ }), 'Chair unavailable')
    await user.click(within(dialog).getByRole('button', { name: 'Reschedule' }))
    expect(await screen.findByText('Meeting rescheduled.')).toBeInTheDocument()
    expect(ifMatch).toEqual(['"3"'])
    expect(bodies).toEqual([
      {
        startsAt: '2026-12-15T10:00:00',
        endsAt: '2026-12-15T13:00:00',
        timeZone: 'Asia/Dhaka',
        reason: 'Chair unavailable',
      },
    ])
  }, 20_000)

  it('cancels with a reason, says what happens, and shows a refusal in words', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    const ifMatch: (string | null)[] = []
    let refuse = true
    server.use(
      ...handlers(),
      http.post(`${API}/meetings/m1/cancel`, async ({ request }) => {
        ifMatch.push(request.headers.get('If-Match'))
        bodies.push(await request.json())
        if (refuse) {
          refuse = false
          return HttpResponse.json({ status: 409, title: 'Conflict', reason: 'WRONG_STATE' }, { status: 409 })
        }
        return HttpResponse.json<MeetingResponse>({ ...scheduled, status: 'CANCELLED', version: 4 })
      }),
    )
    renderRoute('/meetings/m1')

    await user.click(
      await screen.findByRole('button', { name: 'Actions for Board Risk Committee — BRC 3/2026' }, { timeout: 5000 }),
    )
    await user.click(await screen.findByRole('menuitem', { name: 'Cancel the meeting…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Cancel BRC 3/2026?' })
    expect(dialog).toHaveTextContent('Members will be told. Items from the queue go back to Ready for an agenda.')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel the meeting' }))
    expect(within(dialog).getByRole('textbox', { name: /^Reason/ })).toHaveAccessibleDescription(/Give a reason\./)
    expect(bodies).toEqual([])

    await user.type(within(dialog).getByRole('textbox', { name: /^Reason/ }), 'Not enough business')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel the meeting' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'This cannot be done now: the meeting has moved on. The page shows its latest state.',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Cancel the meeting' }))
    expect(await screen.findByText('Meeting cancelled.')).toBeInTheDocument()
    expect(ifMatch).toEqual(['"3"', '"3"'])
    expect(bodies).toEqual([{ reason: 'Not enough business' }, { reason: 'Not enough business' }])
  }, 20_000)

  it('adds a guest with their role and removes one', async () => {
    const user = userEvent.setup()
    const added: unknown[] = []
    const removed: string[] = []
    const withGuest: MeetingResponse = {
      ...scheduled,
      guests: [{ userId: 'u5', name: 'N. Hossain', note: 'Chief Financial Officer', addedAt: '2026-11-18T09:00:00Z' }],
    }
    server.use(
      http.get(`${API}/meetings/m1`, () => HttpResponse.json<MeetingResponse>(withGuest)),
      ...handlers(),
      http.get(`${API}/directory/search`, () =>
        HttpResponse.json({
          users: [{ person: { id: 'u7', displayName: 'Farah Begum', email: 'farah@bank.example' } }],
          positions: [],
          units: [],
        }),
      ),
      http.post(`${API}/meetings/m1/guests`, async ({ request }) => {
        added.push(await request.json())
        return HttpResponse.json<MeetingResponse>(withGuest)
      }),
      http.post(`${API}/meetings/m1/guests/:userId/remove`, ({ params }) => {
        removed.push(String(params.userId))
        return HttpResponse.json<MeetingResponse>(scheduled)
      }),
    )
    renderRoute('/meetings/m1')

    const guests = await screen.findByRole('region', { name: 'Guests' }, { timeout: 5000 })
    expect(within(guests).getByText('N. Hossain')).toBeInTheDocument()
    expect(within(guests).getByText('Chief Financial Officer')).toBeInTheDocument()

    await user.click(within(guests).getByRole('button', { name: 'Add a guest' }))
    const dialog = await screen.findByRole('dialog', { name: 'Add a guest to BRC 3/2026' })
    await user.click(within(dialog).getByRole('button', { name: 'Add a guest' }))
    expect(within(dialog).getByText('Choose a person from the list.')).toBeInTheDocument()
    await user.type(within(dialog).getByRole('combobox', { name: 'Person' }), 'Fa')
    await user.click(await within(dialog).findByRole('option', { name: /Farah Begum/ }))
    await user.type(within(dialog).getByRole('textbox', { name: /^Their role/ }), 'Head of Treasury')
    await user.click(within(dialog).getByRole('button', { name: 'Add a guest' }))
    expect(await screen.findByText('Guest added.')).toBeInTheDocument()
    expect(added).toEqual([{ userId: 'u7', note: 'Head of Treasury' }])

    await user.click(within(guests).getByRole('button', { name: 'Remove N. Hossain' }))
    const confirm = await screen.findByRole('dialog', { name: 'Remove N. Hossain as a guest?' })
    await user.click(within(confirm).getByRole('button', { name: 'Remove' }))
    expect(await screen.findByText('Guest removed.')).toBeInTheDocument()
    expect(removed).toEqual(['u5'])
  }, 20_000)

  it('keeps the place and link from forum.read holders outside the audience', async () => {
    server.use(
      http.get(`${API}/meetings/m1`, () =>
        HttpResponse.json<MeetingResponse>({
          ...scheduled,
          relation: 'READER',
          canManage: false,
          location: null,
          joinUrl: null,
          joinDetails: null,
        }),
      ),
      ...handlers(),
    )
    renderRoute('/meetings/m1')

    expect(
      await screen.findByText('Hybrid · The place and the link are shown to the people attending.', undefined, {
        timeout: 5000,
      }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Join the meeting/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Actions for/ })).toBeNull()
  })
})

describe('my meetings (flow N3)', () => {
  const inDays = (days: number, hours: number) => new Date(Date.now() + days * 86_400_000 + hours * 3_600_000)
  /** A meeting [days] from now, held on the viewer's own clock (so "your time" does not show) unless [zone] says. */
  const mine = (id: string, days: number, zone: string, extra: Partial<MeetingSummaryResponse> = {}) => ({
    ...summary(scheduled),
    id,
    startsAt: inDays(days, 0).toISOString(),
    endsAt: inDays(days, 3).toISOString(),
    timeZone: zone,
    relation: 'MEMBER' as const,
    ...extra,
  })
  const here = Intl.DateTimeFormat().resolvedOptions().timeZone
  // Never anybody's machine zone: UTC+14 all year.
  const far = 'Pacific/Kiritimati'

  it('puts the next meetings on the home page, with the viewer’s own time when the zones differ', async () => {
    server.use(
      ...handlers(),
      myMeetingsHandler([
        mine('a', 3, far),
        mine('b', 10, here, { forumName: 'Management Credit Committee', label: 'MCC 7/2026', mode: 'ONLINE' }),
        mine('c', -3, here, { status: 'HELD' }),
      ]),
    )
    renderRoute('/')

    const card = await screen.findByRole('region', { name: 'My meetings' }, { timeout: 5000 })
    const items = within(card).getAllByRole('article')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveAccessibleName('Board Risk Committee — BRC 3/2026 · Hybrid')
    expect(plain(items[0]!)).toMatch(/Kiritimati time ?\(.+ your time\)/)
    expect(items[1]).toHaveAccessibleName('Management Credit Committee — MCC 7/2026 · Online')
    expect(items[1]).not.toHaveTextContent('your time')
    expect(
      within(items[0]!).getByRole('link', {
        name: /^Open the meeting Board Risk Committee — BRC 3\/2026 on /,
      }),
    ).toHaveAttribute('href', '/meetings/a')
    expect(within(card).getByRole('link', { name: 'All my meetings' })).toHaveAttribute('href', '/me/meetings')
  })

  it('lists coming and recent meetings on /me/meetings and the next one on My committees', async () => {
    const membership: MyForumResponse = {
      forumId: 'f1',
      name: 'Board Risk Committee',
      code: 'BRC',
      status: 'ACTIVE',
      role: 'MEMBER',
      voting: true,
      attributes: [],
      state: 'IN_OFFICE',
      startsOn: '2024-01-01',
      termNumber: 1,
      canOpen: true,
    }
    const list = [
      mine('a', 3, here, { relation: 'GUEST' }),
      mine('c', -3, here, { status: 'CANCELLED', label: null, title: 'Strategy day' }),
    ]
    server.use(...handlers(), myMeetingsHandler(list), myForumsHandler([membership]))
    const { container, router } = renderRoute('/me/meetings')

    const coming = await screen.findByRole('region', { name: 'Coming up' }, { timeout: 5000 })
    expect(within(coming).getByRole('article')).toHaveTextContent('You attend as a guest.')
    const recent = screen.getByRole('region', { name: 'Recent' })
    expect(within(recent).getByRole('article', { name: /Strategy day/ })).toHaveTextContent('Cancelled')
    await expectNoA11yViolations(container)

    await router.navigate({ to: '/me/committees' })
    const card = await screen.findByRole('article', { name: 'Board Risk Committee' }, { timeout: 5000 })
    const next = await within(card).findByRole('link', { name: /^Next meeting: / })
    expect(next).toHaveAttribute('href', '/meetings/a')
  }, 20_000)
})

describe('the time-zone picker by city', () => {
  function Picker({ onPick }: { onPick: (zone: string) => void }) {
    const [zone, setZone] = useState('')
    return (
      <>
        <label htmlFor="zone">Time zone</label>
        <TimeZonePicker
          id="zone"
          value={zone}
          onChange={(z) => {
            setZone(z)
            onPick(z)
          }}
        />
      </>
    )
  }

  it('finds zones by city or by their long name and picks with the keyboard', async () => {
    const user = userEvent.setup()
    const picked: string[] = []
    renderWithProviders(<Picker onPick={(z) => picked.push(z)} />)

    const input = await screen.findByRole('combobox', { name: 'Time zone' })
    expect(input).toHaveValue('Your organisation’s time zone')
    await user.click(input)
    expect(screen.getAllByRole('option')[0]).toHaveTextContent('Your organisation’s time zone')

    await user.type(input, 'buenos aires')
    expect(screen.getByRole('option', { name: /^Buenos Aires \(GMT-3\)/ })).toBeInTheDocument()

    await user.clear(input)
    await user.type(input, 'bangladesh')
    const options = screen.getAllByRole('option')
    expect(options[0]).toHaveTextContent(/^Dhaka \(GMT\+6\)Asia\/Dhaka/)
    await user.keyboard('{ArrowDown}{Enter}')
    expect(picked).toEqual(['Asia/Dhaka'])
    expect(input).toHaveValue('Dhaka (GMT+6)')
    expect(input).toHaveAttribute('aria-expanded', 'false')

    await user.clear(input)
    await user.type(input, 'atlantis')
    expect(screen.getByText('No time zone found for that city.')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    await user.tab()
    expect(input).toHaveValue('Dhaka (GMT+6)')
  })
})

describe('meetings in Arabic', () => {
  it('reads right to left with the times and the sheet translated', async () => {
    const user = userEvent.setup()
    server.use(...handlers())
    const { container } = renderRoute('/committees/f1?tab=meetings', { locale: 'ar' })

    expect(
      await screen.findByRole('tab', { name: 'الاجتماعات', selected: true }, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(document.documentElement.dir).toBe('rtl')
    const table = await screen.findByRole('table', { name: 'اجتماعات Board Risk Committee' })
    const row = (await within(table).findByRole('link', { name: 'BRC 3/2026' })).closest('tr') as HTMLElement
    expect(row).toHaveTextContent('بتوقيت Dhaka')
    expect(within(row).getByText('مختلط')).toBeInTheDocument()
    expect(within(row).getByText('مجدول')).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('button', { name: 'جدولة اجتماع' }))
    const sheet = await screen.findByRole('dialog', { name: 'اجتماع جديد لـ Board Risk Committee' })
    expect(within(sheet).getByRole('combobox', { name: /^المنطقة الزمنية/ })).toHaveValue('المنطقة الزمنية لمؤسستك')
    await expectNoA11yViolations(sheet)
  }, 20_000)
})
