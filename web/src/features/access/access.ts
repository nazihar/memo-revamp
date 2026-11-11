import { useQuery } from '@tanstack/react-query'
import { getMyAccessOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MyAccess } from '@/api/generated/types.gen'

/** Permission keys of the catalogue the web client asks about (ADR-0027); the server decides regardless. */
export const PERMISSION = {
  directoryRead: 'directory.read',
  orgManage: 'org.manage',
  legalEntitiesManage: 'org.legal_entities.manage',
  usersManage: 'identity.users.manage',
  rolesManage: 'authz.roles.manage',
  classificationsManage: 'authz.classifications.manage',
  auditRead: 'audit.read',
  notificationSettings: 'notification.settings.manage',
  memoCreate: 'memo.create',
  memoTypesManage: 'memo.types.manage',
  workflowTasksReassign: 'workflow.tasks.reassign',
} as const

/** What the signed-in user may do (`GET /me/access`), for menus and buttons only. */
export function useMyAccess(enabled = true) {
  return useQuery({ ...getMyAccessOptions(), enabled, staleTime: 30_000 })
}

/** Whether [access] holds [permission] anywhere (tenant-wide or for some unit). */
export function holds(access: MyAccess | undefined, permission: string): boolean {
  return access?.permissions.some((p) => p.key === permission) ?? false
}
