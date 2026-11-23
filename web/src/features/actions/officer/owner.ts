import type { ActionOwnerResponse, OwnerInput } from '@/api/generated/types.gen'
import type { Picked } from '@/features/org/PeoplePicker'

export type OwnerKind = 'PERSON' | 'POSITION'

/** The owner as the form holds it: a person or a position, picked from the directory. */
export interface OwnerChoice {
  kind: OwnerKind
  picked?: Picked
}

/** The owner a form starts from: the action's own (a person by name, a position by its title and holder). */
export function ownerChoice(owner: ActionOwnerResponse | null | undefined): OwnerChoice {
  if (owner?.kind === 'POSITION' && owner.position) {
    return {
      kind: 'POSITION',
      picked: {
        id: owner.position.id,
        label: owner.position.title,
        ...(owner.holder ? { detail: owner.holder.name } : {}),
      },
    }
  }
  if (owner?.person) return { kind: 'PERSON', picked: { id: owner.person.id, label: owner.person.name } }
  return { kind: 'POSITION' }
}

export function ownerInput(choice: OwnerChoice): OwnerInput | undefined {
  if (!choice.picked) return undefined
  return choice.kind === 'PERSON'
    ? { kind: 'PERSON', userId: choice.picked.id }
    : { kind: 'POSITION', positionId: choice.picked.id }
}
