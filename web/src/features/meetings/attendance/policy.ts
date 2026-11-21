import { useQuery } from '@tanstack/react-query'
import { getForumPolicyOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MeetingResponse, QuorumRule } from '@/api/generated/types.gen'

/**
 * The committee's member attribute labels ("Independent") for the quorum's conditions in words, and its quorum rule when
 * the committee type's published version is the one the meeting is pinned to (ADR-0060); otherwise the rule is unknown
 * here and the views fall back to the defaults (the server's numbers are always the ones shown).
 */
export function useQuorumPolicy(meeting: Pick<MeetingResponse, 'forumId' | 'typeVersion'>): {
  labels: Record<string, string>
  rule: QuorumRule | undefined
} {
  const policy = useQuery({ ...getForumPolicyOptions({ path: { forumId: meeting.forumId } }), staleTime: 5 * 60_000 })
  const data = policy.data
  const labels = Object.fromEntries((data?.memberAttributes ?? []).map((a) => [a.key ?? '', a.label ?? a.key ?? '']))
  return { labels, rule: data?.version === meeting.typeVersion ? data.quorum : undefined }
}
