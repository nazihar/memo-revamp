import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useIntl } from 'react-intl'
import {
  getMyNotificationPreferencesOptions,
  getMyNotificationPreferencesQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { setMyNotificationPreferences } from '@/api/generated/sdk.gen'
import type { PreferenceView } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Checkbox } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { categoryLabel, nm, nmv } from './notification-messages'

const CHANNELS = ['IN_APP', 'EMAIL'] as const

/** `/me/notifications` (design E06 §8): channels per category; security notices are always on and cannot be changed. */
export function NotificationPreferencesPage() {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const toast = useToast()
  const prefs = useQuery(getMyNotificationPreferencesOptions())
  const categories = [...new Set((prefs.data ?? []).map((p) => p.category))]
  const find = (category: string, channel: string): PreferenceView | undefined =>
    prefs.data?.find((p) => p.category === category && p.channel === channel)
  const change = async (category: string, channel: string, enabled: boolean) => {
    const updated = await setMyNotificationPreferences({ body: [{ category, channel, enabled }], throwOnError: true })
    queryClient.setQueryData(getMyNotificationPreferencesQueryKey(), updated.data)
    toast.show(intl.formatMessage(nm.saved))
  }
  const channelLabel = (channel: string) => intl.formatMessage(channel === 'EMAIL' ? nm.channelEmail : nm.channelInApp)

  return (
    <section aria-labelledby="prefs-title">
      <PageHeader
        title={<span id="prefs-title">{intl.formatMessage(nm.prefsTitle)}</span>}
        description={intl.formatMessage(nm.prefsLead)}
      />
      {prefs.isPending ? (
        <LoadingState rows={2} />
      ) : prefs.isError ? (
        <ErrorState onRetry={() => void prefs.refetch()} />
      ) : (
        <div className="overflow-x-auto rounded-md border">
          <table className="w-full text-sm">
            <caption className="sr-only">{intl.formatMessage(nm.prefsCaption)}</caption>
            <thead>
              <tr className="border-b bg-muted/50">
                <th scope="col" className="px-4 py-2 text-start font-medium">
                  {intl.formatMessage(nm.colCategory)}
                </th>
                {CHANNELS.map((channel) => (
                  <th key={channel} scope="col" className="px-4 py-2 text-center font-medium">
                    {channelLabel(channel)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {categories.map((category) => {
                const mandatory = find(category, 'IN_APP')?.mandatory ?? false
                const hintId = `pref-${category}-hint`
                return (
                  <tr key={category} className="border-b last:border-b-0">
                    <th scope="row" className="px-4 py-3 text-start font-normal">
                      <span className="block font-medium">{categoryLabel(intl, category)}</span>
                      {mandatory ? (
                        <span id={hintId} className="block text-xs text-muted-foreground">
                          {intl.formatMessage(nm.mandatory)}
                        </span>
                      ) : null}
                    </th>
                    {CHANNELS.map((channel) => {
                      const pref = find(category, channel)
                      return (
                        <td key={channel} className="px-4 py-3 text-center">
                          <Checkbox
                            aria-label={intl.formatMessage(nmv.prefCell, {
                              category: categoryLabel(intl, category),
                              channel: channelLabel(channel),
                            })}
                            aria-describedby={mandatory ? hintId : undefined}
                            checked={pref?.enabled ?? true}
                            disabled={mandatory}
                            onChange={(e) => void change(category, channel, e.target.checked)}
                          />
                        </td>
                      )
                    })}
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
