import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { CalendarPage } from '@/features/workflows/CalendarPage'

const crumbs = defineMessages({
  page: { id: 'nav.calendar', defaultMessage: 'Business calendar' },
})

/** The menu shows it to holders of `workflow.calendar.manage`; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/workflow/calendar')({
  component: CalendarPage,
  staticData: { crumb: crumbs.page },
})
