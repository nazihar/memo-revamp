import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createIntl } from 'react-intl'
import { describe, expect, it } from 'vitest'
import type {
  AgendaDraftResponse,
  AgendaItemResponse,
  DeclarationResponse,
  MeetingDeclarationRegisterResponse,
  MeetingResponse,
  MemberAgendaResponse,
  MemberResponse,
} from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'
import { agendaRefusal } from './problems'

const API = `${API_ORIGIN}/api/v1`

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
  classificationId: 'l1',
  number: 3,
  label: 'BRC 3/2026',
  status: 'SCHEDULED',
  relation: 'OFFICER',
  canManage: true,
  guests: [],
  version: 3,
}

const item = (over: Partial<AgendaItemResponse> & Pick<AgendaItemResponse, 'id' | 'title'>): AgendaItemResponse => ({
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
})

const mine: DeclarationResponse = {
  id: 'd1',
  meetingId: 'm1',
  itemId: 'i6',
  itemNumber: '6',
  user: { id: 'u9', name: 'Company Secretary' },
  nature: 'My spouse works for the counterparty.',
  intent: 'LEAVE',
  source: 'SELF',
  how: 'IN_APP',
  declaredAt: '2026-12-09T08:10:00Z',
  atMeeting: false,
  correction: false,
  state: 'RECUSED',
  version: 1,
}

/** The draft as a secretary recused from item 6, whose title the item hides, reads it (E04 T2 as built). */
const draft: AgendaDraftResponse = {
  meetingId: 'm1',
  started: true,
  currentRevision: 1,
  dirty: false,
  changes: 'ANY',
  canEdit: true,
  canAgree: false,
  chairAgreementRequired: false,
  chairComments: [],
  startsAt: meeting.startsAt,
  endsAt: meeting.endsAt,
  timeZone: 'Asia/Dhaka',
  deadlinePassed: false,
  lateMode: 'ALLOW',
  totalMinutes: 50,
  plannedEndsAt: '2026-12-12T04:50:00Z',
  overrun: false,
  traySize: 0,
  state: 'PUBLISHED',
  revisions: [],
  version: 4,
  sections: [
    {
      id: 's1',
      title: 'For decision',
      kind: 'STANDARD',
      membersOnly: false,
      receivesSubmissions: true,
      minutes: 50,
      items: [
        item({ id: 'i5', title: 'Term loan, Delta Textiles', number: '5', minutes: 30 }),
        item({
          id: 'i6',
          title: '',
          titleHidden: true,
          number: '6',
          minutes: 20,
          recused: true,
          myDeclaration: mine,
          localStartsAt: '2026-12-12T10:30:00',
        }),
      ],
    },
  ],
}

const register: MeetingDeclarationRegisterResponse = {
  meetingId: 'm1',
  latestRevision: 1,
  canRemind: true,
  reviews: [],
  items: [
    {
      itemId: 'i5',
      number: '5',
      title: 'Term loan, Delta Textiles',
      recused: false,
      declarations: [],
      alsoReadsMemo: [],
      quorumWarning: {
        declared: 4,
        basis: 7,
        needed: 4,
        available: 3,
        recusedCount: false,
        unmet: [{ kind: 'COUNT' }],
      },
    },
    { itemId: 'i6', number: '6', title: null, recused: true, declarations: [mine], alsoReadsMemo: [] },
  ],
}

const members: MemberResponse[] = [
  {
    id: 's1',
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
]

/** The preview as Demo Director: item 6 is the secretary's own recused line (E04 §1.7). */
const preview: MemberAgendaResponse = {
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
      id: 's1',
      title: 'For decision',
      kind: 'STANDARD',
      membersOnly: false,
      items: [
        {
          id: 'i5',
          access: 'FULL',
          number: '5',
          title: 'Term loan, Delta Textiles',
          kind: 'ITEM',
          late: 'NONE',
          changes: [],
          papers: [],
          presenters: [],
          subItems: [],
          links: [],
        },
        {
          id: 'i6',
          access: 'HIDDEN',
          number: '6',
          title: null,
          kind: 'ITEM',
          late: 'NONE',
          changes: [],
          papers: [],
          presenters: [],
          subItems: [],
          links: [],
          recused: true,
        },
      ],
    },
  ],
}

function setup() {
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    http.get(`${API}/meetings/m1`, () => HttpResponse.json<MeetingResponse>(meeting)),
    http.get(`${API}/meetings/m1/agenda/draft`, () => HttpResponse.json<AgendaDraftResponse>(draft)),
    http.get(`${API}/meetings/m1/declarations`, () => HttpResponse.json<MeetingDeclarationRegisterResponse>(register)),
    http.get(`${API}/meetings/m1/agenda/preview`, () => HttpResponse.json<MemberAgendaResponse>(preview)),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([])),
    http.get(`${API}/forums/f1/members`, () => HttpResponse.json<MemberResponse[]>(members)),
    http.get(`${API}/forums/f1/submissions/ready`, () => HttpResponse.json([])),
  )
}

describe('the builder for a recused secretary (Phase 4 E04 T7, flow T4)', () => {
  it('shows the recused row as its line, with no menu or handle, and warns of an item that cannot be quorate', async () => {
    setup()
    const { container } = renderRoute('/meetings/m1/agenda')

    // The hidden title reads "Restricted item", not an empty line.
    const row = (await screen.findByText('Restricted item')).closest('li')
    if (!row) throw new Error('no row')
    expect(row).toHaveTextContent('6')
    expect(row).toHaveTextContent('You declared an interest. Another secretary or an administrator handles this item.')
    expect(within(row).queryByRole('button')).toBeNull()
    expect(screen.queryByRole('button', { name: /Restricted item/ })).toBeNull()
    // The other rows keep their menu and handle.
    expect(screen.getByRole('button', { name: 'Actions for Term loan, Delta Textiles' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Drag to move: Term loan, Delta Textiles' })).toBeInTheDocument()

    // The quorum warning on the row (E04 §1.8).
    const loan = screen.getByText('Term loan, Delta Textiles').closest('li')
    expect(
      await within(loan as HTMLElement).findByText(
        'Even if every member attends, item 5 cannot be decided: 4 of 7 members have declared an interest and the committee needs 4 (members with an interest do not count).',
      ),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 30_000)

  it('says in the preview that the items the officer declared an interest in are hidden', async () => {
    const user = userEvent.setup()
    setup()
    renderRoute('/meetings/m1/agenda')
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Preview as' }), 'u2')
    await user.click(screen.getByRole('button', { name: 'Preview' }))
    const dialog = await screen.findByRole('dialog', { name: 'Preview as Demo Director' })
    expect(dialog).toHaveTextContent('Items you declared an interest in are hidden in this preview.')
    expect(
      await within(dialog).findByText('6 Restricted item — an interest is declared, papers withheld'),
    ).toBeInTheDocument()
  }, 30_000)

  it('says a refused change of a recused item in words', () => {
    const intl = createIntl({ locale: 'en' })
    expect(agendaRefusal(intl, {}, { status: 403, reason: 'RECUSED' })).toBe(
      'You declared an interest in this item, so another secretary or an administrator handles it.',
    )
  })
})
