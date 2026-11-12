import { defineMessages, type IntlShape } from 'react-intl'
import { isProblem } from '@/api/http'

/** Strings of the access screens: user detail, roles, classification (design E04 §13). */
export const am = defineMessages({
  userLead: { id: 'access.user.lead', defaultMessage: 'Positions, roles and clearance of this person.' },
  positions: { id: 'access.positions', defaultMessage: 'Positions' },
  positionsCaption: { id: 'access.positions.caption', defaultMessage: 'Positions held, newest first' },
  noPositions: { id: 'access.positions.none', defaultMessage: 'No positions.' },
  roles: { id: 'access.roles', defaultMessage: 'Roles' },
  rolesCaption: { id: 'access.roles.caption', defaultMessage: 'Roles given to this person' },
  noRoles: { id: 'access.roles.none', defaultMessage: 'Only the basic user role, which everyone has.' },
  wholeOrganisation: { id: 'access.wholeOrganisation', defaultMessage: 'Whole organisation' },
  grant: { id: 'access.grant', defaultMessage: 'Give a role' },
  grantTitle: { id: 'access.grantTitle', defaultMessage: 'Give a role' },
  role: { id: 'access.role', defaultMessage: 'Role' },
  scope: { id: 'access.scope', defaultMessage: 'Where' },
  scopeHint: {
    id: 'access.scope.hint',
    defaultMessage: 'Limit to one unit and the units below it, where the role allows.',
  },
  revoke: { id: 'access.revoke', defaultMessage: 'Revoke' },
  revokeTitle: { id: 'access.revokeTitle', defaultMessage: 'Revoke this role?' },
  revokeLead: { id: 'access.revokeLead', defaultMessage: 'The person loses what this role allowed at once.' },
  clearance: { id: 'access.clearance', defaultMessage: 'Clearance' },
  clearanceLead: {
    id: 'access.clearance.lead',
    defaultMessage: 'The most sensitive classification this person may open.',
  },
  saveClearance: { id: 'access.saveClearance', defaultMessage: 'Save clearance' },
  colRole: { id: 'access.col.role', defaultMessage: 'Role' },
  colScope: { id: 'access.col.scope', defaultMessage: 'Where' },
  colGranted: { id: 'access.col.granted', defaultMessage: 'Given on' },
  colActions: { id: 'access.col.actions', defaultMessage: 'Actions' },
  colPosition: { id: 'access.col.position', defaultMessage: 'Position' },
  colFrom: { id: 'access.col.from', defaultMessage: 'From' },
  colUntil: { id: 'access.col.until', defaultMessage: 'Until' },
  ongoing: { id: 'access.ongoing', defaultMessage: 'Ongoing' },
  rolesTitle: { id: 'access.rolesTitle', defaultMessage: 'Roles' },
  rolesLead: {
    id: 'access.rolesLead',
    defaultMessage: 'Roles are sets of permissions. Built-in roles are fixed; add your own for other combinations.',
  },
  allRolesCaption: { id: 'access.allRoles.caption', defaultMessage: 'Roles of the organisation' },
  newRole: { id: 'access.newRole', defaultMessage: 'New role' },
  editRole: { id: 'access.editRole', defaultMessage: 'Edit role' },
  deleteRole: { id: 'access.deleteRole', defaultMessage: 'Remove' },
  deleteRoleTitle: { id: 'access.deleteRoleTitle', defaultMessage: 'Remove this role?' },
  deleteRoleLead: { id: 'access.deleteRoleLead', defaultMessage: 'Only roles nobody holds can be removed.' },
  builtIn: { id: 'access.builtIn', defaultMessage: 'Built-in' },
  colName: { id: 'access.col.name', defaultMessage: 'Name' },
  colPermissions: { id: 'access.col.permissions', defaultMessage: 'Permissions' },
  colHolders: { id: 'access.col.holders', defaultMessage: 'Grants' },
  name: { id: 'access.name', defaultMessage: 'Name' },
  description: { id: 'access.description', defaultMessage: 'Description (optional)' },
  permissions: { id: 'access.permissions', defaultMessage: 'Permissions' },
  privileged: { id: 'access.privileged', defaultMessage: 'Administrator permission: always needs two-step sign-in.' },
  scopable: { id: 'access.scopable', defaultMessage: 'Can be limited to a unit.' },
  levelsTitle: { id: 'access.levelsTitle', defaultMessage: 'Classification' },
  levelsLead: {
    id: 'access.levelsLead',
    defaultMessage: 'Levels from least to most sensitive. People open documents up to their clearance.',
  },
  levelsCaption: { id: 'access.levels.caption', defaultMessage: 'Classification levels, least sensitive first' },
  newLevel: { id: 'access.newLevel', defaultMessage: 'Add level' },
  renameLevel: { id: 'access.renameLevel', defaultMessage: 'Rename level' },
  moveUp: { id: 'access.moveUp', defaultMessage: 'More sensitive' },
  moveDown: { id: 'access.moveDown', defaultMessage: 'Less sensitive' },
  removeLevel: { id: 'access.removeLevel', defaultMessage: 'Remove' },
  removeLevelTitle: { id: 'access.removeLevelTitle', defaultMessage: 'Remove this level?' },
  removeLevelLead: { id: 'access.removeLevelLead', defaultMessage: 'Only levels nobody is cleared to can be removed.' },
  colRank: { id: 'access.col.rank', defaultMessage: 'Order' },
  noLevels: { id: 'access.levels.none', defaultMessage: 'No levels.' },
  save: { id: 'access.save', defaultMessage: 'Save' },
  cancel: { id: 'access.cancel', defaultMessage: 'Cancel' },
  keep: { id: 'access.keep', defaultMessage: 'Keep' },
  saved: { id: 'access.saved', defaultMessage: 'Saved.' },
  failed: { id: 'access.failed', defaultMessage: 'That did not work. Try again.' },
  changed: { id: 'access.refused.changed', defaultMessage: 'Someone changed this meanwhile. It was reloaded.' },
  BUILT_IN: { id: 'access.refused.builtIn', defaultMessage: 'Built-in roles cannot change.' },
  IN_USE: { id: 'access.refused.inUse', defaultMessage: 'It is still in use.' },
  DUPLICATE_NAME: { id: 'access.refused.duplicateName', defaultMessage: 'Another one has this name.' },
  DUPLICATE: { id: 'access.refused.duplicate', defaultMessage: 'The person already holds this role there.' },
  NOT_SCOPABLE: { id: 'access.refused.notScopable', defaultMessage: 'This role cannot be limited to a unit.' },
  LAST_ADMIN: {
    id: 'access.refused.lastAdmin',
    defaultMessage: 'The organisation needs at least one active administrator.',
  },
  LAST_LEVEL: { id: 'access.refused.lastLevel', defaultMessage: 'The organisation needs at least one level.' },
  IMPLICIT: { id: 'access.refused.implicit', defaultMessage: 'Everyone holds the user role already.' },
})

/** Strings with values, typed so every placeholder gets one. */
export const amv = defineMessages<{ moveUpFor: { name: string }; moveDownFor: { name: string } }>({
  moveUpFor: { id: 'access.moveUpFor', defaultMessage: 'Make {name} more sensitive' },
  moveDownFor: { id: 'access.moveDownFor', defaultMessage: 'Make {name} less sensitive' },
})

/** Built-in role names and permission descriptions in the user's language (custom roles keep their own names). */
export const catalogue = defineMessages({
  tenant_admin: { id: 'access.role.tenantAdmin', defaultMessage: 'Organisation administrator' },
  org_admin: { id: 'access.role.orgAdmin', defaultMessage: 'Org directory administrator' },
  auditor: { id: 'access.role.auditor', defaultMessage: 'Auditor' },
  user: { id: 'access.role.user', defaultMessage: 'User' },
  'directory.read': { id: 'access.permission.directoryRead', defaultMessage: 'See the org directory' },
  'org.manage': { id: 'access.permission.orgManage', defaultMessage: 'Manage units, positions and assignments' },
  'org.legal_entities.manage': { id: 'access.permission.legalEntities', defaultMessage: 'Manage legal entities' },
  'identity.users.manage': { id: 'access.permission.users', defaultMessage: 'Manage users' },
  'authz.roles.manage': { id: 'access.permission.roles', defaultMessage: 'Manage roles and grants' },
  'authz.classifications.manage': {
    id: 'access.permission.classifications',
    defaultMessage: 'Manage classification and clearances',
  },
  'audit.read': { id: 'access.permission.auditRead', defaultMessage: 'Read the audit history' },
  'notification.settings.manage': {
    id: 'access.permission.notificationSettings',
    defaultMessage: 'Manage email delivery',
  },
  'memo.create': { id: 'access.permission.memoCreate', defaultMessage: 'Write and submit memos' },
  'memo.types.manage': { id: 'access.permission.memoTypes', defaultMessage: 'Manage memo types' },
  'workflow.tasks.reassign': {
    id: 'access.permission.workflowTasksReassign',
    defaultMessage: 'Reassign approval tasks',
  },
  'workflow.definitions.manage': {
    id: 'access.permission.workflowDefinitionsManage',
    defaultMessage: 'Write workflow definitions and submit them for review',
  },
  'workflow.definitions.approve': {
    id: 'access.permission.workflowDefinitionsApprove',
    defaultMessage: 'Review and publish workflow definitions',
  },
  'workflow.instances.cancel': {
    id: 'access.permission.workflowInstancesCancel',
    defaultMessage: 'Cancel running workflows',
  },
  'workflow.queues.manage': {
    id: 'access.permission.workflowQueuesManage',
    defaultMessage: 'Manage workflow queues',
  },
  'workflow.doa.manage': {
    id: 'access.permission.workflowDoaManage',
    defaultMessage: 'Draft the delegation of authority and submit it for review',
  },
  'workflow.doa.approve': {
    id: 'access.permission.workflowDoaApprove',
    defaultMessage: 'Review and publish the delegation of authority',
  },
})

export function catalogueLabel(intl: IntlShape, key: string, fallback?: string): string {
  return key in catalogue ? intl.formatMessage(catalogue[key as keyof typeof catalogue]) : (fallback ?? key)
}

const REASONS = [
  'BUILT_IN',
  'IN_USE',
  'DUPLICATE_NAME',
  'DUPLICATE',
  'NOT_SCOPABLE',
  'LAST_ADMIN',
  'LAST_LEVEL',
  'IMPLICIT',
] as const

export function accessProblemMessage(intl: IntlShape, error: unknown): string {
  if (!isProblem(error)) return intl.formatMessage(am.failed)
  const reason = REASONS.find((r) => r === error.reason)
  if (reason) return intl.formatMessage(am[reason])
  if (error.status === 412) return intl.formatMessage(am.changed)
  if (error.status === 400 && error.detail) return error.detail
  return intl.formatMessage(am.failed)
}
