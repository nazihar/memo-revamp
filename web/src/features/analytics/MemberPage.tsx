import { Link } from '@tanstack/react-router'
import { useIntl } from 'react-intl'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState } from '@/components/page/States'
import { TableScroll } from '@/components/page/TableScroll'
import { DataTableOf } from '@/features/dashboard/Figure'
import { periodOf, type PeriodSearch } from './analytics-params'
import { am, amv } from './messages'
import { formatDay } from './period'
import { Arrow, Loaded } from './shared'
import { LINK, nameOf, rateWords, roleWords, seatWords, useCommitteeDetail } from './words'

/**
 * `/analytics/committees/$forumId/attendance/$userId` (Phase 4 E08 T7, design §2.6 "a member's row"): the held meetings
 * of the period during the person's seat, each with their attendance in words and a link to the meeting's page. From the
 * committee's detail, so a member reaches their own row only (another person's is not in their answer).
 */
export function MemberPage({ forumId, userId, search }: { forumId: string; userId: string; search: PeriodSearch }) {
  const intl = useIntl()
  const detail = useCommitteeDetail(forumId, search)
  const period = periodOf(search)
  const row = detail.data?.attendance.members.find((m) => m.userId === userId)
  return (
    <section aria-labelledby="analytics-member-title" className="flex flex-col gap-4">
      <PageHeader
        title={
          <span id="analytics-member-title">
            {row
              ? intl.formatMessage(amv.memberHeading, { name: nameOf(intl, row) })
              : intl.formatMessage(am.memberTitle)}
          </span>
        }
        description={detail.data?.name}
      />
      <Loaded query={detail}>
        {(data) => {
          if (!row) return <EmptyState title={intl.formatMessage(am.memberNone)} />
          const labels = new Map(data.papers.meetings.map((m) => [m.meetingId, m.label]))
          const title = intl.formatMessage(am.memberTitle)
          return (
            <>
              <p className="text-sm font-medium">
                {intl.formatMessage(amv.showingRole, {
                  showing: intl.formatMessage(amv.showing, {
                    from: formatDay(intl, data.from),
                    to: formatDay(intl, data.to),
                  }),
                  role: roleWords(intl, row.role),
                })}
              </p>
              <p className="text-base">
                {intl.formatMessage(amv.memberLead, {
                  attended: row.attended,
                  expected: row.expected,
                  rate: rateWords(intl, row.ratePercent),
                })}
              </p>
              {row.meetings.length > 0 ? (
                <TableScroll className="rounded-md border bg-card p-2">
                  <DataTableOf
                    caption={title}
                    columns={[
                      intl.formatMessage(am.colMeeting),
                      intl.formatMessage(am.colDate),
                      intl.formatMessage(am.colAttendance),
                    ]}
                    rows={row.meetings.map((m) => {
                      const date = formatDay(intl, m.date)
                      return {
                        key: m.meetingId,
                        cells: [
                          <Link key="m" to="/meetings/$meetingId" params={{ meetingId: m.meetingId }} className={LINK}>
                            {labels.get(m.meetingId) ?? intl.formatMessage(amv.meetingOf, { date })}
                          </Link>,
                          date,
                          seatWords(intl, m),
                        ],
                      }
                    })}
                  />
                </TableScroll>
              ) : null}
            </>
          )
        }}
      </Loaded>
      <p className="text-sm font-medium">
        <Link to="/analytics/committees/$forumId/attendance" params={{ forumId }} search={period} className={LINK}>
          {intl.formatMessage(am.linkBackToAttendance)}
          <Arrow />
        </Link>
      </p>
    </section>
  )
}
