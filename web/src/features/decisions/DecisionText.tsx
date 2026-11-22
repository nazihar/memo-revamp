import { Link } from '@tanstack/react-router'
import { CircleCheck } from 'lucide-react'
import { useIntl } from 'react-intl'
import type { DecisionResponse } from '@/api/generated/types.gen'
import { dm, dmv } from './messages'
import { outcomeLine } from './shared'

/**
 * One decision in words (flow AB1): "✓ APPROVED WITH CONDITIONS — Resolution BRC/2026/014", the resolution, the
 * conditions, how long it is valid, where a deferred item went, and for those who see it in full the vote, who asked
 * that their dissent be recorded, who abstained and who took no part. A reader recused from the item (ADR-0062) is told
 * so first and gets no vote or names: the server leaves them out. A numbered decision links to its resolution.
 */
export function DecisionText({ decision: d, timeZone }: { decision: DecisionResponse; timeZone: string }) {
  const intl = useIntl()
  const day = (date: string) => intl.formatDate(`${date}T12:00:00Z`, { dateStyle: 'long', timeZone: 'UTC' })
  const vote = d.vote?.mode === 'VOTE' ? d.vote : undefined
  const abstentions = d.abstentions ?? []
  return (
    <div className="flex flex-col gap-1.5 text-lg">
      {d.viewerRecused ? <p className="font-medium">{intl.formatMessage(dm.recusedLine)}</p> : null}
      <p className="flex items-start gap-2 font-semibold">
        <CircleCheck aria-hidden="true" className="mt-1 size-5 shrink-0 text-status-ok-fg" />
        <span>{outcomeLine(intl, d)}</span>
      </p>
      {d.text ? <p className="whitespace-pre-line">{d.text}</p> : null}
      {d.conditions.length ? (
        <ol className="list-decimal ps-8">
          {d.conditions.map((c, i) => (
            <li key={i}>{c}</li>
          ))}
        </ol>
      ) : null}
      {d.validUntil ? <p>{intl.formatMessage(dmv.validUntilLine, { date: day(d.validUntil) })}</p> : null}
      {d.outcome === 'DEFERRED' && d.deferTo ? (
        <p>
          {d.deferTo.date
            ? intl.formatMessage(dmv.deferredTo, { target: day(d.deferTo.date) })
            : intl.formatMessage(dmv.deferredNext, {})}
        </p>
      ) : null}
      {d.referReason && d.outcome === 'REFERRED' ? <p className="text-muted-foreground">{d.referReason}</p> : null}
      {vote ? (
        <p>
          {intl.formatMessage(dmv.vote, {
            for: vote.votesFor ?? 0,
            against: vote.votesAgainst ?? 0,
            abstain: vote.votesAbstain ?? 0,
          })}
        </p>
      ) : null}
      {(d.dissent ?? []).map((x) => (
        <p key={x.person.id}>
          {x.reason
            ? intl.formatMessage(dmv.dissentReason, { name: x.person.name, reason: x.reason })
            : intl.formatMessage(dmv.dissent, { name: x.person.name })}
        </p>
      ))}
      {abstentions.length ? (
        <p>
          {intl.formatMessage(dmv.abstained, {
            names: intl.formatList(abstentions.map((a) => a.name)),
            count: abstentions.length,
          })}
        </p>
      ) : null}
      {d.tookNoPart?.length ? (
        <p>{intl.formatMessage(dmv.tookNoPart, { names: intl.formatList(d.tookNoPart.map((p) => p.name)) })}</p>
      ) : null}
      {d.toBeRatified ? <p className="font-medium">{intl.formatMessage(dm.toBeRatifiedLine)}</p> : null}
      {d.recordedAt && d.recordedBy ? (
        <p className="text-base text-muted-foreground">
          {intl.formatMessage(dmv.historyRecorded, {
            time: intl.formatDate(d.recordedAt, { dateStyle: 'medium', timeStyle: 'short', timeZone }),
            name: d.recordedBy.name,
          })}
        </p>
      ) : null}
      {d.resolutionId ? (
        <Link
          to="/resolutions/$resolutionId"
          params={{ resolutionId: d.resolutionId }}
          className="inline-flex min-h-11 w-fit items-center text-base underline underline-offset-4 print:hidden"
        >
          {intl.formatMessage(dm.openResolution)}
        </Link>
      ) : null}
    </div>
  )
}
