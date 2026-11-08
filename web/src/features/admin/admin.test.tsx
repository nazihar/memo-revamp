import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { SecuritySettingsView } from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const security: SecuritySettingsView = {
  settings: {
    mfaRequired: 'all',
    idleMinutes: 30,
    absoluteHours: 12,
    stepUpMinutes: 5,
    passwordMinLength: 12,
    throttleAfter: 5,
    lockAfter: 20,
    lockMinutes: 30,
  },
  limits: [{ field: 'idleMinutes', min: 5, max: 480, default: 30 }],
  version: 2,
}

describe('administration overview', () => {
  it('shows only the areas the viewer may use', async () => {
    server.use(sessionHandler(signedInSession(true)), accessHandler(['tenant.settings.manage', 'audit.read']))
    const { container } = renderRoute('/admin')

    expect(await screen.findByRole('heading', { level: 1, name: 'Administration' })).toBeInTheDocument()
    // The cards' accessible names include their descriptions; the menu's links do not.
    expect(await screen.findByRole('link', { name: /Two-step sign-in, session length/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /a verifiable export/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Invite people/ })).not.toBeInTheDocument()
    await expectNoA11yViolations(container)
  })
})

describe('security settings', () => {
  it('shows the allowed range and the server’s field error', async () => {
    const user = userEvent.setup()
    server.use(
      sessionHandler(signedInSession(true)),
      accessHandler(['tenant.settings.manage']),
      http.get(`${API_ORIGIN}/api/v1/admin/security-settings`, () => HttpResponse.json(security)),
      http.put(`${API_ORIGIN}/api/v1/admin/security-settings`, () =>
        HttpResponse.json(
          {
            type: 'urn:memo:problem:validation',
            status: 400,
            errors: [{ field: 'idleMinutes', code: 'OUT_OF_RANGE', message: 'Enter a number from 5 to 480.' }],
          },
          { status: 400 },
        ),
      ),
    )
    const { container } = renderRoute('/admin/security')

    const idle = await screen.findByLabelText(/Sign out after inactivity/)
    expect(screen.getByText('5–480')).toBeInTheDocument()
    await user.clear(idle)
    await user.type(idle, '2')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Enter a number from 5 to 480.')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })
})

describe('branding', () => {
  it('reports the contrast of a colour as you type', async () => {
    const user = userEvent.setup()
    server.use(
      sessionHandler(signedInSession(true)),
      accessHandler(['tenant.settings.manage']),
      http.get(`${API_ORIGIN}/api/v1/admin/branding`, () =>
        HttpResponse.json({ organisation: 'Bank of Example', version: 0 }),
      ),
    )
    const { container } = renderRoute('/admin/branding')

    const light = await screen.findByLabelText(/Colour, light theme/)
    await user.type(light, '#ffff00')
    expect(await screen.findAllByText('Too little contrast')).not.toHaveLength(0)
    await user.clear(light)
    await user.type(light, '#1f4e8c')
    expect(screen.queryByText('Too little contrast')).not.toBeInTheDocument()
    expect(screen.getAllByText('Readable')).toHaveLength(2)
    await expectNoA11yViolations(container)
  })
})

describe('suspended organisation', () => {
  it('shows a full-page notice instead of the app', async () => {
    server.use(
      http.get(`${API_ORIGIN}/api/v1/auth/session`, () =>
        HttpResponse.json(
          { type: 'urn:memo:problem:tenant-suspended', status: 403, detail: 'This organisation is suspended.' },
          { status: 403 },
        ),
      ),
    )
    renderRoute('/')

    expect(await screen.findByRole('heading', { name: 'This organisation is suspended' })).toBeInTheDocument()
  })
})
