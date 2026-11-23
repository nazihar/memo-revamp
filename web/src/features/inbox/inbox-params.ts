/**
 * The inbox's tabs; Team shows only to people with a team view (design E05 §5), My actions only to people with actions
 * (Phase 4 E07 flow AK).
 */
export type InboxTab = 'tasks' | 'actions' | 'drafts' | 'sent' | 'closed' | 'watching' | 'team'

export const INBOX_TABS: readonly InboxTab[] = ['tasks', 'actions', 'drafts', 'sent', 'closed', 'watching', 'team']

/** My actions' lists (`list=verify`); To do when absent. */
const ACTION_LISTS = ['verify', 'involved'] as const

/**
 * What the inbox's address keeps (`/inbox?tab=…`): the tab, My tasks' order (`sort=due`; by assignment when absent) and
 * the Team tab's unit and status, so a reload, a link and the dashboard's "View list" land on the same list.
 */
export interface InboxSearch {
  tab?: InboxTab
  sort?: 'due'
  unit?: string
  status?: 'overdue'
  /** My actions' list: To verify or I'm involved; To do when absent. */
  list?: (typeof ACTION_LISTS)[number]
}

const ID = /^[\w-]{1,64}$/

export function parseInboxSearch(search: Record<string, unknown>): InboxSearch {
  const tab = INBOX_TABS.find((t) => t === search.tab)
  return {
    ...(tab ? { tab } : {}),
    ...(search.sort === 'due' ? { sort: 'due' as const } : {}),
    ...(typeof search.unit === 'string' && ID.test(search.unit) ? { unit: search.unit } : {}),
    ...(search.status === 'overdue' ? { status: 'overdue' as const } : {}),
    ...(tab === 'actions' ? listOf(search.list) : {}),
  }
}

function listOf(value: unknown): Pick<InboxSearch, 'list'> {
  const list = ACTION_LISTS.find((l) => l === value)
  return list ? { list } : {}
}
