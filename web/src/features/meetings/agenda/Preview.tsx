import { useQuery } from '@tanstack/react-query'
import { Eye, FileText } from 'lucide-react'
import { useId, useState } from 'react'
import { useIntl } from 'react-intl'
import { previewAgendaAsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MemberItemResponse } from '@/api/generated/types.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Select } from '@/components/ui/form-controls'
import { useTerms } from '@/features/terms/terms'
import { om, omv } from '@/features/meetings/declarations/officer-messages'
import { allItems, localTime } from './draft'
import { ap, apv, purposeLabels } from './messages'
import { agendaRefusal } from './problems'
import { useAgenda, type Person } from './useAgenda'

/** "Preview as… [person]" (flow O4): the draft as that person would read it if it were published now. */
export function PreviewAs() {
  const intl = useIntl()
  const { audience } = useAgenda()
  const id = useId()
  const [person, setPerson] = useState(audience[0]?.id ?? '')
  const [open, setOpen] = useState<Person>()
  if (!audience.length) return null
  return (
    <div className="flex flex-wrap items-end gap-2">
      <div className="flex flex-col gap-1.5">
        <label htmlFor={id} className="text-sm font-medium">
          {intl.formatMessage(ap.previewAs)}
        </label>
        <Select
          id={id}
          className="w-56"
          value={person}
          onChange={(e) => {
            setPerson(e.target.value)
          }}
        >
          {audience.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </Select>
      </div>
      <Button
        variant="outline"
        onClick={() => {
          setOpen(audience.find((p) => p.id === person))
        }}
      >
        <Eye aria-hidden="true" />
        {intl.formatMessage(ap.preview)}
      </Button>
      {open ? (
        <PreviewDialog
          person={open}
          onClose={() => {
            setOpen(undefined)
          }}
        />
      ) : null}
    </div>
  )
}

function PreviewDialog({ person, onClose }: { person: Person; onClose: () => void }) {
  const intl = useIntl()
  const terms = useTerms()
  const { meeting, draft } = useAgenda()
  // Items the previewing officer declared an interest in show as their own line only (E04 §1.7, flow T4).
  const ownRecusals = allItems(draft).some((i) => i.recused)
  const preview = useQuery(previewAgendaAsOptions({ path: { meetingId: meeting.id }, query: { as: person.id } }))
  return (
    <Dialog
      open
      onOpenChange={(o) => {
        if (!o) onClose()
      }}
      title={intl.formatMessage(apv.previewTitle, { name: person.name })}
      className="w-[min(48rem,calc(100vw-2rem))]"
    >
      <p
        role="note"
        className="mb-4 rounded-md border border-status-warn-fg/40 bg-status-warn-bg p-3 text-sm text-status-warn-fg"
      >
        {intl.formatMessage(apv.previewBanner, { name: person.name })}
        {ownRecusals ? <> {intl.formatMessage(om.previewHidden)}</> : null}
      </p>
      {preview.isPending ? <LoadingState rows={4} /> : null}
      {preview.isError ? (
        <ErrorState title={agendaRefusal(intl, terms, preview.error)} onRetry={() => void preview.refetch()} />
      ) : null}
      {preview.data ? (
        <div className="flex flex-col gap-4">
          {preview.data.sections.length === 0 ? <p>{intl.formatMessage(ap.previewNothing)}</p> : null}
          {preview.data.sections.map((s) => (
            <section
              key={s.id}
              aria-label={s.title ?? intl.formatMessage(ap.untitledSection)}
              className="flex flex-col gap-1"
            >
              {s.title ? (
                <h3 dir="auto" className="border-b pb-1 font-semibold">
                  {s.title}
                  {s.kind === 'CONSENT' ? (
                    <span className="ms-2 text-sm font-normal text-muted-foreground">
                      {intl.formatMessage(ap.consentTaken)}
                    </span>
                  ) : null}
                </h3>
              ) : null}
              <ol className="flex flex-col gap-2">
                {s.items.map((item) => (
                  <PreviewLine key={item.id} item={item} />
                ))}
              </ol>
            </section>
          ))}
        </div>
      ) : null}
    </Dialog>
  )
}

/** One line as the person reads it: in full, by title only ("— papers restricted"), or "Restricted item". */
function PreviewLine({ item }: { item: MemberItemResponse }) {
  const intl = useIntl()
  const number = item.number ? `${item.number} ` : ''
  const line = item.access !== 'HIDDEN' && item.title ? item.title : intl.formatMessage(ap.restrictedItem)
  const title = item.recused
    ? intl.formatMessage(omv.previewRecused, { title: line })
    : item.access === 'FULL'
      ? (item.title ?? '')
      : item.access === 'TITLE' && item.title
        ? intl.formatMessage(apv.titleRestricted, { title: item.title })
        : intl.formatMessage(ap.restrictedItem)
  return (
    <li className="flex flex-col gap-1">
      <p className="flex flex-wrap items-baseline gap-x-3">
        <span dir="auto" className="font-medium">
          {number}
          {title}
        </span>
        {item.localStartsAt ? (
          <span className="text-sm tabular-nums text-muted-foreground">
            {localTime(intl.locale, item.localStartsAt)}
          </span>
        ) : null}
        {item.purpose && item.access !== 'HIDDEN' ? (
          <span className="text-sm text-muted-foreground">{intl.formatMessage(purposeLabels[item.purpose])}</span>
        ) : null}
      </p>
      {item.late === 'LATE' || item.late === 'TABLED' ? (
        <p className="text-sm">{intl.formatMessage(item.late === 'LATE' ? ap.lateLATE : ap.lateTABLED)}</p>
      ) : null}
      {item.resolutionSought ? (
        <p dir="auto" className="text-sm text-muted-foreground">
          {item.resolutionSought}
        </p>
      ) : null}
      {item.papers.length ? (
        <ul className="flex flex-col gap-1">
          {item.papers.map((p) => (
            <li key={p.id} className="flex items-center gap-2 text-sm">
              <FileText aria-hidden="true" className="size-4 text-muted-foreground" />
              <span dir="auto">{p.title}</span>
              {p.pages ? (
                <span className="text-muted-foreground">{intl.formatMessage(apv.pages, { count: p.pages })}</span>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      {item.subItems.length ? (
        <ol className="flex flex-col gap-1 ps-6">
          {item.subItems.map((sub) => (
            <PreviewLine key={sub.id} item={sub} />
          ))}
        </ol>
      ) : null}
    </li>
  )
}
