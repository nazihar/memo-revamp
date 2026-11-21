import { useQuery } from '@tanstack/react-query'
import { useId } from 'react'
import { useIntl } from 'react-intl'
import { getMeetingAttendanceOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MeetingResponse } from '@/api/generated/types.gen'
import { am, amv } from './messages'
import { clock, LIVE_REFRESH_MS, type Person } from './words'

/**
 * The meeting page during the meeting for its audience (flows V2 and Y1): how the secretary has recorded the viewer
 * ("You are recorded as present (online)", "You sent your apologies") and, for members and alternates, whether the
 * meeting is quorate, in a sentence ("The meeting is quorate." or "The meeting is waiting for quorum."); refreshed every
 * 15 seconds. Nothing to do here: the secretary records who attends.
 */
export function AtTheMeeting({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const titleId = useId()
  const register = useQuery({
    ...getMeetingAttendanceOptions({ path: { meetingId: m.id } }),
    refetchInterval: LIVE_REFRESH_MS,
  })
  const mine = register.data?.mine
  const quorum = register.data?.quorum
  return (
    <section
      aria-labelledby={titleId}
      className="flex flex-col gap-2 rounded-md border bg-card p-4 text-lg text-card-foreground print:hidden"
    >
      <h2 id={titleId} className="text-xl font-semibold">
        {intl.formatMessage(am.atTheMeeting)}
      </h2>
      {register.isPending ? (
        <p className="text-muted-foreground">{intl.formatMessage(am.quorumLoading)}</p>
      ) : register.isError ? (
        <p className="text-muted-foreground">{intl.formatMessage(am.quorumUnavailable)}</p>
      ) : (
        <>
          {mine ? <p>{mineWords(intl, mine, m.timeZone)}</p> : null}
          {mine?.standingInFor ? <p>{intl.formatMessage(amv.youStandIn, { name: mine.standingInFor.name })}</p> : null}
          {quorum ? (
            <p className="font-semibold">
              {intl.formatMessage(quorum.quorate ? am.summaryQuorate : am.summaryWaiting)}
            </p>
          ) : null}
        </>
      )}
    </section>
  )
}

function mineWords(intl: ReturnType<typeof useIntl>, p: Person, timeZone: string): string {
  switch (p.state) {
    case 'PRESENT':
      return intl.formatMessage(p.mode === 'REMOTE' ? am.youPresentOnline : am.youPresentInPerson)
    case 'APOLOGIES':
      return intl.formatMessage(am.youApologies)
    case 'ABSENT':
      return intl.formatMessage(am.youAbsent)
    case 'LEFT':
      return p.lastLeftAt
        ? intl.formatMessage(amv.youLeft, { at: clock(intl, p.lastLeftAt, timeZone) })
        : intl.formatMessage(am.youLeftNoTime)
    case 'EXPECTED':
      return intl.formatMessage(am.youExpected)
  }
}
