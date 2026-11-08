import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { BrandingPage } from '@/features/admin/BrandingPage'

const crumbs = defineMessages({
  page: { id: 'nav.branding', defaultMessage: 'Branding' },
})

/** The menu shows it only to those who may use it; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/branding')({
  component: BrandingPage,
  staticData: { crumb: crumbs.page },
})
