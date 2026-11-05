import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { MailSettingsView, NotificationPage, PreferenceView } from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, server, sessionHandler, signedInSession, unreadHandler } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const page: NotificationPage = {
  items: [
    {
      id: 'n1',
      kind: 'ROLE_GRANTED',
      category: 'ADMINISTRATION',
      params: { role: 'Auditor' },
      createdAt: '2026-11-05T09:00:00Z',
      read: false,
    },
    {
      id: 'n2',
      kind: 'SIGN_IN_ALERT',
      category: 'SECURITY',
      params: { device: 'Firefox on Windows' },
      createdAt: '2026-11-04T09:00:00Z',
      read: true,
    },
  ],
}

const preferences: PreferenceView[] = [
  { category: 'SECURITY', channel: 'IN_APP', enabled: true, mandatory: true },
  { category: 'SECURITY', channel: 'EMAIL', enabled: true, mandatory: true },
  { category: 'ADMINISTRATION', channel: 'IN_APP', enabled: true, mandatory: false },
  { category: 'ADMINISTRATION', channel: 'EMAIL', enabled: true, mandatory: false },
]

describe('notification bell', () => {
  it('names the unread count for screen readers and caps the badge at 9+', async () => {
    server.use(sessionHandler(signedInSession()), unreadHandler(12))
    renderRoute('/')

    const bell = await screen.findByRole('link', { name: 'Notifications, 12 unread' }, { timeout: 5000 })
    expect(bell).toHaveAttribute('href', '/notifications')
    expect(within(bell).getByText('9+')).toBeInTheDocument()
  })
})

describe('notifications page', () => {
  it('lists my notifications, marks unread ones, and marks one read when I open it', async () => {
    const user = userEvent.setup()
    const read: string[] = []
    server.use(
      sessionHandler(signedInSession()),
      http.get(`${API_ORIGIN}/api/v1/me/notifications`, () => HttpResponse.json<NotificationPage>(page)),
      http.post(`${API_ORIGIN}/api/v1/me/notifications/:id/read`, ({ params }) => {
        read.push(String(params.id))
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const { container } = renderRoute('/notifications')

    const unread = await screen.findByRole(
      'button',
      { name: /^Unread: You were given the role Auditor/ },
      { timeout: 5000 },
    )
    expect(
      screen.getByRole('button', { name: /^New sign-in to your account from Firefox on Windows/ }),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(unread)
    expect(read).toEqual(['n1'])
  })
})

describe('notification settings', () => {
  it('keeps security notices on and saves other choices', async () => {
    const user = userEvent.setup()
    const saved: unknown[] = []
    server.use(
      sessionHandler(signedInSession()),
      http.get(`${API_ORIGIN}/api/v1/me/notification-preferences`, () =>
        HttpResponse.json<PreferenceView[]>(preferences),
      ),
      http.put(`${API_ORIGIN}/api/v1/me/notification-preferences`, async ({ request }) => {
        saved.push(await request.json())
        return HttpResponse.json<PreferenceView[]>(
          preferences.map((p) =>
            p.category === 'ADMINISTRATION' && p.channel === 'EMAIL' ? { ...p, enabled: false } : p,
          ),
        )
      }),
    )
    const { container } = renderRoute('/me/notifications')

    const securityEmail = await screen.findByRole('checkbox', { name: 'Security: Email' }, { timeout: 5000 })
    expect(securityEmail).toBeDisabled()
    expect(securityEmail).toBeChecked()
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('checkbox', { name: 'Access and administration: Email' }))
    expect(saved).toEqual([[{ category: 'ADMINISTRATION', channel: 'EMAIL', enabled: false }]])
  })
})

describe('email settings', () => {
  it('shows the stored relay without its password and saves changes with the version it saw', async () => {
    const user = userEvent.setup()
    const relay: MailSettingsView = {
      host: 'smtp.bank.example',
      port: 587,
      tlsMode: 'STARTTLS',
      username: 'memo',
      passwordSet: true,
      fromAddress: 'board@bank.example',
      version: 3,
    }
    let sent: { ifMatch: string | null; body: unknown } | undefined
    server.use(
      sessionHandler(signedInSession(true)),
      accessHandler(['notification.settings.manage']),
      http.get(`${API_ORIGIN}/api/v1/admin/mail-settings`, () => HttpResponse.json<MailSettingsView>(relay)),
      http.get(`${API_ORIGIN}/api/v1/admin/mail-log`, () => HttpResponse.json({ entries: [] })),
      http.put(`${API_ORIGIN}/api/v1/admin/mail-settings`, async ({ request }) => {
        sent = { ifMatch: request.headers.get('If-Match'), body: await request.json() }
        return HttpResponse.json<MailSettingsView>({ ...relay, version: 4 })
      }),
    )
    const { container } = renderRoute('/admin/email')

    const host = await screen.findByLabelText('Mail server', {}, { timeout: 5000 })
    expect(host).toHaveValue('smtp.bank.example')
    expect(screen.getByLabelText(/Password/)).toHaveValue('')
    expect(screen.getByText('A password is stored. Leave empty to keep it.')).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.clear(host)
    await user.type(host, 'mail.bank.example')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(sent?.ifMatch).toBe('"3"')
    expect(sent?.body).toMatchObject({ host: 'mail.bank.example', port: 587, tlsMode: 'STARTTLS' })
    expect(sent?.body).not.toHaveProperty('password')
  })
})
