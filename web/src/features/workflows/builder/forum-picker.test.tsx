import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { BuilderCatalogue, EscalationTarget, FieldProblem } from '@/api/generated/types.gen'
import { API_ORIGIN, server } from '@/test/msw'
import { expectNoA11yViolations, renderWithProviders } from '@/test/render'
import type { DefinitionBody } from './definition'
import { apply, newStep } from './edit'
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

const body = (forum: string, wait?: boolean): DefinitionBody => ({
  schemaVersion: 2,
  start: 'committee',
  steps: [
    {
      id: 'committee',
      type: 'escalate-to-forum',
      name: 'Credit committee',
      forum,
      ...(wait === undefined ? {} : { wait }),
      next: 'end',
    },
  ],
})

function render(forum: string, problems: FieldProblem[] = [], wait?: boolean) {
  backend()
  const latest = { body: body(forum, wait) }
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

describe('waiting for the committee’s decision (Phase 4 E02 T4, flow M4)', () => {
  it('starts a new committee step waiting', () => {
    expect(newStep({ schemaVersion: 2, start: 'end', steps: [] }, 'escalate-to-forum', 'Credit committee')).toEqual({
      id: 'forum',
      type: 'escalate-to-forum',
      name: 'Credit committee',
      forum: '',
      wait: true,
    })
  })

  it('switches between waiting and sending for information, saying what each does', async () => {
    const user = userEvent.setup()
    const { latest, container } = render('mcc', [], true)
    const wait = screen.getByRole('switch', { name: 'Wait for the committee’s decision' })
    expect(wait).toBeChecked()
    expect(wait).toHaveAccessibleDescription(
      'On: the memo waits here until the committee decides; nobody else acts on it meanwhile.',
    )
    await user.click(wait)
    expect(latest.body.steps[0]?.wait).toBe(false)
    expect(wait).not.toBeChecked()
    expect(wait).toHaveAccessibleDescription(
      'Off: the memo is sent to the committee for information and its route continues.',
    )
    await user.click(wait)
    expect(latest.body.steps[0]?.wait).toBe(true)
    await expectNoA11yViolations(container)
  })

  it('reads a step saved before E02 (no wait) as sent for information', () => {
    render('mcc')
    expect(screen.getByRole('switch', { name: 'Wait for the committee’s decision' })).not.toBeChecked()
  })
})
