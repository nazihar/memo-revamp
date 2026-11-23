import { defineMessages, type MessageDescriptor } from 'react-intl'

/**
 * The messages as plain descriptors: many take values (dates, names, counts and the organisation's terms), which the
 * typed descriptors defineMessages infers would refuse without a type for each.
 */
function plain<T extends Record<string, MessageDescriptor>>(
  messages: T,
): { readonly [K in keyof T]: MessageDescriptor } {
  return messages
}

/**
 * The owner's and members' side of committee actions (Phase 4 E07 T11, flows AK, AL, AO, AP): "My actions" in the inbox,
 * the action page with its dialogs and timeline, follow-up memos and the action taken report. They load with the `actions`
 * catalogue part (the inbox, `/actions` and the report page), never with core.
 */
export const am = plain(
  defineMessages({
    // The page and the crumbs.
    crumbAction: { id: 'actions.crumb.action', defaultMessage: 'Action' },
    crumbReport: { id: 'actions.crumb.report', defaultMessage: 'Action taken report' },
    notFound: {
      id: 'actions.notFound',
      defaultMessage: 'This action is not there, or it is not yours to see.',
    },
    draftReference: { id: 'actions.draftReference', defaultMessage: 'Draft action' },
    referenceCommittee: { id: 'actions.referenceCommittee', defaultMessage: '{reference} · {committee}' },

    // "My actions" in the inbox (flow AK).
    tabActions: { id: 'actions.inbox.tab', defaultMessage: 'My actions' },
    tabActionsCount: {
      id: 'actions.inbox.tabCount',
      defaultMessage: 'My actions ({count, number})',
    },
    listsLabel: { id: 'actions.inbox.lists', defaultMessage: 'Which actions' },
    listMine: { id: 'actions.inbox.mine', defaultMessage: 'To do ({count, number})' },
    listVerify: { id: 'actions.inbox.verify', defaultMessage: 'To verify ({count, number})' },
    listInvolved: { id: 'actions.inbox.involved', defaultMessage: 'I’m involved ({count, number})' },
    emptyMine: {
      id: 'actions.inbox.emptyMine',
      defaultMessage: 'You have no actions. Actions given to you by a {forum} will appear here.',
    },
    emptyVerify: {
      id: 'actions.inbox.emptyVerify',
      defaultMessage: 'Nothing is waiting for you to verify. Actions marked as done that you check will appear here.',
    },
    emptyInvolved: {
      id: 'actions.inbox.emptyInvolved',
      defaultMessage: 'You are not named on anyone else’s action. Actions you help with will appear here.',
    },
    open: { id: 'actions.open', defaultMessage: 'Open' },
    rowLine: { id: 'actions.inbox.rowLine', defaultMessage: '{committee} · {due}' },
    loadMore: { id: 'actions.loadMore', defaultMessage: 'Show more' },

    // The key.
    keyTitle: { id: 'actions.key.title', defaultMessage: 'Key' },
    keyRed: { id: 'actions.key.red', defaultMessage: 'Overdue or off track' },
    keyAmber: {
      id: 'actions.key.amber',
      defaultMessage:
        'Due within {days, plural, one {# working day} other {# working days}}, at risk, or no recent update',
    },
    keyAmberPlain: { id: 'actions.key.amberPlain', defaultMessage: 'Due soon, at risk, or no recent update' },
    keyGreen: { id: 'actions.key.green', defaultMessage: 'On track' },
    keyBlue: { id: 'actions.key.blue', defaultMessage: 'Done, to be verified' },

    // Red, amber and green in words (design §1.5): the word always comes with its icon and colour.
    ragDRAFT: { id: 'actions.rag.draft', defaultMessage: 'Draft' },
    ragCLOSED: { id: 'actions.rag.closed', defaultMessage: 'Closed' },
    ragCANCELLED: { id: 'actions.rag.cancelled', defaultMessage: 'Cancelled' },
    ragTO_VERIFY: { id: 'actions.rag.toVerify', defaultMessage: 'Done, to be verified' },
    ragOVERRIDE_RED: { id: 'actions.rag.overrideRed', defaultMessage: 'Red, set by hand' },
    ragOVERRIDE_AMBER: { id: 'actions.rag.overrideAmber', defaultMessage: 'Amber, set by hand' },
    ragOVERRIDE_GREEN: { id: 'actions.rag.overrideGreen', defaultMessage: 'Green, set by hand' },
    ragOVERDUE: { id: 'actions.rag.overdue', defaultMessage: 'Overdue' },
    ragOFF_TRACK: { id: 'actions.rag.offTrack', defaultMessage: 'Off track' },
    ragDUE_SOON: { id: 'actions.rag.dueSoon', defaultMessage: 'Due soon' },
    ragAT_RISK: { id: 'actions.rag.atRisk', defaultMessage: 'At risk' },
    ragNO_RECENT_UPDATE: { id: 'actions.rag.noRecentUpdate', defaultMessage: 'No recent update' },
    ragON_TRACK: { id: 'actions.rag.onTrack', defaultMessage: 'On track' },
    overrideBy: { id: 'actions.rag.overrideBy', defaultMessage: 'Set by {name}: {reason}' },
    overrideReason: { id: 'actions.rag.overrideReason', defaultMessage: 'Set by hand: {reason}' },
    overrideUntil: { id: 'actions.rag.overrideUntil', defaultMessage: 'until {date}' },

    // Status and confidence.
    statusDRAFT: { id: 'actions.status.draft', defaultMessage: 'Draft' },
    statusOPEN: { id: 'actions.status.open', defaultMessage: 'Not started' },
    statusIN_PROGRESS: { id: 'actions.status.inProgress', defaultMessage: 'In progress' },
    statusDONE_PENDING: { id: 'actions.status.donePending', defaultMessage: 'Done, waiting to be verified' },
    statusCLOSED: { id: 'actions.status.closed', defaultMessage: 'Closed' },
    statusCANCELLED: { id: 'actions.status.cancelled', defaultMessage: 'Cancelled' },
    confidenceON_TRACK: { id: 'actions.confidence.onTrack', defaultMessage: 'On track' },
    confidenceAT_RISK: { id: 'actions.confidence.atRisk', defaultMessage: 'At risk' },
    confidenceOFF_TRACK: { id: 'actions.confidence.offTrack', defaultMessage: 'Off track' },
    howPHONE: { id: 'actions.how.phone', defaultMessage: 'by phone' },
    howEMAIL: { id: 'actions.how.email', defaultMessage: 'by email' },
    howIN_PERSON: { id: 'actions.how.inPerson', defaultMessage: 'in person' },
    howMEETING: { id: 'actions.how.meeting', defaultMessage: 'at a meeting' },
    cancelNO_LONGER_NEEDED: { id: 'actions.cancel.noLongerNeeded', defaultMessage: 'no longer needed' },
    cancelSUPERSEDED: { id: 'actions.cancel.superseded', defaultMessage: 'replaced by another action' },
    cancelRISK_ACCEPTED: { id: 'actions.cancel.riskAccepted', defaultMessage: 'risk accepted' },
    cancelDISCARDED: { id: 'actions.cancel.discarded', defaultMessage: 'discarded draft' },
    cancelDUPLICATE: { id: 'actions.cancel.duplicate', defaultMessage: 'a duplicate' },

    // Dates (design §1.7: where a date started is always shown).
    due: { id: 'actions.due', defaultMessage: 'due {date}' },
    dueRevised: {
      id: 'actions.dueRevised',
      defaultMessage:
        'due {date} (originally {original}, revised {count, plural, one {once} =2 {twice} other {# times}})',
    },
    noDue: { id: 'actions.noDue', defaultMessage: 'no due date' },
    dueOriginally: {
      id: 'actions.dueOriginally',
      defaultMessage: 'originally {original}, revised {count, plural, one {once} =2 {twice} other {# times}}',
    },
    overdueBy: {
      id: 'actions.overdueBy',
      defaultMessage: 'Overdue by {days, plural, one {# working day} other {# working days}}',
    },

    // The action page (flow AL).
    owner: { id: 'actions.page.owner', defaultMessage: 'Owner' },
    ownerPositionHolder: { id: 'actions.page.ownerPositionHolder', defaultMessage: '{position} ({name})' },
    ownerActingFor: { id: 'actions.page.ownerActingFor', defaultMessage: '{name}, acting for {owner}' },
    ownerWaiting: { id: 'actions.page.ownerWaiting', defaultMessage: '{position} (no one holds it now)' },
    also: { id: 'actions.page.also', defaultMessage: 'Also' },
    dueLabel: { id: 'actions.page.dueLabel', defaultMessage: 'Due' },
    from: { id: 'actions.page.from', defaultMessage: 'From' },
    fromWithTitle: { id: 'actions.page.fromWithTitle', defaultMessage: '{label}: {title}' },
    openSourceMeeting: { id: 'actions.page.openMeeting', defaultMessage: 'Open the meeting' },
    asked: { id: 'actions.page.asked', defaultMessage: 'What the {forum} asked' },
    details: { id: 'actions.page.details', defaultMessage: 'Details' },
    status: { id: 'actions.page.status', defaultMessage: 'Where it stands' },
    statusStep: { id: 'actions.page.statusStep', defaultMessage: '{status} · {step}' },
    priorityHigh: { id: 'actions.page.priorityHigh', defaultMessage: 'High priority' },
    recusedNote: {
      id: 'actions.page.recused',
      defaultMessage:
        'You declared an interest in the item this action comes from. You see the action because you are named on it, but not the item or its papers.',
    },
    pendingRequest: {
      id: 'actions.page.pendingRequest',
      defaultMessage: 'New date requested: {date}. Until it is agreed, the action stays due {current}.',
    },
    pendingRequestBy: { id: 'actions.page.pendingRequestBy', defaultMessage: 'Asked by {name}: “{reason}”' },
    closedOn: { id: 'actions.page.closedOn', defaultMessage: 'Closed on {date}.' },
    cancelledWhy: { id: 'actions.page.cancelledWhy', defaultMessage: 'Cancelled ({kind}): {reason}' },
    reopened: {
      id: 'actions.page.reopened',
      defaultMessage: 'Reopened {count, plural, one {once} =2 {twice} other {# times}}.',
    },
    whatNext: { id: 'actions.page.whatNext', defaultMessage: 'What you can do' },
    nothingToDo: { id: 'actions.page.nothingToDo', defaultMessage: 'There is nothing for you to do on this action.' },
    verifyLater: {
      id: 'actions.page.verifyLater',
      defaultMessage: 'Sent to the {forum} secretary to verify. You will be told when it is closed.',
    },

    // The buttons.
    postUpdate: { id: 'actions.do.postUpdate', defaultMessage: 'Post an update' },
    started: { id: 'actions.do.started', defaultMessage: 'I have started' },
    askNewDate: { id: 'actions.do.askNewDate', defaultMessage: 'Ask for a new date' },
    changeDate: { id: 'actions.do.changeDate', defaultMessage: 'Change the date' },
    raiseMemo: { id: 'actions.do.raiseMemo', defaultMessage: 'Raise a memo' },
    linkMemo: { id: 'actions.do.linkMemo', defaultMessage: 'Link an existing memo' },
    markDone: { id: 'actions.do.markDone', defaultMessage: 'Mark as done' },
    verify: { id: 'actions.do.verify', defaultMessage: 'Verify' },
    cancel: { id: 'actions.do.cancel', defaultMessage: 'Cancel' },
    optional: { id: 'actions.optional', defaultMessage: '(optional)' },

    // Post an update (AL2).
    updateTitle: { id: 'actions.update.title', defaultMessage: 'Post an update' },
    updateText: { id: 'actions.update.text', defaultMessage: 'What has happened?' },
    updateHow: { id: 'actions.update.how', defaultMessage: 'How is it going?' },
    updateHowHint: {
      id: 'actions.update.howHint',
      defaultMessage: 'Off track shows the action as red, at risk as amber, until the next update.',
    },
    updateSend: { id: 'actions.update.send', defaultMessage: 'Post the update' },
    updatePosted: { id: 'actions.update.posted', defaultMessage: 'Your update was posted.' },
    startedDone: { id: 'actions.started.done', defaultMessage: 'The action now shows as in progress.' },

    // Evidence files.
    attach: { id: 'actions.files.attach', defaultMessage: 'Attach files' },
    attachHint: {
      id: 'actions.files.hint',
      defaultMessage: 'PDF, pictures, text, Word, Excel or PowerPoint, up to 25 MB each, at most 10.',
    },
    evidence: { id: 'actions.files.evidence', defaultMessage: 'Evidence' },
    evidenceRequired: {
      id: 'actions.files.evidenceRequired',
      defaultMessage: 'Evidence is required by this {forum}: attach at least one file.',
    },
    attached: { id: 'actions.files.attached', defaultMessage: 'Attached' },
    removeFile: { id: 'actions.files.remove', defaultMessage: 'Remove {name}' },
    fileAdded: { id: 'actions.files.added', defaultMessage: '{name} is attached.' },
    fileRemoved: { id: 'actions.files.removed', defaultMessage: '{name} was removed.' },
    tooManyFiles: { id: 'actions.files.tooMany', defaultMessage: 'At most 10 files.' },

    // Ask for a new date (AL3).
    dueTitle: { id: 'actions.dueRequest.title', defaultMessage: 'Ask for a new date' },
    dueChangeTitle: { id: 'actions.dueChange.title', defaultMessage: 'Change the date' },
    dueNew: { id: 'actions.dueRequest.newDate', defaultMessage: 'New date' },
    dueWhy: { id: 'actions.dueRequest.why', defaultMessage: 'Why' },
    dueSecretaryAgrees: {
      id: 'actions.dueRequest.secretaryAgrees',
      defaultMessage: 'The {forum} secretary will be asked to agree. Until then the date stays {date}.',
    },
    dueChairAgrees: {
      id: 'actions.dueRequest.chairAgrees',
      defaultMessage: 'The chair will be asked to agree. Until then the date stays {date}.',
    },
    dueOwnerChanges: {
      id: 'actions.dueRequest.ownerChanges',
      defaultMessage: 'The date changes at once, and the {forum} secretaries are told why.',
    },
    dueSend: { id: 'actions.dueRequest.send', defaultMessage: 'Ask for this date' },
    dueChangeSend: { id: 'actions.dueChange.send', defaultMessage: 'Change the date' },
    dueRequested: {
      id: 'actions.dueRequest.sent',
      defaultMessage: 'Your request was sent. The date stays the same until it is agreed.',
    },
    dueChanged: { id: 'actions.dueChange.done', defaultMessage: 'The due date was changed.' },

    // Mark as done (AL4).
    doneTitle: { id: 'actions.done.title', defaultMessage: 'Mark as done' },
    doneWhat: { id: 'actions.done.what', defaultMessage: 'What was done' },
    doneHint: {
      id: 'actions.done.hint',
      defaultMessage: 'Someone other than you checks it and closes it. Until then it shows as done, to be verified.',
    },
    doneSend: { id: 'actions.done.send', defaultMessage: 'Mark as done' },
    doneSent: {
      id: 'actions.done.sent',
      defaultMessage: 'Sent to the {forum} secretary to verify. You will be told when it is closed.',
    },

    // Verify (flow AM's dialog, for the verifier who opens the action from To verify).
    verifyTitle: { id: 'actions.verify.title', defaultMessage: 'Verify the action' },
    verifyChoice: { id: 'actions.verify.choice', defaultMessage: 'Is it done?' },
    verifyClose: { id: 'actions.verify.close', defaultMessage: 'Yes: close the action' },
    verifySendBack: { id: 'actions.verify.sendBack', defaultMessage: 'No: send it back to the owner' },
    verifyRemark: { id: 'actions.verify.remark', defaultMessage: 'Remark' },
    verifyRemarkHint: {
      id: 'actions.verify.remarkHint',
      defaultMessage: 'Needed to send it back: say what is missing.',
    },
    verifySend: { id: 'actions.verify.send', defaultMessage: 'Confirm' },
    verifyClosed: { id: 'actions.verify.closed', defaultMessage: 'The action is closed. The owner is told.' },
    verifySentBack: { id: 'actions.verify.sentBack', defaultMessage: 'The action went back to its owner.' },

    // Follow-up memos (flow AP).
    memos: { id: 'actions.memos.title', defaultMessage: 'Follow-up memos' },
    memosNone: { id: 'actions.memos.none', defaultMessage: 'No memo follows up this action yet.' },
    memoLine: { id: 'actions.memos.line', defaultMessage: '{reference} — {subject}' },
    memoUnnumbered: { id: 'actions.memos.unnumbered', defaultMessage: 'Draft memo' },
    memoHidden: { id: 'actions.memos.hidden', defaultMessage: 'A follow-up memo (you do not have access)' },
    memoRaisedKind: { id: 'actions.memos.raised', defaultMessage: 'Raised from this action' },
    memoLinkedKind: { id: 'actions.memos.linked', defaultMessage: 'Linked to this action' },
    memoApprovedNext: {
      id: 'actions.memos.approvedNext',
      defaultMessage: 'The follow-up memo was approved. You can now mark the action as done.',
    },
    raiseTitle: { id: 'actions.raise.title', defaultMessage: 'Raise a memo from {reference}' },
    raiseLead: {
      id: 'actions.raise.lead',
      defaultMessage:
        'The memo opens as your draft, from its type’s template. It says it was raised from this action. Raising it does not close the action.',
    },
    raiseSubject: { id: 'actions.raise.subject', defaultMessage: 'Subject' },
    raiseLevel: { id: 'actions.raise.level', defaultMessage: 'Level' },
    raiseLevelFromAction: { id: 'actions.raise.levelFromAction', defaultMessage: '{level} (from the action)' },
    raiseLevelChange: { id: 'actions.raise.levelChange', defaultMessage: 'Change' },
    raiseLowerConfirm: {
      id: 'actions.raise.lowerConfirm',
      defaultMessage: 'This memo will be {memo}; the action is {action}. Tick to confirm the lower level.',
    },
    raiseSend: { id: 'actions.raise.send', defaultMessage: 'Create the draft memo' },
    raiseNoTypes: {
      id: 'actions.raise.noTypes',
      defaultMessage: 'There is no memo type you may create. Ask your administrator.',
    },
    linkTitle: { id: 'actions.link.title', defaultMessage: 'Link an existing memo' },
    linkSearch: { id: 'actions.link.search', defaultMessage: 'Find a memo by its number or subject' },
    linkSearchHint: {
      id: 'actions.link.searchHint',
      defaultMessage: 'Only memos you can open are found. Type at least two letters.',
    },
    linkResults: { id: 'actions.link.results', defaultMessage: 'Memos found' },
    linkNone: { id: 'actions.link.none', defaultMessage: 'No memo you can open matches.' },
    linkChoose: { id: 'actions.link.choose', defaultMessage: 'Link {name}' },
    linkButton: { id: 'actions.link.button', defaultMessage: 'Link' },
    linkChosen: { id: 'actions.link.chosen', defaultMessage: 'The memo is linked to this action.' },
    linkSearching: { id: 'actions.link.searching', defaultMessage: 'Searching…' },

    // The timeline.
    timeline: { id: 'actions.timeline.title', defaultMessage: 'Timeline' },
    timelineEmpty: { id: 'actions.timeline.empty', defaultMessage: 'Nothing has happened on this action yet.' },
    system: { id: 'actions.timeline.system', defaultMessage: 'the system' },
    forOwner: { id: 'actions.timeline.forOwner', defaultMessage: '{name} for {owner} ({how})' },
    quoted: { id: 'actions.quoted', defaultMessage: '“{text}”' },
    withNote: { id: 'actions.withNote', defaultMessage: '{label} — {note}' },
    withConfidence: { id: 'actions.timeline.withConfidence', defaultMessage: '{headline} · {confidence}' },
    sectionNumbered: { id: 'actions.report.sectionNumbered', defaultMessage: '{number, number}. {heading}' },
    correction: { id: 'actions.timeline.correction', defaultMessage: 'Correction to an earlier update' },
    entryDRAFTED: { id: 'actions.entry.drafted', defaultMessage: 'Drafted by {name}' },
    entryISSUED: { id: 'actions.entry.issued', defaultMessage: 'Sent to its owner by {name}, due {date}' },
    entryUPDATE: { id: 'actions.entry.update', defaultMessage: 'Update from {name}' },
    entrySTARTED: { id: 'actions.entry.started', defaultMessage: '{name} started work on it' },
    entryDUE_REQUESTED: { id: 'actions.entry.dueRequested', defaultMessage: '{name} asked for a new date: {date}' },
    entryDUE_AGREED: { id: 'actions.entry.dueAgreed', defaultMessage: 'New date {date} agreed by {name}' },
    entryDUE_DECLINED: { id: 'actions.entry.dueDeclined', defaultMessage: 'New date {date} declined by {name}' },
    entryDUE_WITHDRAWN: {
      id: 'actions.entry.dueWithdrawn',
      defaultMessage: 'The request for a new date was withdrawn',
    },
    entryDUE_CHANGED: {
      id: 'actions.entry.dueChanged',
      defaultMessage: 'Due date changed from {from} to {date} by {name}',
    },
    entryRAG_OVERRIDDEN: { id: 'actions.entry.ragOverridden', defaultMessage: '{name} set it to {rag}' },
    entryRAG_CLEARED: { id: 'actions.entry.ragCleared', defaultMessage: '{name} took away the colour set by hand' },
    entryOWNER_CHANGED: { id: 'actions.entry.ownerChanged', defaultMessage: 'Owner changed by {name}' },
    entryEDITED: { id: 'actions.entry.edited', defaultMessage: 'Details changed by {name}' },
    entryDONE: { id: 'actions.entry.done', defaultMessage: '{name} marked it as done' },
    entryCLOSED: { id: 'actions.entry.closed', defaultMessage: 'Verified and closed by {name}' },
    entrySENT_BACK: { id: 'actions.entry.sentBack', defaultMessage: 'Sent back to the owner by {name}' },
    entryCANCELLED: { id: 'actions.entry.cancelled', defaultMessage: 'Cancelled by {name}: {kind}' },
    entryREOPENED: { id: 'actions.entry.reopened', defaultMessage: 'Reopened by {name}, due {date}' },
    entryDISCARDED: { id: 'actions.entry.discarded', defaultMessage: 'Draft discarded by {name}' },
    entryMEMO_RAISED: { id: 'actions.entry.memoRaised', defaultMessage: '{name} raised a follow-up memo: {memo}' },
    entryMEMO_LINKED: { id: 'actions.entry.memoLinked', defaultMessage: '{name} linked a follow-up memo: {memo}' },
    entryMEMO_DECIDED: { id: 'actions.entry.memoDecided', defaultMessage: 'Follow-up memo {memo}: {outcome}' },
    entrySOURCE_CORRECTED: {
      id: 'actions.entry.sourceCorrected',
      defaultMessage: 'The {forum}’s decision on this item was corrected',
    },
    entryHELD_BACK: {
      id: 'actions.entry.heldBack',
      defaultMessage: 'Held back from the next action taken report by {name}',
    },
    entryUPDATE_REQUESTED: { id: 'actions.entry.updateRequested', defaultMessage: 'An update was asked for' },
    entryNOT_RUNNING: {
      id: 'actions.entry.notRunning',
      defaultMessage: 'The system found that the action’s reminders were not running and told the secretaries',
    },
    entryRECONCILED: { id: 'actions.entry.reconciled', defaultMessage: 'The system brought the action up to date' },
    entryOther: { id: 'actions.entry.other', defaultMessage: 'Changed by {name}' },
    memoNamed: { id: 'actions.entry.memoNamed', defaultMessage: 'memo {reference}' },
    memoUnnamed: { id: 'actions.entry.memoUnnamed', defaultMessage: 'a memo' },
    outcomeAPPROVED: { id: 'actions.outcome.approved', defaultMessage: 'approved' },
    outcomeREJECTED: { id: 'actions.outcome.rejected', defaultMessage: 'rejected' },
    outcomeWITHDRAWN: { id: 'actions.outcome.withdrawn', defaultMessage: 'withdrawn' },
    outcomeCANCELLED: { id: 'actions.outcome.cancelled', defaultMessage: 'cancelled' },
    outcomeOther: { id: 'actions.outcome.other', defaultMessage: 'decided' },

    // Refusals in words (design §3 and the as-built notes of T4–T6).
    refusedRECUSED: {
      id: 'actions.refused.recused',
      defaultMessage: 'You declared an interest in this item, so another officer does this.',
    },
    refusedWRONG_STATE: {
      id: 'actions.refused.wrongState',
      defaultMessage: 'The action has moved on meanwhile, so this cannot be done now. It has been reloaded.',
    },
    refusedPENDING_REQUEST: {
      id: 'actions.refused.pendingRequest',
      defaultMessage: 'A new date was asked for already. Wait for the answer.',
    },
    refusedTOO_LATE: {
      id: 'actions.refused.tooLate',
      defaultMessage: 'It is too late for this. Raise a new action instead.',
    },
    refusedNOT_HOLDER: {
      id: 'actions.refused.notHolder',
      defaultMessage: 'Whoever holds the action now marks it as done. Someone is acting for you.',
    },
    refusedNOT_VERIFIER: {
      id: 'actions.refused.notVerifier',
      defaultMessage: 'You are not the one to verify this action.',
    },
    refusedCLAIMED: {
      id: 'actions.refused.claimed',
      defaultMessage: 'Another verifier is checking this action already.',
    },
    refusedNOT_INVOLVED: {
      id: 'actions.refused.notInvolved',
      defaultMessage: 'Only the action’s owner and the people involved raise its memos.',
    },
    refusedALREADY_LINKED: {
      id: 'actions.refused.alreadyLinked',
      defaultMessage: 'This memo already follows up the action.',
    },
    refusedASK_FOR_IT: {
      id: 'actions.refused.askForIt',
      defaultMessage: 'This {forum} agrees new dates: ask for a new date instead.',
    },
    refusedOWNER_CHANGES: {
      id: 'actions.refused.ownerChanges',
      defaultMessage: 'This {forum} lets the owner change the date: change it instead of asking.',
    },
    refusedConflict: {
      id: 'actions.refused.conflict',
      defaultMessage: 'Someone changed this action meanwhile. It has been reloaded: check it and try again.',
    },
    refusedForbidden: { id: 'actions.refused.forbidden', defaultMessage: 'You may not do this on this action.' },
    refusedNotFound: { id: 'actions.refused.notFound', defaultMessage: 'This action is no longer there for you.' },
    refusedRateLimited: {
      id: 'actions.refused.rateLimited',
      defaultMessage: 'Too many tries. Wait a moment and try again.',
    },
    refusedFailed: { id: 'actions.refused.failed', defaultMessage: 'That did not work. Try again.' },
    fieldRequired: { id: 'actions.field.required', defaultMessage: 'This is needed.' },
    fieldTextLength: { id: 'actions.field.textLength', defaultMessage: 'This is too long: at most 4,000 characters.' },
    fieldNoteLength: { id: 'actions.field.noteLength', defaultMessage: 'This is too long: at most 2,000 characters.' },
    fieldReasonLength: {
      id: 'actions.field.reasonLength',
      defaultMessage: 'This is too long: at most 500 characters.',
    },
    fieldConfidence: { id: 'actions.field.confidence', defaultMessage: 'Only the owner says how it is going.' },
    fieldEvidenceRequired: {
      id: 'actions.field.evidenceRequired',
      defaultMessage: 'This {forum} asks for evidence: attach the files.',
    },
    fieldFilesTooMany: { id: 'actions.field.filesTooMany', defaultMessage: 'At most 10 files.' },
    fieldFileUnknown: {
      id: 'actions.field.fileUnknown',
      defaultMessage: 'A file is not one you attached to this action. Remove it and attach it again.',
    },
    fieldDueInPast: { id: 'actions.field.dueInPast', defaultMessage: 'Choose today or a later date.' },
    fieldSameDate: { id: 'actions.field.sameDate', defaultMessage: 'The action is due on that date already.' },
    fieldMemosTooMany: {
      id: 'actions.field.memosTooMany',
      defaultMessage: 'An action has at most 10 follow-up memos.',
    },
    fieldLowerLevel: {
      id: 'actions.field.lowerLevel',
      defaultMessage: 'This level is lower than the action’s. Tick the confirmation to use it.',
    },
    fieldNotReadable: { id: 'actions.field.notReadable', defaultMessage: 'Choose a memo you can open.' },
    fieldDiscarded: { id: 'actions.field.discarded', defaultMessage: 'This memo was discarded. Choose another.' },
    fieldTypeUnknown: { id: 'actions.field.typeUnknown', defaultMessage: 'Choose a memo type.' },
    fieldTypeNotAllowed: {
      id: 'actions.field.typeNotAllowed',
      defaultMessage: 'You may not raise memos of this type.',
    },
    fieldBelowTypeDefault: {
      id: 'actions.field.belowTypeDefault',
      defaultMessage: 'This level is below what this memo type needs.',
    },
    fieldRemarkRequired: { id: 'actions.field.remarkRequired', defaultMessage: 'Say what is missing to send it back.' },

    // The action taken report (flow AO).
    reportOf: { id: 'actions.report.of', defaultMessage: 'Action taken report — {committee}, {meeting}' },
    reportOfCommittee: { id: 'actions.report.ofCommittee', defaultMessage: 'Action taken report — {committee}' },
    reportBuilt: {
      id: 'actions.report.built',
      defaultMessage: 'Built when the agenda was published on {date} at {time}. It does not change.',
    },
    reportSince: {
      id: 'actions.report.since',
      defaultMessage: '“Since the last meeting” means since {meeting} began.',
    },
    reportSinceStart: {
      id: 'actions.report.sinceStart',
      defaultMessage: 'There was no earlier meeting: every closed and cancelled action is listed.',
    },
    reportPrint: { id: 'actions.report.print', defaultMessage: 'Print' },
    reportPrintHint: { id: 'actions.report.printHint', defaultMessage: 'Opens your rows as a PDF, to print or keep.' },
    reportRevision: { id: 'actions.report.revision', defaultMessage: 'Agenda revision' },
    reportRevisionN: { id: 'actions.report.revisionN', defaultMessage: 'Revision {n, number}' },
    reportRevisionLatest: { id: 'actions.report.revisionLatest', defaultMessage: 'Revision {n, number} (latest)' },
    reportBack: { id: 'actions.report.back', defaultMessage: 'Back to the meeting' },
    reportNone: {
      id: 'actions.report.none',
      defaultMessage: 'There is no action taken report for this meeting, or it is not for you.',
    },
    sectionTO_CLOSE: {
      id: 'actions.report.section.toClose',
      defaultMessage: 'Proposed to close ({count, number}) — the {forum} is asked to agree',
    },
    sectionOVERDUE: { id: 'actions.report.section.overdue', defaultMessage: 'Overdue ({count, number})' },
    sectionOPEN: { id: 'actions.report.section.open', defaultMessage: 'Open ({count, number})' },
    sectionCLOSED: {
      id: 'actions.report.section.closed',
      defaultMessage: 'Closed since the last meeting, for noting ({count, number})',
    },
    sectionCANCELLED: {
      id: 'actions.report.section.cancelled',
      defaultMessage: 'Cancelled since the last meeting, for noting ({count, number})',
    },
    sectionCORRECTIONS: {
      id: 'actions.report.section.corrections',
      defaultMessage: 'Corrections to earlier minutes ({count, number})',
    },
    sectionEmpty: { id: 'actions.report.section.empty', defaultMessage: 'None.' },
    reportMore: {
      id: 'actions.report.more',
      defaultMessage:
        'And {count, plural, one {# more open action} other {# more open actions}}: see the {forum}’s tracker.',
    },
    reportHeldBack: {
      id: 'actions.report.heldBack',
      defaultMessage:
        '{count, plural, one {# action was held back by the secretary} other {# actions were held back by the secretary}}. Only officers see this line.',
    },
    rowOwner: { id: 'actions.report.row.owner', defaultMessage: 'Owner: {owner}' },
    rowOwnerHolder: { id: 'actions.report.row.ownerHolder', defaultMessage: 'Owner: {owner} (held by {holder})' },
    rowDone: { id: 'actions.report.row.done', defaultMessage: 'Done {date}' },
    rowClosed: { id: 'actions.report.row.closed', defaultMessage: 'Closed {date}' },
    rowCancelled: { id: 'actions.report.row.cancelled', defaultMessage: 'Cancelled: {kind}' },
    rowLatest: { id: 'actions.report.row.latest', defaultMessage: '{date}: “{text}”' },
    rowLatestUndated: { id: 'actions.report.row.latestUndated', defaultMessage: '“{text}”' },
    rowMemos: { id: 'actions.report.row.memos', defaultMessage: 'Follow-up memos, as they stand now' },
    rowMemo: { id: 'actions.report.row.memo', defaultMessage: 'Memo {reference}: {subject} ({status})' },
    rowMemoUnnumbered: {
      id: 'actions.report.row.memoUnnumbered',
      defaultMessage: 'Memo not yet numbered: {subject} ({status})',
    },
    correctionApproved: { id: 'actions.report.correctionApproved', defaultMessage: '{label} (approved {date})' },
  }),
)

/**
 * The small texts other pages show about actions (Phase 4 E07 T11): the "Matters arising" item's link to its report on
 * the agenda (flow AO) and "Raised from action" on the memo (flow AP). Their own part (`actionLinks`), so the meeting and
 * memo pages do not load all of `actions`.
 */
export const al = plain(
  defineMessages({
    reportLink: { id: 'actionLinks.report.link', defaultMessage: 'Action taken report (as published on {date})' },
    reportNothing: { id: 'actionLinks.report.nothing', defaultMessage: 'Nothing to report' },
    reportOpen: { id: 'actionLinks.report.open', defaultMessage: 'Open the report' },
    countTO_CLOSE: { id: 'actionLinks.count.toClose', defaultMessage: '{count, plural, other {# proposed to close}}' },
    countOVERDUE: { id: 'actionLinks.count.overdue', defaultMessage: '{count, plural, other {# overdue}}' },
    countOPEN: { id: 'actionLinks.count.open', defaultMessage: '{count, plural, other {# open}}' },
    countCLOSED: {
      id: 'actionLinks.count.closed',
      defaultMessage: '{count, plural, other {# closed since the last meeting}}',
    },
    countCANCELLED: {
      id: 'actionLinks.count.cancelled',
      defaultMessage: '{count, plural, other {# cancelled since the last meeting}}',
    },
    countCORRECTIONS: {
      id: 'actionLinks.count.corrections',
      defaultMessage: '{count, plural, one {# correction} other {# corrections}} to earlier minutes',
    },
    raisedFrom: { id: 'actionLinks.origin.raisedFrom', defaultMessage: 'Raised from action {reference} ({committee})' },
    linkedTo: { id: 'actionLinks.origin.linkedTo', defaultMessage: 'Follows up action {reference} ({committee})' },
    origins: { id: 'actionLinks.origin.label', defaultMessage: '{Forum} actions this memo follows up' },
  }),
)
