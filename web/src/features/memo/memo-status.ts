import type { Tone } from '@/components/ui/badge'

export type MemoStatus = 'DRAFT' | 'IN_REVIEW' | 'RETURNED' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'DISCARDED'

/** Badge tone per memo status: work in progress warns, approval is ok, rejection is danger, other endings are neutral. */
export const statusTone: Record<MemoStatus, Tone> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'warn',
  RETURNED: 'warn',
  APPROVED: 'ok',
  REJECTED: 'danger',
  WITHDRAWN: 'neutral',
  DISCARDED: 'neutral',
}
