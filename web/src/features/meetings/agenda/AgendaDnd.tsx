import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type Announcements,
  type DragEndEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { GripVertical } from 'lucide-react'
import type { ReactNode } from 'react'
import { useIntl } from 'react-intl'
import type { AgendaMoveRequest } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { locate, type Draft } from './draft'
import { dropMove, dropPlace, SECTION, TRAY, type DragData, type PlaceTarget } from './dnd'
import { ap, apv } from './messages'

/**
 * The drag layer of the agenda builder (flow O2, research R1): dnd-kit 6.3.1 with sortable 10.0.0, pinned. Dragging is
 * an extra: every move is also a Move-menu command calling the same endpoint (WCAG 2.2 SC 2.5.7), so this layer can be
 * swapped without touching the builder. A handle is dragged with the mouse, or focused and moved with Space, the arrow
 * keys and Space again; every step is announced in the reader's language.
 */

export function AgendaDnd({
  draft,
  onMove,
  onPlace,
  children,
}: {
  draft: Draft
  onMove: (body: AgendaMoveRequest) => void
  onPlace: (submissionId: string, target: PlaceTarget) => void
  children: ReactNode
}) {
  const intl = useIntl()
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const titleOf = (data: unknown) => (data as DragData | undefined)?.title ?? ''
  const placeOf = (overId: string) => {
    if (overId.startsWith(SECTION)) {
      const section = draft.sections.find((s) => s.id === overId.slice(SECTION.length))
      return { position: (section?.items.length ?? 0) + 1, section: section?.title ?? '' }
    }
    const at = locate(draft, overId)
    return { position: (at?.index ?? 0) + 1, section: at?.parent?.title ?? at?.section.title ?? '' }
  }
  const announcements: Announcements = {
    onDragStart: ({ active }) => intl.formatMessage(apv.dragStart, { title: titleOf(active.data.current) }),
    onDragOver: ({ active, over }) =>
      over
        ? intl.formatMessage(apv.dragOver, { title: titleOf(active.data.current), ...placeOf(String(over.id)) })
        : intl.formatMessage(apv.dragOutside, { title: titleOf(active.data.current) }),
    onDragEnd: ({ active, over }) =>
      over
        ? intl.formatMessage(apv.dragEnd, { title: titleOf(active.data.current), ...placeOf(String(over.id)) })
        : intl.formatMessage(apv.dragOutside, { title: titleOf(active.data.current) }),
    onDragCancel: ({ active }) => intl.formatMessage(apv.dragCancel, { title: titleOf(active.data.current) }),
  }

  const end = ({ active, over }: DragEndEvent) => {
    if (!over) return
    const id = String(active.id)
    if (id.startsWith(TRAY)) {
      const target = dropPlace(draft, String(over.id))
      if (target) onPlace(id.slice(TRAY.length), target)
      return
    }
    const body = dropMove(draft, id, String(over.id))
    if (body) onMove(body)
  }

  // The context is always there, so every row reads it the same way; rows that cannot move render no handle.
  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragEnd={end}
      accessibility={{
        announcements,
        screenReaderInstructions: { draggable: intl.formatMessage(ap.dragInstructions) },
      }}
    >
      {children}
    </DndContext>
  )
}

/** A list of items (a section's, or an item's sub-items) whose rows can be dragged among each other. */
export function SortableList({ ids, children }: { ids: string[]; children: ReactNode }) {
  return (
    <SortableContext items={ids} strategy={verticalListSortingStrategy}>
      {children}
    </SortableContext>
  )
}

/** One draggable row (a list item): [children] renders its content with the handle, absent when it cannot move. */
export function DragRow({
  id,
  title,
  enabled,
  className,
  children,
}: {
  id: string
  title: string
  enabled: boolean
  className?: string
  children: (handle: ReactNode) => ReactNode
}) {
  const intl = useIntl()
  const { setNodeRef, setActivatorNodeRef, transform, transition, attributes, listeners, isDragging } = useSortable({
    id,
    data: { type: 'item', title } satisfies DragData,
    disabled: !enabled,
  })
  const handle = enabled ? (
    <Button
      ref={setActivatorNodeRef}
      variant="ghost"
      size="icon"
      className="cursor-grab touch-none text-muted-foreground"
      aria-label={intl.formatMessage(apv.dragHandle, { title })}
      {...attributes}
      {...listeners}
    >
      <GripVertical aria-hidden="true" />
    </Button>
  ) : null
  return (
    <li
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      data-dnd-row
      className={cn(className, isDragging && 'relative z-10 bg-card shadow-md')}
    >
      {children(handle)}
    </li>
  )
}

/** An empty section, where an item or a tray submission can be dropped. */
export function EmptySectionDrop({
  sectionId,
  enabled,
  children,
}: {
  sectionId: string
  enabled: boolean
  children: ReactNode
}) {
  const { setNodeRef, isOver } = useDroppable({ id: `${SECTION}${sectionId}`, disabled: !enabled })
  return (
    <div ref={setNodeRef} className={cn('rounded-md', isOver && 'bg-accent')}>
      {children}
    </div>
  )
}

/** The drag handle of a tray submission. */
export function TrayHandle({
  submissionId,
  title,
  enabled,
}: {
  submissionId: string
  title: string
  enabled: boolean
}) {
  const intl = useIntl()
  const { setNodeRef, transform, attributes, listeners } = useDraggable({
    id: `${TRAY}${submissionId}`,
    data: { type: 'tray', title } satisfies DragData,
    disabled: !enabled,
  })
  if (!enabled) return null
  return (
    <Button
      ref={setNodeRef}
      variant="ghost"
      size="icon"
      className="cursor-grab touch-none text-muted-foreground"
      style={{ transform: CSS.Translate.toString(transform) }}
      aria-label={intl.formatMessage(apv.dragTray, { title })}
      {...attributes}
      {...listeners}
    >
      <GripVertical aria-hidden="true" />
    </Button>
  )
}
