import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { BuilderCatalogue, OrgUnit } from '@/api/generated/types.gen'
import { API_ORIGIN, server } from '@/test/msw'
import { expectNoA11yViolations, renderWithProviders } from '@/test/render'
import type { DefinitionBody } from './definition'
import { apply } from './edit'
import { StepPanel } from './StepPanel'

const catalogue: BuilderCatalogue = {
  subjectTypes: [
    {
      type: 'memo',
      attributes: [
        { name: 'classificationRank', type: 'int' },
        { name: 'memoTypeId', type: 'string' },
      ],
    },
  ],
  roles: [],
  queues: [],
  fragments: [],
  stepTypes: [],
}

function backend() {
  server.use(
    http.get(`${API_ORIGIN}/api/v1/admin/workflow-builder/catalogue`, () =>
      HttpResponse.json<BuilderCatalogue>(catalogue),
    ),
    http.get(`${API_ORIGIN}/api/v1/org/units`, () => HttpResponse.json<OrgUnit[]>([])),
  )
}

const start: DefinitionBody = {
  schemaVersion: 2,
  start: 'size',
  steps: [
    {
      id: 'size',
      type: 'branch',
      name: 'By classification',
      branches: [
        { when: 'item.classificationRank >= 2', next: 'director' },
        { when: 'size(item.tags) > 0', next: 'tell' },
      ],
      otherwise: 'director',
    },
    { id: 'director', type: 'approve', name: 'Director', next: 'tell' },
    { id: 'tell', type: 'notify', name: 'Tell', recipients: [{ kind: 'initiator' }], message: '', next: 'end' },
  ],
}

function Harness({ id, onBody }: { id: string; onBody: (body: DefinitionBody) => void }) {
  const [body, setBody] = useState(start)
  const step = body.steps.find((s) => s.id === id)
  if (!step) return null
  return (
    <StepPanel
      body={body}
      step={step}
      problems={[]}
      labels={{}}
      onLabel={() => undefined}
      onCommand={(command) => {
        const next = apply(body, command)
        setBody(next)
        onBody(next)
      }}
    />
  )
}

function render(id: string) {
  backend()
  const latest = { body: start }
  const result = renderWithProviders(
    <Harness
      id={id}
      onBody={(b) => {
        latest.body = b
      }}
    />,
  )
  return { latest, ...result }
}

describe('step panels: when and where', () => {
  it('shows a simple condition as rows, writes CEL and declares the attribute; other CEL is kept as written', async () => {
    const user = userEvent.setup()
    const { latest, container } = render('size')
    const branch = () => latest.body.steps.find((s) => s.id === 'size')

    const attribute = (await screen.findAllByLabelText('Attribute'))[0] as HTMLSelectElement
    expect(attribute.value).toBe('classificationRank')
    expect(screen.getAllByLabelText('Comparison')[0]).toHaveDisplayValue('is at least')
    const value = screen.getAllByLabelText('Value')[0] as HTMLInputElement
    await user.clear(value)
    await user.type(value, '3')
    expect(branch()?.branches?.[0]?.when).toBe('item.classificationRank >= 3')
    expect(latest.body.attributes).toEqual([{ name: 'classificationRank', type: 'int' }])

    await user.click(screen.getByRole('button', { name: 'Add a comparison' }))
    await user.selectOptions(screen.getByLabelText('Holds when'), 'any of these is true')
    expect(branch()?.branches?.[0]?.when).toBe('item.classificationRank >= 3 || item.classificationRank == 0')

    // The second condition is not one the simple form can read: it stays CEL, untouched.
    const cel = screen.getByLabelText('Condition (CEL)')
    expect(cel).toHaveValue('size(item.tags) > 0')
    expect(
      screen.getByText('The simple form cannot show this condition; it is kept exactly as written.'),
    ).toBeInTheDocument()
    expect(branch()?.branches?.[1]?.when).toBe('size(item.tags) > 0')
    await expectNoA11yViolations(container)
  })

  it('sets a deadline: due time, working time, reminders and escalation', async () => {
    const user = userEvent.setup()
    const { latest } = render('director')
    const sla = () => latest.body.steps.find((s) => s.id === 'director')?.sla

    await user.click(screen.getByRole('checkbox', { name: 'This step has a deadline' }))
    expect(sla()).toEqual({ due: 'P1D' })
    await user.selectOptions(screen.getAllByLabelText('Unit')[0]!, 'hours')
    expect(sla()?.due).toBe('PT1H')
    await user.click(screen.getByRole('checkbox', { name: 'Count working time only (the business calendar)' }))
    expect(sla()?.workingTime).toBe(false)
    await user.click(screen.getByRole('checkbox', { name: 'Remind the holder before it is due' }))
    expect(sla()?.remind).toEqual({ before: 'PT4H' })
    await user.click(screen.getByRole('button', { name: 'Add a reminder' }))
    expect(sla()?.remind).toEqual({ before: 'PT4H', after: ['P1D'] })
    await user.selectOptions(screen.getByLabelText('When it is late'), 'Move the task to someone')
    expect(sla()?.escalate).toEqual({ after: 'P1D', action: 'reassign' })
    expect(screen.getByRole('group', { name: 'Move it to' })).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: 'This step has a deadline' }))
    expect(sla()).toBeUndefined()
  })

  it('sets whom a notice tells and its message key', async () => {
    const user = userEvent.setup()
    const { latest } = render('tell')
    const tell = () => latest.body.steps.find((s) => s.id === 'tell')
    expect(screen.getByRole('group', { name: 'Tell 1' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Add someone to tell' }))
    await user.type(screen.getByLabelText('Message'), 'creditDecided')
    expect(tell()).toMatchObject({
      recipients: [{ kind: 'initiator' }, { kind: 'initiator' }],
      message: 'creditDecided',
    })
  })
})
