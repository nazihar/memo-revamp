import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeAll, describe, expect, it } from 'vitest'
import type {
  DefinitionResponse,
  DefinitionSummary,
  DirectorySearchResult,
  MigrationResponse,
  SimulationResponse,
  ValidationResponse,
  VersionResponse,
} from '@/api/generated/types.gen'
import { partForPath, partsForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { installReactFlowStubs } from '@/test/react-flow'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const MANAGE = 'workflow.definitions.manage'
const APPROVE = 'workflow.definitions.approve'

const summary: DefinitionSummary = {
  key: 'purchase',
  name: 'Purchase approval',
  description: 'Purchases above the budget line',
  fragment: false,
  builtIn: false,
  publishedVersion: 1,
  openVersion: { number: 2, state: 'IN_REVIEW', submittedBy: { id: 'u2', name: 'Maker Mo' } },
}

const definition: DefinitionResponse = {
  definition: summary,
  versions: [
    { number: 2, state: 'IN_REVIEW', submittedBy: { id: 'u2', name: 'Maker Mo' } },
    {
      number: 1,
      state: 'PUBLISHED',
      submittedBy: { id: 'u2', name: 'Maker Mo' },
      reviewedBy: { id: 'u3', name: 'Checker Cy' },
    },
  ],
}

const version = (number: number, state: VersionResponse['version']['state']): VersionResponse => ({
  id: `v${String(number)}`,
  key: 'purchase',
  version: { number, state },
  revision: 3,
  body: { schemaVersion: 2, start: 'route', steps: [{ id: 'route', type: 'route', next: 'end' }] },
  problems: [],
  labels: {},
})

function handlers(permissions: string[], shown: VersionResponse = version(2, 'IN_REVIEW')) {
  return [
    sessionHandler(signedInSession(true)),
    accessHandler(permissions),
    csrfHandler(),
    http.get(`${API_ORIGIN}/api/v1/admin/workflow-definitions`, () =>
      HttpResponse.json<DefinitionSummary[]>([summary]),
    ),
    http.get(`${API_ORIGIN}/api/v1/admin/workflow-definitions/purchase`, () =>
      HttpResponse.json<DefinitionResponse>(definition),
    ),
    http.get(`${API_ORIGIN}/api/v1/admin/workflow-definitions/purchase/versions/:number`, () =>
      HttpResponse.json<VersionResponse>(shown),
    ),
  ]
}

describe('workflow definitions', () => {
  beforeAll(installReactFlowStubs)

  it('lists definitions with their published and open versions', async () => {
    server.use(...handlers([MANAGE]))
    const { container } = renderRoute('/admin/workflows')

    const link = await screen.findByRole('link', { name: 'Purchase approval' }, { timeout: 5000 })
    expect(link).toHaveAttribute('href', '/admin/workflows/purchase')
    const row = link.closest('tr')
    expect(row).not.toBeNull()
    expect(within(row as HTMLElement).getByText('Version 1')).toBeInTheDocument()
    expect(within(row as HTMLElement).getByText('Version 2 · In review')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New workflow' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('offers a reviewer the decision on a version in review, and says why the submitter may not decide', async () => {
    const user = userEvent.setup()
    server.use(
      ...handlers([APPROVE]),
      http.post(`${API_ORIGIN}/api/v1/admin/workflow-definitions/purchase/versions/2/send-back`, () =>
        HttpResponse.json(
          { type: 'https://memo.example/problems/conflict', status: 409, reason: 'SAME_PERSON', detail: 'x' },
          { status: 409 },
        ),
      ),
    )
    renderRoute('/admin/workflows/purchase')

    const toolbar = await screen.findByRole('toolbar', { name: 'Versions' }, { timeout: 5000 })
    expect(within(toolbar).getByRole('button', { name: 'Approve and publish' })).toBeInTheDocument()
    expect(within(toolbar).queryByRole('button', { name: 'Save draft' })).toBeNull()
    // A reviewer opens a version in review on its changes against the published one (E06 T7); this fixture has none.
    expect(screen.getByRole('tab', { name: 'Changes' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByText('Nothing changed between these versions.')).toBeInTheDocument()
    // The diagram and the Steps view say the same in words.
    await user.click(screen.getByRole('tab', { name: 'Diagram' }))
    expect(screen.getByRole('group', { name: 'Workflow diagram' })).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Steps' }))
    const steps = screen.getByRole('list', { name: 'Steps, in the order they are written' })
    expect(within(steps).getByRole('heading', { name: '1. route' })).toBeInTheDocument()
    expect(within(steps).getByText('The approvers the initiator chooses')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'JSON' }))
    expect(screen.getByRole('textbox', { name: /Definition/ })).toHaveAttribute('readonly')

    await user.click(within(toolbar).getByRole('button', { name: 'Send back' }))
    const dialog = await screen.findByRole('dialog', { name: 'Send this version back?' })
    await user.click(within(dialog).getByRole('button', { name: 'Send back' }))
    expect(within(dialog).getByText('Say what needs to change.')).toBeInTheDocument()
    await user.type(within(dialog).getByRole('textbox', { name: /Remark/ }), 'Name the steps')
    await user.click(within(dialog).getByRole('button', { name: 'Send back' }))
    expect(
      await screen.findByText('Someone other than the person who submitted it has to review it.'),
    ).toBeInTheDocument()
  })

  it('lets an author check an unsaved draft and shows each problem with its pointer', async () => {
    const user = userEvent.setup()
    server.use(
      ...handlers([MANAGE], version(2, 'DRAFT')),
      http.post(`${API_ORIGIN}/api/v1/admin/workflow-definitions/purchase/versions/2/validate`, () =>
        HttpResponse.json<ValidationResponse>({
          valid: false,
          problems: [{ field: '/steps/0/next', code: 'UNKNOWN_STEP', message: 'No step "nowhere".' }],
        }),
      ),
    )
    renderRoute('/admin/workflows/purchase?version=2')

    await user.click(await screen.findByRole('tab', { name: 'JSON' }, { timeout: 5000 }))
    const editor = screen.getByRole('textbox', { name: /Definition/ })
    expect(editor).not.toHaveAttribute('readonly')
    await user.click(screen.getByRole('button', { name: 'Check' }))
    expect(await screen.findByText('No step "nowhere".')).toBeInTheDocument()
    expect(screen.getByText('/steps/0/next')).toBeInTheDocument()
    // Submitting sends the saved version: it waits until the edits are saved.
    await user.type(editor, ' ')
    expect(screen.getByRole('button', { name: 'Submit for review' })).toBeDisabled()
  })

  it('builds a draft: adds a step, checks it as you go, undoes, and keeps the work when someone saved meanwhile', async () => {
    const user = userEvent.setup()
    const validated: unknown[] = []
    const saves: { ifMatch: string | null; body: unknown }[] = []
    server.use(
      ...handlers([MANAGE], version(2, 'DRAFT')),
      http.post(`${API_ORIGIN}/api/v1/admin/workflow-definitions/purchase/versions/2/validate`, async ({ request }) => {
        validated.push(await request.json())
        return HttpResponse.json<ValidationResponse>({
          valid: false,
          problems: [{ field: '/steps/0/assignee', code: 'REQUIRED', message: 'Say who approves.' }],
        })
      }),
      http.put(`${API_ORIGIN}/api/v1/admin/workflow-definitions/purchase/versions/2`, async ({ request }) => {
        saves.push({ ifMatch: request.headers.get('If-Match'), body: await request.json() })
        return HttpResponse.json({ status: 412, title: 'Precondition failed' }, { status: 412 })
      }),
    )
    renderRoute('/admin/workflows/purchase?version=2')

    await user.click(await screen.findByRole('tab', { name: 'Steps' }, { timeout: 5000 }))
    await user.selectOptions(screen.getByLabelText('Add a step at the start'), 'Approval')
    await user.click(screen.getByRole('button', { name: 'Add' }))
    const list = screen.getByRole('list', { name: 'Steps, in the order they are written' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(within(list).getByRole('heading', { name: '1. Approval' })).toBeInTheDocument()
    // Checked half a second after the change, with the problem on the new step.
    expect(await screen.findByText(/1 problem found\./, {}, { timeout: 3000 })).toBeInTheDocument()
    // The change is announced in the same polite status line (E06 T8).
    expect(screen.getByText(/^Approval added. /)).toHaveAttribute('role', 'status')
    expect(validated).toHaveLength(1)
    expect(within(list).getByText('1 problem')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Undo' }))
    expect(within(list).getAllByRole('listitem')).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Redo' }))
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Someone else saved this draft')
    expect(saves[0]?.ifMatch).toBe('"3"')
    expect((saves[0]?.body as { body: { steps: unknown[] } }).body.steps).toHaveLength(2)
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByRole('button', { name: 'Reload the saved version' })).toBeInTheDocument()
  }, 20_000)

  it('simulates the version for a sample item, and marks the steps it reaches', async () => {
    const user = userEvent.setup()
    const requests: unknown[] = []
    const shown: VersionResponse = {
      ...version(2, 'IN_REVIEW'),
      body: {
        schemaVersion: 2,
        attributes: [{ name: 'classificationRank', type: 'int' }],
        start: 'size',
        steps: [
          {
            id: 'size',
            type: 'branch',
            name: 'By classification',
            branches: [{ when: 'item.classificationRank >= 2', next: 'board' }],
            otherwise: 'buyer',
          },
          { id: 'board', type: 'approve', name: 'Board', assignee: { kind: 'person', userId: 'u9' }, next: 'end' },
          { id: 'buyer', type: 'approve', name: 'Buyer', assignee: { kind: 'person', userId: 'u8' }, next: 'end' },
        ],
      },
    }
    server.use(
      ...handlers([APPROVE], shown),
      http.get(`${API_ORIGIN}/api/v1/directory/search`, () =>
        HttpResponse.json<DirectorySearchResult>({
          users: [{ person: { id: 'u1', displayName: 'Amina Initiator', email: 'a@bank.example' } }],
          positions: [],
        }),
      ),
      http.post(`${API_ORIGIN}/api/v1/admin/workflow-definitions/purchase/versions/2/simulate`, async ({ request }) => {
        requests.push(await request.json())
        return HttpResponse.json<SimulationResponse>({
          ended: 'BLOCKED',
          steps: [
            {
              stepId: 'size',
              name: 'By classification',
              type: 'branch',
              outcome: 'BRANCHED',
              people: [],
              branch: 0,
              condition: 'item.classificationRank >= 2',
              next: 'board',
            },
            {
              stepId: 'board',
              name: 'Board',
              type: 'approve',
              outcome: 'BLOCKED',
              people: [],
              blockedReason: 'NO_CLEARANCE',
              sla: { due: 'P2D' },
            },
          ],
        })
      }),
    )
    renderRoute('/admin/workflows/purchase?version=2')

    const panel = await screen.findByRole('region', { name: 'Simulate' }, { timeout: 5000 })
    await user.click(within(panel).getByRole('button', { name: 'Simulate' }))
    expect(within(panel).getByText('Choose who starts it.')).toBeInTheDocument()
    await user.type(within(panel).getByRole('combobox', { name: /Started by/ }), 'Am')
    await user.click(await screen.findByRole('option', { name: /Amina Initiator/ }))
    await user.type(within(panel).getByLabelText('Classification rank'), '2')
    await user.click(within(panel).getByRole('button', { name: 'Simulate' }))

    expect(
      await within(panel).findByText('It would stop and wait for an administrator: someone it reaches cannot take it.'),
    ).toBeInTheDocument()
    expect(requests[0]).toMatchObject({ initiatorId: 'u1', attributes: { classificationRank: 2 }, route: [] })
    const path = within(panel).getByRole('list', { name: 'Steps it would reach, in order' })
    expect(within(path).getByText('Condition 1 held: item.classificationRank >= 2')).toBeInTheDocument()
    expect(within(path).getByText('Not cleared for this classification.')).toBeInTheDocument()
    expect(within(path).getByText('Due in 2 working days')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Steps' }))
    const steps = screen.getByRole('list', { name: 'Steps, in the order they are written' })
    expect(within(steps).getAllByText('Reached in the simulation')).toHaveLength(2)
  }, 20_000)

  it('previews memos on older versions with why some cannot move', async () => {
    const user = userEvent.setup()
    server.use(
      ...handlers([APPROVE], version(1, 'PUBLISHED')),
      http.post(`${API_ORIGIN}/api/v1/admin/workflow-definitions/purchase/migrations/preview`, () =>
        HttpResponse.json<MigrationResponse>({
          toVersion: 2,
          items: [
            {
              instanceId: 'i1',
              subject: { type: 'memo', id: 'm1', reference: 'M-1', title: 'Laptops', restricted: false },
              fromVersion: 1,
              migratable: true,
              migrated: false,
              openSteps: ['Buyer'],
            },
            {
              instanceId: 'i2',
              subject: { type: 'memo', id: 'm2', reference: 'M-2', restricted: true },
              fromVersion: 1,
              migratable: false,
              migrated: false,
              reason: 'STEP_MISSING',
              openSteps: ['Board'],
            },
          ],
        }),
      ),
    )
    renderRoute('/admin/workflows/purchase?version=1')

    await user.click(await screen.findByRole('button', { name: 'Find memos' }, { timeout: 5000 }))
    expect(await screen.findByRole('checkbox', { name: 'Select M-1' })).toBeChecked()
    expect(screen.getByText('Its current step is not in the new version')).toBeInTheDocument()
    expect(screen.getByText('M-2 · Restricted memo')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Move selected' })).toBeEnabled()
  })

  it('loads the admin part of the Arabic catalogue for admin pages', async () => {
    server.use(...handlers([MANAGE]))
    renderRoute('/admin/workflows', { locale: 'ar' })
    expect(await screen.findByRole('heading', { name: 'مسارات العمل' }, { timeout: 5000 })).toBeInTheDocument()
    expect(partForPath('/admin/workflows/purchase')).toBe('admin')
    expect(partForPath('/administrator')).toBe('core')
    expect(partForPath('/platform')).toBe('platform')
  })

  it('loads the admin and builder parts of the Arabic catalogue for a definition', async () => {
    server.use(...handlers([APPROVE]))
    renderRoute('/admin/workflows/purchase?version=2', { locale: 'ar' })
    expect(await screen.findByRole('tab', { name: 'المخطط' }, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'المحاكاة' })).toBeInTheDocument()
    expect(partsForPath('/admin/workflows/purchase')).toEqual(['admin', 'builder'])
    await expectNoA11yViolations(document.body)
    expect(partsForPath('/admin/users')).toEqual(['admin'])
  })
})
