import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { NotificationsPage } from '@/features/notifications/NotificationsPage'

const crumbs = defineMessages({
  page: { id: 'nav.notifications', defaultMessage: 'Notifications' },
})

export const Route = createFileRoute('/_shell/_signedIn/notifications')({
  component: NotificationsPage,
  staticData: { crumb: crumbs.page },
})
