import { useQuery } from '@tanstack/react-query'
import { listMyForumsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MeetingResponse } from '@/api/generated/types.gen'

/**
 * Whether the viewer holds the committee's chair or vice-chair seat without being one of the meeting's officers: a
 * vice-chair while there is a chair. They read the meeting's declarations and may be the one to rule (E04 §1.2).
 */
export function useChairSeat(meeting: MeetingResponse | undefined): boolean {
  const ask = meeting !== undefined && meeting.relation !== 'OFFICER' && meeting.relation !== 'READER'
  const mine = useQuery({ ...listMyForumsOptions(), enabled: ask })
  return (
    ask &&
    (mine.data ?? []).some(
      (f) =>
        f.forumId === meeting.forumId && f.state === 'IN_OFFICE' && (f.role === 'CHAIR' || f.role === 'VICE_CHAIR'),
    )
  )
}
