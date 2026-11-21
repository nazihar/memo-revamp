"""Add an Organisation item after Dashboard in every desktop sidebar and phone drawer."""
import re
from pathlib import Path
proj = Path('canvas/project')
ICON_D = '<svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="16" y="16" width="6" height="6" rx="1"></rect><rect x="2" y="16" width="6" height="6" rx="1"></rect><rect x="9" y="2" width="6" height="6" rx="1"></rect><path d="M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3"></path><path d="M12 12V8"></path></svg>'
DESK = re.compile(r'( *)<a [^>]*title="Dashboard"[^>]*>.*?</a>\n', re.S)
PHONE = re.compile(r'( *)<a [^>]*class="nav-item"[^>]*>\s*<svg[^>]*><path d="M3 3v16a2 2 0 0 0 2 2h16"></path>.*?Dashboard\n\s*</a>\n', re.S)
def clean(block):
    block = block.replace(' aria-current="page"', '').replace('; background: var(--active); font-weight: 500', '')
    block = re.sub(r'<svg.*?</svg>', lambda m: ICON_D if 'width="16"' in m.group(0) else ICON_D.replace('width="16" height="16"', 'width="18" height="18"'), block, count=1, flags=re.S)
    return block
done = []
for f in sorted(proj.glob('*.dc.html')):
    s = f.read_text()
    if '>Organisation<' in s or 'Organisation\n' in s:
        continue
    changed = False
    for rx, phone in ((DESK, False), (PHONE, True)):
        m = rx.search(s)
        if not m:
            continue
        b = clean(m.group(0))
        b = re.sub(r'href="[^"]*"', 'href="%s"' % ('OrgPhone.dc.html' if phone else 'Org.dc.html'), b, count=1)
        b = b.replace('title="Dashboard"', 'title="Organisation"').replace('>Dashboard<', '>Organisation<').replace('Dashboard\n', 'Organisation\n')
        assert 'Dashboard' not in b, (f.name, b)
        s = s[:m.end()] + b + s[m.end():]
        changed = True
    if changed:
        f.write_text(s); done.append(f.name)
print(len(done), done)
