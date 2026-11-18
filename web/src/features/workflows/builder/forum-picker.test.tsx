import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { BuilderCatalogue, EscalationTarget, FieldProblem } from '@/api/generated/types.gen'
import { API_ORIGIN, server } from '@/test/msw'
import { expectNoA11yViolations, renderWithProviders } from '@/test/render'
import type { DefinitionBody } from './definition'
import { apply } from './edit'
import { StepPanel } from './StepPanel'

function backend() {
  server.use(
    http.get(`${API_ORIGIN}/api/v1/admin/workflow-builder/catalogue`, () =>
      HttpResponse.json<BuilderCatalogue>({ subjectTypes: [], roles: [], queues: [], fragments: [], stepTypes: [] }),
    ),
    http.get(`${API_ORIGIN}/api/v1/admin/workflow/escalation-targets`, () =>
      HttpResponse.json<EscalationTarget[]>([
        { key: 'board-risk', name: 'Board Risk Committee', code: 'BRC' },
        { key: 'mcc', name: 'Management Credit Committee', code: 'MCC' },
      ]),
    ),
  )
}

const body = (forum: string): DefinitionBody => ({
  schemaVersion: 2,
  start: 'committee',
  steps: [{ id: 'committee', type: 'escalate-to-forum', name: 'Credit committee', forum, next: 'end' }],
})

function render(forum: string, problems: FieldProblem[] = []) {
  backend()
  const latest = { body: body(forum) }
  function Harness() {
    const [current, setCurrent] = useState(latest.body)
    const step = current.steps[0]
    if (!step) return null
    return (
      <StepPanel
        body={current}
        step={step}
        problems={problems}
        labels={{}}
        onLabel={() => undefined}
        onCommand={(command) => {
          const next = apply(current, command)
          latest.body = next
          setCurrent(next)
        }}
      />
    )
  }
  return { latest, ...renderWithProviders(<Harness />) }
}

describe('the escalate-to-committee step (Phase 4 E01 T5, flow L2)', () => {
  it('picks one of the active committees by name and code, and writes its key', async () => {
    const user = userEvent.setup()
    const { latest, container } = render('')
    const picker = screen.getByRole('combobox', { name: 'Committee' })
    expect(await screen.findByRole('option', { name: 'Management Credit Committee (MCC)' })).toBeInTheDocument()
    await user.selectOptions(picker, 'Management Credit Committee (MCC)')
    expect(latest.body.steps[0]?.forum).toBe('mcc')
    expect(screen.queryByText('This committee does not exist or is dissolved.')).toBeNull()
    await expectNoA11yViolations(container)
  })

  it('flags an imported key that names no active committee, on the step itself', async () => {
    render('old-credit')
    const picker = screen.getByRole('combobox', { name: 'Committee' })
    expect(await screen.findByText('This committee does not exist or is dissolved.')).toBeInTheDocument()
    expect(picker).toHaveDisplayValue('old-credit (not found)')
    expect(picker).toHaveAccessibleDescription(/This committee does not exist or is dissolved\./)
  })

  it('shows the server’s UNKNOWN_FORUM problem at the picker, not as raw text below the step', async () => {
    render('board-risk', [
      { field: '/steps/0/forum', code: 'UNKNOWN_FORUM', message: "no active committee 'board-risk'" },
    ])
    expect(await screen.findByText('This committee does not exist or is dissolved.')).toBeInTheDocument()
    expect(screen.queryByText("no active committee 'board-risk'")).toBeNull()
  })
})
