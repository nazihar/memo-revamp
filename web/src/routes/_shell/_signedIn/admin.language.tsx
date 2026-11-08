import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { LanguagePage } from '@/features/admin/LanguagePage'

const crumbs = defineMessages({
  page: { id: 'nav.language', defaultMessage: 'Language and terms' },
})

/** The menu shows it only to those who may use it; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/language')({
  component: LanguagePage,
  staticData: { crumb: crumbs.page },
})
