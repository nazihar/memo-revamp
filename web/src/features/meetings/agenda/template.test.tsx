import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type {
  AgendaPolicy,
  ClassificationLevel,
  ForumTypeResponse,
  ForumTypeVersionResponse,
} from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute, renderWithProviders } from '@/test/render'
import { TemplateTab } from './TemplateTab'

const BASE = `${API_ORIGIN}/api/v1/admin/forum-types`

const template: AgendaPolicy = {
  chairAgreement: true,
  sections: [
    {
      title: 'Opening',
      kind: 'STANDARD',
      membersOnly: false,
      receivesSubmissions: false,
      items: [
        { title: 'Welcome and apologies', standingKind: 'APOLOGIES', purpose: 'INFORMATION', minutes: 5 },
        { title: 'Declarations of interest', standingKind: 'DECLARATIONS', purpose: 'INFORMATION', minutes: 5 },
      ],
    },
    { title: 'For decision', kind: 'STANDARD', membersOnly: false, receivesSubmissions: true, items: [] },
    { title: 'Consent items', kind: 'CONSENT', membersOnly: false, receivesSubmissions: false, items: [] },
  ],
}

function Harness({ onChange }: { onChange: (agenda: AgendaPolicy) => void }) {
  const [agenda, setAgenda] = useState(template)
  return (
    <TemplateTab
      agenda={agenda}
      onChange={(next) => {
        setAgenda(next)
        onChange(next)
      }}
    />
  )
}

describe('the committee type’s Agenda tab (Phase 4 E03 T7, flow R)', () => {
  it('edits the chair’s agreement, the sections and the standing items, with a Move menu', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn<(agenda: AgendaPolicy) => void>()
    const { container } = renderWithProviders(<Harness onChange={onChange} />)
    const latest = () => onChange.mock.lastCall?.[0]

    await user.click(screen.getByRole('checkbox', { name: /The chair agrees the agenda/ }))
    expect(latest()?.chairAgreement).toBe(false)

    const consent = screen.getByRole('listitem', { name: 'Consent items' })
    await user.click(within(consent).getByRole('checkbox', { name: 'New items land here' }))
    expect(latest()?.sections?.map((s) => s.receivesSubmissions)).toEqual([false, false, true])

    await user.click(screen.getByRole('button', { name: 'Actions for the section Opening' }))
    expect(screen.getByRole('menuitem', { name: 'Move section up' })).toHaveAttribute('aria-disabled', 'true')
    await user.click(screen.getByRole('menuitem', { name: 'Move section down' }))
    expect(latest()?.sections?.map((s) => s.title)).toEqual(['For decision', 'Opening', 'Consent items'])

    await user.click(screen.getByRole('button', { name: 'Actions for Declarations of interest' }))
    await user.click(screen.getByRole('menuitem', { name: 'Move up' }))
    expect(latest()?.sections?.[1]?.items?.map((i) => i.title)).toEqual([
      'Declarations of interest',
      'Welcome and apologies',
    ])

    await user.click(screen.getByRole('button', { name: 'Add standing item to For decision' }))
    expect(latest()?.sections?.[0]?.items).toEqual([
      { title: 'Minutes of the last meeting', standingKind: 'PREVIOUS_MINUTES', purpose: 'INFORMATION', minutes: 5 },
    ])
    const decide = screen.getByRole('list', { name: 'Standing items of For decision' })
    await user.selectOptions(within(decide).getByRole('combobox', { name: 'Purpose' }), 'DECISION')
    expect(latest()?.sections?.[0]?.items?.[0]?.purpose).toBe('DECISION')
    // A standing kind is on an agenda at most once.
    expect(within(decide).getByRole('option', { name: 'Welcome and apologies' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Add section' }))
    expect(latest()?.sections).toHaveLength(4)
    await user.click(screen.getByRole('button', { name: 'Actions for the section Section 4' }))
    await user.click(screen.getByRole('menuitem', { name: 'Remove section' }))
    expect(latest()?.sections).toHaveLength(3)
    await expectNoA11yViolations(container)
  })

  it('reads the template on a version that cannot change', () => {
    renderWithProviders(<TemplateTab agenda={template} />)
    expect(screen.getByText('The chair agrees the agenda before it is first published.')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Consent items' })).toHaveTextContent('Consent section')
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('is a tab of the committee type, saved with the rest of the version', async () => {
    const user = userEvent.setup()
    const version: ForumTypeVersionResponse = {
      number: 3,
      state: 'DRAFT',
      revision: 5,
      name: 'Board committee',
      memberAttributes: [],
      quorum: { counts: 'VOTING', basis: 'IN_OFFICE', threshold: { kind: 'MAJORITY' }, minimum: 2, required: [] },
      outcomes: [{ kind: 'APPROVED', label: 'Approved', resolution: true }],
      termPolicy: { reminderDays: [] },
      submissionPolicy: { deadlineWorkingDays: 5, lateMode: 'CHAIR_APPROVAL', triageWorkingDays: 5 },
      agenda: template,
      meetings: { invitationTitle: 'NAME', replyDeadlineWorkingDays: 2, replyReminder: true, meetingReminder: true },
      minutes: {
        approval: 'AT_NEXT_MEETING',
        circulationWorkingDays: 5,
        draftWorkingDays: 10,
        memoResumes: 'AT_MEETING_END',
        resolutionNumbering: {
          parts: [{ kind: 'COMMITTEE_CODE' }, { kind: 'SEQUENCE', digits: 3 }],
          reset: 'NEVER',
          fyStartMonth: 7,
        },
      },
      createdAt: '2026-11-18T08:00:00Z',
    }
    const bodies: { agenda?: AgendaPolicy }[] = []
    server.use(
      sessionHandler(signedInSession(true)),
      accessHandler(['forum.types.manage']),
      csrfHandler(),
      http.get(`${BASE}/:id`, () =>
        HttpResponse.json<ForumTypeResponse>({
          id: 't1',
          code: 'BC',
          name: 'Board committee',
          active: true,
          openVersion: 3,
          openState: 'DRAFT',
        }),
      ),
      http.get(`${BASE}/:id/versions`, () => HttpResponse.json([version])),
      http.get(`${BASE}/:id/versions/:number`, () => HttpResponse.json(version)),
      http.get(`${API_ORIGIN}/api/v1/authz/classification-levels`, () =>
        HttpResponse.json<ClassificationLevel[]>([{ id: 'l0', name: 'Internal', rank: 0, version: 0 }]),
      ),
      http.put(`${BASE}/t1/versions/3`, async ({ request }) => {
        bodies.push((await request.json()) as { agenda?: AgendaPolicy })
        return HttpResponse.json(version)
      }),
    )
    renderRoute('/admin/committee-types/t1?version=3')

    await user.click(await screen.findByRole('tab', { name: 'Agenda' }, { timeout: 5000 }))
    await user.click(screen.getByRole('checkbox', { name: /The chair agrees the agenda/ }))
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => {
      expect(bodies[0]?.agenda).toEqual({ ...template, chairAgreement: false })
    })
  })
})
