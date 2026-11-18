"""Dark mode options: the current palette and four alternatives on the same slice of the app, with contrast checks."""
import json
from pathlib import Path
proj = Path('canvas/project')

def lum(h):
    h = h.lstrip('#'); r, g, b = (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))
    f = lambda c: c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
def cr(a, b):
    la, lb = lum(a), lum(b); return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
def blend(fg, bg, a):  # an rgba tint over a solid background, as a hex colour
    f = [int(fg[i:i + 2], 16) for i in (1, 3, 5)]; b = [int(bg[i:i + 2], 16) for i in (1, 3, 5)]
    return '#' + ''.join(f'{round(x * a + y * (1 - a)):02x}' for x, y in zip(f, b))

OPTIONS = [
    dict(key='current', name='Current', tag='As designed today',
         why='Near-black page, light grey buttons and dark, muddy status colours. Everything is grey, so it reads as washed out: the brightest thing on screen is the button, and it is grey too.',
         bg='#0a0a0a', card='#0a0a0a', subtle='#141414', muted='#262626', active='#262626', border='#2e2e2e', strong='#525252',
         fg='#fafafa', text1='#e5e5e5', mfg='#a3a3a3', primary='#e5e5e5', pfg='#171717', ring='#737373',
         ok=('#86efac', '#052e16', '#14532d'), warn=('#fcd34d', '#422006', '#78350f'), danger=('#f87171', '#450a0a', '#7f1d1d'), info=('#93c5fd', '#172554'), dest='#dc2626'),
    dict(key='crisp', name='A · Crisp neutral', tag='Smallest change',
         why='Same neutral greys, but cards sit one step above the page, text is brighter, the primary button is near-white, and status colours become vivid tints (12% of the colour) with bright text. Keeps the quiet look, removes the fog.',
         bg='#09090b', card='#121214', subtle='#17171a', muted='#232327', active='#27272a', border='#27272a', strong='#3f3f46',
         fg='#fafafa', text1='#f4f4f5', mfg='#a1a1aa', primary='#fafafa', pfg='#09090b', ring='#a1a1aa',
         ok=('#4ade80', 0.12, 0.30), warn=('#fbbf24', 0.12, 0.30), danger=('#f87171', 0.12, 0.30), info=('#60a5fa', 0.14), dest='#dc2626'),
    dict(key='midnight', name='B · Midnight', tag='Recommended',
         why='A blue-black page with slate surfaces and a blue primary button. The slight blue tint gives depth that pure grey lacks, and the coloured primary makes the one action on each screen stand out. Common in finance tools and easy on the eyes for long reading.',
         bg='#0b1120', card='#111a2e', subtle='#0f172a', muted='#1e293b', active='#1e293b', border='#1e293b', strong='#334155',
         fg='#f8fafc', text1='#e2e8f0', mfg='#94a3b8', primary='#2563eb', pfg='#ffffff', ring='#60a5fa',
         ok=('#4ade80', 0.12, 0.30), warn=('#fbbf24', 0.12, 0.30), danger=('#f87171', 0.14, 0.32), info=('#60a5fa', 0.16), dest='#dc2626'),
    dict(key='indigo', name='C · Neutral with an accent', tag='Brand colour',
         why='Neutral greys like A, with one accent colour (indigo here; any brand colour works) on the primary button, the active sidebar item, focus rings and links. Colour appears only where it means "this is the action".',
         bg='#0c0c0f', card='#141418', subtle='#18181c', muted='#232329', active='#1e1b4b', border='#26262c', strong='#3f3f46',
         fg='#fafafa', text1='#f4f4f5', mfg='#a1a1aa', primary='#4f46e5', pfg='#ffffff', ring='#818cf8',
         ok=('#4ade80', 0.12, 0.30), warn=('#fbbf24', 0.12, 0.30), danger=('#f87171', 0.12, 0.30), info=('#818cf8', 0.16), dest='#dc2626', activeFg='#c7d2fe'),
    dict(key='emerald', name='D · Deep green', tag='Warmer, distinctive',
         why='A green-black page with a bright mint primary and dark text on it. It feels distinctive and calm, and fits a bank. The bright primary is very visible; status green has to be told apart from the brand green, so Approved uses a check icon as well as colour.',
         bg='#07110f', card='#0c1a17', subtle='#0f1f1b', muted='#16302a', active='#16302a', border='#1a2f2a', strong='#2e4b44',
         fg='#f0fdf9', text1='#d1fae5', mfg='#94b8ad', primary='#34d399', pfg='#052e22', ring='#34d399',
         ok=('#6ee7b7', 0.12, 0.30), warn=('#fbbf24', 0.12, 0.30), danger=('#fb7185', 0.14, 0.32), info=('#7dd3fc', 0.14), dest='#e11d48'),
]

def resolve(o):
    out = dict(o)
    for k in ('ok', 'warn', 'danger'):
        c, a, b = o[k]
        out[k] = (c, a, b) if isinstance(a, str) else (c, blend(c, o['card'], a), blend(c, o['card'], b))
    c, a = o['info']; out['info'] = (c, a if isinstance(a, str) else blend(c, o['card'], a))
    return out

def checks(o):
    rows = [('Text on page', o['fg'], o['bg']), ('Body text on card', o['text1'], o['card']), ('Secondary text on card', o['mfg'], o['card']),
            ('Primary button', o['pfg'], o['primary']), ('Overdue badge', '#ffffff', o['dest']), ('Approved badge', o['ok'][0], o['ok'][1]),
            ('Due-soon badge', o['warn'][0], o['warn'][1]), ('Problem text', o['danger'][0], o['danger'][1])]
    return [(n, cr(a, b)) for n, a, b in rows]

def preview(o):
    o = resolve(o)
    af = o.get('activeFg', o['fg'])
    chip = lambda t, c, b, br, icon='': f'<span style="display: inline-flex; align-items: center; gap: 4px; height: 20px; padding: 0 7px; border-radius: 6px; border: 1px solid {br}; background: {b}; color: {c}; font-size: 11px; font-weight: 600; white-space: nowrap">{icon}{t}</span>'
    check = '<svg aria-hidden="true" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"></path></svg>'
    nav = ''.join(f'<span style="display: flex; align-items: center; justify-content: space-between; height: 28px; padding: 0 8px; border-radius: 6px; font-size: 12px; {"background: " + o["active"] + "; color: " + af + "; font-weight: 600" if n == "Inbox" else "color: " + o["text1"]}">{n}{"<span style=" + chr(34) + "font-size: 11px; color: " + o["mfg"] + chr(34) + ">8</span>" if n == "Inbox" else ""}</span>' for n in ['Home', 'Inbox', 'Saved searches', 'Dashboard', 'Organisation'])
    row = lambda s, ref, ch, unread=False: f'<div style="display: flex; align-items: center; gap: 10px; padding: 9px 12px; border-top: 1px solid {o["border"]}">{"<span style=" + chr(34) + "width: 7px; height: 7px; border-radius: 50%; background: " + o["info"][0] + "; flex-shrink: 0" + chr(34) + "></span>" if unread else "<span style=" + chr(34) + "width: 7px; flex-shrink: 0" + chr(34) + "></span>"}<span style="flex: 1; min-width: 0; display: flex; flex-direction: column"><span style="font-size: 13px; font-weight: {"600" if unread else "500"}; color: {o["fg"]}; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{s}</span><span style="font-size: 11px; color: {o["mfg"]}; font-family: Geist Mono, ui-monospace, monospace">{ref}</span></span>{ch}</div>'
    rows = (row('Credit line renewal for Meghna Agro', 'CR/2026/0142', chip('Overdue 1 day', '#ffffff', o['dest'], o['dest']), True)
            + row('Credit Risk unit budget, FY2027', 'FIN/2026/0326', chip('Due tomorrow', o['warn'][0], o['warn'][1], o['warn'][2]), True)
            + row('Treasury limits for FY2027', 'TRY/2026/0019', chip('Approved', o['ok'][0], o['ok'][1], o['ok'][2], check)))
    btn = lambda t, st: f'<span style="display: inline-flex; align-items: center; height: 30px; padding: 0 12px; border-radius: 8px; font-size: 12px; font-weight: 600; {st}">{t}</span>'
    return f'''<div style="display: flex; height: 340px; border: 1px solid {o["strong"]}; border-radius: 12px; overflow: hidden; background: {o["bg"]}; font-family: Geist, ui-sans-serif, system-ui, sans-serif">
  <div style="flex: 0 0 150px; display: flex; flex-direction: column; gap: 2px; padding: 10px 8px; background: {o["subtle"]}; border-right: 1px solid {o["border"]}">
    <span style="display: flex; align-items: center; gap: 6px; margin-bottom: 8px; font-size: 12px; font-weight: 600; color: {o["fg"]}"><span style="display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; border-radius: 5px; background: {o["primary"]}; color: {o["pfg"]}; font-size: 11px">N</span>Organisation</span>
    {nav}
  </div>
  <div style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 10px; padding: 12px 14px">
    <div style="display: flex; align-items: center; justify-content: space-between"><span style="font-size: 16px; font-weight: 600; color: {o["fg"]}">Inbox</span>{btn('New memo', 'background: ' + o['primary'] + '; color: ' + o['pfg'])}</div>
    <div style="border: 1px solid {o["border"]}; border-radius: 10px; background: {o["card"]}; overflow: hidden">
      <div style="padding: 7px 12px; font-size: 11px; font-weight: 500; color: {o["mfg"]}">My tasks · 8</div>
      {rows}
    </div>
    <div style="display: flex; align-items: center; gap: 10px; padding: 10px 12px; border: 1px solid {o["border"]}; border-radius: 10px; background: {o["card"]}">
      <span style="display: flex; align-items: center; justify-content: center; width: 24px; height: 24px; border-radius: 6px; background: {o["info"][1]}; color: {o["info"][0]}; font-size: 12px; font-weight: 700">i</span>
      <span style="flex: 1; font-size: 12px; color: {o["text1"]}">Since you last looked: version 3 and <span style="color: {o["info"][0]}; text-decoration: underline">3 new notes</span>.</span>
    </div>
    <div style="display: flex; align-items: center; gap: 8px">{btn('Approve', 'background: ' + o['primary'] + '; color: ' + o['pfg'] + '; box-shadow: 0 0 0 2px ' + o['bg'] + ', 0 0 0 4px ' + o['ring'])}{btn('Return', 'border: 1px solid ' + o['strong'] + '; color: ' + o['fg'])}{btn('Reject', 'border: 1px solid ' + o['strong'] + '; color: ' + o['danger'][0])}<span style="font-size: 11px; color: {o["mfg"]}">Approve shows the focus ring</span></div>
  </div>
</div>'''

def swatches(o):
    o = resolve(o)
    sw = [('Page', o['bg']), ('Card', o['card']), ('Muted', o['muted']), ('Border', o['border']), ('Text', o['fg']), ('Secondary', o['mfg']), ('Primary', o['primary']), ('Info', o['info'][0])]
    return ''.join(f'<span style="display: flex; align-items: center; gap: 6px; font-size: 11px; color: #a1a1aa"><span style="width: 14px; height: 14px; border-radius: 4px; background: {c}; border: 1px solid #3f3f46"></span>{n} <span style="font-family: Geist Mono, ui-monospace, monospace; color: #d4d4d8">{c}</span></span>' for n, c in sw)

cards = []
for o in OPTIONS:
    ch = checks(resolve(o))
    worst = min(v for _, v in ch)
    rows = ''.join(f'<span style="display: flex; justify-content: space-between; gap: 8px; font-size: 11px"><span style="color: #a1a1aa">{n}</span><span style="font-family: Geist Mono, ui-monospace, monospace; color: {"#4ade80" if v >= 4.5 else "#fbbf24"}">{v:.1f}:1</span></span>' for n, v in ch)
    rec = o['tag'] == 'Recommended'
    cards.append(f'''<figure style="margin: 0; display: flex; flex-direction: column; gap: 10px; padding: 16px; border: 1px solid {"#3b82f6" if rec else "#27272a"}; border-radius: 14px; background: #0f0f11">
  <figcaption style="display: flex; flex-direction: column; gap: 4px">
    <span style="display: flex; align-items: center; gap: 8px"><span style="font-size: 15px; font-weight: 600; color: #fafafa">{o["name"]}</span><span style="display: inline-flex; align-items: center; height: 20px; padding: 0 7px; border-radius: 6px; font-size: 11px; font-weight: 600; {"background: #2563eb; color: #ffffff" if rec else "border: 1px solid #3f3f46; color: #d4d4d8"}">{o["tag"]}</span></span>
    <span style="font-size: 13px; line-height: 19px; color: #d4d4d8">{o["why"]}</span>
  </figcaption>
  {preview(o)}
  <div style="display: flex; flex-wrap: wrap; gap: 6px 14px">{swatches(o)}</div>
  <details style="font-size: 12px; color: #a1a1aa"><summary style="cursor: pointer">Contrast · lowest {worst:.1f}:1 {"(all pass AA)" if worst >= 4.5 else "(some below 4.5:1)"}</summary><div style="display: grid; grid-template-columns: 1fr 1fr; gap: 2px 20px; padding-top: 6px">{rows}</div></details>
</figure>''')

html = f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Dark mode options</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Geist:wght@400;500;600&amp;family=Geist+Mono:wght@400;500&amp;display=swap">
<style>body{{margin:0;background:#050505}} summary::marker{{color:#71717a}}</style>
</helmet>
<div style="min-height: 100vh; display: flex; flex-direction: column; gap: 24px; padding: 32px; box-sizing: border-box; background: #050505; color: #fafafa; font-family: 'Geist', ui-sans-serif, system-ui, sans-serif; font-size: 14px; line-height: 20px">
  <header style="display: flex; flex-direction: column; gap: 6px; max-width: 980px">
    <h1 style="margin: 0; font-size: 20px; line-height: 28px; font-weight: 600">Dark mode options</h1>
    <p style="margin: 0; font-size: 14px; color: #a1a1aa">The current dark theme and four alternatives on the same slice of the app. Only the dark theme changes; light stays as it is. Each option lists its colours and the contrast of the pairs that matter (4.5:1 is the WCAG AA minimum for text). Pick one and it becomes the dark tokens on every board.</p>
  </header>
  <div style="display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px">
{chr(10).join(cards)}
  </div>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":1440,"height":2000}}}}'>
class Component extends DCLogic {{
  renderVals() {{ return {{}}; }}
}}
</script>
</body>
</html>
'''
(proj / 'DarkOptions.dc.html').write_text(html)
c = proj / 'canvas.json'; d = json.loads(c.read_text())
d['boards']['DarkOptions.dc.html'] = {"x": 1540, "y": 0, "w": 1440, "h": 2000, "title": "Dark mode options", "is_interactive": True, "page": "rules"}
if 'DarkOptions.dc.html' not in d['order']: d['order'].append('DarkOptions.dc.html')
c.write_text(json.dumps(d, ensure_ascii=False, separators=(',', ':')))
for o in OPTIONS:
    ch = checks(resolve(o)); print(o['key'], ' '.join(f'{n.split()[0]}={v:.1f}' for n, v in ch))
