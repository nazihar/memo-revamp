import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useId, useState } from 'react'
import { useIntl } from 'react-intl'
import {
  checkResolutionRegisterOptions,
  getResolutionRegisterInfiniteOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import { startResolutionCounter } from '@/api/generated/sdk.gen'
import type { ForumResponse, ResolutionEntryResponse } from '@/api/generated/types.gen'
import { isProblem, problemType, PROBLEM } from '@/api/http'
import { TableScroll } from '@/components/page/TableScroll'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { rsm, rsmt, rsmv } from './messages'
import { checkWords } from './register'

/**
 * The committee's resolutions (flow AC1): the register for a period (the latest first), each number linking to its
 * resolution; a row the reader may not see in full says "Restricted"; a void number says why. "Check the register"
 * answers whether every number from the first to the last is accounted for. Administrators (`forums.manage`) set the
 * starting number for going live (flow AI, with a step-up).
 */
export function ResolutionsTab({ forum }: { forum: ForumResponse }) {
  const intl = useIntl()
  const terms = useTerms()
  const titleId = useId()
  const [period, setPeriod] = useState<string>()
  const [checking, setChecking] = useState(false)
  const [starting, setStarting] = useState(false)
  const query = { path: { forumId: forum.id }, query: { ...(period ? { period } : {}), limit: 50 } }
  const register = useInfiniteQuery({
    ...getResolutionRegisterInfiniteOptions(query),
    initialPageParam: query,
    getNextPageParam: (last) => last.next ?? undefined,
  })
  const shown = register.data?.pages[0]?.period ?? period
  const check = useQuery({
    ...checkResolutionRegisterOptions({ path: { forumId: forum.id }, query: shown ? { period: shown } : {} }),
    enabled: checking,
  })

  if (register.isPending) return <LoadingState rows={3} />
  if (register.isError) return <ErrorState onRetry={() => void register.refetch()} />
  const first = register.data.pages[0]
  const rows = register.data.pages.flatMap((p) => p.entries)
  const periods = first?.periods ?? []

  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h2 id={titleId} className="text-lg font-semibold">
            {intl.formatMessage(rsmv.registerTitle, { committee: forum.name })}
          </h2>
          <p className="text-sm text-muted-foreground">{intl.formatMessage(rsmt.lead, terms)}</p>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          {periods.length > 1 ? (
            <Field label={intl.formatMessage(rsm.year)} className="w-40">
              {(p) => (
                <Select
                  {...p}
                  className="min-h-11"
                  value={shown ?? ''}
                  onChange={(e) => {
                    setPeriod(e.target.value)
                    setChecking(false)
                  }}
                >
                  {periods.map((x) => (
                    <option key={x} value={x}>
                      {x}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          ) : null}
          {rows.length ? (
            <Button
              variant="outline"
              className="min-h-11"
              onClick={() => {
                setChecking(true)
                void check.refetch()
              }}
            >
              {intl.formatMessage(rsm.check)}
            </Button>
          ) : null}
          {forum.canManage ? (
            <Button
              variant="outline"
              className="min-h-11"
              onClick={() => {
                setStarting(true)
              }}
            >
              {intl.formatMessage(rsm.startNumber)}
            </Button>
          ) : null}
        </div>
      </div>
      {checking && check.data ? (
        <div role="status" className="rounded-md border p-3 text-base">
          {checkWords(intl, check.data).map((line) => (
            <p key={line}>{line}</p>
          ))}
        </div>
      ) : null}
      {rows.length === 0 ? (
        <p className="text-base">{intl.formatMessage(rsm.none)}</p>
      ) : (
        <TableScroll>
          <table className="w-full text-start text-base">
            <caption className="sr-only">{intl.formatMessage(rsmv.registerTitle, { committee: forum.name })}</caption>
            <thead>
              <tr className="border-b text-sm">
                <th scope="col" className="p-2 text-start">
                  {intl.formatMessage(rsm.colNumber)}
                </th>
                <th scope="col" className="p-2 text-start">
                  {intl.formatMessage(rsm.colMeeting)}
                </th>
                <th scope="col" className="p-2 text-start">
                  {intl.formatMessage(rsm.colItem)}
                </th>
                <th scope="col" className="p-2 text-start">
                  {intl.formatMessage(rsm.colOutcome)}
                </th>
                <th scope="col" className="p-2 text-start">
                  {intl.formatMessage(rsm.colMemo)}
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <Row key={r.id} entry={r} />
              ))}
            </tbody>
          </table>
        </TableScroll>
      )}
      {register.hasNextPage ? (
        <div>
          <Button
            variant="outline"
            className="min-h-11"
            disabled={register.isFetchingNextPage}
            onClick={() => void register.fetchNextPage()}
          >
            {intl.formatMessage(rsm.more)}
          </Button>
        </div>
      ) : null}
      {starting ? (
        <StartNumberDialog
          forumId={forum.id}
          period={shown ?? String(new Date().getFullYear())}
          onClose={() => {
            setStarting(false)
          }}
        />
      ) : null}
    </section>
  )
}

function Row({ entry: r }: { entry: ResolutionEntryResponse }) {
  const intl = useIntl()
  const restricted = intl.formatMessage(rsm.restricted)
  const outcome =
    r.state === 'VOID'
      ? r.voidReason
        ? intl.formatMessage(rsmv.voidReason, { reason: r.voidReason })
        : intl.formatMessage(rsm.void)
      : r.restricted
        ? restricted
        : (r.outcomeLabel ?? '')
  return (
    <tr className="border-b align-top">
      <th scope="row" className="p-2 text-start font-medium">
        <Link
          to="/resolutions/$resolutionId"
          params={{ resolutionId: r.id }}
          className="inline-flex min-h-11 items-center underline underline-offset-4"
          dir="ltr"
        >
          {r.number}
        </Link>
      </th>
      <td className="p-2">
        {r.meetingLabel ? (
          <Link
            to="/meetings/$meetingId"
            params={{ meetingId: r.meetingId }}
            className="inline-flex min-h-11 items-center underline-offset-4 hover:underline"
          >
            {r.meetingLabel}
          </Link>
        ) : null}
      </td>
      <td className="p-2">{[r.itemNumber, r.restricted ? restricted : r.itemTitle].filter(Boolean).join('  ')}</td>
      <td className="p-2">
        {r.state === 'VOID' ? <Badge tone="neutral">{intl.formatMessage(rsm.void)}</Badge> : null} {outcome}
      </td>
      <td className="p-2">
        {r.restricted ? (
          restricted
        ) : r.memo ? (
          <Link
            to="/memos/$memoId"
            params={{ memoId: r.memo.id }}
            className="inline-flex min-h-11 items-center underline-offset-4 hover:underline"
          >
            {r.memo.reference ?? intl.formatMessage(rsm.colMemo)}
          </Link>
        ) : (
          intl.formatMessage(rsm.noMemo)
        )}
      </td>
    </tr>
  )
}

/**
 * "Set the starting resolution number…" (flow AI, design §1.3): once per committee and period, before the first number,
 * by `forums.manage` with a fresh step-up. Refusals in words: `ALREADY_STARTED`, `FORMAT`, `RANGE`.
 */
function StartNumberDialog({ forumId, period, onClose }: { forumId: string; period: string; onClose: () => void }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const withStepUp = useWithStepUp()
  const [value, setValue] = useState(period)
  const [next, setNext] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const submit = async (): Promise<string | undefined> => {
    const n = Number(next)
    if (!Number.isInteger(n) || n < 1) {
      setErrors({ next: intl.formatMessage(rsm.RANGE) })
      return undefined
    }
    setErrors({})
    const key = newIdempotencyKey()
    try {
      await withStepUp(() =>
        startResolutionCounter({
          path: { forumId, period: value.trim() },
          body: { next: n },
          headers: { 'Idempotency-Key': key },
          throwOnError: true,
        }),
      )
      toast.show(intl.formatMessage(rsm.startDone), 'success')
      await queryClient.invalidateQueries({
        predicate: (q) => (q.queryKey[0] as { _id?: string } | undefined)?._id?.includes('Resolution') ?? false,
      })
      onClose()
      return undefined
    } catch (problem) {
      if (problemType(problem) === PROBLEM.stepUpRequired) return intl.formatMessage(rsm.stepUpCancelled)
      if (!isProblem(problem)) return intl.formatMessage(rsm.failed)
      const code = problem.reason ?? problem.errors?.[0]?.code
      if (code === 'ALREADY_STARTED') return intl.formatMessage(rsm.ALREADY_STARTED)
      if (code === 'FORMAT') {
        setErrors({ period: intl.formatMessage(rsm.FORMAT) })
        return undefined
      }
      if (code === 'RANGE') {
        setErrors({ next: intl.formatMessage(rsm.RANGE) })
        return undefined
      }
      return intl.formatMessage(rsm.failed)
    }
  }
  return (
    <FormDialog
      large
      title={intl.formatMessage(rsm.startTitle)}
      description={intl.formatMessage(rsm.startLead)}
      submitLabel={intl.formatMessage(rsm.startSubmit)}
      cancelLabel={intl.formatMessage(rsm.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      <Field
        label={intl.formatMessage(rsm.startPeriod)}
        hint={intl.formatMessage(rsm.startPeriodHint)}
        error={errors.period}
        required
      >
        {(p) => (
          <Input
            {...p}
            dir="ltr"
            className="min-h-11 text-base"
            value={value}
            onChange={(e) => {
              setValue(e.target.value)
            }}
          />
        )}
      </Field>
      <Field label={intl.formatMessage(rsm.startNext)} error={errors.next} required className="w-48">
        {(p) => (
          <Input
            {...p}
            inputMode="numeric"
            className="min-h-11 text-base"
            value={next}
            onChange={(e) => {
              setNext(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}
