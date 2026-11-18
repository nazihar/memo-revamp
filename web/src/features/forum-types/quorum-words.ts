import type { IntlShape } from 'react-intl'
import { ft, ftv } from './messages'
import type { Role, Rule } from './settings'

const rolePhrases: Record<Role, (typeof ft)[keyof typeof ft]> = {
  CHAIR: ft.CHAIR_phrase,
  VICE_CHAIR: ft.VICE_CHAIR_phrase,
  SECRETARY: ft.SECRETARY_phrase,
  MEMBER: ft.MEMBER_phrase,
  STANDING_INVITEE: ft.STANDING_INVITEE_phrase,
  OBSERVER: ft.OBSERVER_phrase,
}

/** "the chair or a vice-chair": any one of the roles of a requirement. */
export function rolesText(intl: IntlShape, roles: Role[]): string {
  return intl.formatList(
    roles.map((r) => intl.formatMessage(rolePhrases[r])),
    { type: 'disjunction' },
  )
}

/** "voting members in office" or "members in office". */
export function whoText(intl: IntlShape, rule: Rule): string {
  return intl.formatMessage(rule.counts === 'VOTING' ? ft.whoVotingInOffice : ft.whoAllInOffice)
}

/** A label as it reads mid-sentence ("Non-executive" → "non-executive"). */
function midSentence(label: string, locale: string): string {
  return label.toLocaleLowerCase(locale)
}

/**
 * The rule read back as one sentence (ADR-0057, flow J4): "More than half of the voting members in office, and never
 * fewer than 2, including the chair or a vice-chair, with at least 2 of them independent." Built from the rule alone,
 * so it shows while the rule is being typed.
 */
export function quorumSentence(intl: IntlShape, rule: Rule, labels: Record<string, string>): string {
  const base =
    rule.basis === 'SEATS' ? intl.formatMessage(ftv.seatsBase, { seats: rule.seats ?? 0 }) : whoText(intl, rule)
  const t = rule.threshold
  const amount =
    t.kind === 'FRACTION'
      ? intl.formatMessage(ftv.fraction, {
          key: `${String(t.numerator ?? '')}_${String(t.denominator ?? '')}`,
          numerator: t.numerator ?? 0,
          denominator: t.denominator ?? 0,
          base,
        })
      : t.kind === 'COUNT'
        ? intl.formatMessage(ftv.amountCount, { count: t.count ?? 0, base })
        : intl.formatMessage(ftv.amountMajority, { base })
  const moot = t.kind === 'COUNT' && (t.count ?? 0) >= (rule.minimum ?? 0)
  const floor =
    rule.minimum && rule.minimum > 1 && !moot ? intl.formatMessage(ftv.floor, { minimum: rule.minimum }) : ''
  const sets = rule.required.filter((roles) => roles.length > 0)
  const including = sets.length
    ? intl.formatMessage(ftv.including, { roles: intl.formatList(sets.map((roles) => rolesText(intl, roles))) })
    : ''
  const composition = rule.composition
    .map((c) => {
      const label = midSentence(labels[c.attribute] ?? c.attribute, intl.locale)
      return c.majority
        ? intl.formatMessage(ftv.compositionMajority, { label })
        : intl.formatMessage(ftv.composition, { count: c.atLeast ?? 0, label })
    })
    .join('')
  return intl.formatMessage(ftv.sentence, { amount, floor, including, composition })
}

/** How alternates, remote attendance and conflicts count, one sentence each. */
export function quorumNotes(intl: IntlShape, rule: Rule): string[] {
  return [
    intl.formatMessage(rule.alternatesCount ? ft.alternatesYes : ft.alternatesNo),
    intl.formatMessage(rule.remoteCounts ? ft.remoteYes : ft.remoteNo),
    intl.formatMessage(rule.recusedCount ? ft.recusedYes : ft.recusedNo),
  ]
}
