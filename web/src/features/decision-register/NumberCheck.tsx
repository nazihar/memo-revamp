import { useQuery } from '@tanstack/react-query'
import { ListChecks } from 'lucide-react'
import { useState } from 'react'
import { useIntl, type IntlShape } from 'react-intl'
import { checkResolutionRegisterOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { ResolutionCheckResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { useTerms } from '@/features/terms/terms'
import { drm } from './messages'

const SHOWN = 10

function numbers(intl: IntlShape, list: readonly number[], total = list.length) {
  const shown = intl.formatList(list.slice(0, SHOWN).map((n) => intl.formatNumber(n, { useGrouping: false })))
  return total > SHOWN ? intl.formatMessage(drm.checkMore, { numbers: shown, count: total - SHOWN }) : shown
}

/** The check in words: "2026: resolutions 1 to 16 are all accounted for (15 in force, 1 void)." */
function checkWords(intl: IntlShape, c: ResolutionCheckResponse): string[] {
  if (c.lastNumber === 0) return [intl.formatMessage(drm.checkNone, { period: c.period })]
  const range = { period: c.period, first: c.firstNumber, last: c.lastNumber }
  const lines = [
    c.missingCount === 0
      ? intl.formatMessage(drm.checkComplete, { ...range, inForce: c.inForce, void: c.void })
      : intl.formatMessage(drm.checkMissing, {
          ...range,
          count: c.missingCount,
          numbers: numbers(intl, c.missing, c.missingCount),
        }),
  ]
  if (c.duplicates.length > 0)
    lines.push(intl.formatMessage(drm.checkDuplicates, { numbers: numbers(intl, c.duplicates) }))
  if (c.outOfRange.length > 0)
    lines.push(intl.formatMessage(drm.checkOutOfRange, { numbers: numbers(intl, c.outOfRange) }))
  return lines
}

/**
 * "Check the numbers" (flow AS): E06's check of one committee's resolution numbers for its latest period, said in a
 * sentence, for officers and oversight. Asked for only when pressed.
 */
export function NumberCheck({ forumId }: { forumId: string }) {
  const intl = useIntl()
  const terms = useTerms()
  const [asked, setAsked] = useState(false)
  const check = useQuery({ ...checkResolutionRegisterOptions({ path: { forumId } }), enabled: asked, retry: false })
  const words = check.data
    ? checkWords(intl, check.data)
    : check.isError
      ? [
          isProblem(check.error) && check.error.status === 403
            ? intl.formatMessage(drm.checkForbidden, terms)
            : intl.formatMessage(drm.checkFailed),
        ]
      : asked
        ? [intl.formatMessage(drm.checking)]
        : []
  return (
    <div className="flex flex-col gap-2">
      <Button
        variant="outline"
        className="min-h-11 w-fit"
        disabled={asked && check.isFetching}
        onClick={() => {
          if (asked) void check.refetch()
          else setAsked(true)
        }}
      >
        <ListChecks aria-hidden="true" />
        {intl.formatMessage(drm.checkNumbers)}
      </Button>
      <div role="status" className="text-sm">
        {words.map((w) => (
          <p key={w}>{w}</p>
        ))}
      </div>
    </div>
  )
}
