import { useIntl } from 'react-intl'
import { Badge, type Tone } from '@/components/ui/badge'
import { mm } from './messages'
import type { Meeting, MeetingStatus } from './shared'
import { longPast } from './time'

const TONES: Record<MeetingStatus, Tone> = {
  DRAFT: 'neutral',
  SCHEDULED: 'ok',
  IN_PROGRESS: 'warn',
  HELD: 'ok',
  MINUTES_APPROVED: 'ok',
  CLOSED: 'neutral',
  CANCELLED: 'danger',
}

/**
 * The meeting's state in words; to its [officer]s, a scheduled meeting that ended more than a day ago also says
 * "Not marked as held" (design §1.1).
 */
export function StatusBadge({
  meeting,
  officer = false,
}: {
  meeting: Pick<Meeting, 'status' | 'endsAt'>
  officer?: boolean
}) {
  const intl = useIntl()
  if (officer && meeting.status === 'SCHEDULED' && longPast(meeting)) {
    return <Badge tone="warn">{intl.formatMessage(mm.notHeld)}</Badge>
  }
  return <Badge tone={TONES[meeting.status]}>{intl.formatMessage(mm[meeting.status])}</Badge>
}
