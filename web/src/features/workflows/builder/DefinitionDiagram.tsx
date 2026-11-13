import { Background, Controls, Handle, Position, ReactFlow, type Node, type NodeProps } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { Footprints } from 'lucide-react'
import { useMemo, useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import type { FieldProblem } from '@/api/generated/types.gen'
import { Badge } from '@/components/ui/badge'
import { bm, bmv } from './builder-messages'
import type { DefinitionBody } from './definition'
import { stepName, type Labels } from './describe'
import { diagram, type StepData } from './diagram'
import type { Command } from './edit'
import { MARKER_SIZE, NODE_HEIGHT, NODE_WIDTH } from './layout'
import { START_NODE } from './model'
import { AddStep, StepPanel } from './StepPanel'

/** React Flow's own words, in the user's language (research R1: `ariaLabelConfig`). */
const flow = defineMessages({
  nodeDescription: {
    id: 'builder.flow.node',
    defaultMessage: 'Press Enter or Space to select a step, and Escape to clear the selection.',
  },
  edgeDescription: {
    id: 'builder.flow.edge',
    defaultMessage: 'Press Enter or Space to select a connection, and Escape to clear the selection.',
  },
  controls: { id: 'builder.flow.controls', defaultMessage: 'Diagram controls' },
  zoomIn: { id: 'builder.flow.zoomIn', defaultMessage: 'Zoom in' },
  zoomOut: { id: 'builder.flow.zoomOut', defaultMessage: 'Zoom out' },
  fitView: { id: 'builder.flow.fitView', defaultMessage: 'Fit the workflow to the view' },
  interactive: { id: 'builder.flow.interactive', defaultMessage: 'Toggle interactivity' },
  handle: { id: 'builder.flow.handle', defaultMessage: 'Connection point' },
})

interface MarkerData extends Record<string, unknown> {
  label: string
}

type StepNode = Node<StepData, 'step'>
type MarkerNode = Node<MarkerData, 'marker'>

function StepBox({ data }: NodeProps<StepNode>) {
  const intl = useIntl()
  return (
    <div
      data-reached={data.reached || undefined}
      data-change={data.change}
      className="flex h-full flex-col gap-0.5 overflow-hidden rounded-md border-2 border-foreground/60 bg-background px-3 py-2 text-start text-xs data-[reached]:border-primary data-[reached]:ring-2 data-[reached]:ring-primary data-[change]:border-dashed"
      style={{ width: NODE_WIDTH, height: NODE_HEIGHT }}
    >
      <Handle type="target" position={Position.Top} isConnectable={false} />
      <span className="flex items-center gap-2">
        <span className="font-medium text-muted-foreground">{data.type}</span>
        {data.problems > 0 ? (
          <Badge tone="danger">{intl.formatMessage(bmv.problems, { count: data.problems })}</Badge>
        ) : null}
        {data.change ? (
          <Badge tone={data.change === 'added' ? 'ok' : 'warn'}>
            {intl.formatMessage(data.change === 'added' ? bm.added : bm.changed)}
          </Badge>
        ) : null}
        {data.reached ? (
          <Footprints aria-label={intl.formatMessage(bm.reached)} className="ms-auto size-3.5 text-primary" />
        ) : null}
      </span>
      <span className="truncate text-sm font-semibold" dir="auto">
        {data.title}
      </span>
      <span className="truncate text-muted-foreground" dir="auto">
        {data.who}
      </span>
      <Handle type="source" position={Position.Bottom} isConnectable={false} />
    </div>
  )
}

function MarkerBox({ data }: NodeProps<MarkerNode>) {
  return (
    <div
      className="flex items-center justify-center rounded-full border-2 border-foreground/60 bg-muted text-sm font-semibold"
      style={{ width: MARKER_SIZE * 2, height: MARKER_SIZE }}
    >
      <Handle type="target" position={Position.Top} isConnectable={false} />
      {data.label}
      <Handle type="source" position={Position.Bottom} isConnectable={false} />
    </div>
  )
}

const nodeTypes = { step: StepBox, marker: MarkerBox }

/**
 * The diagram of a definition (design E06 §2, ADR-0042): read-only here, laid out automatically, nothing dragged.
 * The Steps view next to it carries the same content for keyboard and screen-reader use.
 */
export function DefinitionDiagram({
  body,
  labels,
  problems,
  onCommand,
  onLabel,
  reached,
  changes,
}: {
  body: DefinitionBody
  labels: Labels
  problems: FieldProblem[]
  /** Set for a draft the user may change: the selected step's panel opens beside the diagram. */
  onCommand?: (command: Command) => void
  /** Names picked in a panel before the draft is saved. */
  onLabel?: (id: string, label: string) => void
  /** Steps the last simulation reached. */
  reached?: ReadonlySet<string>
  /** Steps added or changed since the version compared with. */
  changes?: ReadonlyMap<string, 'added' | 'changed'>
}) {
  const intl = useIntl()
  const [selected, setSelected] = useState<string>()
  const { nodes, edges } = useMemo(
    () => diagram(intl, body, labels, problems, selected, reached, changes),
    [intl, body, labels, problems, selected, reached, changes],
  )
  const selectedStep = body.steps.find((s) => s.id === selected)
  const select = (id: string | undefined) => {
    setSelected(id !== undefined && body.steps.some((s) => s.id === id) ? id : undefined)
  }
  const ariaLabelConfig = useMemo(
    () => ({
      'node.a11yDescription.default': intl.formatMessage(flow.nodeDescription),
      'node.a11yDescription.keyboardDisabled': intl.formatMessage(flow.nodeDescription),
      'edge.a11yDescription.default': intl.formatMessage(flow.edgeDescription),
      'controls.ariaLabel': intl.formatMessage(flow.controls),
      'controls.zoomIn.ariaLabel': intl.formatMessage(flow.zoomIn),
      'controls.zoomOut.ariaLabel': intl.formatMessage(flow.zoomOut),
      'controls.fitView.ariaLabel': intl.formatMessage(flow.fitView),
      'controls.interactive.ariaLabel': intl.formatMessage(flow.interactive),
      'handle.ariaLabel': intl.formatMessage(flow.handle),
    }),
    [intl],
  )
  const figure = (
    <figure className="flex flex-col gap-1">
      <div
        role="group"
        aria-label={intl.formatMessage(bm.diagramLabel)}
        className="h-120 w-full rounded-md border"
        dir="ltr"
      >
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable
          nodesFocusable
          edgesFocusable
          disableKeyboardA11y
          fitView
          minZoom={0.2}
          ariaLabelConfig={ariaLabelConfig}
          onNodeClick={(_, node) => {
            select(node.id)
          }}
          onPaneClick={() => {
            select(undefined)
          }}
          onNodesChange={(changes) => {
            // Keyboard selection (Enter or Space on a focused node) arrives as a select change.
            for (const c of changes) if (c.type === 'select' && c.selected) select(c.id)
          }}
        >
          <Background />
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>
      <figcaption className="text-xs text-muted-foreground">{intl.formatMessage(bm.diagramHint)}</figcaption>
    </figure>
  )
  if (!onCommand) return figure
  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(18rem,1fr)]">
      {figure}
      <section aria-labelledby="builder-selected" className="rounded-md border p-3">
        <h4 id="builder-selected" className="mb-2 text-sm font-semibold" dir="auto">
          {selectedStep ? stepName(selectedStep) : intl.formatMessage(bm.selected)}
        </h4>
        {selectedStep ? (
          <StepPanel
            key={selectedStep.id}
            body={body}
            step={selectedStep}
            problems={problems}
            labels={labels}
            onLabel={onLabel ?? (() => undefined)}
            onCommand={onCommand}
          />
        ) : (
          <div className="flex flex-col gap-3">
            <p className="text-sm text-muted-foreground">{intl.formatMessage(bm.noSelection)}</p>
            <AddStep body={body} after={START_NODE} label={intl.formatMessage(bm.addFirst)} onCommand={onCommand} />
          </div>
        )}
      </section>
    </div>
  )
}
