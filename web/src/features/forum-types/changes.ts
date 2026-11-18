import type { IntlShape } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'
import { meaningLabels } from './labels'
import { ft, ftv } from './messages'
import { quorumSentence } from './quorum-words'
import { OUTCOME_KINDS, same, type TypeSettings } from './settings'

/** One line of the changes view (flow J7): what changed, and its value before and after (one side for added or removed). */
export interface Change {
  id: string
  what: string
  before?: string
  after?: string
}

/** What differs between the published policy and this version, in the order of the tabs. */
export function changesBetween(
  intl: IntlShape,
  terms: TermValues,
  before: TypeSettings,
  after: TypeSettings,
  levelName: (id: string | null) => string,
): Change[] {
  const none = intl.formatMessage(ft.none)
  const text = (value: string | number | null | undefined) => (value == null || value === '' ? none : String(value))
  const yesNo = (value: boolean) => intl.formatMessage(value ? ft.yes : ft.no)
  const changes: Change[] = []
  const scalar = (id: string, what: string, a: string, b: string) => {
    if (a !== b) changes.push({ id, what, before: a, after: b })
  }

  scalar('name', intl.formatMessage(ft.name), before.name, after.name)
  scalar('description', intl.formatMessage(ft.description), text(before.description), text(after.description))
  scalar(
    'classification',
    intl.formatMessage(ft.classification),
    levelName(before.defaultClassificationId),
    levelName(after.defaultClassificationId),
  )
  scalar('retention', intl.formatMessage(ft.retention), text(before.retentionYears), text(after.retentionYears))

  for (const a of after.memberAttributes) {
    const was = before.memberAttributes.find((b) => b.key === a.key)
    const what = intl.formatMessage(ftv.whatAttribute, { label: a.label || a.key })
    if (!was) changes.push({ id: `attribute-${a.key}`, what, after: a.label })
    else scalar(`attribute-${a.key}`, what, was.label, a.label)
  }
  for (const b of before.memberAttributes) {
    if (!after.memberAttributes.some((a) => a.key === b.key)) {
      changes.push({
        id: `attribute-${b.key}`,
        what: intl.formatMessage(ftv.whatAttribute, { label: b.label || b.key }),
        before: b.label,
      })
    }
  }

  if (!same(before.quorum, after.quorum)) {
    const labels = (s: TypeSettings) => Object.fromEntries(s.memberAttributes.map((a) => [a.key, a.label]))
    changes.push({
      id: 'quorum',
      what: intl.formatMessage(ft.whatQuorum),
      before: quorumSentence(intl, before.quorum, labels(before)),
      after: quorumSentence(intl, after.quorum, labels(after)),
    })
  }

  for (const kind of OUTCOME_KINDS) {
    const meaning = intl.formatMessage(meaningLabels[kind], terms)
    const was = before.outcomes.find((o) => o.kind === kind)
    const now = after.outcomes.find((o) => o.kind === kind)
    if (!was && !now) continue
    if (!was && now) {
      changes.push({ id: `outcome-${kind}`, what: intl.formatMessage(ftv.whatOutcome, { meaning }), after: now.label })
    } else if (was && !now) {
      changes.push({ id: `outcome-${kind}`, what: intl.formatMessage(ftv.whatOutcome, { meaning }), before: was.label })
    } else if (was && now) {
      scalar(`outcome-${kind}-label`, intl.formatMessage(ftv.whatOutcomeLabel, { meaning }), was.label, now.label)
      scalar(
        `outcome-${kind}-resolution`,
        intl.formatMessage(ftv.whatOutcomeResolution, { meaning }),
        yesNo(was.resolution),
        yesNo(now.resolution),
      )
    }
  }

  scalar('termMonths', intl.formatMessage(ft.termMonths), text(before.termMonths), text(after.termMonths))
  scalar('maxTerms', intl.formatMessage(ft.maxTerms), text(before.maxTerms), text(after.maxTerms))
  const days = (s: TypeSettings) => {
    const list = s.reminderDays.filter((d): d is number => d != null)
    return list.length ? intl.formatList(list.map(String)) : none
  }
  scalar('reminders', intl.formatMessage(ft.whatReminders), days(before), days(after))
  return changes
}
