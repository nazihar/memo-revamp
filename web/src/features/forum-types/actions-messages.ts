import { defineMessages, type MessageDescriptor } from 'react-intl'

function plain<T extends Record<string, MessageDescriptor>>(
  messages: T,
): { readonly [K in keyof T]: MessageDescriptor } {
  return messages
}

/**
 * The committee type's Actions tab (Phase 4 E07 T10, flow AQ, design §1.13). It loads with the `forumActions` catalogue
 * part on the committee type pages only.
 */
export const fam = plain(
  defineMessages({
    tab: { id: 'forumActions.tab', defaultMessage: 'Actions' },
    lead: {
      id: 'forumActions.lead',
      defaultMessage:
        'How this type’s {forums} track their actions. Changes are reviewed by a second administrator, and apply to open actions from their next change.',
    },
    amber: { id: 'forumActions.amber', defaultMessage: 'Amber when due within' },
    amberHint: { id: 'forumActions.amberHint', defaultMessage: 'Working days, 1 to 60.' },
    workingDays: { id: 'forumActions.workingDays', defaultMessage: 'working days' },
    stale: { id: 'forumActions.stale', defaultMessage: '“No recent update” after' },
    staleUse: { id: 'forumActions.staleUse', defaultMessage: 'Use the “no recent update” rule' },
    staleHint: {
      id: 'forumActions.staleHint',
      defaultMessage: 'Working days without an update, 5 to 120, after which an action shows as amber.',
    },
    dueChange: { id: 'forumActions.dueChange', defaultMessage: 'A new due date needs' },
    dueSECRETARY_AGREES: { id: 'forumActions.due.secretary', defaultMessage: 'The secretary’s agreement' },
    dueCHAIR_AGREES: { id: 'forumActions.due.chair', defaultMessage: 'The chair’s agreement' },
    dueOWNER_WITH_REASON: {
      id: 'forumActions.due.reason',
      defaultMessage: 'Only a reason: the owner changes it, and the secretaries are told',
    },
    dueChangeHint: {
      id: 'forumActions.dueChangeHint',
      defaultMessage: 'The original date is always shown beside the new one.',
    },
    verifiedBy: { id: 'forumActions.verifiedBy', defaultMessage: 'Actions are verified by' },
    verifiedSECRETARY: { id: 'forumActions.verified.secretary', defaultMessage: 'A secretary' },
    verifiedCHAIR: { id: 'forumActions.verified.chair', defaultMessage: 'The chair' },
    verifiedHint: {
      id: 'forumActions.verifiedHint',
      defaultMessage:
        'Never the owner or someone involved in the action: another secretary, or the vice-chair, does it.',
    },
    evidence: { id: 'forumActions.evidence', defaultMessage: 'Evidence is required when an action is marked as done' },
    defaultDue: { id: 'forumActions.defaultDue', defaultMessage: 'Default due date' },
    defaultNEXT_MEETING: { id: 'forumActions.default.nextMeeting', defaultMessage: 'The next meeting' },
    defaultWORKING_DAYS: { id: 'forumActions.default.workingDays', defaultMessage: 'A number of working days' },
    defaultDays: { id: 'forumActions.defaultDays', defaultMessage: 'Working days' },
    defaultDaysHint: {
      id: 'forumActions.defaultDaysHint',
      defaultMessage: '1 to 120. Also used when no next meeting is scheduled.',
    },
    updates: { id: 'forumActions.updates', defaultMessage: 'Ask owners for an update' },
    updatesUse: {
      id: 'forumActions.updatesUse',
      defaultMessage: 'Ask owners for an update before the papers are due',
    },
    updatesDays: { id: 'forumActions.updatesDays', defaultMessage: 'Working days before the papers are due' },
    updatesHint: {
      id: 'forumActions.updatesHint',
      defaultMessage: '1 to 20. Owners of open actions with no update since the last meeting are asked once.',
    },
    definition: { id: 'forumActions.definition', defaultMessage: 'Reminders and escalation follow the workflow' },
    definitionHint: {
      id: 'forumActions.definitionHint',
      defaultMessage: 'A published workflow for action items. Running actions keep the version they started on.',
    },
    definitionMissing: { id: 'forumActions.definitionMissing', defaultMessage: '{key} (not published)' },
    openBuilder: { id: 'forumActions.openBuilder', defaultMessage: 'Open in the builder' },
    preset: { id: 'forumActions.preset', defaultMessage: 'Start from an example' },
    presetHint: {
      id: 'forumActions.presetHint',
      defaultMessage:
        'Copies only the actions settings of the example. Nothing applies until the version is published.',
    },
    presetChoose: { id: 'forumActions.presetChoose', defaultMessage: 'Choose an example' },
    presetUse: { id: 'forumActions.presetUse', defaultMessage: 'Use these values' },
    presetUsed: {
      id: 'forumActions.presetUsed',
      defaultMessage: 'The example’s values are in the draft. Save to keep them.',
    },
  }),
)
