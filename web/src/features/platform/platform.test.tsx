import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { PlatformSession, PlatformTenant, PlatformTenantPage } from '@/api/generated/types.gen'
import { API_ORIGIN, platformSessionHandler, server } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const operator: PlatformSession = {
  state: 'AUTHENTICATED',
  operator: { id: 'op-1', email: 'owner@memo.example', displayName: 'Owner' },
}

const acme: PlatformTenant = {
  id: 't-1',
  slug: 'acme',
  name: 'Acme Bank',
  status: 'ACTIVE',
  version: 3,
  createdAt: '2026-11-01T09:00:00Z',
  hasAdmin: false,
}

const platformCsrf = http.get(`${API_ORIGIN}/api/v1/platform/auth/csrf`, () => {
  document.cookie = 'XSRF-TOKEN=test-csrf-token; path=/'
  return new HttpResponse(null, { status: 204 })
})

describe('operator sign-in', () => {
  it('asks for the code after the password, then lands on the organisations', async () => {
    const user = userEvent.setup()
    let session: PlatformSession = { state: 'ANONYMOUS' }
    server.use(
      platformCsrf,
      http.get(`${API_ORIGIN}/api/v1/platform/auth/session`, () => HttpResponse.json(session)),
      http.post(`${API_ORIGIN}/api/v1/platform/auth/login`, () => {
        session = { state: 'MFA_REQUIRED', operator: operator.operator }
        return HttpResponse.json(session)
      }),
      http.post(`${API_ORIGIN}/api/v1/platform/auth/mfa`, () => {
        session = operator
        return HttpResponse.json(session)
      }),
      http.get(`${API_ORIGIN}/api/v1/platform/tenants`, () => HttpResponse.json<PlatformTenantPage>({ items: [acme] })),
    )
    const { container } = renderRoute('/platform/login')

    await user.type(await screen.findByLabelText(/Email/), 'owner@memo.example')
    await user.type(screen.getByLabelText(/Password/), 'a long operator password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    await user.type(await screen.findByLabelText(/Code/), '123456')
    await expectNoA11yViolations(container)
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    expect(await screen.findByRole('link', { name: 'Acme Bank' }, { timeout: 5000 })).toBeInTheDocument()
  })
})

describe('organisation page', () => {
  it('confirms suspension with the effects spelled out and sends the version', async () => {
    const user = userEvent.setup()
    const sent: (string | null)[] = []
    server.use(
      platformCsrf,
      platformSessionHandler(operator),
      http.get(`${API_ORIGIN}/api/v1/platform/tenants/:id`, () => HttpResponse.json(acme)),
      http.post(`${API_ORIGIN}/api/v1/platform/tenants/:id/suspend`, ({ request }) => {
        sent.push(request.headers.get('If-Match'))
        return HttpResponse.json({ ...acme, status: 'SUSPENDED', version: 4 })
      }),
    )
    const { container } = renderRoute('/platform/tenants/t-1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Acme Bank' })).toBeInTheDocument()
    expect(screen.getByText('No administrator yet')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Invite the first administrator' })).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('button', { name: 'Suspend' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Suspend this organisation?' })
    expect(within(dialog).getByText(/Nobody can sign in/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Suspend' }))

    await screen.findByText('Organisation suspended.')
    expect(sent).toEqual(['"3"'])
  })
})
