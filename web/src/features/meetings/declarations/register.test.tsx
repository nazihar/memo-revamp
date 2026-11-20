import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  DeclarationRegisterResponse,
  DeclarationRegisterRowResponse,
  ForumResponse,
  MemberResponse,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const API = `${API_ORIGIN}/api/v1`

const forum = (id: string, name: string, over: Partial<ForumResponse> = {}): ForumResponse => ({
  id,
  key: id,
  code: id.toUpperCase(),
  codeLocked: true,
  name,
  shortName: name,
  canManage: false,
  canManageMembers: false,
  memberCount: 7,
  secretaries: [],
  status: 'ACTIVE',
  termWarnings: 0,
  typeId: 't1',
  typeName: 'Board committee',
  version: 0,
  ...over,
})

const forums = [forum('f1', 'Board Risk Committee'), forum('f2', 'Asset Liability Committee')]

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

const row = (over: Partial<DeclarationRegisterRowResponse> & Pick<DeclarationRegisterRowResponse, 'id'>) =>
  ({
    meetingId: 'm1',
    meetingLabel: 'BRC 3/2026',
    meetingDate: '2026-12-12',
    meetingStatus: 'SCHEDULED',
    forumId: 'f1',
    committee: 'Board Risk Committee',
    itemId: 'i5',
    itemNumber: '5',
    itemTitle: 'Term loan, Delta Textiles',
    user: { id: 'u2', name: 'Demo Director' },
    nature: 'Brother-in-law is a director of Delta Textiles.',
    intent: 'LEAVE',
    source: 'ON_BEHALF',
    how: 'EMAIL',
    recordedBy: { id: 'u9', name: 'Company Secretary' },
    declaredAt: '2026-12-09T08:10:00Z',
    atMeeting: false,
    correction: false,
    state: 'RECUSED',
    ...over,
  }) satisfies DeclarationRegisterRowResponse

const first: DeclarationRegisterResponse = {
  items: [
    row({ id: 'd1' }),
    row({
      id: 'd2',
      itemId: 'i7',
      itemNumber: '7',
      itemTitle: 'Liquidity stress results',
      user: { id: 'u3', name: 'R. Khan' },
      nature: 'Holds shares in a bank in the stress set.',
      source: 'SELF',
      how: 'IN_APP',
      intent: 'ASK_CHAIR',
      state: 'TAKING_PART',
      ruling: {
        by: { id: 'u1', name: 'S. Ali' },
        onBehalfBy: { id: 'u9', name: 'Company Secretary' },
        how: 'PHONE',
        outcome: 'TAKE_PART',
        note: 'Shareholding below 0.1%.',
        at: '2026-12-10T05:00:00Z',
      },
    }),
  ],
  nextCursor: 'c2',
}

const second: DeclarationRegisterResponse = {
  items: [
    row({
      id: 'd3',
      meetingId: 'm2',
      meetingLabel: 'ALCO 9/2026',
      meetingDate: '2026-10-03',
      forumId: 'f2',
      committee: 'Asset Liability Committee',
      itemId: 'i4',
      itemNumber: '4',
      itemTitle: null,
      nature: null,
      user: { id: 'u8', name: 'T. Islam' },
      source: 'MEMO',
      how: 'SYSTEM',
    }),
  ],
  nextCursor: null,
}

interface State {
  reads: URLSearchParams[]
  exports: URLSearchParams[]
}

function setup(options: { permissions?: string[]; refuse?: number; exportStatus?: number[] } = {}): State {
  const state: State = { reads: [], exports: [] }
  const statuses = [...(options.exportStatus ?? [200])]
  server.use(
    sessionHandler(signedInSession()),
    accessHandler(options.permissions ?? ['forum.read']),
    csrfHandler(),
    http.get(`${API}/forums`, () => HttpResponse.json<ForumResponse[]>(forums)),
    http.get(`${API}/forums/f1/members`, () => HttpResponse.json<MemberResponse[]>(members)),
    http.get(`${API}/declarations`, ({ request }) => {
      const params = new URL(request.url).searchParams
      state.reads.push(params)
      if (options.refuse) {
        return HttpResponse.json({ status: options.refuse, title: 'Refused' }, { status: options.refuse })
      }
      return HttpResponse.json<DeclarationRegisterResponse>(params.get('cursor') === 'c2' ? second : first)
    }),
    http.get(`${API}/declarations/export`, ({ request }) => {
      state.exports.push(new URL(request.url).searchParams)
      const status = statuses.shift() ?? 200
      if (status === 429) {
        return HttpResponse.json(
          { type: 'urn:memo:problem:rate-limited', status: 429, title: 'Too many requests' },
          { status: 429, headers: { 'Content-Type': 'application/problem+json' } },
        )
      }
      return new HttpResponse('xlsx', {
        headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
      })
    }),
  )
  return state
}

const created = vi.fn(() => 'blob:declarations')

beforeEach(() => {
  Object.defineProperty(URL, 'createObjectURL', { value: created, configurable: true })
  Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true })
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  created.mockClear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('the register of declarations (Phase 4 E04 T8, flow U)', () => {
  it('lists declarations across committees newest first, with restricted rows, details and keyset paging', async () => {
    const user = userEvent.setup()
    const state = setup()
    const { container } = renderRoute('/declarations')

    expect(partsForPath('/declarations')).toEqual(['declarations'])
    expect(await screen.findByRole('heading', { name: 'Register of declarations of interest' })).toBeInTheDocument()
    // In the menu for oversight access.
    expect(
      (await screen.findAllByRole('link', { name: 'Declarations of interest' })).some(
        (l) => l.getAttribute('href') === '/declarations',
      ),
    ).toBe(true)
    const table = await screen.findByRole('table', { name: 'Declarations of interest, newest first' })
    expect(await within(table).findByText('Brother-in-law is a director of Delta Textiles.')).toBeInTheDocument()
    const rows = within(table).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('BRC 3/2026')
    expect(rows[1]).toHaveTextContent('5 Term loan, Delta Textiles')
    expect(rows[1]).toHaveTextContent(/Demo DirectorBy email, recorded by Company Secretary/)
    expect(rows[1]).toHaveTextContent('Leaving the room')
    expect(rows[2]).toHaveTextContent('Taking part')
    expect(rows[2]).toHaveTextContent(
      /S\. Ali decided: may take part, .*; recorded by Company Secretary \(by phone\)\./,
    )
    expect(state.reads[0]?.toString()).toBe('')

    // A row's details: 44 px, then how it was made and decided.
    const stress = rows[2]
    if (!stress) throw new Error('no second row')
    const details = within(stress).getByText('Details')
    expect(details.className).toMatch(/min-h-11/)
    await user.click(details)
    expect(rows[2]).toHaveTextContent('Note: “Shareholding below 0.1%.”')
    await expectNoA11yViolations(container)

    // Load more: the next page by its cursor.
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    expect(await within(table).findByText('ALCO 9/2026')).toBeInTheDocument()
    expect(state.reads.at(-1)?.get('cursor')).toBe('c2')
    const restricted = within(table).getAllByRole('row').at(-1)
    if (!restricted) throw new Error('no last row')
    expect(restricted).toHaveTextContent('4 Restricted item')
    expect(restricted).toHaveTextContent('(restricted item)')
    expect(restricted).toHaveTextContent('Carried over from the memo')
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull()
  }, 30_000)

  it('filters by committee, member, dates and state, and exports the rows under the filters', async () => {
    const user = userEvent.setup()
    const state = setup({ exportStatus: [200, 429] })
    renderRoute('/declarations?forumId=f1')

    const committee = await screen.findByRole('combobox', { name: 'Committee' })
    await waitFor(() => {
      expect(committee).toHaveValue('f1')
    })
    await waitFor(() => {
      expect(state.reads.at(-1)?.get('forumId')).toBe('f1')
    })
    const member = screen.getByRole('combobox', { name: 'Member' })
    await waitFor(() => {
      expect(
        within(member)
          .getAllByRole('option')
          .map((o) => o.textContent),
      ).toEqual(['Any', 'Demo Director'])
    })
    await user.selectOptions(member, 'u2')
    await user.selectOptions(screen.getByRole('combobox', { name: 'State' }), 'AWAITING_RULING')
    await user.type(screen.getByLabelText('From'), '2026-01-01')
    await user.type(screen.getByLabelText('To'), '2026-12-31')
    await waitFor(() => {
      expect(Object.fromEntries(state.reads.at(-1) ?? [])).toEqual({
        forumId: 'f1',
        userId: 'u2',
        state: 'AWAITING_RULING',
        from: '2026-01-01',
        to: '2026-12-31',
      })
    })

    await user.click(screen.getByRole('button', { name: 'Export to Excel' }))
    expect(await screen.findByText('The workbook has been downloaded.')).toBeInTheDocument()
    expect(created).toHaveBeenCalledTimes(1)
    expect(Object.fromEntries(state.exports[0] ?? [])).toEqual({
      forumId: 'f1',
      userId: 'u2',
      state: 'AWAITING_RULING',
      from: '2026-01-01',
      to: '2026-12-31',
    })
    // Rate-limited: said in words.
    await user.click(screen.getByRole('button', { name: 'Export to Excel' }))
    expect(
      await screen.findByText('You have exported this register several times in the last hour. Try again later.'),
    ).toBeInTheDocument()

    // Choosing all committees again clears the member.
    await user.selectOptions(screen.getByRole('combobox', { name: 'Committee' }), '')
    expect(screen.getByRole('combobox', { name: 'Member' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Member' })).toHaveAccessibleDescription(
      'Choose a committee to pick one of its members.',
    )
  }, 30_000)

  it('is for oversight and officers only: others read why, and cannot export', async () => {
    setup({ permissions: [], refuse: 403 })
    renderRoute('/declarations')
    expect(
      await screen.findByText(
        'This register is for auditors and administrators, and for the secretaries and chairs of each committee.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Export to Excel' })).toBeDisabled()
    // Not in the menu without oversight access.
    expect(
      screen
        .queryAllByRole('link', { name: 'Declarations of interest' })
        .filter((l) => l.getAttribute('href') === '/declarations'),
    ).toEqual([])
    expect(screen.queryByRole('table')).toBeNull()
  }, 30_000)

  it('reads right to left in Arabic without accessibility violations', async () => {
    setup()
    const { container } = renderRoute('/declarations', { locale: 'ar' })
    expect(await screen.findByRole('heading', { name: 'سجل الإفصاحات عن المصالح' })).toBeInTheDocument()
    expect(document.documentElement).toHaveAttribute('dir', 'rtl')
    const table = await screen.findByRole('table', { name: 'الإفصاحات عن المصالح، الأحدث أولًا' })
    expect((await within(table).findAllByText('سيغادر القاعة')).length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: 'تصدير إلى Excel' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 30_000)
})
