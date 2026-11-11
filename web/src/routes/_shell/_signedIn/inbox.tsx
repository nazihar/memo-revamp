import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { InboxPage, type InboxTab } from '@/features/memo/InboxPage'

const crumbs = defineMessages({
  page: { id: 'nav.inbox', defaultMessage: 'Inbox' },
})

const TABS: InboxTab[] = ['tasks', 'drafts', 'sent', 'closed']

/** My tasks and my memos (design E01 §14); the tab is in the address, so it survives a reload and a link. */
export const Route = createFileRoute('/_shell/_signedIn/inbox')({
  validateSearch: (search: Record<string, unknown>): { tab?: InboxTab } =>
    TABS.includes(search.tab as InboxTab) ? { tab: search.tab as InboxTab } : {},
  component: InboxRoute,
  staticData: { crumb: crumbs.page },
})

function InboxRoute() {
  const { tab } = Route.useSearch()
  return <InboxPage tab={tab ?? 'tasks'} />
}
