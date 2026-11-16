import { useCallback, useMemo, useState } from 'react'
import type { IntlShape } from 'react-intl'
import { inboxBulk } from '@/api/generated/sdk.gen'
import type { BulkItemResult } from '@/api/generated/types.gen'
import { isProblem, PROBLEM, problemType } from '@/api/http'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { im, imv } from './inbox-messages'

/**
 * The bulk actions of the inbox (design E05 §4, ux-review flows I and J). There is no bulk decision: Approve, Return
 * and Reject are never sent in bulk (research R10), so they are not in this type.
 */
export type BulkAction = 'WATCH' | 'UNWATCH' | 'CLAIM' | 'RELEASE' | 'REASSIGN'

export type Outcome = BulkItemResult['outcome']

/** One bulk call's answer, with what to call each item when it did not go through. */
export interface BulkRun {
  action: BulkAction
  results: BulkItemResult[]
  names: Record<string, string>
}

/** The selected ids of a list; the selection keeps only ids still shown. */
export function useSelection(shown: readonly string[]) {
  const [picked, setPicked] = useState<ReadonlySet<string>>(new Set())
  const selected = useMemo(() => shown.filter((id) => picked.has(id)), [shown, picked])
  const toggle = useCallback((id: string, on: boolean) => {
    setPicked((current) => {
      const next = new Set(current)
      if (on) next.add(id)
      else next.delete(id)
      return next
    })
  }, [])
  const setAll = useCallback(
    (on: boolean) => {
      setPicked(on ? new Set(shown) : new Set())
    },
    [shown],
  )
  const clear = useCallback(() => {
    setPicked(new Set())
  }, [])
  return { selected, toggle, setAll, clear, has: (id: string) => picked.has(id) }
}

/**
 * Runs a bulk action: each item is checked on the server as if acted on alone, and the answer says how each went.
 * Reassign asks the person to confirm it is them first (step-up); cancelling leaves everything as it was.
 */
export function useBulk(intl: IntlShape, onDone: () => Promise<unknown>) {
  const withStepUp = useWithStepUp()
  const toast = useToast()
  const [run, setRun] = useState<BulkRun>()
  const [busy, setBusy] = useState(false)

  const perform = async (
    action: BulkAction,
    ids: string[],
    names: Record<string, string>,
    extra: { to?: string; reason?: string } = {},
  ): Promise<boolean> => {
    if (ids.length === 0) return false
    setBusy(true)
    try {
      const answer = await withStepUp(() => inboxBulk({ body: { action, ids, ...extra }, throwOnError: true }))
      setRun({ action, results: answer.data.results, names })
      await onDone()
      return true
    } catch (problem) {
      // Cancelling the confirmation is not an error.
      if (problemType(problem) !== PROBLEM.stepUpRequired) {
        const forbidden = isProblem(problem) && problem.status === 403
        toast.show(intl.formatMessage(forbidden ? im.forbiddenAll : im.failed), 'error')
      }
      return false
    } finally {
      setBusy(false)
    }
  }

  return {
    run,
    busy,
    perform,
    dismiss: () => {
      setRun(undefined)
    },
  }
}

const DONE = {
  CLAIM: imv.doneCLAIM,
  RELEASE: imv.doneRELEASE,
  WATCH: imv.doneWATCH,
  UNWATCH: imv.doneUNWATCH,
  REASSIGN: imv.doneREASSIGN,
}

const COUNT = { CHANGED: imv.countCHANGED, FORBIDDEN: imv.countFORBIDDEN, NOT_FOUND: imv.countNOT_FOUND }
const ITEM = { CHANGED: im.itemCHANGED, FORBIDDEN: im.itemFORBIDDEN, NOT_FOUND: im.itemNOT_FOUND }

/** "3 claimed · 1 not changed: it was decided or moved meanwhile". */
export function summaryOf(intl: IntlShape, run: BulkRun): string {
  const count = (outcome: Outcome) => run.results.filter((r) => r.outcome === outcome).length
  const done = count('OK')
  const parts = (['CHANGED', 'FORBIDDEN', 'NOT_FOUND'] as const)
    .filter((outcome) => count(outcome) > 0)
    .map((outcome) => intl.formatMessage(COUNT[outcome], { count: count(outcome) }))
  return [
    ...(done > 0 || parts.length === 0 ? [intl.formatMessage(DONE[run.action], { count: done })] : []),
    ...parts,
  ].join(' · ')
}

/** Each item that did not go through, with why. */
export function failuresOf(intl: IntlShape, run: BulkRun): { id: string; text: string }[] {
  return run.results
    .filter((r): r is BulkItemResult & { outcome: Exclude<Outcome, 'OK'> } => r.outcome !== 'OK')
    .map((r) => ({
      id: r.id,
      text: intl.formatMessage(imv.itemResult, {
        name: run.names[r.id] ?? r.id,
        result: intl.formatMessage(ITEM[r.outcome]),
      }),
    }))
}
