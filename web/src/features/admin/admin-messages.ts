import { defineMessages } from 'react-intl'

/** Tenant admin console texts (design E07 §7). */
export const am = defineMessages({
  homeTitle: { id: 'admin.home.title', defaultMessage: 'Administration' },
  homeLead: {
    id: 'admin.home.lead',
    defaultMessage: 'Settings for your whole organisation. You see the parts you may change.',
  },
  cardUsers: { id: 'admin.card.users', defaultMessage: 'Invite people, reset sign-in, end sessions.' },
  cardRoles: { id: 'admin.card.roles', defaultMessage: 'Who may do what, for the whole organisation or one part.' },
  cardClassifications: { id: 'admin.card.classifications', defaultMessage: 'Classification levels and clearances.' },
  cardEmail: { id: 'admin.card.email', defaultMessage: 'Your mail server and the delivery log.' },
  cardSecurity: {
    id: 'admin.card.security',
    defaultMessage: 'Two-step sign-in, session length, passwords and lockout.',
  },
  cardBranding: { id: 'admin.card.branding', defaultMessage: 'Logo and colours.' },
  cardLanguage: { id: 'admin.card.language', defaultMessage: 'Default language, time zone and your own words.' },
  cardAudit: { id: 'admin.card.audit', defaultMessage: 'Who changed what, and a verifiable export.' },

  saved: { id: 'admin.saved', defaultMessage: 'Saved.' },
  save: { id: 'admin.save', defaultMessage: 'Save' },
  stale: {
    id: 'admin.stale',
    defaultMessage: 'Someone changed these settings meanwhile. The page now shows their version.',
  },
  invalid: { id: 'admin.invalid', defaultMessage: 'Check the highlighted fields.' },
  failed: { id: 'admin.failed', defaultMessage: 'That did not work. Try again.' },

  securityTitle: { id: 'admin.security.title', defaultMessage: 'Security' },
  securityLead: {
    id: 'admin.security.lead',
    defaultMessage: 'Changes apply to the next sign-in. Saving asks you to confirm it is you.',
  },
  mfa: { id: 'admin.security.mfa', defaultMessage: 'Who must use two-step sign-in' },
  mfaAll: { id: 'admin.security.mfa.all', defaultMessage: 'Everyone' },
  mfaAdmins: { id: 'admin.security.mfa.admins', defaultMessage: 'Administrators only (administrators always need it)' },
  idleMinutes: { id: 'admin.security.idle', defaultMessage: 'Sign out after inactivity (minutes)' },
  absoluteHours: { id: 'admin.security.absolute', defaultMessage: 'Longest session (hours)' },
  stepUpMinutes: { id: 'admin.security.stepUp', defaultMessage: 'A confirmation lasts (minutes)' },
  passwordMinLength: { id: 'admin.security.password', defaultMessage: 'Shortest password (characters)' },
  throttleAfter: { id: 'admin.security.throttle', defaultMessage: 'Wrong passwords before each attempt has to wait' },
  lockAfter: { id: 'admin.security.lock', defaultMessage: 'Wrong passwords in a row that lock the account' },
  lockMinutes: { id: 'admin.security.lockMinutes', defaultMessage: 'Lock lasts (minutes)' },

  brandingTitle: { id: 'admin.branding.title', defaultMessage: 'Branding' },
  brandingLead: {
    id: 'admin.branding.lead',
    defaultMessage:
      'Your logo appears on the sign-in page and in the top bar. Colours must stay readable for everyone.',
  },
  logo: { id: 'admin.branding.logo', defaultMessage: 'Logo' },
  logoHint: {
    id: 'admin.branding.logo.hint',
    defaultMessage: 'PNG, JPEG or WebP, up to 512 KB. Shown at most 160 × 40.',
  },
  logoNone: { id: 'admin.branding.logo.none', defaultMessage: 'No logo yet.' },
  logoRemove: { id: 'admin.branding.logo.remove', defaultMessage: 'Remove logo' },
  colourLight: { id: 'admin.branding.light', defaultMessage: 'Colour, light theme' },
  colourDark: { id: 'admin.branding.dark', defaultMessage: 'Colour, dark theme' },
  colourHint: { id: 'admin.branding.colour.hint', defaultMessage: 'As #rrggbb. Leave empty for the default.' },
  contrastOk: { id: 'admin.branding.contrast.ok', defaultMessage: 'Readable' },
  contrastLow: { id: 'admin.branding.contrast.low', defaultMessage: 'Too little contrast' },

  languageTitle: { id: 'admin.language.title', defaultMessage: 'Language and terms' },
  languageLead: {
    id: 'admin.language.lead',
    defaultMessage: 'The defaults for new people, and the words your organisation uses for some things.',
  },
  defaultLanguage: { id: 'admin.language.default', defaultMessage: 'Default language' },
  timeZone: { id: 'admin.language.timeZone', defaultMessage: 'Time zone' },
  termsTitle: { id: 'admin.terms.title', defaultMessage: 'Your terms' },
  termsLead: {
    id: 'admin.terms.lead',
    defaultMessage: 'Write the words as they appear in a sentence. Empty rows use the standard words.',
  },
  termsLanguage: { id: 'admin.terms.language', defaultMessage: 'Language' },
  termsCaption: { id: 'admin.terms.caption', defaultMessage: 'Terms for the chosen language' },
  colTerm: { id: 'admin.terms.col.term', defaultMessage: 'Term' },
  colOne: { id: 'admin.terms.col.one', defaultMessage: 'One' },
  colOther: { id: 'admin.terms.col.other', defaultMessage: 'Several' },
  colGender: { id: 'admin.terms.col.gender', defaultMessage: 'Grammatical gender' },
  masculine: { id: 'admin.terms.masculine', defaultMessage: 'Masculine' },
  feminine: { id: 'admin.terms.feminine', defaultMessage: 'Feminine' },
  termUnit: { id: 'admin.terms.unit', defaultMessage: 'Unit (department, branch)' },
  termPosition: { id: 'admin.terms.position', defaultMessage: 'Position' },
  termMemo: { id: 'admin.terms.memo', defaultMessage: 'Memo' },
  saveTerms: { id: 'admin.terms.save', defaultMessage: 'Save terms' },

  auditTitle: { id: 'admin.audit.title', defaultMessage: 'Audit' },
  auditLead: {
    id: 'admin.audit.lead',
    defaultMessage: 'Every change in your organisation, newest first. The record cannot be altered without it showing.',
  },
  from: { id: 'admin.audit.from', defaultMessage: 'From' },
  to: { id: 'admin.audit.to', defaultMessage: 'To' },
  action: { id: 'admin.audit.action', defaultMessage: 'Action starts with' },
  objectType: { id: 'admin.audit.objectType', defaultMessage: 'Object type' },
  auditCaption: { id: 'admin.audit.caption', defaultMessage: 'Audit entries, newest first' },
  colTime: { id: 'admin.audit.col.time', defaultMessage: 'Time' },
  colActor: { id: 'admin.audit.col.actor', defaultMessage: 'Who' },
  colAction: { id: 'admin.audit.col.action', defaultMessage: 'Action' },
  colObject: { id: 'admin.audit.col.object', defaultMessage: 'Object' },
  colChange: { id: 'admin.audit.col.change', defaultMessage: 'Change' },
  details: { id: 'admin.audit.details', defaultMessage: 'Details' },
  before: { id: 'admin.audit.before', defaultMessage: 'Before' },
  after: { id: 'admin.audit.after', defaultMessage: 'After' },
  system: { id: 'admin.audit.system', defaultMessage: 'System' },
  platform: { id: 'admin.audit.platform', defaultMessage: 'Platform operator' },
  anonymous: { id: 'admin.audit.anonymous', defaultMessage: 'Not signed in' },
  loadMore: { id: 'admin.audit.loadMore', defaultMessage: 'Load more' },
  empty: { id: 'admin.audit.empty', defaultMessage: 'Nothing in this period.' },
  export: { id: 'admin.audit.export', defaultMessage: 'Export' },
  exportHint: {
    id: 'admin.audit.export.hint',
    defaultMessage:
      'A ZIP file with the entries, a manifest and the steps to verify it. Without filters it proves nothing is missing.',
  },
  exported: { id: 'admin.audit.exported', defaultMessage: 'Export downloaded.' },
  tooMany: { id: 'admin.audit.tooMany', defaultMessage: 'Too many exports this hour. Try again later.' },
})

/** Texts with values. */
export const amv = defineMessages<{
  contrast: { ratio: string; minimum: string }
  termCell: { term: string; column: string }
}>({
  termCell: { id: 'admin.terms.cell', defaultMessage: '{term}: {column}' },
  contrast: { id: 'admin.branding.contrast', defaultMessage: '{ratio}:1 (at least {minimum}:1)' },
})
