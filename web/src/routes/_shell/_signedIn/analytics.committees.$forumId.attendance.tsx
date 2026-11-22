import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'

const crumbs = defineMessages({
  page: { id: 'nav.analyticsAttendance', defaultMessage: 'Attendance' },
})

export const Route = createFileRoute('/_shell/_signedIn/analytics/committees/$forumId/attendance')({
  staticData: { crumb: crumbs.page },
})
