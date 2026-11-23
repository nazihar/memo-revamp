import {
  Activity,
  Bookmark,
  CalendarClock,
  ChartColumn,
  ChartNoAxesColumn,
  FileClock,
  Gavel,
  House,
  Inbox,
  ListChecks,
  ListTodo,
  Scale,
  ScrollText,
  UserRoundCog,
  Workflow,
  FileStack,
  FileInput,
  Hash,
  Landmark,
  Languages,
  LayoutTemplate,
  LockKeyhole,
  Mail,
  Network,
  Palette,
  Settings,
  ShieldCheck,
  Tags,
  Users,
  UsersRound,
  type LucideIcon,
} from 'lucide-react'
import { defineMessages, type MessageDescriptor } from 'react-intl'
import { patternsEnabled } from './features'

export type NavGroup = 'work' | 'administration' | 'developer'

/**
 * Who sees a menu entry: everyone, any signed-in user, holders of a permission (ADR-0027), or people with a committee
 * seat in office and holders of a permission (`seatOrAnyOf`). The server decides.
 */
export type Audience =
  'everyone' | 'signedIn' | { permission: string } | { anyOf: string[] } | { seatOrAnyOf: string[] }

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
  /** A secretary, chair, vice-chair or member seat in office on a committee (`GET /me/forums`). */
  committeeSeat?: boolean
}

export const ANONYMOUS: Viewer = { signedIn: false, permissions: [] }

const messages = defineMessages({
  home: { id: 'nav.home', defaultMessage: 'Home' },
  inbox: { id: 'nav.inbox', defaultMessage: 'Inbox' },
  savedSearches: { id: 'nav.savedSearches', defaultMessage: 'Saved searches' },
  dashboard: { id: 'nav.dashboard', defaultMessage: 'Dashboard' },
  analytics: { id: 'nav.analytics', defaultMessage: '{Forum} analytics' },
  workflowTasks: { id: 'nav.workflowTasks', defaultMessage: 'Open tasks' },
  memoTypes: { id: 'nav.memoTypes', defaultMessage: 'Memo types' },
  memoNumbering: { id: 'nav.memoNumbering', defaultMessage: 'Memo numbering' },
  // Takes the organisation's terms (ADR-0032): the menu, breadcrumbs and overview pass them to every label.
  forumTypes: { id: 'nav.forumTypes', defaultMessage: '{Forum} types' },
  forums: { id: 'nav.forums', defaultMessage: '{Forums}' },
  decisions: { id: 'nav.decisions', defaultMessage: 'Decisions' },
  declarations: { id: 'nav.declarations', defaultMessage: 'Declarations of interest' },
  workflows: { id: 'nav.workflows', defaultMessage: 'Workflows' },
  doa: { id: 'nav.doa', defaultMessage: 'Delegation of authority' },
  queues: { id: 'nav.queues', defaultMessage: 'Queues' },
  distributionLists: { id: 'nav.distributionLists', defaultMessage: 'Distribution lists' },
  substitutions: { id: 'nav.substitutions', defaultMessage: 'Substitutions' },
  calendar: { id: 'nav.calendar', defaultMessage: 'Business calendar' },
  status: { id: 'nav.status', defaultMessage: 'Status' },
  users: { id: 'nav.users', defaultMessage: 'Users' },
  org: { id: 'nav.org', defaultMessage: 'Organisation' },
  roles: { id: 'nav.roles', defaultMessage: 'Roles' },
  classifications: { id: 'nav.classifications', defaultMessage: 'Classification' },
  email: { id: 'nav.email', defaultMessage: 'Email' },
  admin: { id: 'nav.admin', defaultMessage: 'Overview' },
  security: { id: 'nav.security', defaultMessage: 'Security' },
  branding: { id: 'nav.branding', defaultMessage: 'Branding' },
  language: { id: 'nav.language', defaultMessage: 'Language and terms' },
  audit: { id: 'nav.audit', defaultMessage: 'Audit' },
  imports: { id: 'nav.imports', defaultMessage: 'Import' },
  patterns: { id: 'nav.patterns', defaultMessage: 'Patterns' },
})

export const groupLabels = defineMessages({
  work: { id: 'nav.group.work', defaultMessage: 'Work' },
  administration: { id: 'nav.group.administration', defaultMessage: 'Administration' },
  developer: { id: 'nav.group.developer', defaultMessage: 'Developer' },
})

/** Any of these makes someone an administrator for the menu: they see the administration overview. */
export const ADMIN_PERMISSIONS = [
  'identity.users.manage',
  'authz.roles.manage',
  'authz.classifications.manage',
  'notification.settings.manage',
  'tenant.settings.manage',
  'audit.read',
  'memo.types.manage',
  'forum.types.manage',
  'workflow.tasks.reassign',
  'workflow.definitions.manage',
  'workflow.definitions.approve',
  'memo.import',
]

export const navigation: NavItem[] = [
  { id: 'home', to: '/', label: messages.home, icon: House, group: 'work', audience: 'signedIn', enabled: true },
  { id: 'inbox', to: '/inbox', label: messages.inbox, icon: Inbox, group: 'work', audience: 'signedIn', enabled: true },
  {
    id: 'savedSearches',
    to: '/search/saved',
    label: messages.savedSearches,
    icon: Bookmark,
    group: 'work',
    audience: 'signedIn',
    enabled: true,
  },
  {
    id: 'dashboard',
    to: '/dashboards/approvals',
    label: messages.dashboard,
    icon: ChartColumn,
    group: 'work',
    audience: 'signedIn',
    enabled: true,
  },
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
    id: 'forums',
    to: '/committees',
    label: messages.forums,
    icon: UsersRound,
    group: 'work',
    audience: 'signedIn',
    enabled: true,
  },
  {
    // The decision register (Phase 4 E08 T6): members and officers in office, and oversight (design E08 §1.3).
    id: 'decisions',
    to: '/decisions',
    label: messages.decisions,
    icon: Gavel,
    group: 'work',
    audience: { seatOrAnyOf: ['forum.read', 'forums.manage'] },
    enabled: true,
  },
  {
    // The register across committees (Phase 4 E04 T8): oversight access; secretaries and chairs reach it from a meeting.
    id: 'declarations',
    to: '/declarations',
    label: messages.declarations,
    icon: ScrollText,
    group: 'work',
    audience: { anyOf: ['forum.read', 'forums.manage'] },
    enabled: true,
  },
  {
    // Committee analytics (Phase 4 E08 T7): members and officers in office, and oversight (design E08 §2.3).
    id: 'analytics',
    to: '/analytics/committees',
    label: messages.analytics,
    icon: ChartNoAxesColumn,
    group: 'work',
    audience: { seatOrAnyOf: ['forum.read', 'forums.manage'] },
    enabled: true,
  },
  { id: 'org', to: '/org', label: messages.org, icon: Network, group: 'work', audience: 'signedIn', enabled: true },
  {
    id: 'admin',
    to: '/admin',
    label: messages.admin,
    icon: Settings,
    group: 'administration',
    audience: { anyOf: ADMIN_PERMISSIONS },
    enabled: true,
  },
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
    id: 'security',
    to: '/admin/security',
    label: messages.security,
    icon: LockKeyhole,
    group: 'administration',
    audience: { permission: 'tenant.settings.manage' },
    enabled: true,
  },
  {
    id: 'branding',
    to: '/admin/branding',
    label: messages.branding,
    icon: Palette,
    group: 'administration',
    audience: { permission: 'tenant.settings.manage' },
    enabled: true,
  },
  {
    id: 'language',
    to: '/admin/language',
    label: messages.language,
    icon: Languages,
    group: 'administration',
    audience: { permission: 'tenant.settings.manage' },
    enabled: true,
  },
  {
    id: 'workflowTasks',
    to: '/admin/workflow/tasks',
    label: messages.workflowTasks,
    icon: ListChecks,
    group: 'administration',
    audience: { permission: 'workflow.tasks.reassign' },
    enabled: true,
  },
  {
    id: 'workflows',
    to: '/admin/workflows',
    label: messages.workflows,
    icon: Workflow,
    group: 'administration',
    audience: { anyOf: ['workflow.definitions.manage', 'workflow.definitions.approve'] },
    enabled: true,
  },
  {
    id: 'doa',
    to: '/admin/workflow/doa',
    label: messages.doa,
    icon: Scale,
    group: 'administration',
    audience: { anyOf: ['workflow.doa.manage', 'workflow.doa.approve'] },
    enabled: true,
  },
  {
    id: 'queues',
    to: '/admin/workflow/queues',
    label: messages.queues,
    icon: ListTodo,
    group: 'administration',
    audience: { permission: 'workflow.queues.manage' },
    enabled: true,
  },
  {
    id: 'substitutions',
    to: '/admin/workflow/substitutions',
    label: messages.substitutions,
    icon: UserRoundCog,
    group: 'administration',
    audience: { permission: 'workflow.tasks.reassign' },
    enabled: true,
  },
  {
    id: 'calendar',
    to: '/admin/workflow/calendar',
    label: messages.calendar,
    icon: CalendarClock,
    group: 'administration',
    audience: { permission: 'workflow.calendar.manage' },
    enabled: true,
  },
  {
    id: 'memoTypes',
    to: '/admin/memo-types',
    label: messages.memoTypes,
    icon: FileStack,
    group: 'administration',
    audience: { anyOf: ['memo.types.manage', 'memo.types.approve'] },
    enabled: true,
  },
  {
    id: 'forumTypes',
    to: '/admin/committee-types',
    label: messages.forumTypes,
    icon: Landmark,
    group: 'administration',
    audience: { anyOf: ['forum.types.manage', 'forum.types.approve'] },
    enabled: true,
  },
  {
    id: 'distributionLists',
    to: '/admin/distribution-lists',
    label: messages.distributionLists,
    icon: Users,
    group: 'administration',
    audience: { permission: 'memo.distribution.manage' },
    enabled: true,
  },
  {
    id: 'memoNumbering',
    to: '/admin/memo-numbering',
    label: messages.memoNumbering,
    icon: Hash,
    group: 'administration',
    audience: { anyOf: ['memo.types.manage', 'memo.types.approve', 'audit.read'] },
    enabled: true,
  },
  {
    id: 'imports',
    to: '/admin/imports',
    label: messages.imports,
    icon: FileInput,
    group: 'administration',
    audience: { permission: 'memo.import' },
    enabled: true,
  },
  {
    id: 'audit',
    to: '/admin/audit',
    label: messages.audit,
    icon: FileClock,
    group: 'administration',
    audience: { permission: 'audit.read' },
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
  if ('seatOrAnyOf' in audience) {
    return (
      viewer.signedIn &&
      (viewer.committeeSeat === true || audience.seatOrAnyOf.some((p) => viewer.permissions.includes(p)))
    )
  }
  if ('anyOf' in audience) return viewer.signedIn && audience.anyOf.some((p) => viewer.permissions.includes(p))
  return viewer.signedIn && viewer.permissions.includes(audience.permission)
}

/** The enabled administration entries the viewer may see, for the overview page. */
export function adminItems(viewer: Viewer): NavItem[] {
  return navigation.filter(
    (i) => i.enabled && i.group === 'administration' && i.id !== 'admin' && visibleTo(viewer, i.audience),
  )
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
