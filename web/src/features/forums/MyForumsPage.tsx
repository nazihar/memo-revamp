import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useIntl } from 'react-intl'
import {
  getForumPolicyOptions,
  listForumsOptions,
  listMyForumsOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import type { ForumResponse, MeetingSummaryResponse, MyForumResponse } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useMyMeetings } from '@/features/meetings/mine'
import { NextMeeting } from '@/features/meetings/MyMeetings'
import { useTerms } from '@/features/terms/terms'
import { fm, fmt, fmv } from './messages'
import { formatDay } from './shared'

/**
 * `/me/committees` (flow L1): one card per committee the user sits on, will sit on or sat on, with their role, vote,
 * attributes and term, the committee's chair, secretaries and size, and its next meeting (Phase 4 E03, flow N3).
 */
export function MyForumsPage() {
  const intl = useIntl()
  const terms = useTerms()
  const mine = useQuery(listMyForumsOptions())
  const forums = useQuery(listForumsOptions())
  const meetings = useMyMeetings()

  return (
    <section aria-labelledby="my-forums-title" className="max-w-3xl">
      <PageHeader
        title={<span id="my-forums-title">{intl.formatMessage(fmt.mineTitle, terms)}</span>}
        description={intl.formatMessage(fmt.mineLead, terms)}
      />
      {mine.isPending ? (
        <LoadingState rows={3} />
      ) : mine.isError ? (
        <ErrorState onRetry={() => void mine.refetch()} />
      ) : mine.data.length === 0 ? (
        <EmptyState title={intl.formatMessage(fmt.mineEmpty, terms)} />
      ) : (
        <ul className="flex flex-col gap-3">
          {mine.data.map((m) => (
            <MyForumCard
              key={m.forumId}
              membership={m}
              forum={forums.data?.find((f) => f.id === m.forumId)}
              meetings={meetings.data ?? []}
            />
          ))}
        </ul>
      )}
    </section>
  )
}

function MyForumCard({
  membership: m,
  forum,
  meetings,
}: {
  membership: MyForumResponse
  forum?: ForumResponse
  meetings: MeetingSummaryResponse[]
}) {
  const intl = useIntl()
  const policy = useQuery({
    ...getForumPolicyOptions({ path: { forumId: m.forumId } }),
    enabled: m.canOpen && m.attributes.length > 0,
  })
  const titleId = `my-forum-${m.forumId}`
  const attributes = m.attributes.map((key) => policy.data?.memberAttributes.find((a) => a.key === key)?.label ?? key)
  const role = [
    intl.formatMessage(fmv.mineRole, {
      role: intl.formatMessage(fm[m.role]),
      votes: intl.formatMessage(m.voting ? fm.votesYes : fm.votesNo),
    }),
    ...attributes,
  ].join(' · ')
  const term =
    m.state === 'ENDED' && m.endedOn
      ? intl.formatMessage(fmv.mineEnded, { date: formatDay(intl, m.endedOn) })
      : m.state === 'UPCOMING'
        ? intl.formatMessage(fmv.mineStarts, { date: formatDay(intl, m.startsOn) })
        : m.termEndsOn
          ? intl.formatMessage(fmv.mineTermEnds, { n: m.termNumber, date: formatDay(intl, m.termEndsOn) })
          : undefined
  const secretaries = forum?.secretaries.map((s) => s.name) ?? []
  return (
    <li>
      <article
        aria-labelledby={titleId}
        className="flex flex-col gap-2 rounded-lg border bg-card p-4 text-card-foreground"
      >
        <div className="flex flex-wrap items-start justify-between gap-2">
          <h2 id={titleId} className="text-lg font-semibold">
            {m.name}
          </h2>
          {m.state !== 'IN_OFFICE' || m.status === 'DISSOLVED' ? (
            <Badge tone="neutral">{intl.formatMessage(m.status === 'DISSOLVED' ? fm.DISSOLVED : fm[m.state])}</Badge>
          ) : null}
        </div>
        <p className="font-medium">{role}</p>
        {term ? <p className="text-sm">{term}</p> : null}
        {forum ? (
          <p className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted-foreground">
            {forum.chair ? <span>{intl.formatMessage(fmv.mineChair, { name: forum.chair.name })}</span> : null}
            {secretaries.length ? (
              <span>
                {intl.formatMessage(fmv.secretaries, {
                  count: secretaries.length,
                  names: intl.formatList(secretaries),
                })}
              </span>
            ) : null}
            <span>{intl.formatMessage(fmv.mineMembers, { count: forum.memberCount })}</span>
          </p>
        ) : null}
        {m.state !== 'ENDED' ? <NextMeeting forumId={m.forumId} meetings={meetings} /> : null}
        {m.canOpen ? (
          <div>
            <Button asChild variant="outline">
              <Link
                to="/committees/$forumId"
                params={{ forumId: m.forumId }}
                aria-label={intl.formatMessage(fmv.openNamed, { name: m.name })}
              >
                {intl.formatMessage(fm.open)}
              </Link>
            </Button>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">{intl.formatMessage(fm.formerOnly)}</p>
        )}
      </article>
    </li>
  )
}
