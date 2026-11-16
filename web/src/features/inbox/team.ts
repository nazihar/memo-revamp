import { useQuery } from '@tanstack/react-query'
import type { IntlShape } from 'react-intl'
import { listTeamTasksOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { InboxMemo, Named } from '@/api/generated/types.gen'
import { referenceOf } from '@/features/memo/memo-fields'
import { mm } from '@/features/memo/memo-messages'

/**
 * Whether the viewer has a team view (design E05 §5): one small request, kept for a few minutes. Heads and holders of
 * `inbox.team.read` get the units they oversee; everyone else gets 403, and the Team tab stays hidden.
 */
export function useTeamAccess(enabled = true) {
  const probe = useQuery({
    ...listTeamTasksOptions({ query: { limit: 1 } }),
    enabled,
    staleTime: 5 * 60_000,
    retry: false,
  })
  return {
    pending: enabled && probe.isPending,
    visible: probe.isSuccess,
    units: probe.data?.units ?? ([] as Named[]),
  }
}

/** "M-2026-00042 · Office lease extension", or "Restricted memo" when the viewer may not read it. */
export function memoName(intl: IntlShape, memo: InboxMemo): string {
  if (memo.restricted) return intl.formatMessage(mm.restricted)
  return `${referenceOf(intl, memo.reference)} · ${memo.subject ?? ''}`
}
