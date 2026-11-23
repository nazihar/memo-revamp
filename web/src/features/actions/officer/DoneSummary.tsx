import { useQuery } from '@tanstack/react-query'
import { useIntl } from 'react-intl'
import { getActionEntriesOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { ActionResponse } from '@/api/generated/types.gen'
import { EvidenceLinks } from '../Evidence'
import { MemoLine } from '../FollowUpMemos'
import { am } from '../messages'
import { ao } from './messages'

/**
 * What the verifier checks before closing (flow AM1, design §1.9): who marked it done and when, what they wrote, the
 * evidence files, and the follow-up memos with their outcome (a memo the verifier cannot read shows its status only).
 */
export function DoneSummary({ action }: { action: ActionResponse }) {
  const intl = useIntl()
  const entries = useQuery(getActionEntriesOptions({ path: { actionId: action.id } }))
  const done = entries.data?.entries.find((e) => e.kind === 'DONE')
  return (
    <div className="flex flex-col gap-3 rounded-md border bg-muted/30 p-3 text-base">
      {done ? (
        <div className="flex flex-col gap-1">
          <p className="font-medium">
            {intl.formatMessage(ao.doneBy, {
              name: done.actor?.name ?? intl.formatMessage(ao.someone),
              date: intl.formatDate(done.at, { dateStyle: 'medium' }),
            })}
          </p>
          <p className="whitespace-pre-line">
            {done.text ? intl.formatMessage(am.quoted, { text: done.text }) : intl.formatMessage(ao.doneNoNote)}
          </p>
        </div>
      ) : null}
      <div className="flex flex-col gap-1">
        <p className="font-medium">{intl.formatMessage(ao.evidence)}</p>
        {done?.files.length ? (
          <EvidenceLinks files={done.files} />
        ) : (
          <p className="text-muted-foreground">{intl.formatMessage(ao.noEvidence)}</p>
        )}
      </div>
      {action.memos.length ? (
        <div className="flex flex-col gap-1">
          <p className="font-medium">{intl.formatMessage(ao.memos)}</p>
          <ul className="flex flex-col gap-1">
            {action.memos.map((m) => (
              <li key={m.memoId}>
                <MemoLine memo={m} />
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  )
}
