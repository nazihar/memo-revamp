/**
 * The steps of an import batch, as its address names them (`?step=`). The route validates the address in the shell's
 * bundle, so this module imports nothing (the batch's messages would otherwise load on every first page).
 */
export const STEPS = ['metadata', 'originals', 'mappings', 'dryRun', 'import', 'report'] as const
export type StepId = (typeof STEPS)[number]
