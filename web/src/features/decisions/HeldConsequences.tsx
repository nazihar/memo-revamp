import { useQuery } from '@tanstack/react-query'
import { useIntl } from 'react-intl'
import { getMeetingAgendaOptions, getMeetingDecisionsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MeetingResponse } from '@/api/generated/types.gen'
import { dm, dmv } from './messages'
import { itemShort, numbers, panelItems, useDecisionPolicy } from './shared'

/**
 * What marking the meeting as held will do (flow AA3), inside E03's End dialog: how many decisions are recorded and
 * which are resolutions to be numbered in agenda order, which memos move on now (or when the minutes are approved, as
 * the committee type says), which submitted items have no decision and keep their memos waiting, and what happens to
 * decisions recorded afterwards. The numbers themselves are given by the server when the meeting is held.
 */
export function HeldConsequences({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const path = { path: { meetingId: m.id } }
  const agenda = useQuery(getMeetingAgendaOptions(path))
  const decisions = useQuery(getMeetingDecisionsOptions(path))
  const { memoResumes } = useDecisionPolicy(m)
  if (agenda.isPending || decisions.isPending) {
    return <p className="text-base text-muted-foreground">{intl.formatMessage(dm.heldLoading)}</p>
  }
  if (agenda.isError || decisions.isError) return null
  const items = panelItems(agenda.data)
  const byItem = new Map(decisions.data.decisions.map((d) => [d.itemId, d]))
  const decided = items.filter((i) => byItem.has(i.id))
  const resolutions = decided.filter((i) => byItem.get(i.id)?.resolution && !byItem.get(i.id)?.resolutionNumber)
  const memos = decided.filter((i) => i.kind === 'SUBMISSION' && byItem.get(i.id)?.state !== 'RELEASED')
  const undecided = items.filter((i) => i.kind === 'SUBMISSION' && !byItem.has(i.id))
  const later = memoResumes === 'ON_MINUTES_APPROVAL'
  const memoWords = intl.formatList(
    memos.map((i) =>
      intl.formatMessage(dmv.itemWithOutcome, {
        item: intl.formatMessage(dmv.itemWords, { number: i.number ?? '' }),
        outcome: byItem.get(i.id)?.outcomeLabel ?? '',
      }),
    ),
  )
  return (
    <section aria-label={intl.formatMessage(dm.heldTitle)} className="flex flex-col gap-2 text-base">
      <ul className="flex list-disc flex-col gap-1 ps-5">
        <li>
          {decided.length
            ? [
                intl.formatMessage(dmv.heldDecisions, { count: decided.length }),
                intl.formatMessage(dmv.heldResolutions, {
                  count: resolutions.length,
                  items: numbers(intl, resolutions),
                }),
              ].join(' ')
            : intl.formatMessage(dm.heldNone)}
        </li>
        {memos.length ? (
          <li>
            {intl.formatMessage(later ? dmv.heldMemosLater : dmv.heldMemosNow, {
              count: memos.length,
              items: memoWords,
            })}
          </li>
        ) : null}
        {undecided.length ? (
          <li>
            {intl.formatMessage(dmv.heldUndecided, {
              count: undecided.length,
              items: intl.formatList(undecided.map((i) => itemShort(intl, i))),
            })}
          </li>
        ) : null}
      </ul>
      <p>{intl.formatMessage(later ? dm.heldAfterApproval : dm.heldAfterNow)}</p>
    </section>
  )
}
