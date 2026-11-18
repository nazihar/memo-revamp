import { Link } from '@tanstack/react-router'
import { useIntl } from 'react-intl'
import type { ForumPolicyResponse } from '@/api/generated/types.gen'
import { PERMISSION, holds, useMyAccess } from '@/features/access/access'
import { meaningLabels } from '@/features/forum-types/labels'
import { ft } from '@/features/forum-types/messages'
import { quorumNotes, quorumSentence } from '@/features/forum-types/quorum-words'
import { ruleOf } from '@/features/forum-types/settings'
import { useTerms } from '@/features/terms/terms'
import { fm, fmt, fmv } from './messages'
import { attributeNames } from './shared'

/**
 * Policy (flow K2): the committee type's published policy, to read: the quorum in words, the decisions it records,
 * its member attributes and its terms of office. Administrators of committee types get a link to the type.
 */
export function PolicyTab({ policy }: { policy: ForumPolicyResponse }) {
  const intl = useIntl()
  const terms = useTerms()
  const access = useMyAccess()
  const rule = ruleOf(policy.quorum)
  const labels = attributeNames(policy)
  const typeAdmin = holds(access.data, PERMISSION.forumTypesManage) || holds(access.data, PERMISSION.forumTypesApprove)
  const t = policy.termPolicy
  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <p className="flex flex-wrap gap-x-2 text-sm text-muted-foreground">
        <span>{intl.formatMessage(fmv.policyOf, { name: policy.typeName, version: policy.version })}</span>
        {typeAdmin ? (
          <Link
            to="/admin/committee-types/$typeId"
            params={{ typeId: policy.typeId }}
            className="underline underline-offset-4"
          >
            {intl.formatMessage(fmt.policyLink, terms)}
          </Link>
        ) : null}
      </p>
      {policy.description ? <p dir="auto">{policy.description}</p> : null}
      <section aria-labelledby="policy-quorum" className="flex flex-col gap-2">
        <h2 id="policy-quorum" className="text-base font-semibold">
          {intl.formatMessage(ft.tabQuorum)}
        </h2>
        <p className="rounded-md border bg-card p-4 font-medium">{quorumSentence(intl, rule, labels)}</p>
        <ul className="list-disc ps-6 text-sm text-muted-foreground">
          {quorumNotes(intl, rule).map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      </section>
      <section aria-labelledby="policy-outcomes" className="flex flex-col gap-2">
        <h2 id="policy-outcomes" className="text-base font-semibold">
          {intl.formatMessage(ft.tabOutcomes)}
        </h2>
        <ul className="flex flex-col gap-1 text-sm">
          {policy.outcomes
            .flatMap((o) => (o.kind ? [{ ...o, kind: o.kind }] : []))
            .map((o) => (
              <li key={o.kind} className="flex flex-wrap gap-x-3">
                <span dir="auto">
                  {intl.formatMessage(fmv.outcome, {
                    label: o.label ?? '',
                    meaning: intl.formatMessage(meaningLabels[o.kind], terms),
                  })}
                </span>
                {o.resolution ? (
                  <span className="text-muted-foreground">{intl.formatMessage(ft.colResolution)}</span>
                ) : null}
              </li>
            ))}
        </ul>
      </section>
      {policy.memberAttributes.length ? (
        <section aria-labelledby="policy-attributes" className="flex flex-col gap-2">
          <h2 id="policy-attributes" className="text-base font-semibold">
            {intl.formatMessage(fm.attributes)}
          </h2>
          <p className="text-sm">{intl.formatList(policy.memberAttributes.map((a) => a.label))}</p>
        </section>
      ) : null}
      <section aria-labelledby="policy-terms" className="flex flex-col gap-2">
        <h2 id="policy-terms" className="text-base font-semibold">
          {intl.formatMessage(ft.tabTerms)}
        </h2>
        <ul className="list-disc ps-6 text-sm">
          <li>
            {t.termMonths
              ? intl.formatMessage(fmv.termMonths, { months: t.termMonths })
              : intl.formatMessage(fm.noTermMonths)}
          </li>
          {t.maxTerms ? <li>{intl.formatMessage(fmv.maxTerms, { max: t.maxTerms })}</li> : null}
          {t.reminderDays?.length ? (
            <li>
              {intl.formatMessage(fmv.reminders, {
                days: intl.formatList(
                  t.reminderDays.map((d) => intl.formatNumber(d)),
                  { type: 'conjunction' },
                ),
              })}
            </li>
          ) : null}
        </ul>
      </section>
    </div>
  )
}
