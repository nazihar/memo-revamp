import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type {
  AdminUser,
  ClassificationLevel,
  Grant,
  OrgUnit,
  OrgUserPosition,
  PermissionInfo,
  Role,
  UserAccess,
} from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const permissions: PermissionInfo[] = [
  { key: 'directory.read', scopable: false, privileged: false },
  { key: 'org.manage', scopable: true, privileged: true },
  { key: 'audit.read', scopable: false, privileged: true },
]
const roles: Role[] = [
  {
    id: 'r-admin',
    builtIn: 'tenant_admin',
    name: 'x',
    permissions: ['audit.read', 'org.manage'],
    grants: 1,
    version: 0,
  },
  {
    id: 'r-org',
    builtIn: 'org_admin',
    name: 'x',
    permissions: ['directory.read', 'org.manage'],
    grants: 0,
    version: 0,
  },
  { id: 'r-custom', name: 'Branch auditor', permissions: ['audit.read'], grants: 0, version: 2 },
]
const level: ClassificationLevel = { id: 'l0', rank: 0, name: 'Public', version: 0 }
const north: OrgUnit = {
  id: 'n',
  legalEntityId: 'e',
  code: 'N',
  name: 'North Region',
  validFrom: '2026-01-01',
  version: 0,
}

function adminBackend() {
  server.use(
    sessionHandler(signedInSession(true)),
    accessHandler(['identity.users.manage', 'authz.roles.manage', 'authz.classifications.manage']),
    http.get(`${API_ORIGIN}/api/v1/authz/permissions`, () => HttpResponse.json<PermissionInfo[]>(permissions)),
    http.get(`${API_ORIGIN}/api/v1/authz/roles`, () => HttpResponse.json<Role[]>(roles)),
    http.get(`${API_ORIGIN}/api/v1/authz/classification-levels`, () =>
      HttpResponse.json<ClassificationLevel[]>([level]),
    ),
    http.get(`${API_ORIGIN}/api/v1/org/units`, () => HttpResponse.json<OrgUnit[]>([north])),
  )
}

describe('roles', () => {
  it('lists built-in roles in the user’s language, read-only, and custom roles with actions', async () => {
    adminBackend()
    const { container } = renderRoute('/admin/roles')

    const table = await screen.findByRole('table', { name: 'Roles of the organisation' }, { timeout: 5000 })
    const adminRow = (await within(table).findByText('Organisation administrator')).closest('tr')
    const customRow = within(table).getByText('Branch auditor').closest('tr')
    expect(adminRow && within(adminRow).queryByRole('button', { name: 'Edit role' })).toBeNull()
    expect(customRow && within(customRow).getByRole('button', { name: 'Edit role' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })
})

describe('user access', () => {
  it('gives a role limited to a unit after the step-up the server asks for', async () => {
    const user = userEvent.setup()
    adminBackend()
    const target: AdminUser = {
      id: 'u1',
      email: 'omar@bank.example',
      displayName: 'Omar Haddad',
      status: 'ACTIVE',
      tenantAdmin: false,
      mfaEnrolled: true,
      version: 1,
    }
    const grants: Grant[] = []
    let steppedUp = false
    server.use(
      http.get(`${API_ORIGIN}/api/v1/admin/users/u1`, () => HttpResponse.json<AdminUser>(target)),
      http.get(`${API_ORIGIN}/api/v1/org/users/u1/assignments`, () => HttpResponse.json<OrgUserPosition[]>([])),
      http.get(`${API_ORIGIN}/api/v1/authz/users/u1/access`, () =>
        HttpResponse.json<UserAccess>({ grants, clearance: level }),
      ),
      http.post(`${API_ORIGIN}/api/v1/authz/users/u1/grants`, async ({ request }) => {
        if (!steppedUp) {
          return HttpResponse.json(
            { type: 'urn:memo:problem:step-up-required', status: 401, stepUpWindowSeconds: 300 },
            { status: 401 },
          )
        }
        const body = (await request.json()) as { roleId: string; scopeUnitId?: string }
        const grant: Grant = {
          id: 'g1',
          roleId: body.roleId,
          roleName: 'x',
          builtIn: 'org_admin',
          ...(body.scopeUnitId ? { scopeUnitId: body.scopeUnitId } : {}),
          grantedAt: '2026-11-02T08:00:00Z',
        }
        grants.push(grant)
        return HttpResponse.json<Grant>(grant, { status: 201 })
      }),
      http.post(`${API_ORIGIN}/api/v1/auth/step-up`, () => {
        steppedUp = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderRoute('/admin/users/u1')

    await user.click(await screen.findByRole('button', { name: 'Give a role' }, { timeout: 5000 }))
    const dialog = await screen.findByRole('dialog', { name: 'Give a role' })
    const where = within(dialog).getByLabelText('Where')
    expect(where).toBeDisabled()
    await user.selectOptions(within(dialog).getByLabelText(/^Role/), 'Org directory administrator')
    expect(where).toBeEnabled()
    await user.selectOptions(where, 'North Region')
    await user.click(within(dialog).getByRole('button', { name: 'Give a role' }))

    const stepUp = await screen.findByRole('dialog', { name: 'Confirm it is you' })
    await user.type(within(stepUp).getByLabelText('Code'), '123456')
    await user.click(within(stepUp).getByRole('button', { name: 'Confirm' }))

    const table = await screen.findByRole('table', { name: 'Roles given to this person' })
    expect(await within(table).findByText('North Region')).toBeInTheDocument()
    expect(grants[0]?.scopeUnitId).toBe('n')
  })
})
