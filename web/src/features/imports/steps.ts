import type { MessageDescriptor } from 'react-intl'
import type { ImportBatchView } from '@/api/generated/types.gen'
import { im } from './messages'

export const STEPS = ['metadata', 'originals', 'mappings', 'dryRun', 'import', 'report'] as const
export type StepId = (typeof STEPS)[number]

export const stepLabels: Record<StepId, MessageDescriptor> = {
  metadata: im.stepMetadata,
  originals: im.stepOriginals,
  mappings: im.stepMappings,
  dryRun: im.stepDryRun,
  import: im.stepImport,
  report: im.stepReport,
}

const STARTED = new Set<ImportBatchView['state']>([
  'IMPORTING',
  'STOPPING',
  'STOPPED',
  'VERIFYING',
  'COMPLETED',
  'CLOSED',
])

/** Whether [step] is done for [batch] (ux-review L3: "① Metadata ✓"). */
export function stepDone(batch: ImportBatchView, step: StepId): boolean {
  switch (step) {
    case 'metadata':
      return batch.sources.length > 0
    case 'originals':
      return batch.originals.received > 0
    case 'mappings':
    case 'dryRun':
      return batch.state === 'VALIDATED' || STARTED.has(batch.state)
    case 'import':
      return ['VERIFYING', 'COMPLETED', 'CLOSED'].includes(batch.state)
    case 'report':
      return batch.state === 'COMPLETED' || batch.state === 'CLOSED'
  }
}

/** The step a batch opens on when the address names none: the next thing to do. */
export function defaultStep(batch: ImportBatchView): StepId {
  if (batch.state === 'COMPLETED' || batch.state === 'CLOSED') return 'report'
  if (STARTED.has(batch.state) || batch.state === 'VALIDATED') return 'import'
  if (batch.state === 'DRY_RUN' || batch.dryRunAt) return 'dryRun'
  if (batch.sources.length === 0) return 'metadata'
  if (batch.originals.received === 0 && batch.originals.expected === 0) return 'originals'
  return 'mappings'
}
