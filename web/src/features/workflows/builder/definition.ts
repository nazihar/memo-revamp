/**
 * The workflow definition body (schema 2, ADR-0038) as the builder reads it (design E06 §2). Only the fields the builder
 * shows are typed; everything else is kept as it is, so a body written by hand or by a newer server survives a round trip.
 */
export type AssigneeKind = 'person' | 'initiator' | 'position' | 'supervisor' | 'unitHead' | 'role' | 'queue'

export interface AssigneeJson {
  kind: AssigneeKind
  userId?: string
  positionId?: string
  levels?: number
  /** A unit id, or `initiator` for the initiator's unit. */
  unit?: string
  up?: number
  roleId?: string
  queue?: string
}

export interface SlaJson {
  due: string
  workingTime?: boolean
  remind?: { before?: string; after?: string[] }
  escalate?: { after: string; action: 'notify' | 'reassign' | 'nextLevel'; to?: AssigneeJson }
}

export type StepType =
  | 'route'
  | 'review'
  | 'approve'
  | 'parallel'
  | 'doa'
  | 'branch'
  | 'notify'
  | 'subworkflow'
  | 'escalate-to-forum'
  | 'sign'

export interface BranchJson {
  when: string
  next: string
}

export interface StepJson {
  id: string
  type: StepType
  name?: string
  next?: string
  assignee?: AssigneeJson
  assignees?: AssigneeJson[]
  mode?: 'all' | 'any' | 'n-of-m'
  n?: number
  actions?: string[]
  remarks?: string[]
  forward?: { within: 'unit' | 'legalEntity' | 'anyone' }
  branches?: BranchJson[]
  otherwise?: string
  recipients?: AssigneeJson[]
  message?: string
  definition?: string
  forum?: string
  sla?: SlaJson
  fallback?: AssigneeJson
  [other: string]: unknown
}

export interface DefinitionBody {
  schemaVersion: number
  start: string
  steps: StepJson[]
  attributes?: { name: string; type: string }[]
  resubmit?: 'restart' | 'resume' | 'skip-unchanged'
  [other: string]: unknown
}

export const END = 'end'

/** Steps people act on; the others act and move on (ADR-0038). */
export const HUMAN_STEPS: ReadonlySet<StepType> = new Set(['route', 'review', 'approve', 'parallel', 'doa', 'sign'])

/**
 * [value] as a definition body when it has the shape the builder needs (a start and a list of steps with ids and types);
 * otherwise undefined, and the page stays on the JSON view. It does not validate: the server does (design E06 §3).
 */
export function asDefinition(value: unknown): DefinitionBody | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined
  const body = value as Record<string, unknown>
  if (typeof body.start !== 'string' || !Array.isArray(body.steps)) return undefined
  const steps = body.steps as unknown[]
  const wellFormed = steps.every(
    (s) =>
      s !== null &&
      typeof s === 'object' &&
      typeof (s as StepJson).id === 'string' &&
      typeof (s as StepJson).type === 'string',
  )
  return wellFormed ? (body as unknown as DefinitionBody) : undefined
}
