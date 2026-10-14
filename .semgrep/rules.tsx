// Fixtures for .semgrep/rules.yml (`semgrep --test .semgrep/`). Not compiled.
export function Bad({ html }: { html: string }) {
  // ruleid: memo-no-dangerously-set-inner-html
  return <div dangerouslySetInnerHTML={{ __html: html }} />
}

export function Good({ text }: { text: string }) {
  // ok: memo-no-dangerously-set-inner-html
  return <div>{text}</div>
}

export function storeToken(token: string) {
  // ruleid: memo-web-storage-only-preferences
  localStorage.setItem('token', token)
  // ruleid: memo-web-storage-only-preferences
  window.sessionStorage.setItem('jwt', token)
}
