import { Download } from 'lucide-react'
import { useId, useState } from 'react'
import { useIntl } from 'react-intl'
import type { ImportBatchView, ImportSourceView } from '@/api/generated/types.gen'
import { FileUpload } from '@/components/page/FileUpload'
import { Button } from '@/components/ui/button'
import { bytesText } from './format'
import { im, imv } from './messages'

/** Purpose of the metadata sources (design E06 §10); the owner is `{batchId}:{TABLE}`. */
export const SOURCE_PURPOSE = 'imports.source'
const MAX_SOURCE_BYTES = 20 * 1024 * 1024
const XLSX = '.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
const CSV = '.csv,text/csv'

type Table = ImportSourceView['table']

/**
 * Step 1, Metadata (ux-review L3): the template, then one workbook or a CSV file per table, each uploaded through the
 * files API (purpose `imports.source`). A workbook replaces CSV files and the reverse (the server keeps one kind).
 */
export function MetadataStep({ batch, onChanged }: { batch: ImportBatchView; onChanged: () => void }) {
  const intl = useIntl()
  const groupId = useId()
  const editable = batch.allowedActions.includes('ATTACH_SOURCE')
  const [kind, setKind] = useState<'XLSX' | 'CSV'>(batch.sourceKind ?? 'XLSX')
  const tables: { table: Table; label: string }[] =
    kind === 'XLSX'
      ? [{ table: 'WORKBOOK', label: intl.formatMessage(im.tableWorkbook) }]
      : [
          { table: 'MEMOS', label: intl.formatMessage(im.tableMemos) },
          { table: 'FILES', label: intl.formatMessage(im.tableFiles) },
          { table: 'APPROVALS', label: intl.formatMessage(im.tableApprovals) },
        ]
  const sourceOf = (table: Table) => batch.sources.find((s) => s.table === table)

  return (
    <section aria-labelledby="step-metadata" className="flex flex-col gap-4 rounded-md border p-4">
      <h2 id="step-metadata" className="text-base font-semibold">
        {intl.formatMessage(im.stepMetadata)}
      </h2>
      <div className="flex flex-wrap items-center gap-3">
        <p className="text-sm">{intl.formatMessage(im.metadataLead)}</p>
        <Button variant="outline" asChild>
          <a href="/api/v1/admin/imports/template" download>
            <Download aria-hidden="true" />
            {intl.formatMessage(im.template)}
          </a>
        </Button>
      </div>
      {editable ? (
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium">{intl.formatMessage(im.sourceKind)}</legend>
          {(['XLSX', 'CSV'] as const).map((value) => (
            <label key={value} className="flex min-h-8 items-center gap-2 text-sm">
              <input
                type="radio"
                name={groupId}
                value={value}
                checked={kind === value}
                className="size-4 accent-primary"
                onChange={() => {
                  setKind(value)
                }}
              />
              {intl.formatMessage(value === 'XLSX' ? im.kindWorkbook : im.kindCsv)}
            </label>
          ))}
        </fieldset>
      ) : null}
      {batch.sources.length === 0 && !editable ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(im.sourcesNone)}</p>
      ) : null}
      <div className="flex flex-col gap-4">
        {tables.map(({ table, label }) => {
          const source = sourceOf(table)
          return (
            <div key={table} className="flex flex-col gap-2">
              {source ? (
                <p className="text-sm" data-testid={`source-${table}`}>
                  {intl.formatMessage(imv.source, {
                    table: label,
                    name: source.name,
                    size: bytesText(intl, source.sizeBytes),
                    date: intl.formatDate(source.uploadedAt, { dateStyle: 'medium', timeStyle: 'short' }),
                  })}
                </p>
              ) : null}
              {editable ? (
                <FileUpload
                  label={source ? intl.formatMessage(imv.replaceTable, { table: label }) : label}
                  hint={intl.formatMessage(im.sourceHint)}
                  purpose={SOURCE_PURPOSE}
                  owner={`${batch.id}:${table}`}
                  accept={kind === 'XLSX' ? XLSX : CSV}
                  maxBytes={MAX_SOURCE_BYTES}
                  onUploaded={onChanged}
                />
              ) : null}
            </div>
          )
        })}
      </div>
      {kind === 'CSV' ? <p className="text-sm text-muted-foreground">{intl.formatMessage(im.csvTip)}</p> : null}
    </section>
  )
}
