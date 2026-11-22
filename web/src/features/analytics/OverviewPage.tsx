import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { useIntl } from 'react-intl'
import { getCommitteeAnalyticsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { CommitteeSummaryResponse } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState } from '@/components/page/States'
import { Field } from '@/components/ui/field'
import { Select } from '@/components/ui/form-controls'
import { Figure } from '@/features/dashboard/Figure'
import { useTerms, type TermValues } from '@/features/terms/terms'
import { periodOf, type OverviewSearch, type PeriodSearch } from './analytics-params'
import { DataBar } from './bars'
import { am, amt, amv } from './messages'
import { PeriodPicker } from './PeriodPicker'
import { formatDay, formatPercent, periodQuery } from './period'
import { Arrow, Loaded } from './shared'
import { LINK, rateWords, summarySentence } from './words'

/**
 * `/analytics/committees` (Phase 4 E08 T7, flow AU): one block per committee in the viewer's scope, by name, each a
 * sentence and the table of the five measures (ADR-0070) with bars beside the shares. Every number leads to its list
 * (design §2.6); a member's block has no late-paper counts. A select narrows the page to one committee.
 */
export function OverviewPage({ search }: { search: OverviewSearch }) {
  const intl = useIntl()
  const terms = useTerms()
  const navigate = useNavigate()
  const summary = useQuery({ ...getCommitteeAnalyticsOptions({ query: periodQuery(search) }), retry: false })
  const go = (next: OverviewSearch) => void navigate({ to: '/analytics/committees', search: next, replace: true })

  return (
    <section aria-labelledby="analytics-title" className="flex flex-col gap-4">
      <PageHeader
        title={<span id="analytics-title">{intl.formatMessage(amt.overviewTitle, terms)}</span>}
        description={intl.formatMessage(am.overviewLead)}
      />
      <div className="flex flex-wrap items-end gap-4">
        <PeriodPicker
          search={search}
          onChange={(period) => {
            go({ ...period, ...(search.committee ? { committee: search.committee } : {}) })
          }}
        />
        {summary.data && summary.data.committees.length > 1 ? (
          <Field label={intl.formatMessage(amt.committee, terms)} className="min-w-60">
            {(p) => (
              <Select
                {...p}
                className="min-h-11"
                value={search.committee ?? ''}
                onChange={(e) => {
                  go({ ...periodOf(search), ...(e.target.value ? { committee: e.target.value } : {}) })
                }}
              >
                <option value="">{intl.formatMessage(amt.allCommittees, terms)}</option>
                {summary.data.committees.map((c) => (
                  <option key={c.forumId} value={c.forumId}>
                    {c.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        ) : null}
      </div>
      <Loaded query={summary}>
        {(data) => {
          const shown = data.committees.filter((c) => !search.committee || c.forumId === search.committee)
          return (
            <>
              <p className="text-sm font-medium" aria-live="polite">
                {intl.formatMessage(amv.showing, { from: formatDay(intl, data.from), to: formatDay(intl, data.to) })}
              </p>
              {shown.length === 0 ? (
                <EmptyState title={intl.formatMessage(amt.noScope, terms)} />
              ) : (
                <div className="flex flex-col gap-4">
                  {shown.map((c) => (
                    <CommitteeBlock key={c.forumId} committee={c} period={periodOf(search)} terms={terms} />
                  ))}
                </div>
              )}
            </>
          )
        }}
      </Loaded>
    </section>
  )
}

interface Row {
  key: string
  measure: string
  value: ReactNode
  detail: ReactNode
}

function CommitteeBlock({
  committee: c,
  period,
  terms,
}: {
  committee: CommitteeSummaryResponse
  period: PeriodSearch
  terms: TermValues
}) {
  const intl = useIntl()
  const forumId = c.forumId
  const detail = (hash: string, text: string) => (
    <Link
      to="/analytics/committees/$forumId"
      params={{ forumId }}
      search={period}
      hash={hash}
      className={`${LINK} justify-end`}
    >
      {text}
    </Link>
  )
  const ofTotal = (part: number, total: number) => intl.formatMessage(amv.ofTotal, { part, total })
  const a = c.actions
  const t = c.timeToDecision
  const d = c.deferrals
  const p = c.papers

  const rows: Row[] = [
    {
      key: 'attendance',
      measure: intl.formatMessage(am.measureAttendance),
      value:
        c.attendance.ratePercent == null ? (
          intl.formatMessage(am.none)
        ) : (
          <Link
            to="/analytics/committees/$forumId/attendance"
            params={{ forumId }}
            search={period}
            className={`${LINK} justify-end`}
          >
            {formatPercent(intl, c.attendance.ratePercent)}
          </Link>
        ),
      detail:
        c.attendance.ratePercent == null ? (
          intl.formatMessage(am.noMeetings)
        ) : (
          <Detail bar={c.attendance.ratePercent}>
            {intl.formatMessage(amv.atMeetings, { meetings: c.attendance.heldMeetings })}
          </Detail>
        ),
    },
    {
      key: 'overdue',
      measure: intl.formatMessage(am.measureOverdue),
      value: detail('actions', intl.formatNumber(a.overdue)),
      detail:
        a.overdue === 0
          ? intl.formatMessage(am.none)
          : intl.formatMessage(amv.overdueDetail, { over90: a.overdueOver90, revised: a.overdueRevised }),
    },
    {
      key: 'closed',
      measure: intl.formatMessage(am.measureClosed),
      value:
        a.closed === 0 ? intl.formatMessage(am.none) : detail('actions', ofTotal(a.closedByOriginalDate, a.closed)),
      detail:
        a.closed === 0 ? (
          intl.formatMessage(am.noActionsClosed)
        ) : (
          <Detail bar={a.closedByOriginalPercent}>
            {intl.formatMessage(amv.closedDetail, {
              percent: rateWords(intl, a.closedByOriginalPercent),
              agreed: a.closedByAgreedDate,
            })}
          </Detail>
        ),
    },
    {
      key: 'timing',
      measure: intl.formatMessage(am.measureTiming),
      value:
        t.decisions === 0
          ? intl.formatMessage(am.none)
          : detail(
              'timing',
              t.enough && t.medianDays != null
                ? intl.formatMessage(amv.timingValue, { days: t.medianDays })
                : intl.formatMessage(am.notShown),
            ),
      detail:
        t.decisions === 0
          ? intl.formatMessage(am.noDecisions)
          : t.enough && t.p90Days != null
            ? intl.formatMessage(amv.timingDetail, { p90: t.p90Days, decisions: t.decisions })
            : intl.formatMessage(amv.timingFew, { decisions: t.decisions }),
    },
    {
      key: 'deferred',
      measure: intl.formatMessage(am.measureDeferred),
      value: d.decisions === 0 ? intl.formatMessage(am.none) : detail('deferrals', ofTotal(d.deferred, d.decisions)),
      detail:
        d.decisions === 0 ? (
          intl.formatMessage(am.noDecisions)
        ) : (
          <Detail bar={d.deferredPercent}>
            {intl.formatMessage(amv.deferredDetail, {
              percent: rateWords(intl, d.deferredPercent),
              twice: d.deferredTwice,
            })}
          </Detail>
        ),
    },
    {
      key: 'papers',
      measure: intl.formatMessage(am.measurePapers),
      value: p.withDeadline === 0 ? intl.formatMessage(am.none) : detail('papers', ofTotal(p.onTime, p.withDeadline)),
      detail:
        p.withDeadline === 0 ? (
          intl.formatMessage(am.noDeadline)
        ) : (
          <Detail bar={p.onTimePercent}>
            {p.lateItems != null && p.latePapers != null
              ? intl.formatMessage(amv.papersDetailLate, {
                  percent: rateWords(intl, p.onTimePercent),
                  late: intl.formatMessage(amv.lateCounts, { items: p.lateItems, papers: p.latePapers }),
                })
              : intl.formatMessage(amv.papersDetail, { percent: rateWords(intl, p.onTimePercent) })}
          </Detail>
        ),
    },
  ]

  return (
    <Figure
      tableFirst
      title={c.name}
      summary={summarySentence(intl, c)}
      basis={intl.formatMessage(amv.basis, { count: d.decisions })}
      table={
        <table className="w-full text-sm">
          <caption className="sr-only">{c.name}</caption>
          <thead>
            <tr className="border-b">
              <th scope="col" className="px-2 py-1.5 text-start font-medium">
                {intl.formatMessage(am.colMeasure)}
              </th>
              <th scope="col" className="px-2 py-1.5 text-end font-medium">
                {intl.formatMessage(am.colValue)}
              </th>
              <th scope="col" className="px-2 py-1.5 text-start font-medium">
                {intl.formatMessage(am.colDetail)}
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.key} className="border-b last:border-b-0">
                <th scope="row" className="px-2 py-2 text-start font-normal">
                  {r.measure}
                </th>
                <td className="whitespace-nowrap px-2 py-2 text-end font-medium tabular-nums">{r.value}</td>
                <td className="min-w-64 px-2 py-2 text-muted-foreground">{r.detail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
      link={
        <>
          <Link to="/analytics/committees/$forumId/attendance" params={{ forumId }} search={period} className={LINK}>
            {intl.formatMessage(am.linkAttendance)}
            <Arrow />
          </Link>
          <Link to="/analytics/committees/$forumId" params={{ forumId }} search={period} className={LINK}>
            {intl.formatMessage(amt.linkDetail, terms)}
            <Arrow />
          </Link>
        </>
      }
    />
  )
}

/**
 * A detail cell: the share's bar, then its words, which begin with the share itself (the bar's value label). The bar is
 * hidden from assistive technology: the words say everything it shows.
 */
function Detail({ bar, children }: { bar: number | null | undefined; children: ReactNode }) {
  return (
    <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
      {bar != null ? <DataBar value={bar} className="w-32 min-w-32 flex-none" /> : null}
      <span className="min-w-40 flex-1">{children}</span>
    </span>
  )
}
