import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { MyMeetingsPage } from '@/features/meetings/MyMeetings'

const crumbs = defineMessages({
  page: { id: 'nav.myMeetings', defaultMessage: 'My meetings' },
})

export const Route = createFileRoute('/_shell/_signedIn/me/meetings')({
  component: MyMeetingsPage,
  staticData: { crumb: crumbs.page },
})
