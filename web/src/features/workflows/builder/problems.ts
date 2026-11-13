import type { FieldProblem } from '@/api/generated/types.gen'

/** The step a problem's JSON pointer (`/steps/3/sla/due`) points into, or undefined for the whole body. */
export function stepIndexOf(pointer: string | undefined): number | undefined {
  const match = /^\/steps\/(\d+)(?:\/|$)/.exec(pointer ?? '')
  return match ? Number(match[1]) : undefined
}

/** Problems grouped by the step they point into (design E06 §3). */
export function problemsByStep(problems: FieldProblem[]): Map<number, FieldProblem[]> {
  const grouped = new Map<number, FieldProblem[]>()
  for (const problem of problems) {
    const index = stepIndexOf(problem.field)
    if (index !== undefined) grouped.set(index, [...(grouped.get(index) ?? []), problem])
  }
  return grouped
}
