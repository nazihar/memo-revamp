import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { BuilderCatalogue, DirectorySearchResult, OrgUnit } from '@/api/generated/types.gen'
import { API_ORIGIN, server } from '@/test/msw'
import { expectNoA11yViolations, renderWithProviders } from '@/test/render'
import type { DefinitionBody } from './definition'
import { apply, type Command } from './edit'
import { StepPanel } from './StepPanel'

const catalogue: BuilderCatalogue = {
  subjectTypes: [{ type: 'memo', attributes: [{ name: 'classificationRank', type: 'int' }] }],
  roles: [{ id: 'r1', name: 'Credit officer' }],
  queues: [{ key: 'credit-desk', name: 'Credit desk' }],
  fragments: [{ key: 'checks', name: 'Compliance checks' }],
  stepTypes: ['review', 'approve'],
}
const units: OrgUnit[] = [
  { id: 'crd', legalEntityId: 'e1', code: 'CRD', name: 'Credit Division', validFrom: '2026-01-01', version: 0 },
]

function backend() {
  server.use(
    http.get(`${API_ORIGIN}/api/v1/admin/workflow-builder/catalogue`, () =>
      HttpResponse.json<BuilderCatalogue>(catalogue),
    ),
    http.get(`${API_ORIGIN}/api/v1/org/units`, () => HttpResponse.json<OrgUnit[]>(units)),
    http.get(`${API_ORIGIN}/api/v1/directory/search`, () =>
      HttpResponse.json<DirectorySearchResult>({
        users: [],
        positions: [{ id: 'p1', title: 'Head of Credit', unitId: 'crd', unitName: 'Credit Division' }],
        units: [],
      }),
    ),
  )
}

const start: DefinitionBody = {
  schemaVersion: 2,
  start: 'director',
  steps: [
    { id: 'director', type: 'approve', name: 'Director', next: 'committee' },
    { id: 'committee', type: 'parallel', name: 'Committee', mode: 'all', assignees: [], next: 'end' },
  ],
}

/** The panel of one step over a live body, as the page holds it; the latest body is exposed for assertions. */
function Harness({ id, onBody }: { id: string; onBody: (body: DefinitionBody) => void }) {
  const [body, setBody] = useState(start)
  const [labels, setLabels] = useState<Record<string, string>>({})
  const step = body.steps.find((s) => s.id === id)
  if (!step) return null
  return (
    <StepPanel
      body={body}
      step={step}
      problems={[]}
      labels={labels}
      onLabel={(k, v) => {
        setLabels((l) => ({ ...l, [k]: v }))
      }}
      onCommand={(command: Command) => {
        const next = apply(body, command)
        setBody(next)
        onBody(next)
      }}
    />
  )
}

describe('step panels: who and what', () => {
  it('sets who an approval goes to, for each kind, with roles and queues from the catalogue', async () => {
    backend()
    const user = userEvent.setup()
    let body = start
    const { container } = renderWithProviders(
      <Harness
        id="director"
        onBody={(b) => {
          body = b
        }}
      />,
    )
    const director = () => body.steps.find((s) => s.id === 'director')

    const kind = screen.getByLabelText('Goes to')
    await user.selectOptions(kind, 'People with a role')
    await screen.findByRole('option', { name: 'Credit officer' })
    await user.selectOptions(screen.getByLabelText('Role'), 'Credit officer')
    await user.selectOptions(screen.getByLabelText('Unit'), 'Credit Division')
    expect(director()?.assignee).toEqual({ kind: 'role', roleId: 'r1', unit: 'crd' })

    await user.selectOptions(kind, 'A queue (whoever takes it first)')
    await user.selectOptions(screen.getByLabelText('Queue'), 'Credit desk')
    expect(director()?.assignee).toEqual({ kind: 'queue', queue: 'credit-desk' })

    await user.selectOptions(kind, 'The initiator’s supervisor')
    await user.selectOptions(screen.getByLabelText('Levels up the reporting line'), '2')
    expect(director()?.assignee).toEqual({ kind: 'supervisor', levels: 2 })

    await user.selectOptions(kind, 'The holder of a position')
    await user.type(screen.getByRole('combobox', { name: 'Position' }), 'He')
    await user.click(await screen.findByRole('option', { name: /Head of Credit/ }))
    expect(director()?.assignee).toEqual({ kind: 'position', positionId: 'p1' })

    // The initiator is only for notices: an approval never offers it.
    expect(within(kind).queryByRole('option', { name: 'The initiator' })).toBeNull()
    await expectNoA11yViolations(container)
  })

  it('sets the actions, which need a remark, and the forward limit', async () => {
    backend()
    const user = userEvent.setup()
    let body = start
    renderWithProviders(
      <Harness
        id="director"
        onBody={(b) => {
          body = b
        }}
      />,
    )
    const director = () => body.steps.find((s) => s.id === 'director')
    expect(screen.getByRole('checkbox', { name: 'Approve' })).toBeDisabled()
    await user.click(screen.getByRole('checkbox', { name: 'Forward' }))
    await user.click(screen.getByRole('checkbox', { name: 'Reject needs a remark' }))
    await user.selectOptions(screen.getByLabelText('Forwarding is allowed to'), 'The holder’s unit and below')
    expect(director()).toMatchObject({
      actions: ['APPROVE', 'RETURN', 'REJECT', 'FORWARD'],
      remarks: ['REJECT'],
      forward: { within: 'unit' },
    })
    await user.click(screen.getByRole('checkbox', { name: 'Forward' }))
    expect(director()?.forward).toBeUndefined()
  })

  it('sets a parallel step’s mode and people', async () => {
    backend()
    const user = userEvent.setup()
    let body = start
    renderWithProviders(
      <Harness
        id="committee"
        onBody={(b) => {
          body = b
        }}
      />,
    )
    const committee = () => body.steps.find((s) => s.id === 'committee')
    await user.click(screen.getByRole('button', { name: 'Add someone' }))
    await user.click(screen.getByRole('button', { name: 'Add someone' }))
    await user.selectOptions(screen.getByLabelText('Decided when'), 'A number of them have approved')
    expect(committee()).toMatchObject({ mode: 'n-of-m', n: 2, assignees: [{ kind: 'person' }, { kind: 'person' }] })
    await user.click(screen.getByRole('button', { name: 'Remove person or group 2' }))
    expect(committee()?.assignees).toHaveLength(1)
  })
})
