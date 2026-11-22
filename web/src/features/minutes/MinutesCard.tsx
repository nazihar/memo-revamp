import { Link } from '@tanstack/react-router'
import { FileText } from 'lucide-react'
import { useIntl, type IntlShape } from 'react-intl'
import type { ItemLink, MeetingResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { fullName } from '@/features/meetings/shared'
import { useMinutes, useMinutesItems } from './data'
import { mn, mnv } from './messages'
import { ReaderCard } from './ReaderCard'
import { day } from './words'

/** Statuses of a meeting that has minutes. */
const WITH_MINUTES: MeetingResponse['status'][] = ['HELD', 'MINUTES_APPROVED', 'CLOSED']

/**
 * The meeting page's "Minutes" (flows AF1, AE1, AG3): for a reader of circulated minutes the "Minutes to read" card
 * (read, "No comments", "Suggest a correction"); for the secretaries, the reviewing chair and the approver a line on
 * where they stand with "Open the minutes". Nothing for anyone who does not read them (guests and item attendees never
 * do), nor before the meeting is held.
 */
export function MinutesCard({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const asked = WITH_MINUTES.includes(m.status) && m.relation !== 'GUEST' && m.relation !== 'ATTENDEE'
  const minutes = useMinutes(m.id, asked)
  const items = useMinutesItems(minutes.data, m.id)
  const page = minutes.data
  if (!asked || !page) return null
  const reader = (page.viewerAccess === 'READER' || page.viewerAccess === 'LINKED') && !page.mayConfirm
  if (reader && page.state !== 'APPROVED' && page.mayReadCopy) {
    return <ReaderCard page={page} meetingName={fullName(intl, m)} items={items} timeZone={m.timeZone} />
  }
  const line =
    page.myAction === 'REVIEW'
      ? intl.formatMessage(mn.reviewReady)
      : page.myAction === 'CONFIRM'
        ? intl.formatMessage(mn.approveTitle)
        : page.state === 'APPROVED' && page.approvedAt
          ? intl.formatMessage(mnv.approvedOn, { date: day(intl, page.approvedAt, m.timeZone) })
          : intl.formatMessage(mnv.cardStage, { stage: intl.formatMessage(mn[page.state]) })
  return (
    <section
      aria-labelledby="meeting-minutes"
      className="flex flex-col gap-2 rounded-lg border bg-card p-4 print:hidden"
    >
      <h2 id="meeting-minutes" className="text-lg font-semibold">
        {intl.formatMessage(mn.cardTitle)}
      </h2>
      <p className="text-lg">{line}</p>
      <div>
        <Button asChild variant={page.myAction ? 'default' : 'outline'} className="min-h-11 text-base">
          <Link to="/meetings/$meetingId/minutes" params={{ meetingId: m.id }}>
            {intl.formatMessage(mn.openMinutes)}
          </Link>
        </Button>
      </div>
    </section>
  )
}

/**
 * The linked meeting in the reader's words, from its own fields (never the server's English `label`): its number
 * ("BRC 3/2026"), else "the meeting of 12 December 2026" on its own calendar, else "the previous meeting".
 */
function meetingOf(intl: IntlShape, link: ItemLink): string {
  if (link.meetingLabel) return link.meetingLabel
  if (link.meetingStartsAt) {
    return intl.formatMessage(mnv.meetingOn, {
      date: day(intl, link.meetingStartsAt, link.meetingTimeZone ?? undefined),
    })
  }
  return intl.formatMessage(mn.previousMeeting)
}

/**
 * The previous meeting's minutes on the next meeting's "Minutes of the previous meeting" item (flow AG1, `ItemLinks`):
 * "Minutes of BRC 3/2026 (draft) — read your copy", or for minutes approved by circulation "… approved by circulation on
 * 19 December, for noting". Each opens that meeting's minutes page, where the reader's copy is.
 */
export function PreviousMinutesLinks({ links }: { links: ItemLink[] | undefined }) {
  const intl = useIntl()
  const minutes = (links ?? []).filter((l) => l.kind === 'MINUTES' && l.meetingId)
  if (!minutes.length) return null
  return (
    <ul className="flex flex-col gap-2 print:hidden">
      {minutes.map((l) => {
        const meeting = meetingOf(intl, l)
        return (
          <li key={l.meetingId}>
            <Button
              asChild
              variant="outline"
              className="h-auto min-h-11 max-w-full justify-start whitespace-normal py-2 text-start text-base"
            >
              <Link to="/meetings/$meetingId/minutes" params={{ meetingId: l.meetingId ?? '' }}>
                <FileText aria-hidden="true" />
                {l.at
                  ? intl.formatMessage(mnv.previousMinutesNoted, { meeting, date: day(intl, l.at) })
                  : intl.formatMessage(mnv.previousMinutes, { meeting, stage: l.state ?? 'DRAFT' })}
              </Link>
            </Button>
          </li>
        )
      })}
    </ul>
  )
}
