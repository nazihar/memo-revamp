import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { AdminUser, AdminUserPage, Me, Session } from '@/api/generated/types.gen'
import { API_ORIGIN, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const mfaRequired: Session = { state: 'MFA_REQUIRED', organisation: 'Bank of Example' }

/** The session endpoint follows the sign-in: each step changes what it answers. */
function sessionSequence(initial: Session) {
  let current = initial
  server.use(http.get(`${API_ORIGIN}/api/v1/auth/session`, () => HttpResponse.json<Session>(current)))
  return (next: Session) => {
    current = next
  }
}

describe('sign-in', () => {
  it('sends an anonymous visitor to sign-in and back, with the CSRF token on the request', async () => {
    const user = userEvent.setup()
    const setSession = sessionSequence({ state: 'ANONYMOUS', organisation: 'Bank of Example' })
    const csrfHeaders: (string | null)[] = []
    server.use(
      http.post(`${API_ORIGIN}/api/v1/auth/login`, ({ request }) => {
        csrfHeaders.push(request.headers.get('X-XSRF-TOKEN'))
        setSession(signedInSession())
        return HttpResponse.json<Session>(signedInSession())
      }),
    )
    const { container } = renderRoute('/')

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByText('Bank of Example')).toBeInTheDocument()
    await expectNoA11yViolations(container)
    await user.type(screen.getByLabelText('Email'), 'nadia@bank.example')
    await user.type(screen.getByLabelText('Password'), 'violet harbour lantern sixteen')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Welcome, Nadia Karim' })).toBeInTheDocument()
    expect(csrfHeaders).toEqual(['test-csrf-token'])
  })

  it('says the same thing for every wrong-credentials answer and clears the password', async () => {
    const user = userEvent.setup()
    sessionSequence({ state: 'ANONYMOUS', organisation: 'Bank of Example' })
    server.use(
      http.post(`${API_ORIGIN}/api/v1/auth/login`, () =>
        HttpResponse.json({ type: 'urn:memo:problem:invalid-credentials', status: 401, title: 'x' }, { status: 401 }),
      ),
    )
    renderRoute('/login')

    await user.type(await screen.findByLabelText('Email'), 'nadia@bank.example')
    await user.type(screen.getByLabelText('Password'), 'wrong password here')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The email or password is incorrect, or the account is temporarily locked.',
    )
    expect(screen.getByLabelText('Password')).toHaveValue('')
  })

  it('continues with a code when the session needs one, and explains a wrong code', async () => {
    const user = userEvent.setup()
    const setSession = sessionSequence(mfaRequired)
    let attempts = 0
    server.use(
      http.post(`${API_ORIGIN}/api/v1/auth/mfa`, async ({ request }) => {
        attempts += 1
        const body = (await request.json()) as { code: string }
        if (body.code !== '123456') {
          return HttpResponse.json({ type: 'urn:memo:problem:invalid-credentials', status: 401 }, { status: 401 })
        }
        setSession(signedInSession())
        return HttpResponse.json<Session>(signedInSession())
      }),
    )
    renderRoute('/login?next=/me/security')

    expect(await screen.findByRole('heading', { level: 1, name: 'Enter your code' })).toBeInTheDocument()
    await user.type(screen.getByLabelText('Code'), '000000')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('That code is not valid.')

    await user.click(screen.getByRole('button', { name: 'Use a recovery code instead' }))
    expect(screen.getByLabelText('Recovery code')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Use the code from my app' }))
    await user.type(screen.getByLabelText('Code'), '123456')
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'My security' })).toBeInTheDocument()
    expect(attempts).toBe(2)
  })

  it('keeps people with a pending enrolment out of the app', async () => {
    sessionSequence({ state: 'MFA_ENROLMENT_REQUIRED', organisation: 'Bank of Example' })
    server.use(
      http.post(`${API_ORIGIN}/api/v1/auth/mfa/enrolment`, () =>
        HttpResponse.json({
          secret: 'JBSWY3DPEHPK3PXP',
          otpauthUri: 'otpauth://totp/x',
          qrSvg: '<svg xmlns="http://www.w3.org/2000/svg"/>',
        }),
      ),
    )
    renderRoute('/')

    expect(await screen.findByRole('heading', { level: 1, name: 'Set up two-step sign-in' })).toBeInTheDocument()
    expect(await screen.findByText('JBSW Y3DP EHPK 3PXP')).toBeInTheDocument()
  })
})

const nadia: AdminUser = {
  id: '01900000-0000-7000-8000-0000000000bb',
  email: 'omar@bank.example',
  displayName: 'Omar Haddad',
  status: 'ACTIVE',
  tenantAdmin: false,
  mfaEnrolled: true,
  version: 3,
}

function adminBackend() {
  server.use(
    sessionHandler(signedInSession(true)),
    http.get(`${API_ORIGIN}/api/v1/me`, () =>
      HttpResponse.json<Me>({
        id: '01900000-0000-7000-8000-0000000000aa',
        email: 'nadia@bank.example',
        displayName: 'Nadia Karim',
        tenantAdmin: true,
        mfaEnrolled: true,
      }),
    ),
    http.get(`${API_ORIGIN}/api/v1/admin/users`, () => HttpResponse.json<AdminUserPage>({ items: [nadia] })),
  )
}

describe('user administration', () => {
  it('asks for a step-up when the server wants one, then retries the action once with If-Match', async () => {
    const user = userEvent.setup()
    adminBackend()
    const ifMatch: (string | null)[] = []
    let steppedUp = false
    server.use(
      http.post(`${API_ORIGIN}/api/v1/admin/users/:id/deactivate`, ({ request }) => {
        ifMatch.push(request.headers.get('If-Match'))
        if (!steppedUp) {
          return HttpResponse.json(
            { type: 'urn:memo:problem:step-up-required', status: 401, stepUpWindowSeconds: 300 },
            { status: 401 },
          )
        }
        return HttpResponse.json<AdminUser>({ ...nadia, status: 'DEACTIVATED', version: 4 })
      }),
      http.post(`${API_ORIGIN}/api/v1/auth/step-up`, () => {
        steppedUp = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderRoute('/admin/users')

    await user.click(await screen.findByRole('button', { name: 'Actions for Omar Haddad' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Deactivate' }))
    const confirm = await screen.findByRole('alertdialog', { name: 'Deactivate Omar Haddad?' })
    await user.click(within(confirm).getByRole('button', { name: 'Deactivate' }))

    const stepUp = await screen.findByRole('dialog', { name: 'Confirm it is you' })
    await user.type(within(stepUp).getByLabelText('Code'), '123456')
    await user.click(within(stepUp).getByRole('button', { name: 'Confirm' }))

    expect(await screen.findByText('Done.')).toBeInTheDocument()
    expect(ifMatch).toEqual(['"3"', '"3"'])
  })

  it('invites with an Idempotency-Key and shows a taken email on its field', async () => {
    const user = userEvent.setup()
    adminBackend()
    const keys: (string | null)[] = []
    server.use(
      http.post(`${API_ORIGIN}/api/v1/admin/users`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return HttpResponse.json(
          {
            type: 'urn:memo:problem:validation',
            status: 409,
            errors: [{ field: 'email', code: 'TAKEN', message: 'x' }],
          },
          { status: 409 },
        )
      }),
    )
    renderRoute('/admin/users')

    await user.click(await screen.findByRole('button', { name: 'Invite user' }))
    const dialog = await screen.findByRole('dialog', { name: 'Invite a user' })
    await user.type(within(dialog).getByLabelText('Work email'), 'omar@bank.example')
    await user.type(within(dialog).getByLabelText('Full name'), 'Omar Haddad')
    await user.click(within(dialog).getByRole('button', { name: 'Send invitation' }))

    expect(await within(dialog).findByText('An account with this email already exists.')).toBeInTheDocument()
    await waitFor(() => {
      expect(keys).toHaveLength(1)
    })
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
  })
})
