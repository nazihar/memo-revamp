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
    const bodies: { name: string; quorum: { threshold: unknown }; outcomes: { kind: string }[] }[] = []
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
    await expectNoA11yViolations(container)
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
    expect(screen.getByRole('tab', { name: 'Changes (2)' })).toHaveAttribute('aria-selected', 'true')
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
