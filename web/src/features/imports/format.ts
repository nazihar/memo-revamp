import { defineMessages, type IntlShape } from 'react-intl'
import type { ImportBatchView } from '@/api/generated/types.gen'
import type { Tone } from '@/components/ui/badge'

export type BatchState = ImportBatchView['state']

/** A batch's state in words (states read as words, never colour alone: ux-review L1). */
export const stateLabels = defineMessages<BatchState>({
  OPEN: { id: 'imports.state.open', defaultMessage: 'Open' },
  DRY_RUN: { id: 'imports.state.dryRun', defaultMessage: 'Dry run running' },
  VALIDATED: { id: 'imports.state.validated', defaultMessage: 'Dry run done' },
  IMPORTING: { id: 'imports.state.importing', defaultMessage: 'Importing' },
  STOPPING: { id: 'imports.state.stopping', defaultMessage: 'Stopping' },
  STOPPED: { id: 'imports.state.stopped', defaultMessage: 'Stopped' },
  VERIFYING: { id: 'imports.state.verifying', defaultMessage: 'Checking' },
  COMPLETED: { id: 'imports.state.completed', defaultMessage: 'Completed' },
  CLOSED: { id: 'imports.state.closed', defaultMessage: 'Closed' },
  ABANDONED: { id: 'imports.state.abandoned', defaultMessage: 'Abandoned' },
})

export const stateTone: Record<BatchState, Tone> = {
  OPEN: 'neutral',
  DRY_RUN: 'warn',
  VALIDATED: 'ok',
  IMPORTING: 'warn',
  STOPPING: 'warn',
  STOPPED: 'warn',
  VERIFYING: 'warn',
  COMPLETED: 'ok',
  CLOSED: 'neutral',
  ABANDONED: 'neutral',
}

/** States in which a job is working on the batch, so the page polls it. */
export const BUSY: ReadonlySet<BatchState> = new Set(['DRY_RUN', 'IMPORTING', 'STOPPING', 'VERIFYING'])

/** A size in bytes for people (KB, MB, GB), in the reader's locale. */
export function bytesText(intl: IntlShape, bytes: number): string {
  const units = [
    ['gigabyte', 1024 ** 3],
    ['megabyte', 1024 ** 2],
    ['kilobyte', 1024],
  ] as const
  for (const [unit, size] of units) {
    if (bytes >= size || unit === 'kilobyte') {
      return intl.formatNumber(
        unit === 'kilobyte' ? Math.max(bytes > 0 ? 1 : 0, Math.round(bytes / size)) : bytes / size,
        {
          style: 'unit',
          unit,
          maximumFractionDigits: 1,
        },
      )
    }
  }
  return ''
}

/** A memo row's result in words. */
export const resultLabels = defineMessages({
  PENDING: { id: 'imports.result.pending', defaultMessage: 'Not checked' },
  READY: { id: 'imports.result.ready', defaultMessage: 'Ready' },
  REJECTED: { id: 'imports.result.rejected', defaultMessage: 'Rejected' },
  IMPORTING: { id: 'imports.result.importing', defaultMessage: 'Importing' },
  IMPORTED: { id: 'imports.result.imported', defaultMessage: 'Imported' },
  SKIPPED: { id: 'imports.result.skipped', defaultMessage: 'Skipped' },
  FAILED: { id: 'imports.result.failed', defaultMessage: 'Failed' },
  NOT_STARTED: { id: 'imports.result.notStarted', defaultMessage: 'Not started' },
})

/** A problem's level in words. */
export const levelLabels = defineMessages({
  BATCH: { id: 'imports.level.batch', defaultMessage: 'Batch' },
  ERROR: { id: 'imports.level.error', defaultMessage: 'Errors' },
  WARNING: { id: 'imports.level.warning', defaultMessage: 'Warnings' },
  FILE: { id: 'imports.level.file', defaultMessage: 'Files' },
  INFO: { id: 'imports.level.info', defaultMessage: 'Information' },
})

/** A sheet in words. */
export const sheetLabels = defineMessages({
  MEMOS: { id: 'imports.sheet.memos', defaultMessage: 'Memos' },
  FILES: { id: 'imports.sheet.files', defaultMessage: 'Files' },
  APPROVALS: { id: 'imports.sheet.approvals', defaultMessage: 'Approvals' },
  ORIGINALS: { id: 'imports.sheet.originals', defaultMessage: 'Originals' },
  BATCH: { id: 'imports.sheet.batch', defaultMessage: 'Batch' },
})
