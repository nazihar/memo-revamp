import { Activity, House, LayoutTemplate, Mail, Network, ShieldCheck, Tags, Users, type LucideIcon } from 'lucide-react'
import { defineMessages, type MessageDescriptor } from 'react-intl'
import { patternsEnabled } from './features'

export type NavGroup = 'work' | 'administration' | 'developer'

/** Who sees a menu entry: everyone, any signed-in user, or holders of a permission (ADR-0027). The server decides. */
export type Audience = 'everyone' | 'signedIn' | { permission: string }

export interface NavItem {
  id: string
  to: string
  label: MessageDescriptor
  icon: LucideIcon
  group: NavGroup
  audience: Audience
  /** Screens join the menu as their epic ships (E01 design §2). */
  enabled: boolean
}

/** What the current viewer may see. */
export interface Viewer {
  signedIn: boolean
  /** Permission keys held anywhere (`GET /me/access`). */
  permissions: string[]
}

export const ANONYMOUS: Viewer = { signedIn: false, permissions: [] }

const messages = defineMessages({
  home: { id: 'nav.home', defaultMessage: 'Home' },
  status: { id: 'nav.status', defaultMessage: 'Status' },
  users: { id: 'nav.users', defaultMessage: 'Users' },
  org: { id: 'nav.org', defaultMessage: 'Organisation' },
  roles: { id: 'nav.roles', defaultMessage: 'Roles' },
  classifications: { id: 'nav.classifications', defaultMessage: 'Classification' },
  email: { id: 'nav.email', defaultMessage: 'Email' },
  patterns: { id: 'nav.patterns', defaultMessage: 'Patterns' },
})

export const groupLabels = defineMessages({
  work: { id: 'nav.group.work', defaultMessage: 'Work' },
  administration: { id: 'nav.group.administration', defaultMessage: 'Administration' },
  developer: { id: 'nav.group.developer', defaultMessage: 'Developer' },
})

export const navigation: NavItem[] = [
  { id: 'home', to: '/', label: messages.home, icon: House, group: 'work', audience: 'signedIn', enabled: true },
  {
    id: 'status',
    to: '/status',
    label: messages.status,
    icon: Activity,
    group: 'work',
    audience: 'everyone',
    enabled: true,
  },
  { id: 'org', to: '/org', label: messages.org, icon: Network, group: 'work', audience: 'signedIn', enabled: true },
  {
    id: 'users',
    to: '/admin/users',
    label: messages.users,
    icon: Users,
    group: 'administration',
    audience: { permission: 'identity.users.manage' },
    enabled: true,
  },
  {
    id: 'roles',
    to: '/admin/roles',
    label: messages.roles,
    icon: ShieldCheck,
    group: 'administration',
    audience: { permission: 'authz.roles.manage' },
    enabled: true,
  },
  {
    id: 'classifications',
    to: '/admin/classifications',
    label: messages.classifications,
    icon: Tags,
    group: 'administration',
    audience: { permission: 'authz.classifications.manage' },
    enabled: true,
  },
  {
    id: 'email',
    to: '/admin/email',
    label: messages.email,
    icon: Mail,
    group: 'administration',
    audience: { permission: 'notification.settings.manage' },
    enabled: true,
  },
  {
    id: 'patterns',
    to: '/patterns',
    label: messages.patterns,
    icon: LayoutTemplate,
    group: 'developer',
    audience: 'everyone',
    enabled: patternsEnabled,
  },
]

function visibleTo(viewer: Viewer, audience: Audience): boolean {
  if (audience === 'everyone') return true
  if (audience === 'signedIn') return viewer.signedIn
  return viewer.signedIn && viewer.permissions.includes(audience.permission)
}

/** Enabled items the viewer may see, grouped in display order; empty groups are left out. */
export function navigationGroups(
  viewer: Viewer = ANONYMOUS,
  items: NavItem[] = navigation,
): { group: NavGroup; items: NavItem[] }[] {
  const order: NavGroup[] = ['work', 'administration', 'developer']
  return order
    .map((group) => ({
      group,
      items: items.filter((i) => i.enabled && i.group === group && visibleTo(viewer, i.audience)),
    }))
    .filter((g) => g.items.length > 0)
}
