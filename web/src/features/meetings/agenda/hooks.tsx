import { useState } from 'react'
import { useIntl } from 'react-intl'
import { addAgendaItem, moveAgendaRow } from '@/api/generated/sdk.gen'
import type { AgendaMoveRequest, SubmissionResponse } from '@/api/generated/types.gen'
import { FormDialog } from '@/features/forums/FormDialog'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import type { PlaceTarget } from './dnd'
import { whereNow, type Draft } from './draft'
import { ap, apv } from './messages'
import { lateness } from './place'
import { focusRow, useAgenda } from './useAgenda'

/** Says where [itemId] is now, after a move or an addition, in the words of flow O2. */
export function useWhere() {
  const intl = useIntl()
  return (draft: Draft, itemId: string, added = false): string => {
    const where = whereNow(draft, itemId)
    if (!where) return ''
    const values = {
      title: where.item.title,
      number: where.item.number ?? '',
      position: where.position,
      section: where.section,
      parent: where.parent?.title ?? '',
    }
    if (where.parent) return intl.formatMessage(added ? apv.addedUnder : apv.movedUnder, values)
    return intl.formatMessage(added ? apv.addedTo : where.item.number ? apv.movedNumbered : apv.moved, values)
  }
}

/** Sends one move and says where the item went; focus returns to its menu button (flow O2). */
export function useMove() {
  const { meeting, change, announce } = useAgenda()
  const where = useWhere()
  const [refusal, setRefusal] = useState<string>()
  const move = async (body: AgendaMoveRequest) => {
    setRefusal(undefined)
    const out = await change((headers) =>
      moveAgendaRow({ path: { meetingId: meeting.id }, headers, body, throwOnError: true }),
    )
    if (out.draft) {
      if (body.itemId) {
        announce(where(out.draft, body.itemId))
        focusRow(body.itemId)
      }
    } else {
      setRefusal(out.refusal)
      announce(out.refusal)
    }
  }
  return {
    move,
    refusal,
    clear: () => {
      setRefusal(undefined)
    },
  }
}

/** A submission's line in the tray and the dialogs: its memo number and subject. */
export function submissionTitle(s: SubmissionResponse): string {
  return s.memo ? `${s.memo.reference ?? ''} ${s.memo.subject}`.trim() : s.id
}

/**
 * Placing a submission from the tray (flow O2's "Add to agenda", or a drop): straight away while papers are on time;
 * once the paper deadline has passed, the committee's rule is said first (to the chair first, accepted as late, or not
 * accepted at all), and a refused placement is said in words.
 */
export function usePlacing() {
  const intl = useIntl()
  const terms = useTerms()
  const { draft, meeting, change, announce } = useAgenda()
  const where = useWhere()
  const [asking, setAsking] = useState<{ submission: SubmissionResponse; target?: PlaceTarget }>()
  const [refusal, setRefusal] = useState<string>()

  const place = async (submission: SubmissionResponse, target?: PlaceTarget): Promise<string | undefined> => {
    setRefusal(undefined)
    const before = new Set(draft.sections.flatMap((s) => s.items.map((i) => i.id)))
    const out = await change((headers) =>
      addAgendaItem({
        path: { meetingId: meeting.id },
        headers: { ...headers, 'Idempotency-Key': newIdempotencyKey() },
        body: {
          kind: 'SUBMISSION',
          submissionId: submission.id,
          sectionId: target?.sectionId ?? null,
          position: target?.position ?? null,
        },
        throwOnError: true,
      }),
    )
    if (out.draft) {
      const added = out.draft.sections.flatMap((s) => s.items).find((i) => !before.has(i.id))
      if (added) {
        announce(where(out.draft, added.id, true))
        focusRow(added.id)
      }
      return undefined
    }
    announce(out.refusal)
    return out.refusal
  }

  const ask = (submission: SubmissionResponse, target?: PlaceTarget) => {
    const late = lateness(intl, terms, draft)
    if (late) {
      setAsking({ submission, target })
      return
    }
    void place(submission, target).then(setRefusal)
  }

  const late = lateness(intl, terms, draft)
  const dialog =
    asking && late ? (
      <FormDialog
        title={intl.formatMessage(apv.placeTitle, { title: submissionTitle(asking.submission) })}
        description={late.text}
        submitLabel={intl.formatMessage(late.blocked ? ap.close : ap.addToAgenda)}
        cancelLabel={intl.formatMessage(ap.cancel)}
        onClose={() => {
          setAsking(undefined)
        }}
        onSubmit={async () => {
          if (late.blocked) {
            setAsking(undefined)
            return undefined
          }
          const refused = await place(asking.submission, asking.target)
          if (!refused) setAsking(undefined)
          return refused
        }}
      />
    ) : null

  return { ask, dialog, refusal }
}
