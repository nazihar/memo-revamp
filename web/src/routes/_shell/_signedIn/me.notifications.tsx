import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { NotificationPreferencesPage } from '@/features/notifications/NotificationPreferencesPage'

const crumbs = defineMessages({
  page: { id: 'nav.notificationSettings', defaultMessage: 'Notification settings' },
})

export const Route = createFileRoute('/_shell/_signedIn/me/notifications')({
  component: NotificationPreferencesPage,
  staticData: { crumb: crumbs.page },
})
