import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  DoaSimulationResponse,
  DoaVersionResponse,
  MemoTypeOption,
  OrgUnit,
  QueueResponse,
  Role,
  SubstitutionResponse,
} from '@/api/generated/types.gen'
import { partForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const published: DoaVersionResponse = {
  number: 1,
  state: 'PUBLISHED',
  effectiveFrom: '2026-11-01',
  revision: 4,
  submittedBy: { id: 'u2', name: 'Maker Mo' },
  reviewedBy: { id: 'u3', name: 'Checker Cy' },
  positions: { p1: 'DMD', p2: 'AMD' },
  rules: [
    {
      name: 'Credit',
      subjectType: 'memo',
      includeSubtree: true,
      condition: 'item.classificationRank >= 1',
      groups: [
        {
          needed: 2,
          of: [
            { kind: 'position', positionId: 'p1' },
            { kind: 'position', positionId: 'p2' },
          ],
        },
      ],
    },
  ],
}

const draft: DoaVersionResponse = { ...published, number: 2, state: 'DRAFT', revision: 0, reviewedBy: null }

function handlers(shown: DoaVersionResponse[]) {
  return [
    sessionHandler(signedInSession(true)),
    accessHandler(['workflow.doa.manage', 'workflow.doa.approve']),
    csrfHandler(),
    http.get(`${API_ORIGIN}/api/v1/admin/workflow/doa`, () =>
      HttpResponse.json<DoaVersionResponse[]>(shown.map((v) => ({ ...v, rules: null }))),
    ),
    http.get(`${API_ORIGIN}/api/v1/admin/workflow/doa/versions/:number`, ({ params }) =>
      HttpResponse.json<DoaVersionResponse>(shown.find((v) => String(v.number) === params.number) ?? published),
    ),
    http.get(`${API_ORIGIN}/api/v1/memo-types`, () =>
      HttpResponse.json<MemoTypeOption[]>([{ id: 't1', name: 'Credit memo', stepUpOnApprove: true }]),
    ),
    http.get(`${API_ORIGIN}/api/v1/org/units`, () => HttpResponse.json<OrgUnit[]>([])),
    http.get(`${API_ORIGIN}/api/v1/authz/roles`, () => HttpResponse.json<Role[]>([])),
  ]
}

describe('delegation of authority', () => {
  it('lists versions and shows the rules of the newest, read-only once published', async () => {
    server.use(...handlers([published]))
    const { container } = renderRoute('/admin/workflow/doa')

    expect(await screen.findByRole('heading', { name: 'Version 1' }, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByText('Rule 1 · Credit')).toBeInTheDocument()
    expect(screen.getByText('2 approvals from: DMD, AMD')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument()
    expect(partForPath('/admin/workflow/doa')).toBe('admin')
    await expectNoA11yViolations(container)
  })

  it('edits a draft and saves its rules with the revision it was read at', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    const matches: (string | null)[] = []
    server.use(
      ...handlers([draft, published]),
      http.put(`${API_ORIGIN}/api/v1/admin/workflow/doa/versions/2`, async ({ request }) => {
        matches.push(request.headers.get('If-Match'))
        bodies.push(await request.json())
        return HttpResponse.json<DoaVersionResponse>({ ...draft, revision: 1 })
      }),
    )
    const { container } = renderRoute('/admin/workflow/doa')

    const name = await screen.findByRole('textbox', { name: /Name/ }, { timeout: 5000 })
    await user.clear(name)
    await user.type(name, 'Large credit')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    await screen.findByText('Done.')
    expect(matches).toEqual(['"0"'])
    expect(bodies).toHaveLength(1)
    expect((bodies[0] as { rules: { name: string }[] }).rules[0]?.name).toBe('Large credit')
    await expectNoA11yViolations(container)
  })

  it('simulates who would approve a memo', async () => {
    server.use(
      ...handlers([published]),
      http.post(`${API_ORIGIN}/api/v1/admin/workflow/doa/simulate`, () =>
        HttpResponse.json<DoaSimulationResponse>({
          version: 1,
          rule: 0,
          ruleName: 'Credit',
          groups: [{ needed: 2, people: [{ id: 'u6', name: 'Dave DMD' }] }],
        }),
      ),
    )
    const user = userEvent.setup()
    renderRoute('/admin/workflow/doa')

    await user.click(await screen.findByRole('button', { name: 'Simulate' }, { timeout: 5000 }))
    expect(screen.getByText('Choose the initiator.')).toBeInTheDocument()
  })
})

describe('queues and substitutions', () => {
  it('lists queues with their members', async () => {
    server.use(
      sessionHandler(signedInSession(true)),
      accessHandler(['workflow.queues.manage']),
      http.get(`${API_ORIGIN}/api/v1/admin/workflow/queues`, () =>
        HttpResponse.json<QueueResponse[]>([
          {
            key: 'credit-desk',
            name: 'Credit desk',
            version: 0,
            members: [{ user: { id: 'u2', name: 'Bob' } }, { positionId: 'p1', positionTitle: 'DMD' }],
          },
        ]),
      ),
    )
    const { container } = renderRoute('/admin/workflow/queues')

    expect(await screen.findByText('Credit desk', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByText('Bob, DMD')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('lists substitutions and ends one', async () => {
    const user = userEvent.setup()
    const ended: string[] = []
    const row: SubstitutionResponse = {
      id: 's1',
      principal: { id: 'u2', name: 'Bob Away' },
      substitute: { id: 'u3', name: 'Carol Cover' },
      validFrom: '2026-01-01',
      validTo: null,
      reason: 'Annual leave',
    }
    server.use(
      sessionHandler(signedInSession(true)),
      accessHandler(['workflow.tasks.reassign']),
      csrfHandler(),
      http.get(`${API_ORIGIN}/api/v1/admin/workflow/substitutions`, () =>
        HttpResponse.json<SubstitutionResponse[]>([row]),
      ),
      http.post(`${API_ORIGIN}/api/v1/admin/workflow/substitutions/s1/end`, () => {
        ended.push('s1')
        return HttpResponse.json<SubstitutionResponse>({ ...row, validTo: '2026-11-13' })
      }),
    )
    const { container } = renderRoute('/admin/workflow/substitutions')

    const cell = await screen.findByText('Carol Cover', {}, { timeout: 5000 })
    const table = cell.closest('table') as HTMLElement
    expect(within(table).getByText('In force')).toBeInTheDocument()
    await expectNoA11yViolations(container)
    await user.click(within(table).getByRole('button', { name: 'End today' }))
    await screen.findByText('Done.')
    expect(ended).toEqual(['s1'])
  })
})
