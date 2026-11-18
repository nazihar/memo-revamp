import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type {
  ChangeRoleRequest,
  CloneRequest,
  DissolveRequest,
  EndMemberRequest,
  ForumPolicyResponse,
  ForumResponse,
  ForumTypeResponse,
  MemberChangeResponse,
  MemberRequest,
  MemberResponse,
  MyForumResponse,
  RenewRequest,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
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
import { termEnd, today } from './shared'

const API = `${API_ORIGIN}/api/v1`
const ME = signedInSession(true).user?.id ?? ''

const brc: ForumResponse = {
  id: 'f1',
  key: 'board-risk',
  code: 'BRC',
  codeLocked: false,
  name: 'Board Risk Committee',
  shortName: 'Risk',
  typeId: 't1',
  typeName: 'Board committee',
  parentId: 'f0',
  parentName: 'Board of Directors',
  status: 'ACTIVE',
  chair: { id: 'u1', name: 'S. Ali' },
  secretaries: [{ id: 'u9', name: 'Company Secretary' }],
  memberCount: 4,
  termWarnings: 1,
  canManageMembers: true,
  canManage: true,
  version: 3,
}

const board: ForumResponse = {
  ...brc,
  id: 'f0',
  key: 'board',
  code: 'BOD',
  name: 'Board of Directors',
  typeId: 't0',
  typeName: 'Board',
  parentId: null,
  parentName: null,
  chair: { id: 'u2', name: 'R. Khan' },
  memberCount: 9,
  termWarnings: 0,
}

const row = (m: Partial<MemberResponse> & Pick<MemberResponse, 'id' | 'role'>): MemberResponse => ({
  kind: 'PERSON',
  voting: true,
  attributes: [],
  startsOn: '2025-07-01',
  termNumber: 1,
  state: 'IN_OFFICE',
  version: 1,
  ...m,
})

const members: MemberResponse[] = [
  row({
    id: 'm1',
    role: 'CHAIR',
    person: { id: 'u1', name: 'S. Ali' },
    attributes: ['independent'],
    termNumber: 2,
    termEndsOn: '2028-06-30',
  }),
  row({
    id: 'm2',
    role: 'MEMBER',
    person: { id: 'u3', name: 'Demo Director' },
    attributes: ['independent'],
    alternate: { id: 'u4', name: 'F. Rahman' },
    termEndsOn: '2026-12-31',
    termEndsInDays: 43,
    version: 4,
  }),
  row({
    id: 'm3',
    role: 'MEMBER',
    kind: 'POSITION',
    positionId: 'p1',
    positionTitle: 'Chief Risk Officer',
    person: { id: 'u5', name: 'N. Hossain' },
  }),
  row({ id: 'm4', role: 'SECRETARY', voting: false, person: { id: 'u9', name: 'Company Secretary' } }),
]

const history: MemberResponse[] = [
  members[0]!,
  row({
    id: 'm0',
    role: 'MEMBER',
    person: { id: 'u1', name: 'S. Ali' },
    startsOn: '2022-07-01',
    termEndsOn: '2025-06-30',
    endedOn: '2025-06-30',
    endReason: 'RENEWED',
    state: 'ENDED',
    authority: 'Board resolution of 12 June 2022',
  }),
]

const policy: ForumPolicyResponse = {
  typeId: 't1',
  typeName: 'Board committee',
  version: 2,
  memberAttributes: [
    { key: 'independent', label: 'Independent' },
    { key: 'nonExecutive', label: 'Non-executive' },
  ],
  quorum: {
    counts: 'VOTING',
    basis: 'IN_OFFICE',
    threshold: { kind: 'MAJORITY' },
    minimum: 2,
    required: [['CHAIR', 'VICE_CHAIR']],
    composition: [{ attribute: 'independent', majority: true }],
    alternatesCount: true,
    remoteCounts: true,
    recusedCount: false,
  },
  outcomes: [
    { kind: 'APPROVED', label: 'Approved', resolution: true },
    { kind: 'NOTED', label: 'Noted', resolution: false },
  ],
  termPolicy: { termMonths: 36, maxTerms: 2, reminderDays: [42, 1] },
  submissionPolicy: { deadlineWorkingDays: 5, lateMode: 'CHAIR_APPROVAL', triageWorkingDays: 5 },
  quorumBasis: 3,
  quorumNeeded: 2,
}

interface Seen {
  memberQueries: URLSearchParams[]
  policyQueries: URLSearchParams[]
}

function handlers(
  forum: ForumResponse = brc,
  permissions: string[] = ['forums.manage', 'forum.types.manage'],
  seen: Seen = { memberQueries: [], policyQueries: [] },
) {
  return [
    sessionHandler(signedInSession(true)),
    accessHandler(permissions),
    csrfHandler(),
    http.get(`${API}/forums`, () => HttpResponse.json<ForumResponse[]>([board, forum])),
    http.get(`${API}/forums/:id`, () => HttpResponse.json<ForumResponse>(forum)),
    http.get(`${API}/forums/:id/members`, ({ request }) => {
      const query = new URL(request.url).searchParams
      seen.memberQueries.push(query)
      return HttpResponse.json<MemberResponse[]>(query.get('history') === 'true' ? history : members)
    }),
    http.get(`${API}/forums/:id/policy`, ({ request }) => {
      seen.policyQueries.push(new URL(request.url).searchParams)
      return HttpResponse.json<ForumPolicyResponse>(policy)
    }),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([{ id: 'l0', name: 'Internal', rank: 0 }])),
    http.get(`${API}/admin/forum-types`, () =>
      HttpResponse.json<ForumTypeResponse[]>([
        { id: 't1', code: 'BC', name: 'Board committee', active: true, publishedVersion: 2 },
      ]),
    ),
  ]
}

/** Opens the row menu of [name] on the visible table and picks [action]. */
async function rowAction(user: ReturnType<typeof userEvent.setup>, name: string, action: string) {
  await user.click(await screen.findByRole('button', { name: `Actions for ${name}` }, { timeout: 5000 }))
  await user.click(await screen.findByRole('menuitem', { name: action }, { timeout: 5000 }))
}

const change = (m: MemberResponse): MemberChangeResponse => ({ member: m, beyondMaxTerms: false })

describe('committees (Phase 4 E01 T5, flow K)', () => {
  it('lists the committees with their type, chair, members and terms needing attention', async () => {
    server.use(...handlers())
    const { container } = renderRoute('/committees')

    expect(await screen.findByRole('heading', { level: 1, name: 'Committees' }, { timeout: 5000 })).toBeInTheDocument()
    const cells = within(
      (await screen.findByRole('link', { name: 'Board Risk Committee' })).closest('tr') as HTMLElement,
    )
    expect(cells.getByText('BRC')).toBeInTheDocument()
    expect(cells.getByText('Board committee')).toBeInTheDocument()
    expect(cells.getByText('S. Ali')).toBeInTheDocument()
    expect(cells.getByText('1 term ends soon or has ended')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New committee' })).toBeInTheDocument()
    expect(partsForPath('/committees/f1')).toEqual(expect.arrayContaining(['forums', 'forumAdmin']))
    expect(partsForPath('/me/committees')).toContain('forums')
    await expectNoA11yViolations(container)
  })

  it('creates a committee with a key suggested from its name', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    const keys: (string | null)[] = []
    server.use(
      ...handlers(),
      http.post(`${API}/forums`, async ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        bodies.push(await request.json())
        return HttpResponse.json<ForumResponse>({ ...brc, id: 'f9' }, { status: 201 })
      }),
    )
    const { router } = renderRoute('/committees')

    await user.click(await screen.findByRole('button', { name: 'New committee' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'New committee' })
    await user.selectOptions(await within(dialog).findByRole('combobox', { name: /Type/ }), 'Board committee')
    await user.type(within(dialog).getByRole('textbox', { name: /^Name/ }), 'Board Audit Committee')
    await user.type(within(dialog).getByRole('textbox', { name: /Short name/ }), 'Audit')
    await user.type(within(dialog).getByRole('textbox', { name: /^Code/ }), 'bac')
    expect(within(dialog).getByRole('textbox', { name: /Workflow key/ })).toHaveValue('board-audit-committee')
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))

    await vi.waitFor(() => {
      expect(router.state.location.pathname).toBe('/committees/f9')
    })
    expect(bodies).toEqual([
      {
        typeId: 't1',
        name: 'Board Audit Committee',
        shortName: 'Audit',
        code: 'BAC',
        key: 'board-audit-committee',
      },
    ])
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
  }, 20_000)

  it('shows the members on a chosen date with the quorum that day and the rule in words', async () => {
    const user = userEvent.setup()
    const seen: Seen = { memberQueries: [], policyQueries: [] }
    server.use(...handlers(brc, ['forums.manage'], seen))
    renderRoute('/committees/f1')

    const table = await screen.findByRole('table', { name: /Members on/ }, { timeout: 5000 })
    const director = within((await within(table).findByText('Demo Director')).closest('tr') as HTMLElement)
    expect(director.getByText('Independent')).toBeInTheDocument()
    expect(director.getByText('F. Rahman')).toBeInTheDocument()
    expect(director.getByText(/1st term, to/)).toBeInTheDocument()
    expect(director.getByText('Ends in 43 days')).toBeInTheDocument()
    const seat = within(within(table).getByText('Chief Risk Officer').closest('tr') as HTMLElement)
    expect(seat.getByText('Now: N. Hossain')).toBeInTheDocument()
    expect(seat.getByText('Ex officio')).toBeInTheDocument()
    expect(screen.getByText('Quorum today: 2 of 3 voting members')).toBeInTheDocument()
    expect(
      screen.getByText(
        'More than half of the voting members in office, and never fewer than 2, including the chair or a vice-chair, with a majority of them independent.',
      ),
    ).toBeInTheDocument()
    expect(seen.memberQueries.at(-1)?.get('on')).toBe(today())

    const date = screen.getByLabelText('Members on')
    await user.clear(date)
    await user.type(date, '2027-03-01')
    expect(await screen.findByText('Quorum on Mar 1, 2027: 2 of 3 voting members')).toBeInTheDocument()
    expect(seen.memberQueries.at(-1)?.get('on')).toBe('2027-03-01')
    expect(seen.policyQueries.at(-1)?.get('on')).toBe('2027-03-01')
  }, 20_000)

  it('keeps every term in History and shows the type’s policy to read', async () => {
    const user = userEvent.setup()
    server.use(...handlers({ ...brc, canManage: false, canManageMembers: false }, []))
    renderRoute('/committees/f1')

    await user.click(await screen.findByRole('tab', { name: 'History' }, { timeout: 5000 }))
    const table = await screen.findByRole('table', { name: 'Every term of office of Board Risk Committee' })
    expect(await within(table).findByText('Ended: Renewed')).toBeInTheDocument()
    expect(within(table).getByText('Board resolution of 12 June 2022')).toBeInTheDocument()
    expect(screen.getByText(/ending a membership keeps its row\. Nothing is deleted\./)).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Policy' }))
    expect(await screen.findByText(/^The policy of the type “Board committee”, version 2/)).toBeInTheDocument()
    expect(screen.getByText(/^More than half of the voting members in office/)).toBeInTheDocument()
    expect(screen.getByText('A term usually lasts 36 months.')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Open the committee type' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add member' })).not.toBeInTheDocument()
  }, 20_000)

  it('adds a person with an alternate, a term from the type and an authority', async () => {
    const user = userEvent.setup()
    const bodies: MemberRequest[] = []
    const keys: (string | null)[] = []
    server.use(
      ...handlers(),
      http.get(`${API}/directory/search`, ({ request }) => {
        const q = new URL(request.url).searchParams.get('q') ?? ''
        const person = q.startsWith('Fa')
          ? { id: 'u7', displayName: 'Farah Begum', email: 'farah@bank.example' }
          : { id: 'u8', displayName: 'Omar Siddiq', email: 'omar@bank.example' }
        return HttpResponse.json({ users: [{ person }], positions: [], units: [] })
      }),
      http.post(`${API}/forums/f1/members`, async ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        bodies.push((await request.json()) as MemberRequest)
        return HttpResponse.json<MemberChangeResponse>(change(members[1]!), { status: 201 })
      }),
    )
    renderRoute('/committees/f1')

    await user.click(await screen.findByRole('button', { name: 'Add member' }, { timeout: 5000 }))
    const sheet = await screen.findByRole('dialog', { name: 'Add a member to Board Risk Committee' })
    expect(within(sheet).getByRole('combobox', { name: 'Role' })).toHaveValue('MEMBER')
    expect(within(sheet).queryByText('Only an administrator can appoint a chair or a secretary.')).toBeNull()
    await user.type(within(sheet).getByRole('combobox', { name: 'Person' }), 'Fa')
    await user.click(await within(sheet).findByRole('option', { name: /Farah Begum/ }))
    await user.click(within(sheet).getByRole('checkbox', { name: 'Independent' }))
    await user.type(within(sheet).getByRole('combobox', { name: /Alternate/ }), 'Om')
    await user.click(await within(sheet).findByRole('option', { name: /Omar Siddiq/ }))
    expect(within(sheet).getByText('36 months, as the type says.')).toBeInTheDocument()
    await user.type(within(sheet).getByRole('textbox', { name: /Authority/ }), 'Board resolution of 12 Oct 2026')
    await user.click(within(sheet).getByRole('button', { name: 'Add member' }))

    await vi.waitFor(() => {
      expect(bodies).toHaveLength(1)
    })
    expect(bodies[0]).toEqual({
      userId: 'u7',
      role: 'MEMBER',
      startsOn: today(),
      voting: true,
      attributes: ['independent'],
      alternateUserId: 'u8',
      termEndsOn: termEnd(today(), 36),
      authority: 'Board resolution of 12 Oct 2026',
    })
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
  }, 30_000)

  it('a secretary is not offered Chair or Secretary, is told why, and has no menu on chairs, secretaries or their own row', async () => {
    const user = userEvent.setup()
    const asSecretary = { ...brc, canManage: false, myRole: 'SECRETARY' as const }
    server.use(
      ...handlers(asSecretary, []),
      http.get(`${API}/forums/:id/members`, () =>
        HttpResponse.json<MemberResponse[]>([
          ...members.slice(0, 3),
          row({ id: 'm4', role: 'SECRETARY', voting: false, person: { id: ME, name: 'Nadia Karim' } }),
        ]),
      ),
    )
    renderRoute('/committees/f1')

    expect(
      await screen.findByRole('button', { name: 'Actions for Demo Director' }, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Actions for S. Ali' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Actions for Nadia Karim' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'More actions' })).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Add member' }))
    const sheet = await screen.findByRole('dialog', { name: 'Add a member to Board Risk Committee' })
    const roles = within(within(sheet).getByRole('combobox', { name: 'Role' }))
      .getAllByRole('option')
      .map((o) => o.textContent)
    expect(roles).toEqual(['Vice-chair', 'Member', 'Standing invitee', 'Observer'])
    expect(within(sheet).getByText('Only an administrator can appoint a chair or a secretary.')).toBeInTheDocument()
  }, 20_000)

  it('renews a term from the day after it ends and warns past the most consecutive terms', async () => {
    const user = userEvent.setup()
    const bodies: RenewRequest[] = []
    server.use(
      ...handlers(),
      http.post(`${API}/forums/f1/members/m1/renew`, async ({ request }) => {
        bodies.push((await request.json()) as RenewRequest)
        return HttpResponse.json<MemberChangeResponse>({ member: members[0]!, beyondMaxTerms: true })
      }),
    )
    renderRoute('/committees/f1')

    await rowAction(user, 'S. Ali', 'Renew term')
    const dialog = await screen.findByRole('dialog', { name: 'Renew the term of S. Ali' })
    expect(within(dialog).getByText('Term 3 starts on Jul 1, 2028.')).toBeInTheDocument()
    expect(within(dialog).getByRole('note')).toHaveTextContent('This will be term 3. The type allows at most 2')
    expect(within(dialog).getByLabelText(/New term ends/)).toHaveValue('2031-06-30')
    await user.click(within(dialog).getByRole('button', { name: 'Renew' }))

    await vi.waitFor(() => {
      expect(bodies).toEqual([{ termEndsOn: '2031-06-30' }])
    })
  }, 20_000)

  it('changes a role from a date, and ends a membership behind its version with a reason', async () => {
    const user = userEvent.setup()
    const roleBodies: ChangeRoleRequest[] = []
    const endBodies: EndMemberRequest[] = []
    const matches: (string | null)[] = []
    server.use(
      ...handlers(),
      http.post(`${API}/forums/f1/members/m2/change-role`, async ({ request }) => {
        roleBodies.push((await request.json()) as ChangeRoleRequest)
        return HttpResponse.json<MemberChangeResponse>(change(members[1]!))
      }),
      http.post(`${API}/forums/f1/members/m2/end`, async ({ request }) => {
        matches.push(request.headers.get('If-Match'))
        endBodies.push((await request.json()) as EndMemberRequest)
        return HttpResponse.json<MemberChangeResponse>(change(members[1]!))
      }),
    )
    renderRoute('/committees/f1')

    await rowAction(user, 'Demo Director', 'Change role')
    const roleDialog = await screen.findByRole('dialog', { name: 'Change the role of Demo Director' })
    await user.selectOptions(within(roleDialog).getByRole('combobox', { name: 'New role' }), 'Vice-chair')
    const from = within(roleDialog).getByLabelText('First day in the new role')
    await user.clear(from)
    await user.type(from, '2027-01-01')
    await user.click(within(roleDialog).getByRole('button', { name: 'Change role' }))
    await vi.waitFor(() => {
      expect(roleBodies).toEqual([{ role: 'VICE_CHAIR', voting: true, from: '2027-01-01' }])
    })
    await vi.waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull()
    })

    await rowAction(user, 'Demo Director', 'End membership')
    const endDialog = await screen.findByRole('dialog', { name: 'End the membership of Demo Director' })
    await user.click(within(endDialog).getByRole('radio', { name: 'Other' }))
    await user.click(within(endDialog).getByRole('button', { name: 'End membership' }))
    expect(within(endDialog).getByText('Required.')).toBeInTheDocument()
    await user.type(within(endDialog).getByRole('textbox', { name: /Details/ }), 'Left the bank')
    await user.click(within(endDialog).getByRole('button', { name: 'End membership' }))
    await vi.waitFor(() => {
      expect(endBodies).toEqual([{ reason: 'OTHER', endedOn: today(), note: 'Left the bank' }])
    })
    expect(matches).toEqual(['"4"'])
  }, 30_000)

  it('says why a change was refused, in words, inside the form', async () => {
    const user = userEvent.setup()
    server.use(
      ...handlers(),
      http.post(`${API}/forums/f1/members/m2/change-role`, () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Conflict', status: 409, reason: 'ONE_CHAIR' },
          { status: 409 },
        ),
      ),
    )
    renderRoute('/committees/f1')

    await rowAction(user, 'Demo Director', 'Change role')
    const dialog = await screen.findByRole('dialog', { name: 'Change the role of Demo Director' })
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'New role' }), 'Chair')
    await user.click(within(dialog).getByRole('button', { name: 'Change role' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'The committee already has a chair for part of that time.',
    )
  }, 20_000)

  it('clones a committee with its current members and opens the copy', async () => {
    const user = userEvent.setup()
    const bodies: CloneRequest[] = []
    server.use(
      ...handlers(),
      http.post(`${API}/forums/f1/clone`, async ({ request }) => {
        bodies.push((await request.json()) as CloneRequest)
        return HttpResponse.json<ForumResponse>({ ...brc, id: 'f8' }, { status: 201 })
      }),
    )
    const { router } = renderRoute('/committees/f1')

    await user.click(await screen.findByRole('button', { name: 'More actions' }, { timeout: 5000 }))
    await user.click(await screen.findByRole('menuitem', { name: 'Clone' }))
    const dialog = await screen.findByRole('dialog', { name: 'Clone Board Risk Committee' })
    await user.type(within(dialog).getByRole('textbox', { name: /^Name/ }), 'Board Risk Committee 2027')
    await user.type(within(dialog).getByRole('textbox', { name: /Short name/ }), 'Risk 27')
    await user.type(within(dialog).getByRole('textbox', { name: /^Code/ }), 'BRC27')
    expect(within(dialog).getByRole('checkbox', { name: 'Copy the current members' })).toBeChecked()
    await user.click(within(dialog).getByRole('button', { name: 'Clone' }))

    await vi.waitFor(() => {
      expect(router.state.location.pathname).toBe('/committees/f8')
    })
    expect(bodies).toEqual([
      {
        name: 'Board Risk Committee 2027',
        shortName: 'Risk 27',
        code: 'BRC27',
        key: 'board-risk-committee-2027',
        withMembers: true,
        startsOn: today(),
      },
    ])
  }, 20_000)

  it('dissolves only with a reason, behind the committee’s version, saying the history stays', async () => {
    const user = userEvent.setup()
    const bodies: DissolveRequest[] = []
    const matches: (string | null)[] = []
    server.use(
      ...handlers(),
      http.post(`${API}/forums/f1/dissolve`, async ({ request }) => {
        matches.push(request.headers.get('If-Match'))
        bodies.push((await request.json()) as DissolveRequest)
        return HttpResponse.json<ForumResponse>({ ...brc, status: 'DISSOLVED' })
      }),
    )
    renderRoute('/committees/f1')

    await user.click(await screen.findByRole('button', { name: 'More actions' }, { timeout: 5000 }))
    await user.click(await screen.findByRole('menuitem', { name: 'Dissolve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Dissolve Board Risk Committee?' })
    expect(dialog).toHaveTextContent('Its members and history stay. There is no way back')
    await user.click(within(dialog).getByRole('button', { name: 'Dissolve' }))
    expect(within(dialog).getByText('Required.')).toBeInTheDocument()
    expect(bodies).toEqual([])
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Merged into the audit committee')
    await user.click(within(dialog).getByRole('button', { name: 'Dissolve' }))

    await vi.waitFor(() => {
      expect(bodies).toEqual([{ reason: 'Merged into the audit committee', dissolvedOn: today() }])
    })
    expect(matches).toEqual(['"3"'])
  }, 20_000)

  it('reads in Arabic, right to left, with the quorum sentence translated', async () => {
    server.use(...handlers())
    const { container } = renderRoute('/committees/f1', { locale: 'ar' })

    expect(await screen.findByRole('tab', { name: 'الأعضاء' }, { timeout: 5000 })).toBeInTheDocument()
    expect(document.documentElement.dir).toBe('rtl')
    expect(await screen.findByText('النصاب اليوم: 2 من أصل 3 من الأعضاء ذوي حق التصويت')).toBeInTheDocument()
    expect(screen.getByText(/^أكثر من نصف الأعضاء الحاليين ذوي حق التصويت/)).toBeInTheDocument()
    await screen.findByText('Demo Director')
    await expectNoA11yViolations(container)
  }, 20_000)
})

describe('my committees (Phase 4 E01 T5, flow L1)', () => {
  const mine: MyForumResponse[] = [
    {
      forumId: 'f1',
      name: 'Board Risk Committee',
      code: 'BRC',
      status: 'ACTIVE',
      role: 'MEMBER',
      voting: true,
      attributes: ['independent'],
      state: 'IN_OFFICE',
      startsOn: '2024-01-01',
      termEndsOn: '2026-12-31',
      termNumber: 1,
      canOpen: true,
    },
    {
      forumId: 'f5',
      name: 'Old Credit Committee',
      code: 'OCC',
      status: 'ACTIVE',
      role: 'MEMBER',
      voting: false,
      attributes: [],
      state: 'ENDED',
      startsOn: '2020-01-01',
      endedOn: '2023-12-31',
      termNumber: 1,
      canOpen: false,
    },
  ]

  it('shows a card per membership with role, vote, attributes, term and the committee’s officers', async () => {
    server.use(...handlers(brc, []), myForumsHandler(mine))
    const { container } = renderRoute('/me/committees')

    const card = await screen.findByRole('article', { name: 'Board Risk Committee' }, { timeout: 5000 })
    expect(await within(card).findByText('Member · votes · Independent')).toBeInTheDocument()
    expect(within(card).getByText('Term 1 ends on Dec 31, 2026')).toBeInTheDocument()
    expect(await within(card).findByText('Chair: S. Ali')).toBeInTheDocument()
    expect(within(card).getByText('Secretary: Company Secretary')).toBeInTheDocument()
    expect(within(card).getByText('4 members')).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Open Board Risk Committee' })).toHaveAttribute(
      'href',
      '/committees/f1',
    )
    const former = screen.getByRole('article', { name: 'Old Credit Committee' })
    expect(within(former).getByText('Ended on Dec 31, 2023')).toBeInTheDocument()
    expect(within(former).queryByRole('link')).toBeNull()
    await expectNoA11yViolations(container)
  }, 20_000)

  it('puts a card on the home page while the user sits on a committee', async () => {
    server.use(...handlers(brc, []), myForumsHandler(mine))
    renderRoute('/')
    const link = await screen.findByRole('link', { name: /My committees/ }, { timeout: 5000 })
    expect(link).toHaveAttribute('href', '/me/committees')
    expect(link).toHaveTextContent('Board Risk Committee')
    expect(link).not.toHaveTextContent('Old Credit Committee')
  })
})
