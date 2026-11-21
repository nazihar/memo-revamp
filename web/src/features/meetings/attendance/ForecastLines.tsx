import { useQuery } from '@tanstack/react-query'
import { TriangleAlert } from 'lucide-react'
import { useIntl } from 'react-intl'
import { getMeetingQuorumForecastOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MeetingResponse } from '@/api/generated/types.gen'
import { amv, am } from './messages'
import { useQuorumPolicy } from './policy'
import { unmetWords } from './words'

/**
 * The forecast lines of the officers' "Invitations and replies" tab (flow W1, `GET …/quorum/forecast`): "If everyone
 * who said yes attends: quorate — 5 of 7 members, 4 needed." and, for each published item that would not be quorate
 * even then, "Item 5: not quorate even then — 3 of 4 needed (2 members have declared an interest)." An officer recused
 * from an item gets its numbers only. Shown while the meeting is scheduled; from the start the live quorum takes over.
 */
export function ForecastLines({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const forecast = useQuery(getMeetingQuorumForecastOptions({ path: { meetingId: m.id } }))
  const { labels } = useQuorumPolicy(m)
  if (forecast.isPending) return null
  if (forecast.isError) return <p className="text-muted-foreground">{intl.formatMessage(am.forecastUnavailable)}</p>
  const f = forecast.data
  const meeting = f.meeting
  const numbers = { present: meeting.present, basis: meeting.basis, needed: meeting.needed }
  const unmet = meeting.unmet.filter((u) => u.kind !== 'COUNT').map((u) => unmetWords(intl, u, labels))
  const short = f.items.filter((i) => !i.quorum.quorate)
  return (
    <div className="flex flex-col gap-1">
      <p>{intl.formatMessage(meeting.quorate ? amv.forecastQuorate : amv.forecastNotQuorate, numbers)}</p>
      {unmet.length ? (
        <ul className="list-disc ps-5">
          {unmet.map((u) => (
            <li key={u}>{u}</li>
          ))}
        </ul>
      ) : null}
      {short.length ? (
        <ul className="flex flex-col gap-1">
          {short.map((i) => {
            const values = {
              number: i.number ?? '',
              present: i.quorum.present,
              needed: i.quorum.needed,
              declared: i.declared ?? 0,
            }
            return (
              <li key={i.itemId} className="flex items-start gap-2">
                <TriangleAlert aria-hidden="true" className="mt-1 size-4 shrink-0 text-status-warn-fg" />
                <span>{intl.formatMessage(i.declared ? amv.forecastItemDeclared : amv.forecastItem, values)}</span>
              </li>
            )
          })}
        </ul>
      ) : null}
    </div>
  )
}
