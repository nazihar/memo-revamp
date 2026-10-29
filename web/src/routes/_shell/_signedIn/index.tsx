import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { HomePage } from '@/features/home/HomePage'

const crumbs = defineMessages({
  home: { id: 'nav.home', defaultMessage: 'Home' },
})

export const Route = createFileRoute('/_shell/_signedIn/')({
  component: HomePage,
  staticData: { crumb: crumbs.home },
})
