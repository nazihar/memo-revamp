import { useQuery, type QueryClient } from '@tanstack/react-query'
import type { IntlShape } from 'react-intl'
import { getForumPolicyOptions, getMeetingDecisionsQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import type {
  DecisionResponse,
  ForumPolicyResponse,
  LiveQuorumResponse,
  MeetingResponse,
  MemberAgendaResponse,
  MemberItemResponse,
  Outcome as OutcomeDefinition,
} from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { dm, dmv } from './messages'

export type OutcomeKind = DecisionResponse['outcome']

/** One of the committee type's outcomes, as its secretary chooses it ("Approved with conditions"). */
export interface OutcomeChoice {
  kind: OutcomeKind
  label: string
  resolution: boolean
}

/** The statuses in which a secretary records or corrects decisions (design §1.1 windows). */
export const RECORDING: MeetingResponse['status'][] = ['IN_PROGRESS', 'HELD']

/** The statuses in which decisions exist and are shown (from the start of the meeting). */
export const WITH_DECISIONS: MeetingResponse['status'][] = ['IN_PROGRESS', 'HELD', 'MINUTES_APPROVED', 'CLOSED']

/** An agenda item as the decision panel lists it. */
export interface PanelItem {
  id: string
  number: string | null
  title: string | null
  purpose: MemberItemResponse['purpose']
  kind: MemberItemResponse['kind']
  resolutionSought: string | null
  sectionId: string
  /** In a consent section. */
  consent: boolean
  /** A consent item lifted for discussion at a member's request: decided on its own. */
  lifted: boolean
  recused: boolean
  papers: MemberItemResponse['papers']
}

/** The agenda's items in order (sub-items after their item), breaks left out. */
export function panelItems(agenda: MemberAgendaResponse | undefined): PanelItem[] {
  const out: PanelItem[] = []
  for (const section of agenda?.sections ?? []) {
    const add = (item: MemberItemResponse) => {
      if (item.kind !== 'BREAK') {
        out.push({
          id: item.id,
          number: item.number ?? null,
          title: item.title ?? null,
          purpose: item.purpose,
          kind: item.kind,
          resolutionSought: item.resolutionSought ?? null,
          sectionId: section.id,
          consent: section.kind === 'CONSENT',
          lifted: section.kind === 'CONSENT' && (item.discussion?.requestedBy.length ?? 0) > 0,
          recused: item.recused ?? false,
          papers: item.papers,
        })
      }
      item.subItems.forEach(add)
    }
    section.items.forEach(add)
  }
  return out
}

/** "Item 5 — Term loan, Delta Textiles", or what the item has. */
export function itemName(intl: IntlShape, item: Pick<PanelItem, 'number' | 'title'>): string {
  if (item.number && item.title) return intl.formatMessage(dmv.itemLine, { number: item.number, title: item.title })
  if (item.number) return intl.formatMessage(dmv.itemOnly, { number: item.number })
  return item.title ?? ''
}

/** "Item 5" at the start of a sentence, or the title when the item has no number. */
export function itemShort(intl: IntlShape, item: Pick<PanelItem, 'number' | 'title'>): string {
  return item.number ? intl.formatMessage(dmv.itemOnly, { number: item.number }) : (item.title ?? '')
}

/** "5, 7 and 9" for a list of items. */
export function numbers(intl: IntlShape, items: readonly Pick<PanelItem, 'number' | 'title'>[]): string {
  return intl.formatList(
    items.map((i) => i.number ?? i.title ?? ''),
    { type: 'conjunction' },
  )
}

/**
 * The committee type's outcomes and the two rules the decision screen says before acting (the quorum override and when
 * memos move on), from the committee's published policy. The rules are known only when the meeting is pinned to the
 * published version (ADR-0060); otherwise they are undefined and the screen lets the server decide and say so.
 */
export function useDecisionPolicy(meeting: Pick<MeetingResponse, 'forumId' | 'typeVersion'>): {
  outcomes: OutcomeChoice[]
  quorumOverride: ForumPolicyResponse['quorumOverride'] | undefined
  memoResumes: ForumPolicyResponse['memoResumes'] | undefined
  pending: boolean
} {
  const policy = useQuery({ ...getForumPolicyOptions({ path: { forumId: meeting.forumId } }), staleTime: 5 * 60_000 })
  const data = policy.data
  const pinned = data?.version === meeting.typeVersion
  return {
    outcomes: outcomeChoices(data?.outcomes ?? []),
    quorumOverride: pinned ? data.quorumOverride : undefined,
    memoResumes: pinned ? data.memoResumes : undefined,
    pending: policy.isPending,
  }
}

export function outcomeChoices(outcomes: readonly OutcomeDefinition[]): OutcomeChoice[] {
  return outcomes.flatMap((o) =>
    o.kind ? [{ kind: o.kind, label: o.label ?? o.kind, resolution: o.resolution ?? false }] : [],
  )
}

/** A person who may be named as dissenting or abstaining, or who took no part. */
export interface Person {
  id: string
  name: string
}

/**
 * From the item's quorum (the officers' seats): the people present who may vote on the item (a voting seat filled, by
 * its holder or an alternate standing in, not recused, not stepped out), and those present who take no part (declared an
 * interest, stepped out). The server checks the same against the snapshot it takes.
 */
export function voters(quorum: LiveQuorumResponse | undefined): {
  eligible: Person[]
  recused: Person[]
  steppedOut: Person[]
} {
  const seats = quorum?.seats ?? []
  const eligible = seats
    .filter((s) => s.voting && s.filledBy && !s.recused && !s.steppedOut)
    .map((s) => ({ id: s.filledBy?.userId ?? s.holder.userId, name: s.filledBy?.name ?? s.holder.name }))
  const recused = seats
    .filter((s) => s.recused && s.filledBy && !s.byAlternate)
    .map((s) => ({ id: s.holder.userId, name: s.holder.name }))
  const steppedOut = seats.filter((s) => s.steppedOut).map((s) => ({ id: s.holder.userId, name: s.holder.name }))
  return { eligible, recused, steppedOut }
}

/** Reads the decisions of the meeting again (after a write, or a refusal that means the screen is out of date). */
export function refreshDecisions(queryClient: QueryClient, meetingId: string): Promise<void> {
  return queryClient.invalidateQueries({ queryKey: getMeetingDecisionsQueryKey({ path: { meetingId } }) })
}

/** The 409, 403 and 412 refusals of recording a decision, in words; undefined for 422 (said at the fields). */
export function decisionRefusal(intl: IntlShape, problem: unknown): string | undefined {
  if (!isProblem(problem)) return intl.formatMessage(dm.failed)
  if (problem.status === 422) return undefined
  switch (problem.reason) {
    case 'RECUSED':
    case 'FROZEN':
    case 'WRONG_STATE':
    case 'RELEASED':
    case 'NUMBER_TAKEN':
    case 'NOTHING_TO_DECIDE':
      return intl.formatMessage(dm[problem.reason])
    case 'NOT_QUORATE': {
      const { present, needed } = shortfall(problem)
      return intl.formatMessage(dmv.notQuorateRefusal, { present, needed })
    }
  }
  if (problem.status === 412 || problem.status === 428) return intl.formatMessage(dm.stale)
  if (problem.status === 403 || problem.status === 404) return intl.formatMessage(dm.forbidden)
  return intl.formatMessage(dm.failed)
}

/** The numbers of a 409 `NOT_QUORATE` ("3 of 4 needed"). */
export function shortfall(problem: Problem): { present: number; needed: number } {
  const extra = problem as Problem & { present?: unknown; needed?: unknown }
  return {
    present: typeof extra.present === 'number' ? extra.present : 0,
    needed: typeof extra.needed === 'number' ? extra.needed : 0,
  }
}

const FIELD_CODES = [
  'OUTCOME_NOT_OFFERED',
  'TEXT_REQUIRED',
  'CONDITIONS_REQUIRED',
  'TARGET_REQUIRED',
  'SAME_FORUM',
  'REASON_REQUIRED',
  'NOT_PRESENT',
  'NOT_VOTING',
  'RECUSED_NAMED',
  'TOO_MANY_NAMED',
  'DUPLICATE',
  'BOTH',
  'VOTE_MODE',
  'TIME_REQUIRED',
  'TIME_OUT_OF_RANGE',
  'RATIFY_NOT_QUORATE',
  'OUTCOME',
  'RANGE',
  'LENGTH',
  'unknown',
] as const

/**
 * A 422's problems at their fields, in words: `votes:TOO_MANY_VOTES` names how many may vote ([voterCount]); the
 * people's problems (`dissent/2/userId`, `abstentions/0`) stay under their path so the screen shows them at the person.
 */
export function fieldProblems(intl: IntlShape, problem: unknown, voterCount: number): Record<string, string> {
  if (!isProblem(problem) || problem.status !== 422) return {}
  const out: Record<string, string> = {}
  for (const e of problem.errors ?? []) {
    const code = FIELD_CODES.find((c) => c === e.code)
    out[e.field] =
      e.code === 'TOO_MANY_VOTES'
        ? intl.formatMessage(dmv.tooManyVotes, { count: voterCount })
        : code
          ? intl.formatMessage(dm[code])
          : intl.formatMessage(dm.invalid)
  }
  return out
}

/** The decision's outcome as members read it: "APPROVED WITH CONDITIONS — Resolution BRC/2026/014". */
export function outcomeLine(intl: IntlShape, d: Pick<DecisionResponse, 'outcomeLabel' | 'resolutionNumber'>): string {
  const outcome = d.outcomeLabel.toLocaleUpperCase(intl.locale)
  return d.resolutionNumber
    ? intl.formatMessage(dmv.outcomeNumber, { outcome, number: d.resolutionNumber })
    : intl.formatMessage(dmv.outcomeCaps, { outcome })
}

/** The decision's state in the agenda list: "Approved with conditions — numbered when the meeting ends". */
export function stateLine(intl: IntlShape, d: DecisionResponse | undefined, meeting: Pick<MeetingResponse, 'status'>) {
  if (!d) return intl.formatMessage(dm.notYet)
  if (d.resolutionNumber)
    return intl.formatMessage(dmv.stateLine, { outcome: d.outcomeLabel, extra: d.resolutionNumber })
  if (d.resolution && meeting.status === 'IN_PROGRESS') {
    return intl.formatMessage(dmv.stateLine, { outcome: d.outcomeLabel, extra: intl.formatMessage(dm.numberedAtEnd) })
  }
  return d.outcomeLabel
}

/** The purpose of an item in words. */
export function purposeWords(intl: IntlShape, purpose: PanelItem['purpose']): string | undefined {
  if (purpose === 'DECISION') return intl.formatMessage(dm.forDecision)
  if (purpose === 'DISCUSSION') return intl.formatMessage(dm.forDiscussion)
  if (purpose === 'INFORMATION') return intl.formatMessage(dm.forInformation)
  return undefined
}

/** The default outcome a consent section gives an item (design §1.1 as built): approved for decision, else noted. */
export function consentOutcome(outcomes: readonly OutcomeChoice[], item: Pick<PanelItem, 'purpose'>) {
  const approved = outcomes.find((o) => o.kind === 'APPROVED')
  const noted = outcomes.find((o) => o.kind === 'NOTED')
  return item.purpose === 'DECISION' ? (approved ?? noted) : (noted ?? approved)
}
