import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { ForumsPage } from '@/features/forums/ForumsPage'

const crumbs = defineMessages({
  page: { id: 'nav.forums', defaultMessage: '{Forums}' },
})

/** Every signed-in user sees the committees they sit on or may read; the API decides which (ADR-0056). */
export const Route = createFileRoute('/_shell/_signedIn/committees/')({
  component: ForumsPage,
  staticData: { crumb: crumbs.page },
})
