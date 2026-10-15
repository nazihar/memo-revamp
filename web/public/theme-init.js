// Runs before the app (external file: no inline script under our CSP). Applies the saved theme and language direction
// so the first paint is already correct. Preferences only — never credentials (AGENTS.md §2).
;(function () {
  try {
    var root = document.documentElement
    var theme = localStorage.getItem('memo.theme') || 'system'
    var dark = theme === 'dark' || (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
    root.classList.toggle('dark', dark)
    var locale = localStorage.getItem('memo.locale')
    if (locale) {
      root.lang = locale
      root.dir = /^(ar|he|fa|ur)(-|$)/.test(locale) ? 'rtl' : 'ltr'
    }
  } catch (e) {
    // Storage unavailable (privacy mode): defaults apply.
  }
})()
