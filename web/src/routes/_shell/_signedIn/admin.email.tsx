import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { MailSettingsPage } from '@/features/notifications/MailSettingsPage'

const crumbs = defineMessages({
  page: { id: 'nav.email', defaultMessage: 'Email' },
})

/** The menu shows it only to holders of notification.settings.manage; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/email')({
  component: MailSettingsPage,
  staticData: { crumb: crumbs.page },
})
