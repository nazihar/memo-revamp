import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { getWatchSettingsOptions, getWatchSettingsQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { saveWatchSettings } from '@/api/generated/sdk.gen'
import type { WatchSettings as Settings } from '@/api/generated/types.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { useToast } from '@/components/ui/toast-context'
import { nm } from './notification-messages'

type Email = NonNullable<Settings['email']>
const EMAILS: Email[] = ['DIGEST', 'IMMEDIATE', 'OFF']
const EMAIL_LABEL = { DIGEST: nm.watchEmailDIGEST, IMMEDIATE: nm.watchEmailIMMEDIATE, OFF: nm.watchEmailOFF }

/**
 * Following memos (flow J, design E05 §4): follow memos I create or act on (on by default), and email about followed
 * memos as a daily digest (default), at once or never. Each change saves at once, like the channels above.
 */
export function WatchSettings() {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const settings = useQuery(getWatchSettingsOptions())
  const [busy, setBusy] = useState(false)
  const autoWatch = settings.data?.autoWatch ?? true
  const email = settings.data?.email ?? 'DIGEST'

  const save = async (next: Settings) => {
    setBusy(true)
    try {
      const saved = await saveWatchSettings({ body: { autoWatch, email, ...next }, throwOnError: true })
      queryClient.setQueryData(getWatchSettingsQueryKey(), saved.data)
      toast.show(intl.formatMessage(nm.saved))
    } catch {
      toast.show(intl.formatMessage(nm.saveFailed), 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="watch-settings-title" className="mt-8">
      <h2 id="watch-settings-title" className="text-base font-semibold">
        {intl.formatMessage(nm.watchTitle)}
      </h2>
      <p className="mt-1 mb-4 max-w-prose text-sm text-muted-foreground">{intl.formatMessage(nm.watchLead)}</p>
      {settings.isPending ? (
        <LoadingState rows={2} />
      ) : settings.isError ? (
        <ErrorState onRetry={() => void settings.refetch()} />
      ) : (
        <div className="flex flex-col gap-6 rounded-md border bg-card p-4">
          <div className="flex items-start gap-3">
            <input
              id="watch-auto"
              type="checkbox"
              role="switch"
              aria-describedby="watch-auto-hint"
              className="mt-0.5 size-5 accent-primary"
              checked={autoWatch}
              disabled={busy}
              onChange={(e) => void save({ autoWatch: e.target.checked })}
            />
            <div className="flex flex-col gap-0.5">
              <label htmlFor="watch-auto" className="text-sm font-medium">
                {intl.formatMessage(nm.autoWatch)}
              </label>
              <span id="watch-auto-hint" className="text-xs text-muted-foreground">
                {intl.formatMessage(nm.autoWatchHint)}
              </span>
            </div>
          </div>
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-2 text-sm font-medium">{intl.formatMessage(nm.watchEmail)}</legend>
            {EMAILS.map((value) => (
              <label key={value} className="flex min-h-8 items-center gap-3 text-sm">
                <input
                  type="radio"
                  name="watch-email"
                  value={value}
                  className="size-4 accent-primary"
                  checked={email === value}
                  disabled={busy}
                  onChange={() => void save({ email: value })}
                />
                <span>{intl.formatMessage(EMAIL_LABEL[value])}</span>
              </label>
            ))}
          </fieldset>
        </div>
      )}
    </section>
  )
}
