import type { MemoField, MemoTypeVersionResponse, Numbering } from '@/api/generated/types.gen'

/** A memo-type version's settings as the editor holds them and the API takes them (`MemoTypeSettingsRequest`). */
export interface TypeSettings {
  name: string
  code: string
  defaultClassificationId?: string | null
  definitionKey: string
  routePolicy: MemoTypeVersionResponse['routePolicy']
  allowedApprovers: MemoTypeVersionResponse['allowedApprovers']
  doaCheck: boolean
  stepUpOnApprove: boolean
  fields: MemoField[]
  raiseScope: MemoTypeVersionResponse['raiseScope']
  numbering: Numbering
  retention?: MemoTypeVersionResponse['retention']
  attachmentRules: MemoTypeVersionResponse['attachmentRules']
  autoCloseDays?: number | null
}

export function settingsOf(v: MemoTypeVersionResponse): TypeSettings {
  return {
    name: v.name,
    code: v.code,
    defaultClassificationId: v.defaultClassificationId ?? null,
    definitionKey: v.definitionKey,
    routePolicy: v.routePolicy,
    allowedApprovers: v.allowedApprovers,
    doaCheck: v.doaCheck,
    stepUpOnApprove: v.stepUpOnApprove,
    fields: v.fields,
    raiseScope: v.raiseScope,
    numbering: v.numbering,
    retention: v.retention ?? null,
    attachmentRules: v.attachmentRules,
    autoCloseDays: v.autoCloseDays ?? null,
  }
}

/** Deep equality of plain JSON values, ignoring key order (what changed between two versions). */
export function same(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a == null || b == null) return (a ?? null) === (b ?? null)
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => same(x, b[i]))
  }
  if (typeof a === 'object' && typeof b === 'object') {
    const ka = Object.keys(a).filter((k) => (a as Record<string, unknown>)[k] != null)
    const kb = Object.keys(b).filter((k) => (b as Record<string, unknown>)[k] != null)
    return (
      ka.length === kb.length &&
      ka.every((k) => same((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]))
    )
  }
  return false
}

/** One line of the changes view: a setting or field that differs between the published version and this one. */
export interface Change {
  kind: 'added' | 'removed' | 'changed'
  what: string
}

/** The settings and fields that differ, by key; fields are compared by key, so reordering alone is not listed. */
export function changesBetween(before: TypeSettings | undefined, after: TypeSettings): Change[] {
  if (!before) return []
  const settings: (keyof TypeSettings)[] = [
    'name',
    'code',
    'defaultClassificationId',
    'definitionKey',
    'routePolicy',
    'allowedApprovers',
    'doaCheck',
    'stepUpOnApprove',
    'raiseScope',
    'numbering',
    'retention',
    'attachmentRules',
    'autoCloseDays',
  ]
  const changed: Change[] = settings
    .filter((k) => !same(before[k], after[k]))
    .map((k) => ({ kind: 'changed', what: k }))
  const old = new Map(before.fields.map((f) => [f.key, f]))
  const now = new Map(after.fields.map((f) => [f.key, f]))
  for (const f of after.fields) {
    const was = old.get(f.key)
    if (!was) changed.push({ kind: 'added', what: `field:${f.label}` })
    else if (!same(was, f)) changed.push({ kind: 'changed', what: `field:${f.label}` })
  }
  for (const f of before.fields) if (!now.has(f.key)) changed.push({ kind: 'removed', what: `field:${f.label}` })
  return changed
}
