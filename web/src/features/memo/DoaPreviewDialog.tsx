import { useQuery } from '@tanstack/react-query'
import { useIntl } from 'react-intl'
import { previewMemoDoa } from '@/api/generated/sdk.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { mm, mmv } from './memo-messages'

/**
 * "Who approves this?" (design E04 §4.7, ux-review flow 4): the groups of the delegation of authority that apply to the
 * memo, the people who can give each group's approvals today, and how many of them the route reaches.
 */
export function DoaPreviewDialog({ memoId, onClose }: { memoId: string; onClose: () => void }) {
  const intl = useIntl()
  const preview = useQuery({
    queryKey: ['memo-doa-preview', memoId],
    queryFn: async () => (await previewMemoDoa({ path: { id: memoId }, throwOnError: true })).data,
    staleTime: 0,
  })

  const body = () => {
    if (preview.isPending) return <LoadingState />
    if (preview.isError) return <ErrorState onRetry={() => void preview.refetch()} />
    const data = preview.data
    if (data.reason === 'NO_DOA') return <p className="text-sm">{intl.formatMessage(mm.doaNone)}</p>
    if (data.reason === 'NO_RULE' || data.groups.length === 0)
      return <p className="text-sm">{intl.formatMessage(mm.doaNoRule)}</p>
    return (
      <div className="flex flex-col gap-3">
        {data.version != null && data.ruleName ? (
          <p className="text-sm text-muted-foreground">
            {intl.formatMessage(mmv.doaVersion, { version: data.version, rule: data.ruleName })}
          </p>
        ) : null}
        <ol className="flex flex-col gap-3">
          {data.groups.map((group, index) => (
            <li key={index} className="rounded-md border p-3">
              <p
                className={
                  group.covered < group.needed ? 'text-sm font-medium text-destructive' : 'text-sm font-medium'
                }
              >
                {intl.formatMessage(mmv.doaGroup, { group: index + 1, needed: group.needed, covered: group.covered })}
              </p>
              <p className="text-sm">
                {group.people.length > 0
                  ? group.people.map((p) => p.name).join(', ')
                  : intl.formatMessage(mm.doaNobody)}
              </p>
            </li>
          ))}
        </ol>
      </div>
    )
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(mm.doaPreviewTitle)}
      footer={
        <Button variant="outline" onClick={onClose}>
          {intl.formatMessage(mm.close)}
        </Button>
      }
    >
      {body()}
    </Dialog>
  )
}
