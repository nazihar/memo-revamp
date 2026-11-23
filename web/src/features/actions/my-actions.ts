import { useQuery } from '@tanstack/react-query'
import type { IntlShape } from 'react-intl'
import { getMyActionsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MyActionCounts } from '@/api/generated/types.gen'
import { am } from './messages'

const total = (c: MyActionCounts | undefined) => (c ? c.mine + c.toVerify + c.contributing : 0)

/**
 * The counts for the inbox's "My actions" tab ("My actions 3"): asked once with the smallest page. A person with no
 * actions sees no new tab (principle 6), unless they open it by its address.
 */
export function useMyActionCounts(): { counts?: MyActionCounts; visible: boolean } {
  const counts = useQuery(getMyActionsOptions({ query: { tab: 'MINE', limit: 1 } })).data?.counts
  return { counts, visible: total(counts) > 0 }
}

/** The tab's label: "My actions (3)", the count being what waits for the person (to do and to verify). */
export function myActionsTabLabel(intl: IntlShape, counts: MyActionCounts | undefined): string {
  const waiting = counts ? counts.mine + counts.toVerify : 0
  return waiting > 0 ? intl.formatMessage(am.tabActionsCount, { count: waiting }) : intl.formatMessage(am.tabActions)
}
