import { useQuery } from '@tanstack/react-query'
import { listMyMeetingsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MeetingSummaryResponse } from '@/api/generated/types.gen'
import { useSession } from '@/features/auth/session'
import { OPEN } from './shared'
import { notEnded } from './time'

/** Meetings still to come or under way (not cancelled), the soonest first. */
export function upcoming(meetings: MeetingSummaryResponse[]): MeetingSummaryResponse[] {
  return meetings.filter((m) => OPEN.includes(m.status) && notEnded(m))
}

/** "My meetings" for the signed-in user: those they attend, and those of the committees they run (`GET /me/meetings`). */
export function useMyMeetings() {
  const session = useSession()
  return useQuery({ ...listMyMeetingsOptions(), enabled: session.data?.state === 'AUTHENTICATED' })
}
