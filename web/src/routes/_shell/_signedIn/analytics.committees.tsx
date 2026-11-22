import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'

const crumbs = defineMessages({
  page: { id: 'nav.analytics', defaultMessage: '{Forum} analytics' },
})

/**
 * Committee analytics (Phase 4 E08 T7, flows AU–AW): the overview, a committee's detail, its attendance per member and
 * a member's meetings. The pages' code loads only when one opens (the router's automatic code splitting), and their
 * messages with it (the `analytics` catalogue part).
 */
export const Route = createFileRoute('/_shell/_signedIn/analytics/committees')({
  staticData: { crumb: crumbs.page },
})
