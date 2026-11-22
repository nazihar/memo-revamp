import { createFileRoute } from '@tanstack/react-router'
import { parseAttendanceSearch } from '@/features/analytics/analytics-params'
import { AttendancePage } from '@/features/analytics/AttendancePage'

/** Attendance per member (flow AV); the period and the sort are in the address. */
export const Route = createFileRoute('/_shell/_signedIn/analytics/committees/$forumId/attendance/')({
  validateSearch: parseAttendanceSearch,
  component: AttendanceRoute,
})

function AttendanceRoute() {
  const { forumId } = Route.useParams()
  return <AttendancePage key={forumId} forumId={forumId} search={Route.useSearch()} />
}
