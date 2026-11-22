import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'

const crumbs = defineMessages({
  page: { id: 'nav.analyticsCommittee', defaultMessage: '{Forum}' },
})

/** One committee's analytics: its detail, attendance per member and a member's meetings. */
export const Route = createFileRoute('/_shell/_signedIn/analytics/committees/$forumId')({
  staticData: { crumb: crumbs.page },
})
