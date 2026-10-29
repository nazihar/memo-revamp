import { Activity, House, LayoutTemplate, Users, type LucideIcon } from 'lucide-react'
import { defineMessages, type MessageDescriptor } from 'react-intl'
import { patternsEnabled } from './features'

export type NavGroup = 'work' | 'administration' | 'developer'

/** Who sees a menu entry; the server enforces access regardless (E03 design §4). */
export type Audience = 'everyone' | 'signedIn' | 'tenantAdmin'

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
  tenantAdmin: boolean
}

export const ANONYMOUS: Viewer = { signedIn: false, tenantAdmin: false }

const messages = defineMessages({
  home: { id: 'nav.home', defaultMessage: 'Home' },
  status: { id: 'nav.status', defaultMessage: 'Status' },
  users: { id: 'nav.users', defaultMessage: 'Users' },
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
  {
    id: 'users',
    to: '/admin/users',
    label: messages.users,
    icon: Users,
    group: 'administration',
    audience: 'tenantAdmin',
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
  if (audience === 'tenantAdmin') return viewer.tenantAdmin
  if (audience === 'signedIn') return viewer.signedIn
  return true
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
