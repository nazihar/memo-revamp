import { useIntl } from 'react-intl'
import type { WhatChangedResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'
import { useClosable } from './closable'
import { pm, pmv } from './messages'
import { changedLine, lineName, paperChanges } from './words'

/**
 * "Changes since you last looked" (flow P2): each revision after the last one the reader saw, its note, and its
 * changed lines as far as the reader may see them (a removed line is struck through).
 */
export function WhatChangedDialog({ changes, onClose }: { changes: WhatChangedResponse; onClose: () => void }) {
  const intl = useIntl()
  // Close closes the dialog itself, so focus returns to "Show changes".
  const dialog = useClosable(onClose)
  return (
    <Dialog
      open={dialog.open}
      onOpenChange={dialog.onOpenChange}
      title={intl.formatMessage(pm.changesTitle)}
      className="md:max-w-2xl"
      footer={
        <Button
          variant="outline"
          className="min-h-11"
          onClick={() => {
            dialog.close()
          }}
        >
          {intl.formatMessage(pm.close)}
        </Button>
      }
    >
      <div className="flex flex-col gap-5 text-lg">
        {changes.revisions.map((revision) => (
          <section
            key={revision.number}
            aria-labelledby={`changes-${String(revision.number)}`}
            className="flex flex-col gap-2"
          >
            <h3 id={`changes-${String(revision.number)}`} className="font-semibold">
              {intl.formatMessage(pmv.changesOf, {
                number: revision.number,
                date: intl.formatDate(revision.publishedAt, { day: 'numeric', month: 'long', year: 'numeric' }),
              })}
            </h3>
            {revision.note ? <p>{intl.formatMessage(pmv.note, { note: revision.note })}</p> : null}
            <ul className="flex list-disc flex-col gap-1 ps-6">
              {revision.items.map((item) => (
                <li key={item.id}>
                  <span className={cn(item.removed && 'line-through')}>
                    {item.flags.length ? changedLine(intl, item) : lineName(intl, item)}
                  </span>
                  {item.papers.length ? (
                    <ul className="flex list-[circle] flex-col ps-6">
                      {item.papers.map((paper) => (
                        <li key={paper.id} className={cn(paper.removed && 'line-through')}>
                          {intl.formatMessage(pmv.changedLine, {
                            line: paper.title,
                            changes: paperChanges(intl, paper.flags),
                          })}
                        </li>
                      ))}
                    </ul>
                  ) : null}
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </Dialog>
  )
}
