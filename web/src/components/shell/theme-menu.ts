import { Monitor, Moon, Sun } from 'lucide-react'
import { defineMessages, type NoMessageValues } from 'react-intl'

/** Typed placeholders: a missing or misspelled value is a compile error. */
export const themeMessages = defineMessages<{
  label: NoMessageValues
  trigger: { current: string }
  light: NoMessageValues
  dark: NoMessageValues
  system: NoMessageValues
}>({
  label: { id: 'theme.label', defaultMessage: 'Theme' },
  trigger: { id: 'theme.trigger', defaultMessage: 'Change theme (current: {current})' },
  light: { id: 'theme.light', defaultMessage: 'Light' },
  dark: { id: 'theme.dark', defaultMessage: 'Dark' },
  system: { id: 'theme.system', defaultMessage: 'System' },
})

export const themeIcons = { light: Sun, dark: Moon, system: Monitor } as const
