import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { MeetingPage } from '@/features/meetings/MeetingPage'
import { meetingSearch } from '@/features/meetings/page/search'

const crumbs = defineMessages({
  page: { id: 'nav.meeting', defaultMessage: 'Meeting' },
})

/**
 * The meeting page (Phase 4 E03 T6, T8 flow P), the address notifications link to; a meeting the user may not see is not
 * found. An open paper has its own address (`?paper=&revision=`), so the browser's Back closes the viewer.
 */
export const Route = createFileRoute('/_shell/_signedIn/meetings/$meetingId')({
  validateSearch: meetingSearch,
  component: MeetingRoute,
  staticData: { crumb: crumbs.page },
})

function MeetingRoute() {
  const { meetingId } = Route.useParams()
  return <MeetingPage key={meetingId} meetingId={meetingId} />
}
