import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type { AgendaDraftResponse, MeetingPageResponse, SubmissionResponse } from '@/api/generated/types.gen'
import { ToastProvider } from '@/components/ui/toast'
import { TriageDialog } from '@/features/forums/TriageDialogs'
import { API_ORIGIN, csrfHandler, server } from '@/test/msw'
import { renderWithProviders } from '@/test/render'

const API = `${API_ORIGIN}/api/v1`

const queued: SubmissionResponse = {
  id: 'sub1',
  forumId: 'f1',
  forumName: 'Board Risk Committee',
  status: 'QUEUED',
  purpose: 'DECISION',
  subjectId: 'memo1',
  subjectType: 'memo',
  memo: { id: 'memo1', reference: 'CR/HO-CR/2026-27/0042', subject: 'Term loan, Delta Textiles' },
  submittedAt: '2026-11-10T00:00:00Z',
  submittedBy: { id: 'u5', name: 'Credit Officer' },
  version: 1,
  waits: true,
}

const meetings: MeetingPageResponse = {
  items: [
    {
      id: 'm2',
      forumId: 'f1',
      forumName: 'Board Risk Committee',
      forumCode: 'BRC',
      kind: 'ORDINARY',
      startsAt: '2027-02-10T04:00:00Z',
      endsAt: '2027-02-10T07:00:00Z',
      timeZone: 'Asia/Dhaka',
      localStartsAt: '2027-02-10T10:00:00',
      localEndsAt: '2027-02-10T13:00:00',
      mode: 'IN_PERSON',
      status: 'SCHEDULED',
      relation: 'OFFICER',
    },
    {
      id: 'm1',
      forumId: 'f1',
      forumName: 'Board Risk Committee',
      forumCode: 'BRC',
      kind: 'ORDINARY',
      startsAt: '2027-01-14T04:00:00Z',
      endsAt: '2027-01-14T07:00:00Z',
      timeZone: 'Asia/Dhaka',
      localStartsAt: '2027-01-14T10:00:00',
      localEndsAt: '2027-01-14T13:00:00',
      mode: 'IN_PERSON',
      status: 'SCHEDULED',
      relation: 'OFFICER',
    },
  ],
  nextCursor: null,
}

const draft = (over: Partial<AgendaDraftResponse>): AgendaDraftResponse => ({
  meetingId: 'm1',
  started: true,
  currentRevision: 0,
  dirty: false,
  changes: 'ANY',
  canEdit: true,
  canAgree: false,
  chairAgreementRequired: true,
  chairComments: [],
  startsAt: '2027-01-14T04:00:00Z',
  endsAt: '2027-01-14T07:00:00Z',
  timeZone: 'Asia/Dhaka',
  paperDeadline: '2027-01-07',
  deadlinePassed: false,
  lateMode: 'CHAIR_APPROVAL',
  totalMinutes: 0,
  plannedEndsAt: '2027-01-14T04:00:00Z',
  overrun: false,
  traySize: 0,
  sections: [],
  state: 'DRAFT',
  revisions: [],
  version: 4,
  ...over,
})

function setup(agenda: AgendaDraftResponse) {
  const calls: { path: string; body: unknown; ifMatch: string | null }[] = []
  server.use(
    csrfHandler(),
    http.get(`${API}/forums/:id/meetings`, () => HttpResponse.json(meetings)),
    http.get(`${API}/meetings/:id/agenda/draft`, () => HttpResponse.json(agenda)),
    http.post(`${API}/meetings/:id/agenda/items`, async ({ request }) => {
      calls.push({
        path: new URL(request.url).pathname,
        body: await request.json(),
        ifMatch: request.headers.get('If-Match'),
      })
      return HttpResponse.json({ ...agenda, version: agenda.version + 1 })
    }),
    http.post(`${API}/forums/:fid/submissions/:id/accept`, async ({ request }) => {
      calls.push({
        path: new URL(request.url).pathname,
        body: await request.json(),
        ifMatch: request.headers.get('If-Match'),
      })
      return HttpResponse.json({ ...queued, status: 'ACCEPTED' })
    }),
  )
  return calls
}

function open(onClose = vi.fn()) {
  renderWithProviders(
    <ToastProvider>
      <TriageDialog action="accept" submission={queued} onClose={onClose} />
    </ToastProvider>,
  )
  return onClose
}

describe('Accept and place (Phase 4 E03 T7, flow O6)', () => {
  it('accepts a queued paper and places it on the chosen meeting’s agenda in one step', async () => {
    const user = userEvent.setup()
    const calls = setup(draft({}))
    const onClose = open()

    const field = await screen.findByRole('combobox', { name: /Place on the agenda of/ })
    // The coming meetings, the next one first.
    expect(screen.getByRole('option', { name: 'Next meeting: Jan 14, 2027' })).toBeInTheDocument()
    expect(screen.getByLabelText(/Target meeting date/)).toBeInTheDocument()
    await user.selectOptions(field, 'm1')
    expect(await screen.findByRole('button', { name: 'Accept and place' })).toBeInTheDocument()
    expect(screen.queryByLabelText(/Target meeting date/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Accept and place' }))

    await waitFor(() => {
      expect(onClose).toHaveBeenCalled()
    })
    expect(calls).toEqual([
      { path: '/api/v1/meetings/m1/agenda/items', body: { kind: 'SUBMISSION', submissionId: 'sub1' }, ifMatch: '"4"' },
    ])
  })

  it('says the committee’s late rule before placing, and does not place under BLOCK', async () => {
    const user = userEvent.setup()
    const calls = setup(draft({ deadlinePassed: true, lateMode: 'BLOCK' }))
    const onClose = open()

    await user.selectOptions(await screen.findByRole('combobox', { name: /Place on the agenda of/ }), 'm1')
    expect(
      await screen.findByText(/Late papers are not accepted for this committee: papers were due on Jan 7, 2027/),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Accept and place' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose a later meeting.')
    expect(calls).toEqual([])
    expect(onClose).not.toHaveBeenCalled()
  })

  it('tells the secretary a late paper goes to the chair first', async () => {
    const user = userEvent.setup()
    setup(draft({ deadlinePassed: true, lateMode: 'CHAIR_APPROVAL' }))
    open()
    await user.selectOptions(await screen.findByRole('combobox', { name: /Place on the agenda of/ }), 'm1')
    expect(await screen.findByText(/This goes to the chair for agreement first/)).toBeInTheDocument()
  })

  it('only accepts when the meeting’s agenda is not started', async () => {
    const user = userEvent.setup()
    const calls = setup(draft({ started: false }))
    open()
    await user.selectOptions(await screen.findByRole('combobox', { name: /Place on the agenda of/ }), 'm2')
    expect(await screen.findByText(/has not been started yet/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Accept' }))
    await waitFor(() => {
      expect(calls.map((c) => c.path)).toEqual(['/api/v1/forums/f1/submissions/sub1/accept'])
    })
  })
})
