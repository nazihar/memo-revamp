import { defineMessages } from 'react-intl'

/** Texts of the committee type's Agenda tab (Phase 4 E03 T7, flow R), in the `agendaBuilder` catalogue part. */
export const tm = defineMessages({
  tab: { id: 'agendaBuilder.template.tab', defaultMessage: 'Agenda' },
  chairAgreement: {
    id: 'agendaBuilder.template.chairAgreement',
    defaultMessage: 'The chair agrees the agenda before it is first published',
  },
  chairAgreementHint: {
    id: 'agendaBuilder.template.chairAgreementHint',
    defaultMessage: 'The secretary can record an agreement the chair gave by email or in person.',
  },
  chairAgreesOn: {
    id: 'agendaBuilder.template.chairAgreesOn',
    defaultMessage: 'The chair agrees the agenda before it is first published.',
  },
  chairAgreesOff: {
    id: 'agendaBuilder.template.chairAgreesOff',
    defaultMessage: 'The agenda is published without the chair’s agreement.',
  },
  standard: { id: 'agendaBuilder.template.standard', defaultMessage: 'Standard agenda' },
  standardHint: {
    id: 'agendaBuilder.template.standardHint',
    defaultMessage: 'New meetings start from this; meetings already started keep theirs.',
  },
  noSections: { id: 'agendaBuilder.template.noSections', defaultMessage: 'The standard agenda has no sections.' },
  sectionTitle: { id: 'agendaBuilder.template.sectionTitle', defaultMessage: 'Section title' },
  consent: { id: 'agendaBuilder.template.consent', defaultMessage: 'Consent section' },
  membersOnly: { id: 'agendaBuilder.template.membersOnly', defaultMessage: 'Members only' },
  landsHere: { id: 'agendaBuilder.template.landsHere', defaultMessage: 'New items land here' },
  moveSectionUp: { id: 'agendaBuilder.template.moveSectionUp', defaultMessage: 'Move section up' },
  moveSectionDown: { id: 'agendaBuilder.template.moveSectionDown', defaultMessage: 'Move section down' },
  removeSection: { id: 'agendaBuilder.template.removeSection', defaultMessage: 'Remove section' },
  addSection: { id: 'agendaBuilder.template.addSection', defaultMessage: 'Add section' },
  itemTitle: { id: 'agendaBuilder.template.itemTitle', defaultMessage: 'Standing item' },
  standingKind: { id: 'agendaBuilder.template.standingKind', defaultMessage: 'Kind' },
  noKind: { id: 'agendaBuilder.template.noKind', defaultMessage: 'Other' },
  purpose: { id: 'agendaBuilder.template.purpose', defaultMessage: 'Purpose' },
  minutes: { id: 'agendaBuilder.template.minutes', defaultMessage: 'Minutes' },
  moveUp: { id: 'agendaBuilder.template.moveUp', defaultMessage: 'Move up' },
  moveDown: { id: 'agendaBuilder.template.moveDown', defaultMessage: 'Move down' },
  removeItem: { id: 'agendaBuilder.template.removeItem', defaultMessage: 'Remove standing item' },
  addItem: { id: 'agendaBuilder.template.addItem', defaultMessage: 'Add standing item' },
  newItem: { id: 'agendaBuilder.template.newItem', defaultMessage: 'New standing item' },
})

export const tmv = defineMessages<{
  sectionNumber: { number: number }
  sectionActions: { title: string }
  itemActions: { title: string }
  itemsOf: { title: string }
  addItemTo: { title: string }
}>({
  sectionNumber: { id: 'agendaBuilder.template.sectionNumber', defaultMessage: 'Section {number}' },
  sectionActions: { id: 'agendaBuilder.template.sectionActions', defaultMessage: 'Actions for the section {title}' },
  itemActions: { id: 'agendaBuilder.template.itemActions', defaultMessage: 'Actions for {title}' },
  itemsOf: { id: 'agendaBuilder.template.itemsOf', defaultMessage: 'Standing items of {title}' },
  addItemTo: { id: 'agendaBuilder.template.addItemTo', defaultMessage: 'Add standing item to {title}' },
})
