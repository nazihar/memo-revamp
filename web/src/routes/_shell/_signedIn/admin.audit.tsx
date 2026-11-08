import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { AuditPage } from '@/features/admin/AuditPage'

const crumbs = defineMessages({
  page: { id: 'nav.audit', defaultMessage: 'Audit' },
})

/** The menu shows it only to those who may use it; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/audit')({
  component: AuditPage,
  staticData: { crumb: crumbs.page },
})
