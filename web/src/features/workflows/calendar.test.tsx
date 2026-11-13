import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { CalendarDayResponse, CalendarResponse, DuePreviewResponse } from '@/api/generated/types.gen'
import { partForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const calendar: CalendarResponse = {
  timeZone: 'Asia/Dhaka',
  version: 2,
  defaulted: false,
  week: {
    SUNDAY: [['09:00', '17:00']],
    MONDAY: [
      ['09:00', '13:00'],
      ['14:00', '17:00'],
    ],
  },
}

function handlers(days: CalendarDayResponse[] = []) {
  return [
    sessionHandler(signedInSession(true)),
    accessHandler(['workflow.calendar.manage']),
    csrfHandler(),
    http.get(`${API_ORIGIN}/api/v1/admin/workflow/calendar`, () => HttpResponse.json<CalendarResponse>(calendar)),
    http.get(`${API_ORIGIN}/api/v1/admin/workflow/calendar/days`, () => HttpResponse.json<CalendarDayResponse[]>(days)),
  ]
}

describe('business calendar', () => {
  it('shows the weekly hours and the special days of the month, and saves the hours with If-Match', async () => {
    const user = userEvent.setup()
    const saved: { ifMatch: string | null; body: unknown }[] = []
    const today = new Date()
    const day = `${String(today.getFullYear())}-${String(today.getMonth() + 1).padStart(2, '0')}-15`
    server.use(
      ...handlers([{ day, periods: [], note: 'Victory Day' }]),
      http.put(`${API_ORIGIN}/api/v1/admin/workflow/calendar`, async ({ request }) => {
        saved.push({ ifMatch: request.headers.get('If-Match'), body: await request.json() })
        return HttpResponse.json<CalendarResponse>({ ...calendar, version: 3 })
      }),
    )
    const { container } = renderRoute('/admin/workflow/calendar')

    expect(await screen.findAllByText('Time zone: Asia/Dhaka', {}, { timeout: 5000 })).not.toHaveLength(0)
    expect(screen.getAllByText('Not worked')).toHaveLength(5)
    expect(await screen.findByText(/Victory Day/)).toBeInTheDocument()
    expect(partForPath('/admin/workflow/calendar')).toBe('admin')
    await expectNoA11yViolations(container)

    const tuesday = screen.getByText('Tuesday').closest('li') as HTMLElement
    await user.click(within(tuesday).getByRole('button', { name: 'Add hours' }))
    await user.click(screen.getByRole('button', { name: 'Save working hours' }))
    await screen.findByText('Saved.')
    expect(saved[0]?.ifMatch).toBe('"2"')
    expect((saved[0]?.body as { week: Record<string, unknown> }).week).toEqual({
      SUNDAY: [['09:00', '17:00']],
      MONDAY: [
        ['09:00', '13:00'],
        ['14:00', '17:00'],
      ],
      TUESDAY: [['09:00', '17:00']],
    })
  }, 20_000)

  it('previews when a deadline falls', async () => {
    const user = userEvent.setup()
    server.use(
      ...handlers(),
      http.post(`${API_ORIGIN}/api/v1/admin/workflow/calendar/preview`, () =>
        HttpResponse.json<DuePreviewResponse>({ due: '2026-11-15T09:00:00Z' }),
      ),
    )
    renderRoute('/admin/workflow/calendar')

    await user.click(await screen.findByRole('button', { name: 'Calculate' }, { timeout: 5000 }))
    expect(await screen.findByText(/^Due .*2026/)).toBeInTheDocument()
  })
})
