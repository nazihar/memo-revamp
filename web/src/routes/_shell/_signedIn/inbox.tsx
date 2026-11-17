import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { parseInboxSearch } from '@/features/inbox/inbox-params'
import { InboxPage } from '@/features/memo/InboxPage'

const crumbs = defineMessages({
  page: { id: 'nav.inbox', defaultMessage: 'Inbox' },
})

/**
 * My tasks and my memos (design E01 §14), the memos I follow and the team view (E05 §4–§5). The tab, the order of my
 * tasks and the team filters are in the address, so they survive a reload and a link.
 */
export const Route = createFileRoute('/_shell/_signedIn/inbox')({
  validateSearch: parseInboxSearch,
  component: InboxRoute,
  staticData: { crumb: crumbs.page },
})

function InboxRoute() {
  return <InboxPage search={Route.useSearch()} />
}
