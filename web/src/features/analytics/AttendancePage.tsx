import { Link, useNavigate } from '@tanstack/react-router'
import { ArrowDownWideNarrow, ArrowUpDown, FileSpreadsheet } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { exportCommitteeAttendance } from '@/api/generated/sdk.gen'
import { isProblem, problemType, PROBLEM } from '@/api/http'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState } from '@/components/page/States'
import { TableScroll } from '@/components/page/TableScroll'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast-context'
import { useTerms } from '@/features/terms/terms'
import { periodOf, type AttendanceSearch, type PeriodSearch } from './analytics-params'
import { DataBar } from './bars'
import { am, amt, amv } from './messages'
import { PeriodPicker } from './PeriodPicker'
import { formatDay, periodQuery } from './period'
import { Arrow, Loaded, type Detail } from './shared'
import { LINK, nameOf, rateWords, roleWords, sortMembers, useCommitteeDetail } from './words'

/**
 * `/analytics/committees/$forumId/attendance` (Phase 4 E08 T7, flow AV): attendance per seat holder over the period for
 * officers and oversight, with the export for the annual report; a member reads their own row and the committee's
 * total. An alternate's attendance is shown as "by alternate" and never counted as the member's. Sorted by name; the
 * Member and Rate headers sort.
 */
export function AttendancePage({ forumId, search }: { forumId: string; search: AttendanceSearch }) {
  const intl = useIntl()
  const terms = useTerms()
  const navigate = useNavigate()
  const detail = useCommitteeDetail(forumId, search)
  const period = periodOf(search)
  const exportable = detail.data?.access === 'FULL' && !detail.data.attendance.membersOmitted
  return (
    <section aria-labelledby="analytics-attendance-title" className="flex flex-col gap-4">
      <PageHeader
        title={
          <span id="analytics-attendance-title">
            {detail.data
              ? intl.formatMessage(amv.attendanceHeading, { committee: detail.data.name })
              : intl.formatMessage(am.attendanceTitle)}
          </span>
        }
        actions={exportable ? <ExportButton forumId={forumId} period={period} /> : undefined}
      />
      {exportable ? (
        <p className="-mt-3 text-sm text-muted-foreground">{intl.formatMessage(amt.exportHint, terms)}</p>
      ) : null}
      <PeriodPicker
        search={search}
        onChange={(next) => {
          void navigate({
            to: '/analytics/committees/$forumId/attendance',
            params: { forumId },
            search: { ...next, ...(search.sort ? { sort: search.sort } : {}) },
            replace: true,
          })
        }}
      />
      <Loaded query={detail}>
        {(data) => (
          <AttendanceTable
            data={data}
            sort={search.sort}
            period={period}
            onSort={(sort) => {
              void navigate({
                to: '/analytics/committees/$forumId/attendance',
                params: { forumId },
                search: { ...period, ...(sort ? { sort } : {}) },
                replace: true,
              })
            }}
          />
        )}
      </Loaded>
      <p className="flex flex-wrap gap-x-5 gap-y-2 text-sm font-medium">
        <Link to="/analytics/committees/$forumId" params={{ forumId }} search={period} className={LINK}>
          {intl.formatMessage(amt.linkBackToDetail, terms)}
          <Arrow />
        </Link>
      </p>
    </section>
  )
}

function ExportButton({ forumId, period }: { forumId: string; period: PeriodSearch }) {
  const intl = useIntl()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const download = async () => {
    setBusy(true)
    try {
      const { data } = await exportCommitteeAttendance({
        path: { forumId },
        query: periodQuery(period),
        parseAs: 'blob',
        throwOnError: true,
      })
      const url = URL.createObjectURL(data)
      const link = document.createElement('a')
      link.href = url
      link.download = `attendance-${new Date().toISOString().slice(0, 10)}.xlsx`
      link.click()
      URL.revokeObjectURL(url)
      toast.show(intl.formatMessage(am.exported), 'success')
    } catch (problem) {
      const limited = problemType(problem) === PROBLEM.rateLimited || (isProblem(problem) && problem.status === 429)
      toast.show(intl.formatMessage(limited ? am.exportTooMany : am.exportFailed), 'error')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Button className="min-h-11" disabled={busy} onClick={() => void download()}>
      <FileSpreadsheet aria-hidden="true" />
      {intl.formatMessage(am.export)}
    </Button>
  )
}

function AttendanceTable({
  data,
  sort,
  period,
  onSort,
}: {
  data: Detail
  sort: 'rate' | undefined
  period: PeriodSearch
  onSort: (sort: 'rate' | undefined) => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const a = data.attendance
  const total = rateWords(intl, a.summary.ratePercent)
  const rows = sortMembers(a.members, sort, intl.locale)
  const own = a.ownRowOnly ? a.members[0] : undefined
  const anyAlternate = rows.some((r) => r.byAlternate > 0)
  return (
    <>
      <p className="text-sm font-medium" aria-live="polite">
        {intl.formatMessage(amv.showing, { from: formatDay(intl, data.from), to: formatDay(intl, data.to) })}
      </p>
      <p className="text-base">
        {a.summary.heldMeetings === 0
          ? intl.formatMessage(am.noMeetings)
          : intl.formatMessage(amv.attendanceLead, { meetings: a.summary.heldMeetings, rate: total })}
      </p>
      {a.ownRowOnly ? (
        <p className="text-base font-medium">
          {own
            ? intl.formatMessage(amv.ownRow, {
                forum: terms.forum ?? '',
                attended: own.attended,
                expected: own.expected,
                rate: rateWords(intl, own.ratePercent),
                overall: total,
              })
            : intl.formatMessage(am.noOwnRow)}
        </p>
      ) : null}
      {a.membersOmitted ? (
        <p role="note" className="rounded-md border bg-card p-4 text-sm">
          {intl.formatMessage(am.membersOmitted)}
        </p>
      ) : rows.length === 0 ? (
        a.ownRowOnly ? null : (
          <EmptyState title={intl.formatMessage(am.noRows)} />
        )
      ) : (
        <TableScroll className="rounded-md border bg-card">
          <table className="w-full text-sm">
            <caption className="sr-only">{intl.formatMessage(amv.attendanceHeading, { committee: data.name })}</caption>
            <thead>
              <tr className="border-b">
                <SortHeader
                  label={intl.formatMessage(am.colMember)}
                  active={sort !== 'rate'}
                  sortLabel={intl.formatMessage(am.sortByName)}
                  onSort={() => {
                    onSort(undefined)
                  }}
                  align="start"
                />
                <Th>{intl.formatMessage(am.colRole)}</Th>
                <Th end>{intl.formatMessage(am.colExpected)}</Th>
                <Th end>{intl.formatMessage(am.colAttended)}</Th>
                <Th end>{intl.formatMessage(am.colRemote)}</Th>
                <Th end>{intl.formatMessage(am.colApologies)}</Th>
                <Th end>{intl.formatMessage(am.colAbsent)}</Th>
                <SortHeader
                  label={intl.formatMessage(am.colRate)}
                  active={sort === 'rate'}
                  sortLabel={intl.formatMessage(am.sortByRate)}
                  onSort={() => {
                    onSort('rate')
                  }}
                  align="start"
                  descending
                />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.userId} className="border-b last:border-b-0">
                  <th scope="row" className="px-3 py-2 text-start font-normal">
                    <Link
                      to="/analytics/committees/$forumId/attendance/$userId"
                      params={{ forumId: data.forumId, userId: r.userId }}
                      search={period}
                      className={LINK}
                    >
                      {nameOf(intl, r)}
                    </Link>
                  </th>
                  <td className="px-3 py-2">{roleWords(intl, r.role)}</td>
                  <td className="whitespace-nowrap px-3 py-2 text-end tabular-nums">
                    {r.seatFrom > data.from
                      ? intl.formatMessage(amv.expectedFrom, {
                          expected: r.expected,
                          from: formatDay(intl, r.seatFrom, { day: 'numeric', month: 'short' }),
                        })
                      : intl.formatNumber(r.expected)}
                  </td>
                  <td className="px-3 py-2 text-end tabular-nums">{intl.formatNumber(r.attended)}</td>
                  <td className="px-3 py-2 text-end tabular-nums">{intl.formatNumber(r.remote)}</td>
                  <td className="whitespace-nowrap px-3 py-2 text-end tabular-nums">
                    {r.byAlternate > 0
                      ? intl.formatMessage(amv.apologiesAlt, { apologies: r.apologies, alternate: r.byAlternate })
                      : intl.formatNumber(r.apologies)}
                  </td>
                  <td className="px-3 py-2 text-end tabular-nums">{intl.formatNumber(r.absent)}</td>
                  <td className="px-3 py-2">
                    <span className="flex min-w-40 items-center gap-3">
                      <span className="w-12 shrink-0 text-end font-medium tabular-nums">
                        {rateWords(intl, r.ratePercent)}
                      </span>
                      {r.ratePercent != null ? <DataBar value={r.ratePercent} className="min-w-20" /> : null}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableScroll>
      )}
      {anyAlternate ? <p className="text-sm text-muted-foreground">{intl.formatMessage(am.altNote)}</p> : null}
    </>
  )
}

function Th({ children, end = false }: { children: React.ReactNode; end?: boolean }) {
  return (
    <th scope="col" className={`px-3 py-2 font-medium ${end ? 'text-end' : 'text-start'}`}>
      {children}
    </th>
  )
}

/** A column header that sorts the table: a button inside the header, which says how the table is sorted (aria-sort). */
function SortHeader({
  label,
  active,
  sortLabel,
  onSort,
  descending = false,
  align,
}: {
  label: string
  active: boolean
  sortLabel: string
  onSort: () => void
  descending?: boolean
  align: 'start' | 'end'
}) {
  const Icon = active ? ArrowDownWideNarrow : ArrowUpDown
  return (
    <th
      scope="col"
      aria-sort={active ? (descending ? 'descending' : 'ascending') : 'none'}
      className={`px-1 py-1 font-medium ${align === 'end' ? 'text-end' : 'text-start'}`}
    >
      <button
        type="button"
        title={sortLabel}
        onClick={onSort}
        className="inline-flex min-h-10 items-center gap-1.5 rounded-md px-2 hover:bg-accent focus-visible:outline-2 focus-visible:outline-ring"
      >
        {label}
        <Icon aria-hidden="true" className={`size-4 ${active ? '' : 'text-muted-foreground'}`} />
      </button>
    </th>
  )
}
