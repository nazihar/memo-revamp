import { MarkerType, type Edge, type Node } from '@xyflow/react'
import type { IntlShape } from 'react-intl'
import type { FieldProblem } from '@/api/generated/types.gen'
import { bm, bmv } from './builder-messages'
import type { DefinitionBody, StepJson } from './definition'
import { describeType, describeWho, stepName, targetName, type Labels } from './describe'
import { layout } from './layout'
import { END_NODE, START_NODE, toGraph } from './model'
import { problemsByStep } from './problems'

/** What a step node shows. */
export interface StepData extends Record<string, unknown> {
  title: string
  type: string
  who: string
  problems: number
  reached: boolean
}

const SHORT = 40

function short(text: string): string {
  return text.length > SHORT ? `${text.slice(0, SHORT - 1)}…` : text
}

/** Nodes and edges for React Flow, laid out top-down, each with a name a screen reader can say (design E06 §7). */
export function diagram(
  intl: IntlShape,
  body: DefinitionBody,
  labels: Labels,
  problems: FieldProblem[],
  selected?: string,
  reached: ReadonlySet<string> = new Set(),
): { nodes: Node[]; edges: Edge[] } {
  const graph = toGraph(body)
  const positions = layout(graph)
  const byStep = problemsByStep(problems)
  const count = body.steps.length
  const nextOf = (step: StepJson) =>
    step.type === 'branch'
      ? intl.formatList(
          [...(step.branches ?? []).map((b) => b.next), step.otherwise ?? ''].map((t) =>
            targetName(intl, body.steps, t),
          ),
        )
      : targetName(intl, body.steps, step.next)
  const nodes: Node[] = graph.nodes.map((n) => {
    const position = positions.get(n.id) ?? { x: 0, y: 0 }
    if (n.kind !== 'step') {
      const label = intl.formatMessage(n.kind === 'start' ? bm.start : bm.end)
      return { id: n.id, type: 'marker', position, data: { label }, ariaLabel: label, draggable: false }
    }
    const step = n.step
    const who = describeWho(intl, labels, step)
    const data: StepData = {
      title: stepName(step),
      type: describeType(intl, step),
      who,
      problems: byStep.get(n.index)?.length ?? 0,
      reached: reached.has(n.id),
    }
    const described = intl.formatMessage(bmv.stepAria, {
      index: n.index + 1,
      count,
      type: data.type,
      name: data.title,
      who,
      next: nextOf(step),
    })
    const ariaLabel = data.reached ? intl.formatMessage(bmv.stepAriaReached, { label: described }) : described
    return { id: n.id, type: 'step', position, data, ariaLabel, draggable: false, selected: n.id === selected }
  })
  const nodeName = (id: string) =>
    id === START_NODE
      ? intl.formatMessage(bm.start)
      : id === END_NODE
        ? intl.formatMessage(bm.end)
        : targetName(intl, body.steps, id)
  const edges: Edge[] = graph.edges
    .filter((e) => !e.dangling)
    .map((e) => {
      const label =
        e.kind === 'branch'
          ? short(e.condition ?? '')
          : e.kind === 'otherwise'
            ? intl.formatMessage(bm.otherwise)
            : undefined
      const from = nodeName(e.source)
      const to = nodeName(e.target)
      // Every connection is named in words, never by step ids (React Flow's default).
      const ariaLabel =
        e.kind === 'branch'
          ? intl.formatMessage(bmv.edgeIf, { from, to, condition: e.condition ?? '' })
          : e.kind === 'otherwise'
            ? intl.formatMessage(bmv.edgeOtherwise, { from, to })
            : intl.formatMessage(bmv.edge, { from, to })
      return {
        id: e.id,
        source: e.source,
        target: e.target,
        type: 'smoothstep',
        markerEnd: { type: MarkerType.ArrowClosed },
        ariaLabel,
        ...(label ? { label } : {}),
      }
    })
  return { nodes, edges }
}
