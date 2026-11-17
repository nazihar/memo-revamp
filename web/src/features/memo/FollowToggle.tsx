import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, BellRing } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { getMemoWatchOptions, getMemoWatchQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { unwatchMemo, watchMemo } from '@/api/generated/sdk.gen'
import type { WatchStatus } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast-context'

/** The memo page shows on every memo, so these are in the core catalogue (`follow.*`). */
const m = defineMessages({
  follow: { id: 'follow.follow', defaultMessage: 'Follow' },
  unfollow: { id: 'follow.unfollow', defaultMessage: 'Unfollow' },
  following: { id: 'follow.following', defaultMessage: 'Following · you get updates on this memo' },
  MANUAL: { id: 'follow.reason.manual', defaultMessage: 'you follow this' },
  INITIATED: { id: 'follow.reason.initiated', defaultMessage: 'you started it' },
  ACTED: { id: 'follow.reason.acted', defaultMessage: 'you acted on it' },
  MENTIONED: { id: 'follow.reason.mentioned', defaultMessage: 'you were mentioned' },
  failed: { id: 'follow.failed', defaultMessage: 'That did not work. Try again.' },
})

const mv = defineMessages<{ followingBecause: { reason: string } }>({
  followingBecause: {
    id: 'follow.followingBecause',
    defaultMessage: 'Following · you get updates on this memo ({reason})',
  },
})

/**
 * Follow a memo (flow J): "Follow", or "Following · you get updates on this memo (why) · Unfollow". Hidden when the
 * status cannot be read.
 */
export function FollowToggle({ memoId }: { memoId: string }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const key = getMemoWatchQueryKey({ path: { id: memoId } })
  const status = useQuery(getMemoWatchOptions({ path: { id: memoId } }))
  const [busy, setBusy] = useState(false)

  const change = async (watch: boolean) => {
    setBusy(true)
    try {
      const options = { path: { id: memoId }, throwOnError: true as const }
      if (watch) {
        const next = await watchMemo(options)
        queryClient.setQueryData<WatchStatus>(key, next.data)
      } else {
        await unwatchMemo(options)
        queryClient.setQueryData<WatchStatus>(key, { watching: false })
      }
      await queryClient.invalidateQueries({ queryKey: [{ _id: 'listWatching' }] })
    } catch {
      toast.show(intl.formatMessage(m.failed), 'error')
    } finally {
      setBusy(false)
    }
  }

  if (!status.isSuccess) return null
  const watching = status.data.watching
  return (
    <div className="flex flex-wrap items-center gap-2" aria-live="polite">
      {watching ? (
        <>
          <span className="flex items-center gap-1.5 text-sm">
            <BellRing aria-hidden="true" className="size-4" />
            <span>
              {status.data.reason
                ? intl.formatMessage(mv.followingBecause, { reason: intl.formatMessage(m[status.data.reason]) })
                : intl.formatMessage(m.following)}
            </span>
          </span>
          <Button variant="outline" disabled={busy} onClick={() => void change(false)}>
            {intl.formatMessage(m.unfollow)}
          </Button>
        </>
      ) : (
        <Button variant="outline" disabled={busy} onClick={() => void change(true)}>
          <Bell aria-hidden="true" />
          {intl.formatMessage(m.follow)}
        </Button>
      )}
    </div>
  )
}
