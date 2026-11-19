import type { IntlShape } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'
import { meaningLabels } from './labels'
import { ft, ftt, ftv } from './messages'
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
  const papers = (s: TypeSettings) => s.submissionPolicy
  scalar(
    'deadline',
    intl.formatMessage(ft.deadline),
    text(papers(before).deadlineWorkingDays),
    text(papers(after).deadlineWorkingDays),
  )
  scalar(
    'lateMode',
    intl.formatMessage(ft.lateMode),
    intl.formatMessage(ft[papers(before).lateMode]),
    intl.formatMessage(ft[papers(after).lateMode]),
  )
  scalar(
    'triage',
    intl.formatMessage(ft.triage),
    text(papers(before).triageWorkingDays),
    text(papers(after).triageWorkingDays),
  )
  agendaChanges(intl, before, after, yesNo, changes)

  // The meeting policy (Phase 4 E05, design E05 §1.8).
  const title = (s: TypeSettings) =>
    s.meetings.invitationTitle === 'NEUTRAL'
      ? intl.formatMessage(ftv.invitationNeutral, { title: s.meetings.neutralTitle ?? '' })
      : intl.formatMessage(ftt.invitationName, terms)
  scalar('invitationTitle', intl.formatMessage(ft.whatInvitationTitle), title(before), title(after))
  scalar(
    'replyDeadline',
    intl.formatMessage(ft.whatReplyDeadline),
    text(before.meetings.replyDeadlineWorkingDays),
    text(after.meetings.replyDeadlineWorkingDays),
  )
  const flag = (value: boolean | undefined) => yesNo(value ?? true)
  scalar(
    'replyReminder',
    intl.formatMessage(ft.whatReplyReminder),
    flag(before.meetings.replyReminder),
    flag(after.meetings.replyReminder),
  )
  scalar(
    'meetingReminder',
    intl.formatMessage(ft.whatMeetingReminder),
    flag(before.meetings.meetingReminder),
    flag(after.meetings.meetingReminder),
  )
  const override = (s: TypeSettings) =>
    intl.formatMessage(
      s.meetings.quorumOverride === 'WITH_REASON' ? ft.quorumOverrideWithReason : ft.quorumOverrideNever,
    )
  scalar('quorumOverride', intl.formatMessage(ft.whatQuorumOverride), override(before), override(after))
  minutesChanges(intl, before, after, text, changes)
  return changes
}

/** The minutes policy's lines (Phase 4 E06, design E06 §1.8): approval, its days, memos moving on, numbers, template. */
function minutesChanges(
  intl: IntlShape,
  before: TypeSettings,
  after: TypeSettings,
  text: (value: string | number | null | undefined) => string,
  changes: Change[],
) {
  const a = before.minutes
  const b = after.minutes
  const scalar = (id: string, what: string, x: string, y: string) => {
    if (x !== y) changes.push({ id, what, before: x, after: y })
  }
  const approval = (m: TypeSettings['minutes']) =>
    intl.formatMessage(m.approval === 'BY_CIRCULATION' ? ft.minutesByCirculation : ft.minutesAtNextMeeting)
  scalar('minutesApproval', intl.formatMessage(ft.whatMinutesApproval), approval(a), approval(b))
  scalar(
    'circulationDays',
    intl.formatMessage(ft.whatCirculationDays),
    text(a.circulationWorkingDays),
    text(b.circulationWorkingDays),
  )
  scalar('draftDays', intl.formatMessage(ft.whatDraftDays), text(a.draftWorkingDays), text(b.draftWorkingDays))
  const resumes = (m: TypeSettings['minutes']) =>
    intl.formatMessage(m.memoResumes === 'ON_MINUTES_APPROVAL' ? ft.memoResumesOnApproval : ft.memoResumesAtEnd)
  scalar('memoResumes', intl.formatMessage(ft.whatMemoResumes), resumes(a), resumes(b))
  scalar(
    'resolutionNumbering',
    intl.formatMessage(ft.whatResolutionNumbering),
    resolutionWords(intl, a.resolutionNumbering),
    resolutionWords(intl, b.resolutionNumbering),
  )
  if ((a.templateFileId ?? null) !== (b.templateFileId ?? null)) {
    const template = (id: string | null | undefined, other: boolean) =>
      intl.formatMessage(
        id == null ? ft.minutesTemplateBuiltIn : other ? ft.minutesTemplateNew : ft.minutesTemplateUploaded,
      )
    changes.push({
      id: 'minutesTemplate',
      what: intl.formatMessage(ft.whatMinutesTemplate),
      before: template(a.templateFileId, false),
      after: template(b.templateFileId, a.templateFileId != null),
    })
  }
}

/** A resolution numbering in words: its example ("BRC/2026/014") and when it starts again. */
function resolutionWords(intl: IntlShape, numbering: TypeSettings['minutes']['resolutionNumbering']): string {
  const month = intl.formatDate(new Date(Date.UTC(2026, numbering.fyStartMonth - 1, 1)), {
    month: 'long',
    timeZone: 'UTC',
  })
  const reset =
    numbering.reset === 'NEVER'
      ? intl.formatMessage(ft.resolutionResetNever)
      : numbering.reset === 'FINANCIAL_YEAR'
        ? intl.formatMessage(ftv.resolutionResetFinancialYear, { month })
        : intl.formatMessage(ft.resolutionResetYearly)
  return intl.formatMessage(ftv.resolutionNumbering, { example: resolutionExample(numbering), reset })
}

/**
 * The example a resolution numbering gives (flow AI): committee BRC, meeting 3, number 14, on 19 November 2026. The
 * server renders the live example on the Minutes tab; this one only tells two versions apart in the changes view.
 */
export function resolutionExample(numbering: TypeSettings['minutes']['resolutionNumbering']): string {
  const year = 2026
  const month = 11
  const fyStart = month >= numbering.fyStartMonth ? year : year - 1
  const fyEnd = numbering.fyStartMonth === 1 ? fyStart : fyStart + 1
  const two = (n: number) => String(n % 100).padStart(2, '0')
  return numbering.parts
    .map((part) => {
      switch (part.kind) {
        case 'TEXT':
          return part.text ?? ''
        case 'COMMITTEE_CODE':
          return 'BRC'
        case 'MEETING_NUMBER':
          return '3'
        case 'YEAR':
          return part.digits === 2 ? two(year) : String(year)
        case 'FINANCIAL_YEAR':
          if (part.style === 'SHORT') return `FY${two(fyEnd)}`
          return fyStart === fyEnd ? String(fyStart) : `${fyStart}-${two(fyEnd)}`
        case 'SEQUENCE':
          return '14'.padStart(part.digits ?? 1, '0')
      }
    })
    .join('')
}

/** The agenda policy's lines (flow R): the chair's agreement, sections and items by title, and the order of the sections. */
function agendaChanges(
  intl: IntlShape,
  before: TypeSettings,
  after: TypeSettings,
  yesNo: (value: boolean) => string,
  changes: Change[],
) {
  const a = before.agenda
  const b = after.agenda
  if (a.chairAgreement !== b.chairAgreement) {
    changes.push({
      id: 'agenda-chair',
      what: intl.formatMessage(ft.whatChairAgreement),
      before: yesNo(a.chairAgreement ?? true),
      after: yesNo(b.chairAgreement ?? true),
    })
  }
  type Section = NonNullable<typeof a.sections>[number]
  type Item = NonNullable<Section['items']>[number]
  const sectionValue = (s: Section) =>
    intl.formatMessage(ftv.agendaSectionValue, {
      kind: s.kind ?? 'STANDARD',
      membersOnly: String(s.membersOnly ?? false),
      receives: String(s.receivesSubmissions ?? false),
    })
  const itemValue = (i: Item) =>
    intl.formatMessage(ftv.agendaItemValue, {
      purpose: i.purpose ?? 'INFORMATION',
      minutes: String(i.minutes ?? 'none'),
    })
  const pair = <T>(was: T | undefined, now: T | undefined, id: string, what: string, value: (x: T) => string) => {
    if (!was && now) changes.push({ id, what, after: value(now) })
    else if (was && !now) changes.push({ id, what, before: value(was) })
    else if (was && now && value(was) !== value(now)) changes.push({ id, what, before: value(was), after: value(now) })
  }
  const sections = (p: typeof a) => p.sections ?? []
  const titles = [...new Set([...sections(b), ...sections(a)].map((s) => s.title))]
  for (const title of titles) {
    const was = sections(a).find((s) => s.title === title)
    const now = sections(b).find((s) => s.title === title)
    pair(was, now, `agenda-section-${title}`, intl.formatMessage(ftv.whatAgendaSection, { title }), sectionValue)
    const items = [...new Set([...(now?.items ?? []), ...(was?.items ?? [])].map((i) => i.title))]
    for (const item of items) {
      pair(
        was?.items?.find((i) => i.title === item),
        now?.items?.find((i) => i.title === item),
        `agenda-item-${title}-${item}`,
        intl.formatMessage(ftv.whatAgendaItem, { section: title, title: item }),
        itemValue,
      )
    }
  }
  const order = (p: typeof a, other: typeof a) =>
    sections(p)
      .map((s) => s.title)
      .filter((t) => sections(other).some((s) => s.title === t))
  if (order(a, b).join('\n') !== order(b, a).join('\n')) {
    changes.push({
      id: 'agenda-order',
      what: intl.formatMessage(ft.whatAgendaOrder),
      before: intl.formatList(order(a, b)),
      after: intl.formatList(order(b, a)),
    })
  }
}
