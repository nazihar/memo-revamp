import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { SecuritySettingsPage } from '@/features/admin/SecuritySettingsPage'

const crumbs = defineMessages({
  page: { id: 'nav.security', defaultMessage: 'Security' },
})

/** The menu shows it only to those who may use it; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/security')({
  component: SecuritySettingsPage,
  staticData: { crumb: crumbs.page },
})
