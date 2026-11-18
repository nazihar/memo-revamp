import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { previewAgendaPublicationOptions } from '@/api/generated/@tanstack/react-query.gen'
import { publishAgenda } from '@/api/generated/sdk.gen'
import type { ItemChangeResponse, PublishPreviewResponse } from '@/api/generated/types.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { refreshMeetings } from '@/features/meetings/shared'
import { newIdempotencyKey } from '@/lib/idempotency'
import { useQueryClient } from '@tanstack/react-query'
import { CheckField } from './CheckField'
import { allItems } from './draft'
import { ap, apv, flagLabels } from './messages'
import { useAgenda } from './useAgenda'

/** What stops a publication, in the server's order (design E03 §1.3), as sentences. */
function blockers(
  intl: ReturnType<typeof useIntl>,
  preview: PublishPreviewResponse,
  paperTitle: (id: string) => string,
): string[] {
  return [
    preview.meetingNotScheduled ? intl.formatMessage(ap.MEETING_NOT_SCHEDULED) : undefined,
    preview.chairAgreementMissing ? intl.formatMessage(ap.CHAIR_AGREEMENT_REQUIRED) : undefined,
    preview.unreadablePapers.length
      ? intl.formatMessage(apv.PAPER_NOT_READABLE, {
          titles: intl.formatList(preview.unreadablePapers.map(paperTitle)),
          count: preview.unreadablePapers.length,
        })
      : undefined,
    preview.nothingToPublish ? intl.formatMessage(ap.NOTHING_TO_PUBLISH) : undefined,
  ].filter((x): x is string => Boolean(x))
}

/** One line of the change summary: "Moved · Term loan, Delta Textiles — now item 5 (was 6)". */
function changeLine(intl: ReturnType<typeof useIntl>, c: ItemChangeResponse): string {
  const flags = intl.formatList(
    c.flags.map((f) => intl.formatMessage(flagLabels[f])),
    { type: 'unit' },
  )
  const item = c.number ? intl.formatMessage(apv.changeItem, { number: c.number, title: c.title }) : c.title
  return c.previousNumber && c.previousNumber !== c.number
    ? intl.formatMessage(apv.changeLineWas, { flags, item, was: c.previousNumber })
    : intl.formatMessage(apv.changeLine, { flags, item })
}

/**
 * Publish (flow O5): the next revision's number, what changed since the last one (as the publish preview computes it),
 * the note to members (required from revision 2), "Tell the members", what waits for the chair and is left out, and
 * whatever would stop the publication, in words, before the secretary presses Publish.
 */
export function PublishDialog({ onClose }: { onClose: () => void }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const { meeting, draft, change, announce } = useAgenda()
  const preview = useQuery(previewAgendaPublicationOptions({ path: { meetingId: meeting.id } }))
  const [note, setNote] = useState('')
  const [notify, setNotify] = useState(true)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const papers = allItems(draft).flatMap((i) => i.papers)
  const paperTitle = (id: string) => papers.find((p) => p.id === id)?.title ?? id
  const p = preview.data
  const revision = p?.revision ?? draft.currentRevision + 1

  const submit = async () => {
    if (!p) return undefined
    const stops = blockers(intl, p, paperTitle)
    if (stops.length) return stops.join(' ')
    if (p.noteRequired && !note.trim()) {
      setErrors({ note: intl.formatMessage(ap.noteRequired) })
      return undefined
    }
    setErrors({})
    const out = await change((headers) =>
      publishAgenda({
        path: { meetingId: meeting.id },
        headers: { ...headers, 'Idempotency-Key': newIdempotencyKey() },
        body: { note: note.trim() || null, notify },
        throwOnError: true,
      }),
    )
    if (out.draft) {
      const text = intl.formatMessage(apv.published, { revision: out.draft.currentRevision })
      toast.show(text, 'success')
      announce(text)
      void refreshMeetings(queryClient)
      onClose()
      return undefined
    }
    void preview.refetch()
    setErrors(out.fields)
    return Object.keys(out.fields).length ? undefined : out.refusal
  }

  const stops = p ? blockers(intl, p, paperTitle) : []
  return (
    <FormDialog
      title={intl.formatMessage(apv.publishTitle, { revision })}
      description={intl.formatMessage(revision === 1 ? ap.publishFirstLead : ap.publishLead)}
      submitLabel={intl.formatMessage(ap.publish)}
      cancelLabel={intl.formatMessage(ap.cancel)}
      onSubmit={submit}
      onClose={onClose}
    >
      {preview.isPending ? <LoadingState rows={3} /> : null}
      {preview.isError ? <ErrorState onRetry={() => void preview.refetch()} /> : null}
      {p ? (
        <>
          {stops.length ? (
            <ul className="flex flex-col gap-1 rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive">
              {stops.map((s) => (
                <li key={s}>{s}</li>
              ))}
            </ul>
          ) : null}
          {p.changes.length ? (
            <section aria-labelledby="publish-changes" className="flex flex-col gap-1">
              <h3 id="publish-changes" className="text-sm font-semibold">
                {revision === 1
                  ? intl.formatMessage(ap.publishFirstChanges)
                  : intl.formatMessage(apv.publishChanges, { revision: revision - 1 })}
              </h3>
              <ul className="list-disc ps-5 text-sm">
                {p.changes.map((c) => (
                  <li key={c.itemId} dir="auto" className={c.removed ? 'line-through' : undefined}>
                    {changeLine(intl, c)}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
          <Field
            label={intl.formatMessage(ap.note)}
            required={p.noteRequired}
            optionalLabel={intl.formatMessage(ap.optional)}
            hint={intl.formatMessage(ap.noteHint)}
            error={errors.note}
          >
            {(f) => (
              <Textarea
                {...f}
                dir="auto"
                rows={2}
                maxLength={500}
                value={note}
                onChange={(e) => {
                  setNote(e.target.value)
                }}
              />
            )}
          </Field>
          <CheckField
            label={intl.formatMessage(ap.notify)}
            hint={intl.formatMessage(ap.notifyHint)}
            checked={notify}
            onChange={setNotify}
          />
          {p.awaitingChair.length ? (
            <p className="text-sm">
              {intl.formatMessage(apv.awaitingLeftOut, {
                count: p.awaitingChair.length,
                titles: intl.formatList(p.awaitingChair.map((a) => a.title)),
              })}
            </p>
          ) : null}
        </>
      ) : null}
    </FormDialog>
  )
}
