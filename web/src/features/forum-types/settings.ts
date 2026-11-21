import type {
  ActionsPolicy,
  AgendaPolicy,
  ForumTypeSettingsRequest,
  ForumTypeVersionResponse,
  MeetingPolicy,
  MinutesPolicy,
  Outcome,
  QuorumRule,
  QuorumSampleMember,
  SubmissionPolicy,
} from '@/api/generated/types.gen'

export type Role = NonNullable<QuorumSampleMember['role']>
export type OutcomeKind = NonNullable<Outcome['kind']>
export type ThresholdKind = 'MAJORITY' | 'FRACTION' | 'COUNT'

/** Every outcome kind in the order the screens list them (ADR-0056). */
export const OUTCOME_KINDS: OutcomeKind[] = [
  'APPROVED',
  'APPROVED_WITH_CONDITIONS',
  'REJECTED',
  'DEFERRED',
  'RETURNED',
  'REFERRED',
  'NOTED',
  'WITHDRAWN',
]

/** The roles a quorum may require (observers and standing invitees never count, ADR-0057). */
export const REQUIRED_ROLES: Role[] = ['CHAIR', 'VICE_CHAIR', 'SECRETARY', 'MEMBER']

/** A quorum rule as the builder holds it: every part present; numbers being typed may be empty (null). */
export interface Rule {
  counts: 'VOTING' | 'ALL'
  basis: 'IN_OFFICE' | 'SEATS'
  seats: number | null
  threshold: { kind: ThresholdKind; numerator: number | null; denominator: number | null; count: number | null }
  minimum: number | null
  required: Role[][]
  composition: { attribute: string; atLeast: number | null; majority: boolean }[]
  alternatesCount: boolean
  remoteCounts: boolean
  recusedCount: boolean
}

export interface Attribute {
  key: string
  label: string
}

/** A committee-type version's policy as the editor holds it (`ForumTypeSettingsRequest`, with every part present). */
export interface TypeSettings {
  name: string
  description: string
  defaultClassificationId: string | null
  retentionYears: number | null
  memberAttributes: Attribute[]
  quorum: Rule
  outcomes: { kind: OutcomeKind; label: string; resolution: boolean }[]
  termMonths: number | null
  maxTerms: number | null
  reminderDays: (number | null)[]
  /** Papers for meetings (ADR-0059), edited on the Terms tab; empty numbers being typed are null. */
  submissionPolicy: Papers
  /** The agenda template and the chair's agreement (ADR-0060), edited on the Agenda tab (E03 T7). */
  agenda: AgendaPolicy
  /** Invitations, replies and the quorum override (Phase 4 E05), edited on the Meetings tab (E05 T8). */
  meetings: Meetings
  /** Minutes approval, memos moving on, resolution numbers, the template (Phase 4 E06); as loaded until E06 T10. */
  minutes: MinutesPolicy
  /** Action items: RAG, due dates, verification, evidence, update requests, the workflow (Phase 4 E07); as loaded until T10. */
  actions: ActionsPolicy
}

/**
 * The meeting policy as the editor holds it (design E05 §1.8): every part present, the server's defaults filled in; an
 * empty reply deadline being typed is null.
 */
export interface Meetings {
  invitationTitle: NonNullable<MeetingPolicy['invitationTitle']>
  neutralTitle: string
  replyDeadlineWorkingDays: number | null
  replyReminder: boolean
  meetingReminder: boolean
  quorumOverride: NonNullable<MeetingPolicy['quorumOverride']>
}

/** The longest reply deadline, in working days before the meeting (design E05 §1.8). */
export const MAX_REPLY_DAYS = 10

function meetingsOf(p: MeetingPolicy | undefined): Meetings {
  return {
    invitationTitle: p?.invitationTitle ?? 'NAME',
    neutralTitle: p?.neutralTitle ?? 'Committee meeting',
    replyDeadlineWorkingDays: p?.replyDeadlineWorkingDays ?? 2,
    replyReminder: p?.replyReminder ?? true,
    meetingReminder: p?.meetingReminder ?? true,
    quorumOverride: p?.quorumOverride ?? 'NEVER',
  }
}

/** The submission policy as the editor holds it: every part present, the server's defaults filled in. */
export interface Papers {
  deadlineWorkingDays: number | null
  lateMode: NonNullable<SubmissionPolicy['lateMode']>
  triageWorkingDays: number | null
}

export const LATE_MODES: Papers['lateMode'][] = ['CHAIR_APPROVAL', 'ALLOW', 'BLOCK']

export function papersOf(p: SubmissionPolicy | undefined): Papers {
  return {
    deadlineWorkingDays: p?.deadlineWorkingDays ?? 5,
    lateMode: p?.lateMode ?? 'CHAIR_APPROVAL',
    triageWorkingDays: p?.triageWorkingDays ?? 5,
  }
}

/** The rule with the server's defaults (ADR-0057) filled in. */
export function ruleOf(q: QuorumRule | undefined): Rule {
  return {
    counts: q?.counts ?? 'VOTING',
    basis: q?.basis ?? 'IN_OFFICE',
    seats: q?.seats ?? null,
    threshold: {
      kind: q?.threshold?.kind ?? 'MAJORITY',
      numerator: q?.threshold?.numerator ?? null,
      denominator: q?.threshold?.denominator ?? null,
      count: q?.threshold?.count ?? null,
    },
    minimum: q?.minimum ?? 2,
    required: q?.required ?? [],
    composition: (q?.composition ?? []).map((c) => ({
      attribute: c.attribute ?? '',
      atLeast: c.atLeast ?? null,
      majority: c.majority ?? false,
    })),
    alternatesCount: q?.alternatesCount ?? true,
    remoteCounts: q?.remoteCounts ?? true,
    recusedCount: q?.recusedCount ?? false,
  }
}

export function settingsOf(v: ForumTypeVersionResponse): TypeSettings {
  return {
    name: v.name,
    description: v.description ?? '',
    defaultClassificationId: v.defaultClassificationId ?? null,
    retentionYears: v.retention?.years ?? null,
    memberAttributes: v.memberAttributes.map((a) => ({ key: a.key ?? '', label: a.label ?? '' })),
    quorum: ruleOf(v.quorum),
    outcomes: v.outcomes.flatMap((o) =>
      o.kind ? [{ kind: o.kind, label: o.label ?? '', resolution: o.resolution ?? false }] : [],
    ),
    termMonths: v.termPolicy.termMonths ?? null,
    maxTerms: v.termPolicy.maxTerms ?? null,
    reminderDays: v.termPolicy.reminderDays ?? [],
    submissionPolicy: papersOf(v.submissionPolicy),
    agenda: v.agenda,
    meetings: meetingsOf(v.meetings),
    minutes: v.minutes,
    actions: v.actions,
  }
}

/** The rule as the API takes it; an empty "never fewer than" goes as 0, so the server names the field. */
export function ruleRequest(rule: Rule): QuorumRule {
  const t = rule.threshold
  return {
    counts: rule.counts,
    basis: rule.basis,
    seats: rule.basis === 'SEATS' ? rule.seats : null,
    threshold:
      t.kind === 'FRACTION'
        ? { kind: t.kind, numerator: t.numerator, denominator: t.denominator }
        : t.kind === 'COUNT'
          ? { kind: t.kind, count: t.count }
          : { kind: t.kind },
    minimum: rule.minimum ?? 0,
    required: rule.required,
    composition: rule.composition.map((c) =>
      c.majority ? { attribute: c.attribute, majority: true } : { attribute: c.attribute, atLeast: c.atLeast },
    ),
    alternatesCount: rule.alternatesCount,
    remoteCounts: rule.remoteCounts,
    recusedCount: rule.recusedCount,
  }
}

export function settingsRequest(s: TypeSettings): ForumTypeSettingsRequest {
  return {
    name: s.name,
    description: s.description.trim() || null,
    defaultClassificationId: s.defaultClassificationId,
    retention: s.retentionYears == null ? null : { years: s.retentionYears, from: 'MEETING' },
    memberAttributes: s.memberAttributes,
    quorum: ruleRequest(s.quorum),
    outcomes: s.outcomes,
    termPolicy: {
      termMonths: s.termMonths,
      maxTerms: s.maxTerms,
      reminderDays: s.reminderDays.filter((d): d is number => d != null),
    },
    // An empty number goes as -1, so the server names the field (RANGE) instead of quietly using its default.
    submissionPolicy: {
      deadlineWorkingDays: s.submissionPolicy.deadlineWorkingDays ?? -1,
      lateMode: s.submissionPolicy.lateMode,
      triageWorkingDays: s.submissionPolicy.triageWorkingDays ?? -1,
    },
    agenda: s.agenda,
    // An empty reply deadline goes as -1, so the server names the field (RANGE).
    meetings: { ...s.meetings, replyDeadlineWorkingDays: s.meetings.replyDeadlineWorkingDays ?? -1 },
    minutes: s.minutes,
    actions: s.actions,
  }
}

/** Deep equality of plain JSON values, ignoring key order and absent versus null. */
export function same(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a == null || b == null) return (a ?? null) === (b ?? null)
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => same(x, b[i]))
  }
  if (typeof a === 'object' && typeof b === 'object') {
    const ka = Object.keys(a).filter((k) => (a as Record<string, unknown>)[k] != null)
    const kb = Object.keys(b).filter((k) => (b as Record<string, unknown>)[k] != null)
    return (
      ka.length === kb.length &&
      ka.every((k) => same((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]))
    )
  }
  return false
}

/** A suggested attribute key from its label: `Non-executive` → `nonExecutive`; empty when the label has no Latin letters. */
export function keyFromLabel(label: string): string {
  const words = label
    .normalize('NFKD')
    .replace(/[^A-Za-z0-9]+/g, ' ')
    .trim()
    .split(' ')
    .filter(Boolean)
  const key = words
    .map((w, i) => (i === 0 ? w.toLowerCase() : w.slice(0, 1).toUpperCase() + w.slice(1).toLowerCase()))
    .join('')
    .replace(/^[0-9]+/, '')
  return key.slice(0, 30)
}

export const ATTRIBUTE_KEY = /^[a-z][a-zA-Z0-9]{0,29}$/
export const MAX_ATTRIBUTES = 8
export const MAX_REQUIRED = 3
export const MAX_COMPOSITION = 3
export const MAX_REMINDERS = 3

/**
 * Sample members for the quorum tester (flow J4): [inOffice] counting members of whom [present] attend; each required
 * role set ticked puts one of its roles among those present, and each attribute is held by that many of those present.
 */
export function sampleMembers(
  rule: Rule,
  inOffice: number,
  present: number,
  rolesPresent: boolean[],
  attributes: Record<string, number>,
): QuorumSampleMember[] {
  const n = Math.max(0, Math.min(100, Math.trunc(inOffice)))
  const p = Math.max(0, Math.min(n, Math.trunc(present)))
  const members = Array.from(
    { length: n },
    (_, i): { role: Role; voting: boolean; attributes: string[]; present: boolean } => ({
      role: 'MEMBER',
      voting: true,
      attributes: [] as string[],
      present: i < p,
    }),
  )
  rule.required.forEach((roles, j) => {
    const role = roles[0]
    const seat = members[j]
    if (rolesPresent[j] && role && seat?.present) seat.role = role
  })
  for (const [attribute, count] of Object.entries(attributes)) {
    members.slice(0, Math.max(0, Math.min(p, count))).forEach((m) => m.attributes.push(attribute))
  }
  return members
}
