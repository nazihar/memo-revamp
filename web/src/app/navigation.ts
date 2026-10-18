import { Activity, LayoutTemplate, type LucideIcon } from 'lucide-react'
import { defineMessages, type MessageDescriptor } from 'react-intl'
import { patternsEnabled } from './features'

export type NavGroup = 'work' | 'administration' | 'developer'

export interface NavItem {
  id: string
  to: string
  label: MessageDescriptor
  icon: LucideIcon
  group: NavGroup
  /** Screens join the menu as their epic ships (E01 design §2). */
  enabled: boolean
}

const messages = defineMessages({
  status: { id: 'nav.status', defaultMessage: 'Status' },
  patterns: { id: 'nav.patterns', defaultMessage: 'Patterns' },
})

export const groupLabels = defineMessages({
  work: { id: 'nav.group.work', defaultMessage: 'Work' },
  administration: { id: 'nav.group.administration', defaultMessage: 'Administration' },
  developer: { id: 'nav.group.developer', defaultMessage: 'Developer' },
})

export const navigation: NavItem[] = [
  { id: 'status', to: '/', label: messages.status, icon: Activity, group: 'work', enabled: true },
  {
    id: 'patterns',
    to: '/patterns',
    label: messages.patterns,
    icon: LayoutTemplate,
    group: 'developer',
    enabled: patternsEnabled,
  },
]

/** Enabled items grouped in display order; empty groups are left out. */
export function navigationGroups(items: NavItem[] = navigation): { group: NavGroup; items: NavItem[] }[] {
  const order: NavGroup[] = ['work', 'administration', 'developer']
  return order
    .map((group) => ({ group, items: items.filter((i) => i.enabled && i.group === group) }))
    .filter((g) => g.items.length > 0)
}
