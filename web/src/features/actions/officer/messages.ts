import { defineMessages, type MessageDescriptor } from 'react-intl'

/** Plain descriptors: most of these take values (names, dates, counts and the organisation's terms). */
function plain<T extends Record<string, MessageDescriptor>>(
  messages: T,
): { readonly [K in keyof T]: MessageDescriptor } {
  return messages
}

/**
 * The secretary's side of committee actions (Phase 4 E07 T10, flows AJ, AM, AN): raising actions from the decision panel
 * and the meeting page, the action form, the drafts and "Send the drafts", the line in "Mark as held", the officers'
 * buttons on the action page, verification and closing at the meeting, and the committee's tracker. They load with the
 * `actionsOfficer` catalogue part (`/meetings`, `/committees`, `/actions`), never with core.
 */
export const ao = plain(
  defineMessages({
    // The action form (flow AJ2).
    newFromCondition: {
      id: 'actionsOfficer.form.newFromCondition',
      defaultMessage: 'New action — from {item}, condition {number, number}',
    },
    newFromItem: { id: 'actionsOfficer.form.newFromItem', defaultMessage: 'New action — from {item}' },
    newFromMeeting: { id: 'actionsOfficer.form.newFromMeeting', defaultMessage: 'New action — from this meeting' },
    newDirect: { id: 'actionsOfficer.form.newDirect', defaultMessage: 'New action for the {forum}' },
    editDraft: { id: 'actionsOfficer.form.editDraft', defaultMessage: 'Edit the draft action' },
    editIssued: { id: 'actionsOfficer.form.editIssued', defaultMessage: 'Edit {reference}' },
    title: { id: 'actionsOfficer.form.title', defaultMessage: 'What is to be done' },
    description: { id: 'actionsOfficer.form.description', defaultMessage: 'Details' },
    context: { id: 'actionsOfficer.form.context', defaultMessage: 'What the owner is told' },
    contextHint: {
      id: 'actionsOfficer.form.contextHint',
      defaultMessage: 'The owner will see this text. They will not see the item’s papers or the discussion.',
    },
    sourceNote: { id: 'actionsOfficer.form.sourceNote', defaultMessage: 'Where it comes from' },
    sourceNoteHint: {
      id: 'actionsOfficer.form.sourceNoteHint',
      defaultMessage: 'For example: Chair’s instruction of 3 December.',
    },
    owner: { id: 'actionsOfficer.form.owner', defaultMessage: 'Owner' },
    ownerPerson: { id: 'actionsOfficer.form.ownerPerson', defaultMessage: 'A person' },
    ownerPosition: { id: 'actionsOfficer.form.ownerPosition', defaultMessage: 'A position' },
    ownerWho: { id: 'actionsOfficer.form.ownerWho', defaultMessage: 'Who' },
    ownerHint: {
      id: 'actionsOfficer.form.ownerHint',
      defaultMessage: 'Anyone in the organisation cleared for the action’s level. A position follows whoever holds it.',
    },
    ownerNow: { id: 'actionsOfficer.form.ownerNow', defaultMessage: 'Now: {owner}' },
    contributors: { id: 'actionsOfficer.form.contributors', defaultMessage: 'Also involved' },
    contributorsHint: {
      id: 'actionsOfficer.form.contributorsHint',
      defaultMessage: 'Up to 5 people. They can post updates; only the owner marks it as done.',
    },
    addPerson: { id: 'actionsOfficer.form.addPerson', defaultMessage: 'Add this person' },
    findPerson: { id: 'actionsOfficer.form.findPerson', defaultMessage: 'Find a person to add' },
    removePerson: { id: 'actionsOfficer.form.removePerson', defaultMessage: 'Remove {name}' },
    contributorsFull: {
      id: 'actionsOfficer.form.contributorsFull',
      defaultMessage: 'Five people are involved already.',
    },
    contributorAdded: { id: 'actionsOfficer.form.contributorAdded', defaultMessage: '{name} added.' },
    contributorRemoved: { id: 'actionsOfficer.form.contributorRemoved', defaultMessage: '{name} removed.' },
    due: { id: 'actionsOfficer.form.due', defaultMessage: 'Due' },
    dueNextMeeting: {
      id: 'actionsOfficer.form.dueNextMeeting',
      defaultMessage: 'By the next meeting ({date})',
    },
    dueOnDate: { id: 'actionsOfficer.form.dueOnDate', defaultMessage: 'On a date' },
    dueDate: { id: 'actionsOfficer.form.dueDate', defaultMessage: 'Date' },
    dueHint: { id: 'actionsOfficer.form.dueHint', defaultMessage: 'Type a date or pick one.' },
    dueQuick: { id: 'actionsOfficer.form.dueQuick', defaultMessage: 'Quick picks' },
    dueSuggested: {
      id: 'actionsOfficer.form.dueSuggested',
      defaultMessage: 'In {days, plural, one {# working day} other {# working days}} (the {forum}’s usual)',
    },
    in2Weeks: { id: 'actionsOfficer.form.in2Weeks', defaultMessage: 'In two weeks' },
    in1Month: { id: 'actionsOfficer.form.in1Month', defaultMessage: 'In a month' },
    in3Months: { id: 'actionsOfficer.form.in3Months', defaultMessage: 'In three months' },
    dueChosen: { id: 'actionsOfficer.form.dueChosen', defaultMessage: 'Due {date}.' },
    priority: { id: 'actionsOfficer.form.priority', defaultMessage: 'Priority' },
    priorityNORMAL: { id: 'actionsOfficer.form.priorityNormal', defaultMessage: 'Normal' },
    priorityHIGH: { id: 'actionsOfficer.form.priorityHigh', defaultMessage: 'High' },
    level: { id: 'actionsOfficer.form.level', defaultMessage: 'Level' },
    levelSource: { id: 'actionsOfficer.form.levelSource', defaultMessage: 'As its source (the item or the meeting)' },
    levelHint: {
      id: 'actionsOfficer.form.levelHint',
      defaultMessage:
        'An action is never below its source’s level. A higher level keeps it from people not cleared for it.',
    },
    reason: { id: 'actionsOfficer.form.reason', defaultMessage: 'Why it changes' },
    reasonHint: {
      id: 'actionsOfficer.form.reasonHint',
      defaultMessage: 'Kept on the action’s timeline. The owner and the people involved see it.',
    },
    inMeeting: {
      id: 'actionsOfficer.form.inMeeting',
      defaultMessage: 'During the meeting, actions are saved as drafts. Send them to their owners once it is held.',
    },
    saveDraft: { id: 'actionsOfficer.form.saveDraft', defaultMessage: 'Save as draft' },
    saveSend: { id: 'actionsOfficer.form.saveSend', defaultMessage: 'Save and send to the owner' },
    save: { id: 'actionsOfficer.form.save', defaultMessage: 'Save the changes' },
    cancel: { id: 'actionsOfficer.form.cancel', defaultMessage: 'Cancel' },
    savedDraft: { id: 'actionsOfficer.form.savedDraft', defaultMessage: 'Saved as a draft: {title}.' },
    savedSent: { id: 'actionsOfficer.form.savedSent', defaultMessage: '{reference} was sent to its owner.' },
    savedSentNot: {
      id: 'actionsOfficer.form.savedSentNot',
      defaultMessage: 'Saved as a draft, but not sent: {why}',
    },
    saved: { id: 'actionsOfficer.form.saved', defaultMessage: 'The action was changed.' },
    warnings: { id: 'actionsOfficer.warnings', defaultMessage: 'Before you send it' },
    warnOWNER_RECUSED: {
      id: 'actionsOfficer.warn.ownerRecused',
      defaultMessage: '{name} declared an interest in this item. They will see this action, but not the item.',
    },
    warnNOT_CLEARED: {
      id: 'actionsOfficer.warn.notCleared',
      defaultMessage: '{name} is not cleared for the action’s level, or is no longer active.',
    },
    someone: { id: 'actionsOfficer.someone', defaultMessage: 'Someone named' },

    // Field problems and refusals in words (design §3, as built in T3–T8).
    fieldRequiredTitle: { id: 'actionsOfficer.field.title.required', defaultMessage: 'Say what is to be done.' },
    fieldRequiredOwner: { id: 'actionsOfficer.field.owner.required', defaultMessage: 'Choose who owns the action.' },
    fieldRequiredDue: { id: 'actionsOfficer.field.dueDate.required', defaultMessage: 'Choose when it is due.' },
    fieldRequiredReason: { id: 'actionsOfficer.field.reason.required', defaultMessage: 'Give a reason.' },
    fieldRequiredHow: {
      id: 'actionsOfficer.field.how.required',
      defaultMessage: 'Say how you heard from the owner.',
    },
    fieldRequiredHowChair: {
      id: 'actionsOfficer.field.how.chair',
      defaultMessage: 'Say how the chair told you they accept the risk.',
    },
    fieldRequiredKind: { id: 'actionsOfficer.field.kind.required', defaultMessage: 'Say why the action is cancelled.' },
    fieldRequiredRag: { id: 'actionsOfficer.field.rag.required', defaultMessage: 'Choose red, amber or green.' },
    fieldRequiredDecision: { id: 'actionsOfficer.field.decision.required', defaultMessage: 'Agree or decline.' },
    fieldTooLong: { id: 'actionsOfficer.field.length', defaultMessage: 'This is too long.' },
    fieldDueInPast: {
      id: 'actionsOfficer.field.dueInPast',
      defaultMessage: 'Please enter a date on or after today, for example {example}.',
    },
    fieldOwnerNotCleared: {
      id: 'actionsOfficer.field.ownerNotCleared',
      defaultMessage: 'This person is not cleared for the action’s level, or is not active, and cannot own it.',
    },
    fieldNotCleared: {
      id: 'actionsOfficer.field.notCleared',
      defaultMessage: 'This person is not cleared for the action’s level, or is not active.',
    },
    fieldRecusedNamed: {
      id: 'actionsOfficer.field.recusedNamed',
      defaultMessage: 'This person declared an interest in the item and cannot help with this action.',
    },
    fieldSamePerson: {
      id: 'actionsOfficer.field.samePerson',
      defaultMessage: 'The owner is not also among the people involved.',
    },
    fieldSameOwner: { id: 'actionsOfficer.field.sameOwner', defaultMessage: 'This is the action’s owner already.' },
    fieldDuplicate: { id: 'actionsOfficer.field.duplicate', defaultMessage: 'Name each person once.' },
    fieldContributorsTooMany: {
      id: 'actionsOfficer.field.contributorsTooMany',
      defaultMessage: 'At most five people besides the owner.',
    },
    fieldClassification: {
      id: 'actionsOfficer.field.classification',
      defaultMessage: 'An action is never below its source’s level, nor above your own clearance.',
    },
    fieldCondition: { id: 'actionsOfficer.field.condition', defaultMessage: 'The decision has no such condition.' },
    fieldNotEditable: {
      id: 'actionsOfficer.field.notEditable',
      defaultMessage: 'Once the action is sent, this changes with its own button.',
    },
    fieldSupersededBy: {
      id: 'actionsOfficer.field.supersededBy',
      defaultMessage: 'Choose the issued action of this committee that replaces it.',
    },
    fieldUnknownPerson: { id: 'actionsOfficer.field.unknownPerson', defaultMessage: 'Choose someone from the list.' },
    refusedRECUSED: {
      id: 'actionsOfficer.refused.recused',
      defaultMessage:
        'You declared an interest in this item, so you cannot raise or change its actions. Another secretary can.',
    },
    refusedRaiseWindow: {
      id: 'actionsOfficer.refused.raiseWindow',
      defaultMessage: 'Actions are raised once the meeting has started, until its minutes are approved.',
    },
    refusedNO_OVERRIDE: {
      id: 'actionsOfficer.refused.noOverride',
      defaultMessage: 'No colour is set by hand on this action.',
    },
    refusedNO_MEETING: {
      id: 'actionsOfficer.refused.noMeeting',
      defaultMessage: 'The committee has no next meeting yet, so there is no report to keep it out of.',
    },
    refusedALREADY_HELD: {
      id: 'actionsOfficer.refused.alreadyHeld',
      defaultMessage: 'This action is kept out of that meeting’s report already.',
    },
    refusedALREADY_ASKED: {
      id: 'actionsOfficer.refused.alreadyAsked',
      defaultMessage: 'The owners were asked today already. You can ask again tomorrow.',
    },
    refusedCOMMITTEE: {
      id: 'actionsOfficer.refused.committee',
      defaultMessage: 'This action belongs to another committee.',
    },

    // Lists of actions on the decision panel and the meeting page (flows AJ1, AJ3).
    itemActions: { id: 'actionsOfficer.item.title', defaultMessage: 'Actions from this item' },
    itemDrafts: {
      id: 'actionsOfficer.item.drafts',
      defaultMessage: '({count, plural, one {# draft} other {# drafts}})',
    },
    itemNone: { id: 'actionsOfficer.item.none', defaultMessage: 'No actions from this item yet.' },
    makeAction: { id: 'actionsOfficer.item.makeAction', defaultMessage: 'Make this an action' },
    makeActionNamed: {
      id: 'actionsOfficer.item.makeActionNamed',
      defaultMessage: 'Make condition {number, number} an action',
    },
    addAction: { id: 'actionsOfficer.item.add', defaultMessage: 'Add an action' },
    edit: { id: 'actionsOfficer.edit', defaultMessage: 'Edit' },
    editNamed: { id: 'actionsOfficer.editNamed', defaultMessage: 'Edit {title}' },
    open: { id: 'actionsOfficer.open', defaultMessage: 'Open' },
    openNamed: { id: 'actionsOfficer.openNamed', defaultMessage: 'Open {reference}' },
    rowDue: { id: 'actionsOfficer.row.due', defaultMessage: 'by {date}' },
    rowNoOwner: { id: 'actionsOfficer.row.noOwner', defaultMessage: 'No owner yet' },
    rowWithSource: { id: 'actionsOfficer.row.withSource', defaultMessage: '{source} · {facts}' },
    cancelOption: { id: 'actionsOfficer.cancel.option', defaultMessage: '{reference} — {title}' },
    meetingActions: { id: 'actionsOfficer.meeting.title', defaultMessage: 'Actions from this meeting' },
    meetingActionsHint: {
      id: 'actionsOfficer.meeting.hint',
      defaultMessage: 'Every action raised at this meeting. Those from an item are also on the Decisions tab.',
    },
    meetingNone: { id: 'actionsOfficer.meeting.none', defaultMessage: 'No actions from this meeting yet.' },
    meetingAdd: { id: 'actionsOfficer.meeting.add', defaultMessage: 'Add an action for the meeting' },
    sentLine: {
      id: 'actionsOfficer.meeting.sent',
      defaultMessage: '{count, plural, one {# action sent to its owner} other {# actions sent to their owners}}',
    },
    draftsLine: {
      id: 'actionsOfficer.meeting.drafts',
      defaultMessage: '{count, plural, one {# draft action not sent} other {# draft actions not sent}}',
    },
    sendDrafts: {
      id: 'actionsOfficer.meeting.send',
      defaultMessage: 'Send the {count, plural, one {draft} other {# drafts}} to their owners',
    },
    sendLater: {
      id: 'actionsOfficer.meeting.sendLater',
      defaultMessage: 'Drafts are sent to their owners once the meeting is held.',
    },
    sentAll: {
      id: 'actionsOfficer.meeting.sentAll',
      defaultMessage:
        '{count, plural, one {# action was sent to its owner.} other {# actions were sent to their owners.}}',
    },
    sentSome: {
      id: 'actionsOfficer.meeting.sentSome',
      defaultMessage:
        '{sent, number} sent. {failed, plural, one {# draft was not sent:} other {# drafts were not sent:}}',
    },
    sentNone: { id: 'actionsOfficer.meeting.sentNone', defaultMessage: 'There were no drafts you may send.' },
    notSentLine: { id: 'actionsOfficer.meeting.notSent', defaultMessage: '{title}: {why}' },
    heldSend: {
      id: 'actionsOfficer.held.send',
      defaultMessage:
        'Send the {count, plural, one {# draft action} other {# draft actions}} to {count, plural, one {its owner} other {their owners}} now',
    },
    heldSent: {
      id: 'actionsOfficer.held.sent',
      defaultMessage:
        'The meeting is held. {count, plural, one {# action was sent to its owner.} other {# actions were sent to their owners.}}',
    },
    heldNotSent: {
      id: 'actionsOfficer.held.notSent',
      defaultMessage:
        'The meeting is held. {failed, plural, one {# draft was not sent} other {# drafts were not sent}}: review them on the meeting page.',
    },

    // The officers' buttons on the action page (flow AN).
    officerTitle: { id: 'actionsOfficer.page.title', defaultMessage: 'For the committee’s officers' },
    issue: { id: 'actionsOfficer.do.issue', defaultMessage: 'Send to the owner' },
    discard: { id: 'actionsOfficer.do.discard', defaultMessage: 'Discard the draft' },
    decideDue: { id: 'actionsOfficer.do.decideDue', defaultMessage: 'Answer the date request' },
    changeOwner: { id: 'actionsOfficer.do.changeOwner', defaultMessage: 'Change the owner' },
    setColour: { id: 'actionsOfficer.do.setColour', defaultMessage: 'Set the colour' },
    clearColour: { id: 'actionsOfficer.do.clearColour', defaultMessage: 'Take away the colour set by hand' },
    cancelAction: { id: 'actionsOfficer.do.cancel', defaultMessage: 'Cancel the action' },
    reopen: { id: 'actionsOfficer.do.reopen', defaultMessage: 'Reopen' },
    holdBack: { id: 'actionsOfficer.do.holdBack', defaultMessage: 'Keep out of the next report' },
    onBehalf: { id: 'actionsOfficer.do.onBehalf', defaultMessage: 'Record an update for the owner' },
    warningsOnPage: { id: 'actionsOfficer.page.warnings', defaultMessage: 'For the secretaries' },

    issueTitle: { id: 'actionsOfficer.issue.title', defaultMessage: 'Send the action to its owner' },
    issueExplain: {
      id: 'actionsOfficer.issue.explain',
      defaultMessage: 'It gets its reference, and {owner} is told by email, without its title.',
    },
    issueSend: { id: 'actionsOfficer.issue.send', defaultMessage: 'Send' },
    issued: { id: 'actionsOfficer.issue.done', defaultMessage: 'Sent to the owner as {reference}.' },
    discardTitle: { id: 'actionsOfficer.discard.title', defaultMessage: 'Discard the draft action' },
    discardExplain: {
      id: 'actionsOfficer.discard.explain',
      defaultMessage: 'A discarded draft is kept in the record, never numbered and never sent.',
    },
    discardWhy: { id: 'actionsOfficer.discard.why', defaultMessage: 'Why' },
    discarded: { id: 'actionsOfficer.discard.done', defaultMessage: 'The draft was discarded.' },
    optional: { id: 'actionsOfficer.optional', defaultMessage: '(optional)' },

    decideTitle: { id: 'actionsOfficer.decide.title', defaultMessage: 'The owner asks for a new date' },
    decideAsked: {
      id: 'actionsOfficer.decide.asked',
      defaultMessage: '{name} asks for {date} instead of {current}: “{reason}”',
    },
    decideAgree: { id: 'actionsOfficer.decide.agree', defaultMessage: 'Agree to the new date' },
    decideDecline: { id: 'actionsOfficer.decide.decline', defaultMessage: 'Decline: keep the date' },
    decideNote: { id: 'actionsOfficer.decide.note', defaultMessage: 'Note for the owner' },
    decideSend: { id: 'actionsOfficer.decide.send', defaultMessage: 'Answer' },
    agreed: { id: 'actionsOfficer.decide.agreed', defaultMessage: 'The new date was agreed. The owner is told.' },
    declined: { id: 'actionsOfficer.decide.declined', defaultMessage: 'The request was declined. The owner is told.' },

    ownerTitle: { id: 'actionsOfficer.owner.title', defaultMessage: 'Change the owner' },
    ownerExplain: {
      id: 'actionsOfficer.owner.explain',
      defaultMessage: 'The open task moves to the new owner. Both the previous and the new owner are told.',
    },
    ownerSend: { id: 'actionsOfficer.owner.send', defaultMessage: 'Change the owner' },
    ownerChanged: { id: 'actionsOfficer.owner.done', defaultMessage: 'The owner was changed.' },

    colourTitle: { id: 'actionsOfficer.colour.title', defaultMessage: 'Set the colour by hand' },
    colourExplain: {
      id: 'actionsOfficer.colour.explain',
      defaultMessage: 'Everyone sees that it was set by hand, by whom and why.',
    },
    colourChoice: { id: 'actionsOfficer.colour.choice', defaultMessage: 'Colour' },
    colourRED: { id: 'actionsOfficer.colour.red', defaultMessage: 'Red: off track' },
    colourAMBER: { id: 'actionsOfficer.colour.amber', defaultMessage: 'Amber: at risk' },
    colourGREEN: { id: 'actionsOfficer.colour.green', defaultMessage: 'Green: on track' },
    colourUntil: { id: 'actionsOfficer.colour.until', defaultMessage: 'Until' },
    colourUntilHint: {
      id: 'actionsOfficer.colour.untilHint',
      defaultMessage: 'Leave it empty to keep the colour until the next update.',
    },
    colourSend: { id: 'actionsOfficer.colour.send', defaultMessage: 'Set the colour' },
    colourSet: { id: 'actionsOfficer.colour.done', defaultMessage: 'The colour was set by hand.' },
    colourCleared: { id: 'actionsOfficer.colour.cleared', defaultMessage: 'The colour set by hand was taken away.' },

    cancelTitle: { id: 'actionsOfficer.cancel.title', defaultMessage: 'Cancel the action' },
    cancelExplain: {
      id: 'actionsOfficer.cancel.explain',
      defaultMessage: 'The owner and the people involved are told. It can be reopened within 90 days.',
    },
    cancelWhy: { id: 'actionsOfficer.cancel.why', defaultMessage: 'Why it is cancelled' },
    cancelNO_LONGER_NEEDED: { id: 'actionsOfficer.cancel.noLongerNeeded', defaultMessage: 'No longer needed' },
    cancelSUPERSEDED: { id: 'actionsOfficer.cancel.superseded', defaultMessage: 'Replaced by another action' },
    cancelDUPLICATE: { id: 'actionsOfficer.cancel.duplicate', defaultMessage: 'A duplicate' },
    cancelRISK_ACCEPTED: { id: 'actionsOfficer.cancel.riskAccepted', defaultMessage: 'Risk accepted by the chair' },
    cancelRiskForChair: {
      id: 'actionsOfficer.cancel.riskForChair',
      defaultMessage: 'Only the chair accepts the risk. You record it for the chair: say how they told you.',
    },
    cancelHowChair: { id: 'actionsOfficer.cancel.howChair', defaultMessage: 'How the chair told you' },
    cancelReplacedBy: { id: 'actionsOfficer.cancel.replacedBy', defaultMessage: 'The action that replaces it' },
    cancelChoose: { id: 'actionsOfficer.cancel.choose', defaultMessage: 'Choose an action' },
    cancelReason: { id: 'actionsOfficer.cancel.reason', defaultMessage: 'Reason' },
    cancelSend: { id: 'actionsOfficer.cancel.send', defaultMessage: 'Cancel the action' },
    cancelled: { id: 'actionsOfficer.cancel.done', defaultMessage: 'The action was cancelled.' },

    reopenTitle: { id: 'actionsOfficer.reopen.title', defaultMessage: 'Reopen the action' },
    reopenExplain: {
      id: 'actionsOfficer.reopen.explain',
      defaultMessage: 'It starts again with the owner and the new date. The owner is told.',
    },
    reopenWhy: { id: 'actionsOfficer.reopen.why', defaultMessage: 'Why it is reopened' },
    reopenDue: { id: 'actionsOfficer.reopen.due', defaultMessage: 'New due date' },
    reopenSend: { id: 'actionsOfficer.reopen.send', defaultMessage: 'Reopen' },
    reopened: { id: 'actionsOfficer.reopen.done', defaultMessage: 'The action was reopened.' },

    holdTitle: { id: 'actionsOfficer.hold.title', defaultMessage: 'Keep it out of the next report' },
    holdExplain: {
      id: 'actionsOfficer.hold.explain',
      defaultMessage:
        'It stays open on the tracker. The next meeting’s action taken report leaves it out, and tells the officers that one was held back.',
    },
    holdWhy: { id: 'actionsOfficer.hold.why', defaultMessage: 'Why' },
    holdSend: { id: 'actionsOfficer.hold.send', defaultMessage: 'Keep it out' },
    held: {
      id: 'actionsOfficer.hold.done',
      defaultMessage: 'It will be left out of the action taken report of {meeting}.',
    },

    behalfTitle: { id: 'actionsOfficer.behalf.title', defaultMessage: 'Record an update for {owner}' },
    behalfExplain: {
      id: 'actionsOfficer.behalf.explain',
      defaultMessage: 'For what the owner told you. The timeline shows that you wrote it for them, and how you heard.',
    },
    behalfText: { id: 'actionsOfficer.behalf.text', defaultMessage: 'What has happened?' },
    behalfHow: { id: 'actionsOfficer.behalf.how', defaultMessage: 'How you heard' },
    behalfHowPHONE: { id: 'actionsOfficer.behalf.phone', defaultMessage: 'By phone' },
    behalfHowEMAIL: { id: 'actionsOfficer.behalf.email', defaultMessage: 'By email' },
    behalfHowIN_PERSON: { id: 'actionsOfficer.behalf.inPerson', defaultMessage: 'In person' },
    behalfHowMEETING: { id: 'actionsOfficer.behalf.meeting', defaultMessage: 'At a meeting' },
    behalfConfidence: { id: 'actionsOfficer.behalf.confidence', defaultMessage: 'How is it going?' },
    behalfSend: { id: 'actionsOfficer.behalf.send', defaultMessage: 'Record the update' },
    behalfPosted: { id: 'actionsOfficer.behalf.done', defaultMessage: 'The update was recorded for the owner.' },
    dueOfficer: {
      id: 'actionsOfficer.due.officer',
      defaultMessage:
        'The date changes at once. The owner and the people involved are told, and its reminders move with it.',
    },

    // Verification with what was done, its evidence and memos (flow AM1).
    doneBy: { id: 'actionsOfficer.verify.doneBy', defaultMessage: 'Done by {name} on {date}:' },
    doneNoNote: { id: 'actionsOfficer.verify.doneNoNote', defaultMessage: 'Marked as done.' },
    evidence: { id: 'actionsOfficer.verify.evidence', defaultMessage: 'Evidence' },
    noEvidence: { id: 'actionsOfficer.verify.noEvidence', defaultMessage: 'No files were attached.' },
    memos: { id: 'actionsOfficer.verify.memos', defaultMessage: 'Follow-up memos' },

    // Closing at the meeting (flow AM2), on the action taken report.
    closeAgreedTitle: { id: 'actionsOfficer.close.title', defaultMessage: 'Close what the committee agreed' },
    closeAgreedHint: {
      id: 'actionsOfficer.close.hint',
      defaultMessage: 'Tick the actions the committee agreed to close at this meeting.',
    },
    closeAgreedRow: { id: 'actionsOfficer.close.row', defaultMessage: 'Agreed to close {reference}' },
    closeAgreedRemark: { id: 'actionsOfficer.close.remark', defaultMessage: 'Remark' },
    closeAgreedRemarkHint: {
      id: 'actionsOfficer.close.remarkHint',
      defaultMessage: 'Left empty, each action records that it was agreed at this meeting, under its item.',
    },
    closeAgreed: {
      id: 'actionsOfficer.close.send',
      defaultMessage: 'Close the actions the committee agreed ({count, number})',
    },
    closeChooseOne: { id: 'actionsOfficer.close.chooseOne', defaultMessage: 'Tick at least one action.' },
    closedAll: {
      id: 'actionsOfficer.close.all',
      defaultMessage: '{count, plural, one {# action was closed.} other {# actions were closed.}}',
    },
    closedSome: {
      id: 'actionsOfficer.close.some',
      defaultMessage: '{closed, number} closed. {failed, plural, one {# was not closed:} other {# were not closed:}}',
    },
    closeNotVerifier: {
      id: 'actionsOfficer.close.notVerifier',
      defaultMessage: 'you are its owner or involved in it, so another officer closes it',
    },

    // The committee's tracker (flow AN).
    tab: { id: 'actionsOfficer.tracker.tab', defaultMessage: 'Actions' },
    trackerTitle: { id: 'actionsOfficer.tracker.title', defaultMessage: '{name} — Actions' },
    newAction: { id: 'actionsOfficer.tracker.new', defaultMessage: 'New action' },
    export: { id: 'actionsOfficer.tracker.export', defaultMessage: 'Export' },
    exported: { id: 'actionsOfficer.tracker.exported', defaultMessage: 'The actions were downloaded.' },
    exportLimited: {
      id: 'actionsOfficer.tracker.exportLimited',
      defaultMessage: 'You have exported ten times this hour. Try again later.',
    },
    exportFailed: { id: 'actionsOfficer.tracker.exportFailed', defaultMessage: 'The export did not work. Try again.' },
    askUpdates: { id: 'actionsOfficer.tracker.ask', defaultMessage: 'Ask owners for an update' },
    askedToday: {
      id: 'actionsOfficer.tracker.askedToday',
      defaultMessage: 'Owners were asked for an update today. You can ask again tomorrow.',
    },
    asked: {
      id: 'actionsOfficer.tracker.asked',
      defaultMessage:
        '{count, plural, =0 {No owner needed asking.} one {# owner was asked for an update.} other {# owners were asked for an update.}}',
    },
    filters: { id: 'actionsOfficer.tracker.filters', defaultMessage: 'Filters' },
    filterStatus: { id: 'actionsOfficer.tracker.status', defaultMessage: 'Status' },
    statusOPEN: { id: 'actionsOfficer.tracker.status.open', defaultMessage: 'Open' },
    statusALL: { id: 'actionsOfficer.tracker.status.all', defaultMessage: 'All' },
    statusDRAFT: { id: 'actionsOfficer.tracker.status.draft', defaultMessage: 'Drafts' },
    statusTO_VERIFY: { id: 'actionsOfficer.tracker.status.toVerify', defaultMessage: 'Done, to be verified' },
    statusCLOSED: { id: 'actionsOfficer.tracker.status.closed', defaultMessage: 'Closed' },
    statusCANCELLED: { id: 'actionsOfficer.tracker.status.cancelled', defaultMessage: 'Cancelled' },
    filterColour: { id: 'actionsOfficer.tracker.colour', defaultMessage: 'Colour' },
    colourALL: { id: 'actionsOfficer.tracker.colour.all', defaultMessage: 'All' },
    colourOVERDUE: { id: 'actionsOfficer.tracker.colour.overdue', defaultMessage: 'Overdue' },
    colourFilterRED: { id: 'actionsOfficer.tracker.colour.red', defaultMessage: 'Red' },
    colourFilterAMBER: { id: 'actionsOfficer.tracker.colour.amber', defaultMessage: 'Amber' },
    colourFilterGREEN: { id: 'actionsOfficer.tracker.colour.green', defaultMessage: 'Green' },
    colourFilterBLUE: { id: 'actionsOfficer.tracker.colour.blue', defaultMessage: 'To verify' },
    filterOwner: { id: 'actionsOfficer.tracker.owner', defaultMessage: 'Owner' },
    ownerANYONE: { id: 'actionsOfficer.tracker.owner.anyone', defaultMessage: 'Anyone' },
    filterMeeting: { id: 'actionsOfficer.tracker.meeting', defaultMessage: 'Meeting' },
    meetingANY: { id: 'actionsOfficer.tracker.meeting.any', defaultMessage: 'Any' },
    search: { id: 'actionsOfficer.tracker.search', defaultMessage: 'Search actions' },
    sort: { id: 'actionsOfficer.tracker.sort', defaultMessage: 'Sort by' },
    sortDUE: { id: 'actionsOfficer.tracker.sort.due', defaultMessage: 'Due date' },
    sortUPDATED: { id: 'actionsOfficer.tracker.sort.updated', defaultMessage: 'Latest update' },
    sortREFERENCE: { id: 'actionsOfficer.tracker.sort.reference', defaultMessage: 'Reference' },
    totals: { id: 'actionsOfficer.tracker.totals', defaultMessage: 'Totals' },
    totalRED: { id: 'actionsOfficer.tracker.total.red', defaultMessage: 'Overdue or off track: {count, number}' },
    totalAMBER: { id: 'actionsOfficer.tracker.total.amber', defaultMessage: 'Due soon or at risk: {count, number}' },
    totalGREEN: { id: 'actionsOfficer.tracker.total.green', defaultMessage: 'On track: {count, number}' },
    totalBLUE: { id: 'actionsOfficer.tracker.total.blue', defaultMessage: 'To verify: {count, number}' },
    totalsMore: {
      id: 'actionsOfficer.tracker.totalsMore',
      defaultMessage: 'Closed or cancelled: {closed, number} · Drafts: {drafts, number}',
    },
    matching: {
      id: 'actionsOfficer.tracker.matching',
      defaultMessage: '{count, plural, =0 {No actions match.} one {# action matches.} other {# actions match.}}',
    },
    colRef: { id: 'actionsOfficer.tracker.col.ref', defaultMessage: 'Ref' },
    colAction: { id: 'actionsOfficer.tracker.col.action', defaultMessage: 'Action' },
    colOwner: { id: 'actionsOfficer.tracker.col.owner', defaultMessage: 'Owner' },
    colDue: { id: 'actionsOfficer.tracker.col.due', defaultMessage: 'Due' },
    colStatus: { id: 'actionsOfficer.tracker.col.status', defaultMessage: 'Status' },
    revised: { id: 'actionsOfficer.tracker.revised', defaultMessage: '(rev. {count, number})' },
    empty: { id: 'actionsOfficer.tracker.empty', defaultMessage: 'This committee has no actions yet.' },
    noneMatch: { id: 'actionsOfficer.tracker.noneMatch', defaultMessage: 'No actions match these filters.' },
    notYours: {
      id: 'actionsOfficer.tracker.notYours',
      defaultMessage: 'There are no actions of this committee for you to see.',
    },
    keyStale: {
      id: 'actionsOfficer.tracker.keyStale',
      defaultMessage:
        'This committee: amber when due within {amber, plural, one {# working day} other {# working days}}, at risk, or with no update for {stale, plural, one {# working day} other {# working days}}.',
    },
    keyNoStale: {
      id: 'actionsOfficer.tracker.keyNoStale',
      defaultMessage:
        'This committee: amber when due within {amber, plural, one {# working day} other {# working days}}, or at risk.',
    },
  }),
)
