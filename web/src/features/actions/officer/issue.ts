import type { IntlShape } from 'react-intl'
import { issueMeetingActions } from '@/api/generated/sdk.gen'
import type { IssueResult } from '@/api/generated/types.gen'
import type { TermValues } from '@/features/terms/terms'
import { ao } from './messages'
import { reasonWords } from './problems'

/** "2 sent. 1 draft was not sent: Report covenant compliance: Choose who owns the action." */
export function issueWords(
  intl: IntlShape,
  terms: TermValues,
  results: IssueResult[],
  titles: Map<string, string>,
): string {
  const sent = results.filter((r) => r.issued).length
  const failed = results.filter((r) => !r.issued)
  if (!results.length) return intl.formatMessage(ao.sentNone)
  if (!failed.length) return intl.formatMessage(ao.sentAll, { count: sent })
  return [
    intl.formatMessage(ao.sentSome, { sent, failed: failed.length }),
    ...failed.map((r) =>
      intl.formatMessage(ao.notSentLine, {
        title: titles.get(r.actionId) ?? intl.formatMessage(ao.someone),
        why: reasonWords(intl, terms, r.reason),
      }),
    ),
  ].join('\n')
}

/** Sends a held meeting's drafts after "Mark as held", and says how it went for the toast. */
export async function sendHeldDrafts(
  intl: IntlShape,
  terms: TermValues,
  meetingId: string,
  key: string,
): Promise<string> {
  try {
    const results = await issueMeetingActions({
      path: { meetingId },
      headers: { 'Idempotency-Key': key },
      throwOnError: true,
    })
    const failed = results.data.results.filter((r) => !r.issued).length
    return failed
      ? intl.formatMessage(ao.heldNotSent, { failed })
      : intl.formatMessage(ao.heldSent, { count: results.data.results.length })
  } catch (problem) {
    return `${intl.formatMessage(ao.heldNotSent, { failed: 1 })} ${reasonWords(intl, terms, (problem as { reason?: string } | undefined)?.reason)}`
  }
}
