import { useQuery } from '@tanstack/react-query'
import { listMyMeetingsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MeetingResponse, MeetingSummaryResponse } from '@/api/generated/types.gen'
import { useSession } from '@/features/auth/session'

// This module is on the home page, which is in the first page's script (NFR P5): it imports no messages, and `shared.ts`
// and `time.ts` re-export what it defines.

/** States before the meeting is held: the Upcoming list. */
export const OPEN: MeetingResponse['status'][] = ['DRAFT', 'SCHEDULED', 'IN_PROGRESS']

/** Whether [m] has not ended yet. */
export function notEnded(m: Pick<MeetingSummaryResponse, 'endsAt'>, now = Date.now()): boolean {
  return new Date(m.endsAt).getTime() >= now
}

/** Meetings still to come or under way (not cancelled), the soonest first. */
export function upcoming(meetings: MeetingSummaryResponse[]): MeetingSummaryResponse[] {
  return meetings.filter((m) => OPEN.includes(m.status) && notEnded(m))
}

/** "My meetings" for the signed-in user: those they attend, and those of the committees they run (`GET /me/meetings`). */
export function useMyMeetings() {
  const session = useSession()
  return useQuery({ ...listMyMeetingsOptions(), enabled: session.data?.state === 'AUTHENTICATED' })
}
