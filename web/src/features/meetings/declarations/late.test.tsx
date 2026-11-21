import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  LateRequestResponse,
  LateRequestsResponse,
  MeetingResponse,
  MemberAgendaResponse,
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
const VICE = { id: 'u4', name: 'T. Islam' }

/** BRC 3/2026, scheduled; the vice-chair reads it as a member while the chair is in office. */
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

const viceSeat: MyForumResponse = {
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

/** A late paper on an item the chair is recused from, and one on a restricted item whose title the vice-chair may not read. */
const valuation: LateRequestResponse = {
  itemId: 'i5',
  paperId: 'p1',
  number: '5',
  itemTitle: 'Term loan, Delta Textiles',
  paperTitle: 'Valuation report',
  purpose: 'DECISION',
}
const restricted: LateRequestResponse = { itemId: 'i8', paperId: 'p2', number: '8', itemTitle: null, paperTitle: null }

const session = (): Session => {
  const s = signedInSession()
  return { ...s, ...(s.user ? { user: { ...s.user, id: VICE.id, displayName: VICE.name } } : {}) }
}

interface Sent {
  body: unknown
  ifMatch: string | null
}

function setup(options: { requests?: LateRequestResponse[]; answer?: (sent: Sent) => Response | undefined } = {}) {
  const sent: Sent[] = []
  const state: LateRequestsResponse = {
    meetingId: 'm1',
    paperDeadline: '2026-12-05',
    requests: options.requests ?? [valuation, restricted],
    version: 7,
  }
  server.use(
    sessionHandler(session()),
    accessHandler([]),
    csrfHandler(),
    myForumsHandler([viceSeat]),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([])),
    http.get(`${API}/meetings/m1`, () => HttpResponse.json<MeetingResponse>(meeting)),
    http.get(`${API}/meetings/m1/agenda`, () => HttpResponse.json<MemberAgendaResponse>(agenda)),
    http.get(`${API}/meetings/m1/declarations/mine`, () => HttpResponse.json<MyDeclarationsResponse>(mine)),
    http.post(`${API}/meetings/m1/seen`, () => new HttpResponse(null, { status: 204 })),
    http.get(`${API}/meetings/m1/agenda/late-requests`, () => HttpResponse.json<LateRequestsResponse>(state)),
    http.post(`${API}/meetings/m1/agenda/late-requests/decisions`, async ({ request }) => {
      const call: Sent = { body: await request.json(), ifMatch: request.headers.get('If-Match') }
      sent.push(call)
      const refused = options.answer?.(call)
      if (refused) return refused
      const { paperId } = call.body as { paperId?: string }
      state.requests = state.requests.filter((r) => r.paperId !== paperId)
      state.version += 1
      return HttpResponse.json<LateRequestsResponse>(state, { headers: { ETag: `"${String(state.version)}"` } })
    }),
  )
  return { sent, state }
}

const problem = (status: number, extra: object = {}) =>
  HttpResponse.json(
    { status, title: 'Refused', ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

const panel = () => screen.findByRole('region', { name: 'Late papers for you to decide' })

describe('late papers for the person who decides (Phase 4 E04, design §1.7 as built)', () => {
  it('lets the vice-chair, a member here, agree a late paper and decline another after confirming', async () => {
    const user = userEvent.setup()
    const { sent } = setup()
    const { container } = renderRoute('/meetings/m1')

    const late = await panel()
    expect(late).toHaveTextContent('These came in after the paper deadline. The members see them only if you agree.')
    expect(late).toHaveTextContent('Papers were due on Dec 5, 2026.')
    expect(late).toHaveTextContent('Valuation report, for 5 Term loan, Delta Textiles')
    expect(late).toHaveTextContent('For decision')
    // A title the rule withholds: "Restricted item", "A restricted paper".
    expect(late).toHaveTextContent('A restricted paper, for 8 Restricted item')
    for (const button of within(late).getAllByRole('button')) expect(button.className).toMatch(/min-h-11/)
    await expectNoA11yViolations(container)

    await user.click(
      within(late).getByRole('button', { name: 'Agree to include Valuation report, for 5 Term loan, Delta Textiles' }),
    )
    await waitFor(() => {
      expect(sent).toHaveLength(1)
    })
    expect(sent[0]).toEqual({ body: { itemId: 'i5', paperId: 'p1', agree: true }, ifMatch: '"7"' })
    expect(await within(late).findByRole('status')).toHaveTextContent(
      'Valuation report, for 5 Term loan, Delta Textiles is agreed and will be published as a late paper.',
    )
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Late papers for you to decide' })).toHaveFocus()
    })

    await user.click(
      within(late).getByRole('button', { name: 'Not this meeting: A restricted paper, for 8 Restricted item' }),
    )
    const confirm = await screen.findByRole('alertdialog', {
      name: 'Not this meeting: A restricted paper, for 8 Restricted item?',
    })
    expect(confirm).toHaveTextContent('The paper is taken off the item and not published.')
    await user.click(within(confirm).getByRole('button', { name: 'Not this meeting' }))
    await waitFor(() => {
      expect(sent).toHaveLength(2)
    })
    expect(sent[1]).toEqual({ body: { itemId: 'i8', paperId: 'p2', agree: false }, ifMatch: '"8"' })
    // The list is empty now; the answer stays said in the panel.
    expect(
      await within(screen.getByRole('region', { name: 'Late papers for you to decide' })).findByText(
        'A restricted paper, for 8 Restricted item is not for this meeting.',
      ),
    ).toHaveAttribute('role', 'status')
    expect(screen.queryByRole('button', { name: /^Agree to include/ })).toBeNull()
  })

  it('says each refusal in words and reads the list again', async () => {
    const user = userEvent.setup()
    const answers = [
      problem(403, { reason: 'RECUSED' }),
      problem(403),
      problem(412),
      problem(409, { reason: 'WRONG_STATE' }),
      problem(409, { reason: 'FROZEN' }),
      problem(429, { type: 'urn:memo:problem:rate-limited' }),
    ]
    setup({ requests: [valuation], answer: () => answers.shift() })
    renderRoute('/meetings/m1')
    const late = await panel()
    const words = [
      'You have declared an interest in this item, so you cannot decide it. A secretary records the committee’s answer.',
      'Only the chair decides this, or the vice-chair when the chair has declared an interest in the item.',
      'The agenda changed just now. The list below is up to date again: please decide once more.',
      'This is no longer waiting for a decision.',
      'The meeting has been held or cancelled: its agenda no longer changes.',
      'Too many changes at once. Wait a minute and try again.',
    ]
    for (const text of words) {
      await user.click(await within(late).findByRole('button', { name: /^Agree to include/ }))
      expect(await within(late).findByRole('alert')).toHaveTextContent(text)
    }
  })

  it('shows nothing when there is nothing to decide', async () => {
    setup({ requests: [] })
    renderRoute('/meetings/m1')
    expect(await screen.findByRole('heading', { name: /BRC 3\/2026/ })).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.queryByRole('region', { name: 'Late papers for you to decide' })).toBeNull()
    })
  })

  it('reads right to left in Arabic without accessibility violations', async () => {
    setup()
    const { container } = renderRoute('/meetings/m1', { locale: 'ar' })
    const late = await screen.findByRole('region', { name: 'أوراق متأخرة بانتظار قرارك' })
    expect(late).toHaveTextContent('ورقة مقيّدة')
    expect(document.documentElement).toHaveAttribute('dir', 'rtl')
    await expectNoA11yViolations(container)
  })
})
