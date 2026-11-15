import type { HistoryAction, NoteEntryView, StampView } from '@/api/generated/types.gen'

/** What the sheet shows in a round, in time order. */
export type Item =
  | { kind: 'note'; at: string; note: NoteEntryView }
  | { kind: 'stamp'; at: string; stamp: StampView; remark?: NoteEntryView; plainRemark?: string }
  | { kind: 'event'; at: string; action: HistoryAction }

const DECISIONS = new Set(['APPROVE', 'RECOMMEND', 'RETURN', 'REJECT'])

/** Rounds newest first; in each, entries by time. A decision's remark sits in its stamp; decided actions are not repeated. */
export function roundsOf(
  notes: NoteEntryView[],
  stamps: StampView[],
  actions: (HistoryAction & { round: number })[],
): [number, Item[]][] {
  const byRound = new Map<number, Item[]>()
  const add = (round: number, item: Item) => byRound.set(round, [...(byRound.get(round) ?? []), item])
  const usedRemarks = new Set<string>()
  for (const stamp of stamps) {
    const remark = notes.find(
      (n) =>
        n.kind === 'REMARK' &&
        n.round === stamp.round &&
        n.actionKind === stamp.decision &&
        n.author.id === stamp.person.id &&
        !usedRemarks.has(n.id),
    )
    if (remark) usedRemarks.add(remark.id)
    // A remark made before notes existed is only on the action.
    const plainRemark = remark
      ? undefined
      : (actions.find((a) => a.round === stamp.round && a.kind === stamp.decision && a.actor.id === stamp.person.id)
          ?.remark ?? undefined)
    add(stamp.round, { kind: 'stamp', at: stamp.decidedAt, stamp, remark, plainRemark })
  }
  for (const note of notes) if (!usedRemarks.has(note.id)) add(note.round, { kind: 'note', at: note.createdAt, note })
  const stamped = new Set(stamps.map((s) => `${s.round}|${s.decision}|${s.person.id}`))
  for (const action of actions) {
    const decided = DECISIONS.has(action.kind) && stamped.has(`${action.round}|${action.kind}|${action.actor.id}`)
    const remarked = notes.some(
      (n) => n.round === action.round && n.actionKind === action.kind && n.author.id === action.actor.id,
    )
    if (!decided && !remarked) add(action.round, { kind: 'event', at: action.occurredAt, action })
  }
  return [...byRound.entries()]
    .sort(([a], [b]) => b - a)
    .map(([round, items]) => [round, items.sort((x, y) => x.at.localeCompare(y.at))])
}
