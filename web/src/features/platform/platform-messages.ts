import { defineMessages } from 'react-intl'
import type { PlatformTenant } from '@/api/generated/types.gen'

/** Operator console texts (design E07 §7). Operators see registry data only; no text here names tenant content. */
export const pm = defineMessages({
  product: { id: 'platform.product', defaultMessage: 'memo' },
  console: { id: 'platform.console', defaultMessage: 'Platform administration' },
  navTenants: { id: 'platform.nav.tenants', defaultMessage: 'Organisations' },
  navAudit: { id: 'platform.nav.audit', defaultMessage: 'Platform audit' },
  signOut: { id: 'platform.signOut', defaultMessage: 'Sign out' },
  notServed: {
    id: 'platform.notServed',
    defaultMessage: 'The platform console is not available at this address.',
  },

  loginTitle: { id: 'platform.login.title', defaultMessage: 'Operator sign-in' },
  loginLead: {
    id: 'platform.login.lead',
    defaultMessage: 'Platform operators only. Two-step sign-in is always required.',
  },
  email: { id: 'platform.login.email', defaultMessage: 'Email' },
  password: { id: 'platform.login.password', defaultMessage: 'Password' },
  signIn: { id: 'platform.login.submit', defaultMessage: 'Sign in' },
  invalid: {
    id: 'platform.login.invalid',
    defaultMessage: 'The email or password is incorrect, or the account is locked for a while.',
  },
  tooMany: { id: 'platform.login.tooMany', defaultMessage: 'Too many attempts. Wait a few minutes and try again.' },
  failed: { id: 'platform.login.failed', defaultMessage: 'That did not work. Try again.' },
  codeTitle: { id: 'platform.code.title', defaultMessage: 'Enter your code' },
  codeLead: { id: 'platform.code.lead', defaultMessage: 'Enter the 6-digit code from your authenticator app.' },
  code: { id: 'platform.code', defaultMessage: 'Code' },
  continue: { id: 'platform.code.submit', defaultMessage: 'Continue' },
  codeInvalid: { id: 'platform.code.invalid', defaultMessage: 'That code is not valid. Try the next one.' },
  enrolTitle: { id: 'platform.enrol.title', defaultMessage: 'Set up two-step sign-in' },
  enrolLead: {
    id: 'platform.enrol.lead',
    defaultMessage: 'Scan the code with an authenticator app, or type the key, then enter the 6-digit code it shows.',
  },
  enrolQr: { id: 'platform.enrol.qr', defaultMessage: 'QR code for your authenticator app' },
  enrolKey: { id: 'platform.enrol.key', defaultMessage: 'Key' },

  linkTitle: { id: 'platform.link.title', defaultMessage: 'Choose your password' },
  linkInvalid: {
    id: 'platform.link.invalid',
    defaultMessage: 'This link is no longer valid. Ask for a new one to be generated on the server.',
  },
  linkPassword: { id: 'platform.link.password', defaultMessage: 'New password (at least 15 characters)' },
  linkSubmit: { id: 'platform.link.submit', defaultMessage: 'Set password' },
  linkWeak: {
    id: 'platform.link.weak',
    defaultMessage:
      'Choose a longer password that is not on a list of breached passwords and does not contain your name.',
  },

  tenantsTitle: { id: 'platform.tenants.title', defaultMessage: 'Organisations' },
  tenantsLead: {
    id: 'platform.tenants.lead',
    defaultMessage: 'Each organisation has its own address and data. You see its registry entry only.',
  },
  tenantsSearch: { id: 'platform.tenants.search', defaultMessage: 'Search by name or address' },
  tenantsEmpty: { id: 'platform.tenants.empty', defaultMessage: 'No organisations match.' },
  tenantsCaption: { id: 'platform.tenants.caption', defaultMessage: 'Organisations, by address' },
  newTenant: { id: 'platform.tenants.new', defaultMessage: 'New organisation' },
  colName: { id: 'platform.col.name', defaultMessage: 'Name' },
  colSlug: { id: 'platform.col.slug', defaultMessage: 'Address' },
  colStatus: { id: 'platform.col.status', defaultMessage: 'Status' },
  colCreated: { id: 'platform.col.created', defaultMessage: 'Created' },
  loadMore: { id: 'platform.loadMore', defaultMessage: 'Load more' },

  newTitle: { id: 'platform.new.title', defaultMessage: 'New organisation' },
  newLead: {
    id: 'platform.new.lead',
    defaultMessage: 'Creates the organisation and emails its first administrator an invitation.',
  },
  name: { id: 'platform.new.name', defaultMessage: 'Name' },
  slug: { id: 'platform.new.slug', defaultMessage: 'Address' },
  slugHint: {
    id: 'platform.new.slug.hint',
    defaultMessage:
      '3 to 63 lower-case letters, digits and hyphens. Becomes the start of the organisation’s web address.',
  },
  locale: { id: 'platform.new.locale', defaultMessage: 'Default language' },
  timeZone: { id: 'platform.new.timeZone', defaultMessage: 'Time zone' },
  adminName: { id: 'platform.new.adminName', defaultMessage: 'First administrator’s name' },
  adminEmail: { id: 'platform.new.adminEmail', defaultMessage: 'First administrator’s email' },
  create: { id: 'platform.new.create', defaultMessage: 'Create and invite' },
  cancel: { id: 'platform.cancel', defaultMessage: 'Cancel' },
  created: { id: 'platform.new.created', defaultMessage: 'Organisation created. The invitation is on its way.' },
  invalidForm: { id: 'platform.new.invalid', defaultMessage: 'Check the highlighted fields.' },

  statusActive: { id: 'platform.status.active', defaultMessage: 'Active' },
  statusSuspended: { id: 'platform.status.suspended', defaultMessage: 'Suspended' },
  admin: { id: 'platform.tenant.admin', defaultMessage: 'Administrator' },
  adminYes: { id: 'platform.tenant.admin.yes', defaultMessage: 'Has an active administrator' },
  adminNo: { id: 'platform.tenant.admin.no', defaultMessage: 'No administrator yet' },
  rename: { id: 'platform.tenant.rename', defaultMessage: 'Rename' },
  renamed: { id: 'platform.tenant.renamed', defaultMessage: 'Name saved.' },
  suspend: { id: 'platform.tenant.suspend', defaultMessage: 'Suspend' },
  resume: { id: 'platform.tenant.resume', defaultMessage: 'Resume' },
  suspendTitle: { id: 'platform.tenant.suspend.title', defaultMessage: 'Suspend this organisation?' },
  suspendEffects: {
    id: 'platform.tenant.suspend.effects',
    defaultMessage:
      'Nobody can sign in, everyone signed in is signed out, and its background work waits until you resume it. Its data stays as it is.',
  },
  suspended: { id: 'platform.tenant.suspended', defaultMessage: 'Organisation suspended.' },
  resumed: { id: 'platform.tenant.resumed', defaultMessage: 'Organisation resumed.' },
  inviteTitle: { id: 'platform.tenant.invite.title', defaultMessage: 'Invite the first administrator' },
  inviteLead: {
    id: 'platform.tenant.invite.lead',
    defaultMessage: 'Only possible while the organisation has no active administrator.',
  },
  invite: { id: 'platform.tenant.invite', defaultMessage: 'Send invitation' },
  invited: { id: 'platform.tenant.invited', defaultMessage: 'Invitation sent.' },
  hasAdmin: { id: 'platform.tenant.hasAdmin', defaultMessage: 'The organisation already has an active administrator.' },
  stale: { id: 'platform.stale', defaultMessage: 'Someone changed this meanwhile. The page shows the current state.' },
  createdAt: { id: 'platform.tenant.createdAt', defaultMessage: 'Created' },
  statusChangedAt: { id: 'platform.tenant.statusChangedAt', defaultMessage: 'Status changed' },
  back: { id: 'platform.back', defaultMessage: 'All organisations' },

  auditTitle: { id: 'platform.audit.title', defaultMessage: 'Platform audit' },
  auditLead: {
    id: 'platform.audit.lead',
    defaultMessage:
      'What operators and platform jobs did, newest first. Organisations also see actions that concern them.',
  },
  auditCaption: { id: 'platform.audit.caption', defaultMessage: 'Platform audit entries, newest first' },
  colTime: { id: 'platform.col.time', defaultMessage: 'Time' },
  colAction: { id: 'platform.col.action', defaultMessage: 'Action' },
  colActor: { id: 'platform.col.actor', defaultMessage: 'Actor' },
  colObject: { id: 'platform.col.object', defaultMessage: 'Object' },
  auditFilter: { id: 'platform.audit.filter', defaultMessage: 'Action starts with' },
})

/** Texts with values. */
export const pmv = defineMessages<{
  linkLead: { name: string; email: string }
}>({
  linkLead: { id: 'platform.link.lead', defaultMessage: 'Operator account for {name} ({email}).' },
})

export function statusMessage(status: PlatformTenant['status']) {
  return status === 'SUSPENDED' ? pm.statusSuspended : pm.statusActive
}
