import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { MeetingPage } from '@/features/meetings/MeetingPage'

const crumbs = defineMessages({
  page: { id: 'nav.meeting', defaultMessage: 'Meeting' },
})

/** One meeting in brief (Phase 4 E03 T6), the address notifications link to; a meeting the user may not see is not found. */
export const Route = createFileRoute('/_shell/_signedIn/meetings/$meetingId')({
  component: MeetingRoute,
  staticData: { crumb: crumbs.page },
})

function MeetingRoute() {
  const { meetingId } = Route.useParams()
  return <MeetingPage key={meetingId} meetingId={meetingId} />
}
