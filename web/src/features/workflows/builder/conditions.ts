/**
 * The simple form of branch conditions (design E06 §3): rows of attribute · comparison · value, joined by "all of"
 * (`&&`) or "any of" (`||`), written as CEL (ADR-0039). A condition this form did not write and cannot read stays CEL
 * text: [parseCondition] returns undefined, and the editor never rewrites it.
 */
export type AttributeKind = 'string' | 'int' | 'double' | 'bool' | 'timestamp'

export type Comparison = '==' | '!=' | '<' | '<=' | '>' | '>=' | 'in'

export type Value = string | number | boolean | (string | number)[]

export interface ConditionRow {
  attribute: string
  op: Comparison
  value: Value
}

export interface SimpleCondition {
  join: 'all' | 'any'
  rows: ConditionRow[]
}

/** The comparisons an attribute of [kind] offers; timestamps are CEL only. */
export function comparisonsFor(kind: AttributeKind): Comparison[] {
  switch (kind) {
    case 'int':
    case 'double':
      return ['==', '!=', '<', '<=', '>', '>=']
    case 'string':
      return ['==', '!=', 'in']
    case 'bool':
      return ['==']
    case 'timestamp':
      return []
  }
}

/** Splits [text] at top-level [separator]s, outside string literals and brackets. */
function splitTop(text: string, separator: string): string[] {
  const parts: string[] = []
  let depth = 0
  let quoted = false
  let start = 0
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quoted) {
      if (c === '\\') i++
      else if (c === '"') quoted = false
    } else if (c === '"') {
      quoted = true
    } else if (c === '[' || c === '(') {
      depth++
    } else if (c === ']' || c === ')') {
      depth--
    } else if (depth === 0 && text.startsWith(separator, i)) {
      parts.push(text.slice(start, i))
      start = i + separator.length
      i += separator.length - 1
    }
  }
  parts.push(text.slice(start))
  return parts.map((p) => p.trim())
}

function parseString(text: string): string | undefined {
  if (text.length < 2 || !text.startsWith('"') || !text.endsWith('"')) return undefined
  let out = ''
  for (let i = 1; i < text.length - 1; i++) {
    const c = text[i] ?? ''
    if (c === '"') return undefined
    if (c === '\\') {
      const next = text[i + 1]
      if (next !== '"' && next !== '\\') return undefined
      out += next
      i++
    } else {
      out += c
    }
  }
  return out
}

const INTEGER = /^-?\d+$/
const DECIMAL = /^-?\d+\.\d+$/

function parseScalar(text: string): string | number | boolean | undefined {
  if (text === 'true') return true
  if (text === 'false') return false
  if (INTEGER.test(text) || DECIMAL.test(text)) return Number(text)
  return parseString(text)
}

function parseValue(text: string, op: Comparison): Value | undefined {
  if (op !== 'in') {
    return parseScalar(text)
  }
  if (!text.startsWith('[') || !text.endsWith(']')) return undefined
  const inner = text.slice(1, -1).trim()
  if (inner === '') return undefined
  const items = splitTop(inner, ',').map(parseScalar)
  if (items.some((v) => v === undefined || typeof v === 'boolean')) return undefined
  return items as (string | number)[]
}

const OPS: Comparison[] = ['==', '!=', '<=', '>=', '<', '>']

function parseRow(term: string): ConditionRow | undefined {
  const head = /^item\.([A-Za-z]\w*)\s+/.exec(term)
  if (!head) return undefined
  const attribute = head[1] ?? ''
  const rest = term.slice(head[0].length)
  const op = rest.startsWith('in ') ? 'in' : OPS.find((o) => rest.startsWith(`${o} `))
  if (!op) return undefined
  const value = parseValue(rest.slice(op.length).trim(), op)
  return value === undefined ? undefined : { attribute, op, value }
}

/** [text] as rows, when it is one join of simple comparisons; otherwise undefined (left as CEL). */
export function parseCondition(text: string): SimpleCondition | undefined {
  const trimmed = text.trim()
  if (trimmed === '') return undefined
  const all = splitTop(trimmed, '&&')
  const any = splitTop(trimmed, '||')
  if (all.length > 1 && any.length > 1) return undefined
  const join = any.length > 1 ? 'any' : 'all'
  const rows = (join === 'any' ? any : all).map(parseRow)
  if (rows.some((r) => r === undefined)) return undefined
  return { join, rows: rows as ConditionRow[] }
}

function printString(value: string): string {
  return `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`
}

/** A number as CEL reads it for [kind]: a double always has a decimal point, or CEL refuses the comparison. */
function printNumber(value: number, kind: AttributeKind | undefined): string {
  const text = String(value)
  return kind === 'double' && Number.isInteger(value) ? `${text}.0` : text
}

function printScalar(value: string | number | boolean, kind: AttributeKind | undefined): string {
  if (typeof value === 'string') return printString(value)
  if (typeof value === 'number') return printNumber(value, kind)
  return value ? 'true' : 'false'
}

/** The rows as CEL; [kinds] gives each attribute's type (doubles print with a decimal point). */
export function printCondition(condition: SimpleCondition, kinds: Record<string, AttributeKind> = {}): string {
  const terms = condition.rows.map((row) => {
    const kind = kinds[row.attribute]
    const value = Array.isArray(row.value)
      ? `[${row.value.map((v) => printScalar(v, kind)).join(', ')}]`
      : printScalar(row.value, kind)
    return `item.${row.attribute} ${row.op} ${value}`
  })
  return terms.join(condition.join === 'any' ? ' || ' : ' && ')
}
