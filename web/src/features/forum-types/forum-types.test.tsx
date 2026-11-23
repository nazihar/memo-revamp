import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  ClassificationLevel,
  ForumTypePresetResponse,
  ForumTypeResponse,
  ForumTypeVersionResponse,
  QuorumPreviewRequest,
  QuorumResult,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const BASE = `${API_ORIGIN}/api/v1/admin/forum-types`

const types: ForumTypeResponse[] = [
  {
    id: 't1',
    code: 'BC',
    name: 'Board committee',
    active: true,
    publishedVersion: 2,
    openVersion: 3,
    openState: 'IN_REVIEW',
    openSubmittedBy: { id: 'u2', name: 'Admin Two' },
  },
  {
    id: 't2',
    code: 'MC',
    name: 'Management committee',
    active: true,
    publishedVersion: 1,
    openVersion: 2,
    openState: 'DRAFT',
  },
  { id: 't3', code: 'BOARD', name: 'Board', active: false, publishedVersion: 1 },
]

const published: ForumTypeVersionResponse = {
  number: 2,
  state: 'PUBLISHED',
  revision: 4,
  name: 'Board committee',
  description: 'Committees of the Board',
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
  termPolicy: { termMonths: 36, maxTerms: 3, reminderDays: [42, 1] },
  submissionPolicy: { deadlineWorkingDays: 5, lateMode: 'CHAIR_APPROVAL', triageWorkingDays: 5 },
  agenda: {
    chairAgreement: true,
    sections: [
      {
        title: 'Opening',
        kind: 'STANDARD',
        membersOnly: false,
        receivesSubmissions: false,
        items: [{ title: 'Welcome and apologies', standingKind: 'APOLOGIES', purpose: 'INFORMATION', minutes: 5 }],
      },
      { title: 'For decision', kind: 'STANDARD', membersOnly: false, receivesSubmissions: true, items: [] },
    ],
  },
  meetings: {
    invitationTitle: 'NAME',
    neutralTitle: 'Committee meeting',
    replyDeadlineWorkingDays: 2,
    replyReminder: true,
    meetingReminder: true,
    quorumOverride: 'NEVER',
  },
  minutes: {
    approval: 'AT_NEXT_MEETING',
    circulationWorkingDays: 5,
    draftWorkingDays: 10,
    memoResumes: 'AT_MEETING_END',
    resolutionNumbering: {
      parts: [
        { kind: 'COMMITTEE_CODE' },
        { kind: 'TEXT', text: '/' },
        { kind: 'YEAR', digits: 4 },
        { kind: 'TEXT', text: '/' },
        { kind: 'SEQUENCE', digits: 3 },
      ],
      reset: 'YEARLY',
      fyStartMonth: 7,
    },
  },
  actions: {
    amberWorkingDays: 10,
    staleWorkingDays: 20,
    dueChange: 'SECRETARY_AGREES',
    verifiedBy: 'SECRETARY',
    evidenceRequired: false,
    defaultDue: 'NEXT_MEETING',
    defaultDueWorkingDays: 20,
    updateRequestWorkingDays: 5,
    definitionKey: 'action-item',
  },
  retention: { years: 10, from: 'MEETING' },
  createdAt: '2026-11-18T08:00:00Z',
}

const draft: ForumTypeVersionResponse = { ...published, number: 3, state: 'DRAFT', revision: 5 }
const inReview: ForumTypeVersionResponse = {
  ...draft,
  state: 'IN_REVIEW',
  quorum: { ...published.quorum, threshold: { kind: 'FRACTION', numerator: 2, denominator: 3 } },
  outcomes: [
    { kind: 'APPROVED', label: 'Sanctioned', resolution: true },
    { kind: 'NOTED', label: 'Noted', resolution: false },
  ],
  meetings: { ...published.meetings, invitationTitle: 'NEUTRAL', neutralTitle: 'Board committee meeting' },
  minutes: {
    ...published.minutes,
    approval: 'BY_CIRCULATION',
    resolutionNumbering: { ...published.minutes.resolutionNumbering, reset: 'FINANCIAL_YEAR' },
  },
  actions: { ...published.actions, staleWorkingDays: 0, dueChange: 'OWNER_WITH_REASON', evidenceRequired: true },
  submittedBy: { id: 'u2', name: 'Admin Two' },
  submittedAt: '2026-11-18T10:20:00Z',
}

const preview: QuorumResult = {
  basis: 7,
  needed: 4,
  present: 4,
  quorate: false,
  unmet: [{ kind: 'COMPOSITION', needed: 3, actual: 2, attribute: 'independent', majority: true }],
}

function handlers(
  versions: ForumTypeVersionResponse[],
  permissions = ['forum.types.manage', 'forum.types.approve'],
  previews: QuorumPreviewRequest[] = [],
) {
  return [
    sessionHandler(signedInSession(true)),
    accessHandler(permissions),
    csrfHandler(),
    http.get(BASE, () => HttpResponse.json<ForumTypeResponse[]>(types)),
    http.get(`${BASE}/presets`, () =>
      HttpResponse.json<ForumTypePresetResponse[]>([
        { key: 'BOARD', name: 'Board', settings: {} },
        { key: 'BOARD_COMMITTEE', name: 'Board committee', settings: {} },
        { key: 'MANAGEMENT_COMMITTEE', name: 'Management committee', settings: {} },
      ]),
    ),
    http.get(`${BASE}/:id`, ({ params }) =>
      HttpResponse.json<ForumTypeResponse>(types.find((t) => t.id === params.id) ?? types[0]!),
    ),
    http.get(`${BASE}/:id/versions`, () => HttpResponse.json(versions)),
    http.get(`${BASE}/:id/versions/:number`, ({ params }) =>
      HttpResponse.json(versions.find((v) => String(v.number) === params.number) ?? published),
    ),
    http.get(`${API_ORIGIN}/api/v1/authz/classification-levels`, () =>
      HttpResponse.json<ClassificationLevel[]>([{ id: 'l0', name: 'Internal', rank: 0 } as ClassificationLevel]),
    ),
    http.post(`${API_ORIGIN}/api/v1/forums/quorum-preview`, async ({ request }) => {
      previews.push((await request.json()) as QuorumPreviewRequest)
      return HttpResponse.json<QuorumResult>(preview)
    }),
  ]
}

describe('committee types (Phase 4 E01 T4, flow J)', () => {
  it('lists types with their code, published version and the version open for change or review', async () => {
    server.use(...handlers([published]))
    const { container } = renderRoute('/admin/committee-types')

    expect(await screen.findByRole('heading', { level: 1, name: 'Committee types' }, { timeout: 5000 }))
    const row = (await screen.findByRole('link', { name: 'Board committee' })).closest('tr')
    const cells = within(row as HTMLElement)
    expect(cells.getByText('BC')).toBeInTheDocument()
    expect(cells.getByText('v2')).toBeInTheDocument()
    expect(cells.getByText('In review v3 · Admin Two')).toBeInTheDocument()
    expect(screen.getByText('Draft v2')).toBeInTheDocument()
    expect(screen.getByText('Switched off')).toBeInTheDocument()
    expect(partsForPath('/admin/committee-types/t1')).toContain('forumAdmin')
    await expectNoA11yViolations(container)
  })

  it('uses the organisation’s word for a committee', async () => {
    server.use(
      ...handlers([published]),
      http.get(`${API_ORIGIN}/api/v1/terms`, () =>
        HttpResponse.json({ locale: 'en', terms: [{ term: 'forum', one: 'board', other: 'boards' }], version: 1 }),
      ),
    )
    renderRoute('/admin/committee-types')
    expect(await screen.findByRole('heading', { level: 1, name: 'Board types' }, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New board type' })).toBeInTheDocument()
  })

  it('reads in Arabic, right to left, with the quorum sentence translated', async () => {
    const user = userEvent.setup()
    server.use(...handlers([draft, published]))
    const { container } = renderRoute('/admin/committee-types/t1?version=3', { locale: 'ar' })

    await user.click(await screen.findByRole('tab', { name: 'النصاب' }, { timeout: 5000 }))
    expect(document.documentElement.dir).toBe('rtl')
    expect(screen.getByText(/^أكثر من نصف الأعضاء الحاليين ذوي حق التصويت/)).toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 20_000)

  it('words the changes to the agenda policy in the changes view (flow R)', async () => {
    const user = userEvent.setup()
    const sections = published.agenda.sections ?? []
    const changed: ForumTypeVersionResponse = {
      ...draft,
      agenda: {
        chairAgreement: false,
        sections: [
          ...sections,
          { title: 'Strategy', kind: 'CONSENT', membersOnly: true, receivesSubmissions: false, items: [] },
        ],
      },
    }
    server.use(...handlers([changed, published]))
    renderRoute('/admin/committee-types/t1?version=3')

    await user.click(await screen.findByRole('tab', { name: /Changes/ }, { timeout: 5000 }))
    expect(screen.getByText('Agenda: the chair agrees it before it is first published:')).toBeInTheDocument()
    expect(screen.getByText('Agenda section “Strategy”:')).toBeInTheDocument()
    expect(document.body.textContent).toContain('Consent section, members only')
  }, 20_000)

  it('creates a type from a preset and opens its draft version 1', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    server.use(
      ...handlers([{ ...draft, number: 1, revision: 0 }]),
      http.post(BASE, async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json<ForumTypeResponse>(
          { id: 't9', code: 'ARC', name: 'Audit and risk', active: true, openVersion: 1, openState: 'DRAFT' },
          { status: 201 },
        )
      }),
    )
    const { router } = renderRoute('/admin/committee-types')

    await user.click(await screen.findByRole('button', { name: 'New committee type' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'New committee type' })
    await user.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'Audit and risk')
    await user.type(within(dialog).getByRole('textbox', { name: /Code/ }), 'arc')
    await user.click(await within(dialog).findByRole('radio', { name: 'Board committee' }))
    await user.click(within(dialog).getByRole('button', { name: 'Create draft' }))

    await screen.findByRole('heading', { level: 2, name: /v1 · Draft/ }, { timeout: 5000 })
    expect(bodies).toEqual([{ name: 'Audit and risk', code: 'ARC', preset: 'BOARD_COMMITTEE' }])
    expect(router.state.location.pathname).toBe('/admin/committee-types/t9')
  }, 20_000)

  it('a draft is edited on its tabs and saved behind its revision', async () => {
    const user = userEvent.setup()
    const matches: (string | null)[] = []
    const bodies: {
      name: string
      quorum: { threshold: unknown }
      outcomes: { kind: string }[]
      submissionPolicy: unknown
      agenda: unknown
      meetings: unknown
      minutes: unknown
      actions: unknown
    }[] = []
    server.use(
      ...handlers([draft, published]),
      http.put(`${BASE}/t1/versions/3`, async ({ request }) => {
        matches.push(request.headers.get('If-Match'))
        bodies.push((await request.json()) as (typeof bodies)[number])
        return HttpResponse.json<ForumTypeVersionResponse>({ ...draft, revision: 6 })
      }),
    )
    const { container } = renderRoute('/admin/committee-types/t1?version=3')

    const name = await screen.findByRole('textbox', { name: /Name/ }, { timeout: 5000 })
    await user.clear(name)
    await user.type(name, 'Board committees')
    await user.click(screen.getByRole('tab', { name: 'Quorum' }))
    await user.click(screen.getByRole('radio', { name: 'A fraction' }))
    await user.click(screen.getByRole('tab', { name: 'Outcomes' }))
    await user.click(screen.getByRole('checkbox', { name: 'Use Deferred' }))
    expect(screen.getByRole('textbox', { name: 'Label for Deferred' })).toHaveValue('Deferred')
    await user.click(screen.getByRole('tab', { name: /Changes/ }))
    expect(screen.getByText('Outcome “Deferred”:')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText('Saved.')
    expect(matches).toEqual(['"5"'])
    expect(bodies[0]?.name).toBe('Board committees')
    expect(bodies[0]?.quorum.threshold).toEqual({ kind: 'FRACTION', numerator: 2, denominator: 3 })
    expect(bodies[0]?.outcomes.map((o) => o.kind)).toEqual(['APPROVED', 'DEFERRED', 'NOTED'])
    // Untouched on the Terms tab, the submission policy (ADR-0059) is saved as loaded.
    expect(bodies[0]?.submissionPolicy).toEqual(draft.submissionPolicy)
    // So is the agenda policy (ADR-0060) until the Agenda tab edits it (E03 T7).
    expect(bodies[0]?.agenda).toEqual(draft.agenda)
    // Likewise the meeting policy (Phase 4 E05), untouched on its Meetings tab (E05 T8).
    expect(bodies[0]?.meetings).toEqual(draft.meetings)
    // And the minutes policy (Phase 4 E06) until its tab comes (E06 T10).
    expect(bodies[0]?.minutes).toEqual(draft.minutes)
    // And the actions policy (Phase 4 E07) until its tab comes (E07 T10).
    expect(bodies[0]?.actions).toEqual(draft.actions)
    await expectNoA11yViolations(container)
  }, 20_000)

  it('papers for meetings are set on the Terms tab and listed among the changes (Phase 4 E02 T4)', async () => {
    const user = userEvent.setup()
    const bodies: { submissionPolicy: unknown }[] = []
    server.use(
      ...handlers([draft, published]),
      http.put(`${BASE}/t1/versions/3`, async ({ request }) => {
        bodies.push((await request.json()) as (typeof bodies)[number])
        return HttpResponse.json<ForumTypeVersionResponse>({ ...draft, revision: 6 })
      }),
    )
    const { container } = renderRoute('/admin/committee-types/t1?version=3')

    await user.click(await screen.findByRole('tab', { name: 'Terms' }, { timeout: 5000 }))
    const papers = screen.getByRole('group', { name: 'Papers for meetings' })
    const deadline = within(papers).getByRole('spinbutton', { name: /Papers due/ })
    expect(deadline).toHaveValue(5)
    expect(deadline).toHaveAccessibleDescription(/From 0 \(the day of the meeting\) to 30/)
    await user.clear(deadline)
    await user.type(deadline, '3')
    const late = within(papers).getByRole('combobox', { name: /Late papers/ })
    expect(late).toHaveDisplayValue('Allowed only if the chair agrees')
    await user.selectOptions(late, 'Held for the next meeting')
    const triage = within(papers).getByRole('spinbutton', { name: /Remind the secretaries after/ })
    await user.clear(triage)
    await user.type(triage, '7')
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('tab', { name: /Changes/ }))
    expect(screen.getByText('Papers due (working days before a meeting):')).toBeInTheDocument()
    expect(screen.getByText('Late papers:')).toBeInTheDocument()
    expect(screen.getByText('Held for the next meeting')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText('Saved.')
    expect(bodies[0]?.submissionPolicy).toEqual({ deadlineWorkingDays: 3, lateMode: 'BLOCK', triageWorkingDays: 7 })
  }, 20_000)

  it('the Meetings tab sets invitations, replies and the quorum override, listed among the changes (Phase 4 E05 T8, flow Z)', async () => {
    const user = userEvent.setup()
    const bodies: { meetings: unknown }[] = []
    server.use(
      ...handlers([draft, published]),
      http.put(`${BASE}/t1/versions/3`, async ({ request }) => {
        bodies.push((await request.json()) as (typeof bodies)[number])
        return HttpResponse.json<ForumTypeVersionResponse>({ ...draft, revision: 6 })
      }),
    )
    const { container } = renderRoute('/admin/committee-types/t1?version=3')

    await user.click(await screen.findByRole('tab', { name: 'Meetings' }, { timeout: 5000 }))
    const invitations = screen.getByRole('group', { name: 'Calendar invitations' })
    expect(invitations).toHaveAccessibleDescription(
      'Use a neutral title for committees whose name should not appear in people’s calendars.',
    )
    expect(
      within(invitations).getByRole('radio', { name: "The meeting's title or the committee's name" }),
    ).toBeChecked()
    await user.click(within(invitations).getByRole('radio', { name: 'A neutral title' }))
    const title = within(invitations).getByRole('textbox', { name: /Neutral title/ })
    expect(title).toHaveValue('Committee meeting')
    await user.clear(title)
    await user.type(title, 'Board committee meeting')
    const deadline = screen.getByRole('spinbutton', { name: /Ask for replies by/ })
    expect(deadline).toHaveValue(2)
    expect(deadline).toHaveAccessibleDescription('From 0 (no deadline) to 10.')
    await user.clear(deadline)
    await user.type(deadline, '3')
    expect(
      screen.getByRole('checkbox', { name: 'Remind those who have not replied, on the deadline day' }),
    ).toBeChecked()
    await user.click(
      screen.getByRole('checkbox', { name: 'Remind everyone attending the working day before the meeting' }),
    )
    const override = screen.getByRole('group', { name: 'Decisions without a quorum' })
    expect(within(override).getByRole('radio', { name: 'Never allowed' })).toBeChecked()
    await user.click(
      within(override).getByRole('radio', {
        name: 'Allowed with a reason, to be ratified at a later quorate meeting',
      }),
    )
    await expectNoA11yViolations(container)

    // The second administrator reads the same lines among the changes.
    await user.click(screen.getByRole('tab', { name: /Changes/ }))
    expect(screen.getByText('Invitation title in calendars:')).toBeInTheDocument()
    expect(screen.getByText('A neutral title: Board committee meeting')).toBeInTheDocument()
    expect(screen.getByText('Reply deadline (working days before a meeting):')).toBeInTheDocument()
    expect(screen.getByText('Reminder the working day before:')).toBeInTheDocument()
    expect(screen.getByText('Decisions without a quorum:')).toBeInTheDocument()
    expect(screen.getByText('With a reason, to be ratified later')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText('Saved.')
    expect(bodies[0]?.meetings).toEqual({
      invitationTitle: 'NEUTRAL',
      neutralTitle: 'Board committee meeting',
      replyDeadlineWorkingDays: 3,
      replyReminder: true,
      meetingReminder: false,
      quorumOverride: 'WITH_REASON',
    })
  }, 30_000)

  it('the Meetings tab only reads for a reviewer, and the quorum tab says alternates count for the number only', async () => {
    const user = userEvent.setup()
    server.use(...handlers([inReview, published], ['forum.types.approve']))
    renderRoute('/admin/committee-types/t1?version=3')

    await user.click(await screen.findByRole('tab', { name: 'Meetings' }, { timeout: 5000 }))
    const neutral = screen.getByRole('radio', { name: 'A neutral title' })
    expect(neutral).toBeChecked()
    expect(neutral).toBeDisabled()
    expect(screen.getByRole('textbox', { name: /Neutral title/ })).toHaveValue('Board committee meeting')
    expect(screen.getByRole('radio', { name: 'Never allowed' })).toBeDisabled()

    await user.click(screen.getByRole('tab', { name: 'Quorum' }))
    expect(
      screen.getByText('Alternates count for the member they replace, for the number of members only.'),
    ).toBeInTheDocument()
  }, 20_000)

  it('the quorum tab reads the rule as a sentence, with the server’s example and tester', async () => {
    const user = userEvent.setup()
    const previews: QuorumPreviewRequest[] = []
    server.use(...handlers([draft, published], undefined, previews))
    const { container } = renderRoute('/admin/committee-types/t1?version=3')

    await user.click(await screen.findByRole('tab', { name: 'Quorum' }, { timeout: 5000 }))
    expect(
      screen.getByText(
        'More than half of the voting members in office, and never fewer than 2, including the chair or a vice-chair, with a majority of them independent.',
      ),
    ).toBeInTheDocument()
    expect(
      await screen.findByText('With 7 voting members in office, 4 must attend.', {}, { timeout: 3000 }),
    ).toBeInTheDocument()
    expect(screen.getByText('Not quorate')).toBeInTheDocument()
    expect(screen.getByText('Needs 3 independent members, has 2.')).toBeInTheDocument()
    // The sample: 7 in office, 4 present, the chair among them, 2 of them independent.
    const sample = previews.at(-1)?.members ?? []
    expect(sample).toHaveLength(7)
    expect(sample.filter((m) => m.present)).toHaveLength(4)
    expect(sample.find((m) => m.role === 'CHAIR')?.present).toBe(true)
    expect(sample.filter((m) => m.attributes?.includes('independent'))).toHaveLength(2)

    await user.click(screen.getByRole('radio', { name: 'A fixed number' }))
    expect(screen.getByText(/^At least 3 of the voting members in office/)).toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 20_000)

  it('a reviewer sees the changes first; approving asks for a step-up and names the same-person rule', async () => {
    const user = userEvent.setup()
    let steppedUp = false
    const approved: unknown[] = []
    server.use(
      ...handlers([inReview, published]),
      http.post(`${BASE}/t1/versions/3/approve`, async ({ request }) => {
        if (!steppedUp) {
          return HttpResponse.json(
            { type: 'urn:memo:problem:step-up-required', status: 401, stepUpWindowSeconds: 300 },
            { status: 401 },
          )
        }
        approved.push(await request.json())
        return HttpResponse.json(
          { type: 'urn:memo:problem:conflict', status: 409, reason: 'SAME_PERSON' },
          { status: 409 },
        )
      }),
      http.post(`${API_ORIGIN}/api/v1/auth/step-up`, () => {
        steppedUp = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const { container } = renderRoute('/admin/committee-types/t1?version=3')

    expect(await screen.findByText(/^Submitted by Admin Two/, {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByText('Changes apply to every committee of this type from its next meeting.')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Changes (8)' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('Actions: “No recent update” after:')).toBeInTheDocument()
    expect(screen.getByText('Off')).toBeInTheDocument()
    expect(screen.getByText('Actions: a new due date needs:')).toBeInTheDocument()
    expect(screen.getByText('The secretary’s agreement')).toBeInTheDocument()
    expect(screen.getByText('Only a reason')).toBeInTheDocument()
    expect(screen.getByText('Actions: evidence required when marked as done:')).toBeInTheDocument()
    expect(screen.getByText('Invitation title in calendars:')).toBeInTheDocument()
    expect(screen.getByText('A neutral title: Board committee meeting')).toBeInTheDocument()
    expect(screen.getByText('How minutes are approved:')).toBeInTheDocument()
    expect(screen.getByText('By circulation, then noted at the next meeting')).toBeInTheDocument()
    expect(screen.getByText('Resolution numbers:')).toBeInTheDocument()
    expect(screen.getByText('BRC/2026/014, starting again every calendar year')).toBeInTheDocument()
    expect(screen.getByText('BRC/2026/014, starting again every financial year from July')).toBeInTheDocument()
    expect(screen.getByText('Outcome “Approved” label:')).toBeInTheDocument()
    expect(screen.getByText('Sanctioned')).toBeInTheDocument()
    expect(screen.getByText(/^Two-thirds of the voting members in office, rounded up/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send back' })).toBeDisabled()
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('button', { name: 'Approve and publish' }))
    const stepUp = await screen.findByRole('dialog', { name: 'Confirm it is you' })
    await user.type(within(stepUp).getByLabelText('Code'), '123456')
    await user.click(within(stepUp).getByRole('button', { name: 'Confirm' }))

    expect(await screen.findByText('Someone other than the submitter has to review it.')).toBeInTheDocument()
    expect(approved).toHaveLength(1)
  }, 20_000)
})

describe('the committee type’s Minutes tab (Phase 4 E06 T10, flow AI)', () => {
  it('edits how minutes are approved and resolution numbers with a live example, and says the template’s problems in words', async () => {
    const user = userEvent.setup()
    const previews: unknown[] = []
    const saved: unknown[] = []
    server.use(
      ...handlers([draft, published], ['forum.types.manage']),
      http.post(`${BASE}/resolution-numbering/preview`, async ({ request }) => {
        previews.push(await request.json())
        return HttpResponse.json({ samples: ['BRC/2026/014', 'BRC/2027/001'], problems: [] })
      }),
      http.get(`${BASE}/t1/versions/3/minutes-template`, () =>
        HttpResponse.json({
          owner: 'v3',
          template: {
            fileId: 'f9',
            versionId: 'fv9',
            name: 'brc-minutes.docx',
            sizeBytes: 40960,
            uploadedAt: '2026-11-19T08:00:00Z',
          },
          tags: ['meeting.label', 'item.discusion', 'item.outcomeLabel'],
          problems: [
            {
              code: 'UNKNOWN_TAG',
              tag: 'item.discusion',
              suggestion: 'item.notes',
              part: 'word/document.xml',
              paragraph: 12,
              excerpt: '{{item.discusion}}',
            },
          ],
          warnings: [
            {
              code: 'DEPRECATED_TAG',
              tag: 'item.outcomeLabel',
              suggestion: 'item.outcome',
              part: 'word/document.xml',
              paragraph: 14,
              excerpt: '{{item.outcomeLabel}}',
            },
          ],
          refusals: [],
          available: [{ tag: 'item.notes', label: 'Key points discussed', kind: 'VALUE' }],
        }),
      ),
      http.put(`${BASE}/t1/versions/3`, async ({ request }) => {
        saved.push(await request.json())
        return HttpResponse.json(draft)
      }),
    )
    const { container } = renderRoute('/admin/committee-types/t1?version=3')
    expect(partsForPath('/admin/committee-types/t1')).toContain('forumMinutes')
    await user.click(await screen.findByRole('tab', { name: 'Minutes' }, { timeout: 5000 }))
    expect(
      screen.getByRole('radio', { name: 'At the next meeting (as “Minutes of the previous meeting”)' }),
    ).toBeChecked()
    expect(screen.getByRole('radio', { name: 'When the meeting is marked as held' })).toBeChecked()
    expect(await screen.findByText('BRC/2026/014 — the first of the next period: BRC/2027/001')).toBeInTheDocument()
    expect(screen.getByText('{{item.discusion}} is not a known tag')).toBeInTheDocument()
    expect(screen.getByText(/Did you mean \{\{item\.notes\}\}\?/)).toBeInTheDocument()
    expect(screen.getByText('{{item.outcomeLabel}} still works but has a newer name')).toBeInTheDocument()
    // A template with problems cannot be previewed.
    expect(screen.getByRole('button', { name: 'Preview with a sample meeting' })).toBeDisabled()
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('radio', { name: 'By circulation, then noted at the next meeting' }))
    await user.click(screen.getByRole('radio', { name: 'When the minutes are approved' }))
    await user.click(screen.getByRole('button', { name: 'Add Meeting number' }))
    expect(await screen.findAllByText('Meeting number')).toHaveLength(2)
    expect(screen.getByText('Save your other changes before uploading.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText('Done.', {}, { timeout: 5000 }).catch(() => undefined)
    expect(saved[0]).toMatchObject({
      minutes: {
        approval: 'BY_CIRCULATION',
        memoResumes: 'ON_MINUTES_APPROVAL',
        resolutionNumbering: { parts: expect.arrayContaining([{ kind: 'MEETING_NUMBER' }]) as unknown },
      },
    })
    expect(previews.length).toBeGreaterThan(1)
  }, 30_000)
})
