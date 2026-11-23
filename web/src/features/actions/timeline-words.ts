import type { IntlShape } from 'react-intl'
import type { ActionEntryResponse, ActionRagOverride, ActionResponse } from '@/api/generated/types.gen'
import type { TermValues } from '@/features/terms/terms'
import { am } from './messages'
import { cancelWord, confidenceWord, day, howWord, type CancelKind } from './words'

/** The entries the timeline names; any other kind reads "Changed by {name}". */
const KINDS = {
  DRAFTED: am.entryDRAFTED,
  ISSUED: am.entryISSUED,
  UPDATE: am.entryUPDATE,
  STARTED: am.entrySTARTED,
  DUE_REQUESTED: am.entryDUE_REQUESTED,
  DUE_AGREED: am.entryDUE_AGREED,
  DUE_DECLINED: am.entryDUE_DECLINED,
  DUE_WITHDRAWN: am.entryDUE_WITHDRAWN,
  DUE_CHANGED: am.entryDUE_CHANGED,
  RAG_OVERRIDDEN: am.entryRAG_OVERRIDDEN,
  RAG_CLEARED: am.entryRAG_CLEARED,
  OWNER_CHANGED: am.entryOWNER_CHANGED,
  EDITED: am.entryEDITED,
  DONE: am.entryDONE,
  CLOSED: am.entryCLOSED,
  SENT_BACK: am.entrySENT_BACK,
  CANCELLED: am.entryCANCELLED,
  REOPENED: am.entryREOPENED,
  DISCARDED: am.entryDISCARDED,
  MEMO_RAISED: am.entryMEMO_RAISED,
  MEMO_LINKED: am.entryMEMO_LINKED,
  MEMO_DECIDED: am.entryMEMO_DECIDED,
  SOURCE_CORRECTED: am.entrySOURCE_CORRECTED,
  HELD_BACK: am.entryHELD_BACK,
  UPDATE_REQUESTED: am.entryUPDATE_REQUESTED,
  NOT_RUNNING: am.entryNOT_RUNNING,
  RECONCILED: am.entryRECONCILED,
} as const

const OUTCOMES = {
  APPROVED: am.outcomeAPPROVED,
  REJECTED: am.outcomeREJECTED,
  WITHDRAWN: am.outcomeWITHDRAWN,
  CANCELLED: am.outcomeCANCELLED,
} as const

const RAG_OVERRIDES: Record<string, keyof typeof am> = {
  RED: 'ragOVERRIDE_RED',
  AMBER: 'ragOVERRIDE_AMBER',
  GREEN: 'ragOVERRIDE_GREEN',
}

const text = (details: ActionEntryResponse['details'], key: string): string | undefined => {
  const value = details[key]
  return typeof value === 'string' ? value : undefined
}

/** One entry's headline in words: who did what, with the dates and memo it names; never an id. */
export function entryHeadline(
  intl: IntlShape,
  terms: TermValues,
  entry: ActionEntryResponse,
  action: ActionResponse,
): string {
  const name = entry.actor?.name ?? intl.formatMessage(am.system)
  const d = entry.details
  const date = (key: string) => {
    const v = text(d, key)
    return v ? day(intl, v) : ''
  }
  const memoId = text(d, 'memoId')
  const memo = action.memos.find((m) => m.memoId === memoId)
  const memoName =
    memo?.readable && memo.reference
      ? intl.formatMessage(am.memoNamed, { reference: memo.reference })
      : intl.formatMessage(am.memoUnnamed)
  const outcome = text(d, 'outcome')
  const kind = text(d, 'kind')
  const rag = text(d, 'rag')
  const values = {
    ...terms,
    name,
    date: date(entry.kind === 'ISSUED' || entry.kind === 'REOPENED' ? 'dueDate' : 'to'),
    from: date('from'),
    memo: memoName,
    outcome: intl.formatMessage(
      outcome && outcome in OUTCOMES ? OUTCOMES[outcome as keyof typeof OUTCOMES] : am.outcomeOther,
    ),
    kind: kind ? cancelWord(intl, kind as CancelKind) : '',
    rag: rag && RAG_OVERRIDES[rag] ? intl.formatMessage(am[RAG_OVERRIDES[rag]]) : '',
  }
  const known = entry.kind in KINDS ? KINDS[entry.kind as keyof typeof KINDS] : am.entryOther
  return intl.formatMessage(known, values)
}

/** Who wrote an update for the owner, and how ("S. Ahmed for A. Karim (by phone)"). */
export function onBehalf(intl: IntlShape, entry: ActionEntryResponse): string | undefined {
  if (!entry.onBehalfOf || !entry.actor) return undefined
  return intl.formatMessage(am.forOwner, {
    name: entry.actor.name,
    owner: entry.onBehalfOf.name,
    how: entry.how ? howWord(intl, entry.how) : '',
  })
}

/** "Set by S. Ahmed: Stress results delayed (until 5 December 2026)": who set the colour by hand, and why. */
export function overrideWords(intl: IntlShape, override: ActionRagOverride): string {
  const base = override.by
    ? intl.formatMessage(am.overrideBy, { name: override.by.name, reason: override.reason })
    : intl.formatMessage(am.overrideReason, { reason: override.reason })
  return override.until
    ? `${base} (${intl.formatMessage(am.overrideUntil, { date: day(intl, override.until) })})`
    : base
}

/** The entry's headline with how it is going, when the entry says ("A. Karim: update · At risk"). */
export function headlineOf(
  intl: IntlShape,
  terms: TermValues,
  entry: ActionEntryResponse,
  action: ActionResponse,
): string {
  const headline = onBehalf(intl, entry) ?? entryHeadline(intl, terms, entry, action)
  return entry.confidence
    ? intl.formatMessage(am.withConfidence, { headline, confidence: confidenceWord(intl, entry.confidence) })
    : headline
}
