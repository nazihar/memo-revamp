class Component extends DCLogic {
  renderVals() {
    const st = this.state || {};
    const PHONE = /*PHONE*/false;
    const canEdit = this.props.canEdit !== false;
    const variant = this.props.state || 'filled';
    const closed = variant === 'closed', vacantNow = variant !== 'filled';
    const manage = canEdit && !closed;
    const dlg = st.dialog !== undefined ? st.dialog : (this.props.dialog || 'none');
    const target = st.target || 'a1';
    const raw = [
      { id: 'a1', person: 'Nusrat Jahan', from: '15 Aug 2024', until: vacantNow ? '1 Nov 2026' : '', status: vacantNow ? 'past' : 'current' },
      { id: 'a2', person: 'Kamal Uddin', from: '1 Aug 2024', until: '', status: 'cancelled' },
      { id: 'a3', person: 'Rezaul Karim', from: '1 Jan 2021', until: '15 Aug 2024', status: 'past' },
      { id: 'a4', person: 'Shirin Akter', from: '1 Jan 2018', until: '1 Jan 2021', status: 'past' }
    ];
    const chip = 'display: inline-flex; align-items: center; height: 22px; padding: 0 8px; border-radius: 6px; font-size: 12px; font-weight: 500; white-space: nowrap; ';
    const tone = {
      current: { label: 'Current', style: chip + 'border: 1px solid var(--ok-border); background: var(--ok-bg); color: var(--ok-fg)' },
      upcoming: { label: 'Upcoming', style: chip + 'border: 1px solid var(--warn-border); background: var(--warn-bg); color: var(--warn-fg)' },
      past: { label: 'Past', style: chip + 'border: 1px solid var(--border); background: var(--muted); color: var(--text-2)' },
      cancelled: { label: 'Cancelled', style: chip + 'border: 1px dashed var(--border-strong); background: transparent; color: var(--muted-fg)' }
    };
    const open = (d, t) => this.setState({ dialog: d, target: t || target, toast: '', err: 'none', q: '', typed: {} });
    const initials = (n) => n.split(' ').map((x) => x[0]).join('');
    const assignments = raw.map((a, i) => {
      const live = a.status !== 'cancelled';
      return {
        person: a.person, initials: initials(a.person), from: a.from,
        until: a.until || (live ? 'Ongoing' : '—'),
        status: tone[a.status].label, chipStyle: tone[a.status].style,
        cancelled: !live,
        nameStyle: live ? 'font-weight: 500' : 'font-weight: 500; color: var(--muted-fg); text-decoration: line-through',
        canEnd: manage && live && !a.until, canCancel: manage && live,
        hasActions: manage && live,
        end: () => open('end', a.id), cancel: () => open('cancel', a.id),
        more: () => open('row', a.id),
        endLabel: 'End ' + a.person + '’s assignment', cancelLabel: 'Cancel the record for ' + a.person, moreLabel: 'Actions for ' + a.person,
        rowStyle: (PHONE ? 'display: flex; align-items: center; gap: 10px; padding: 10px 8px 10px 12px' : '') + (i > 0 ? (PHONE ? '; ' : '') + 'border-top: 1px solid var(--hover)' : '')
      };
    });
    const who = raw.find((a) => a.id === target) || raw[0];
    const input = 'width: 100%; height: ' + (PHONE ? '44px' : '36px') + '; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: ' + (PHONE ? '16px' : '14px') + '; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)';
    const err = st.err !== undefined ? st.err : (this.props.error || 'none');
    const F = (kind, label, extra) => Object.assign({ kind: kind, label: label, id: 'f-' + label.replace(/\W+/g, '').toLowerCase(), value: '', hint: '', placeholder: '', required: false, error: '' }, extra || {});
    const q = st.q || '';
    const defs = {
      assign: { title: 'Assign a person to Credit Risk Manager', submit: 'Assign person', refused: { field: 'From', text: 'Someone else holds this position in that period.', values: { Person: 'Arif Hasan', From: '2026-11-17' } }, fields: [F('picker', 'Person', { placeholder: 'Search people', hint: 'Type at least two letters of a name or title.', required: true, value: q }), F('date', 'From', { value: '2026-11-17', required: true })] },
      edit: { title: 'Edit position', submit: 'Save', refused: { field: 'Code (optional)', text: 'Another open unit or position has this code.', values: { 'Code (optional)': 'CR-H' } }, required: { Title: '' }, fields: [F('text', 'Title', { value: 'Credit Risk Manager', required: true }), F('text', 'Code (optional)', { value: 'CR-M1' }), F('check', 'Head of the unit', { checked: false })] },
      close: { title: 'Close Credit Risk Manager', lead: 'It stays in the history. Give the first day it no longer exists.', submit: 'Close', refused: { alert: 'Close or end what is still inside it first.' }, fields: [F('date', 'Closes on', { value: '2026-12-01', required: true })] },
      end: { title: 'End ' + who.person + '’s assignment', submit: 'End', refused: { field: 'First day without the position', text: 'Those dates do not fit.', values: { 'First day without the position': '2024-08-01' } }, fields: [F('date', 'First day without the position', { value: '2026-12-01', required: true })] },
      line: { title: 'Who does Credit Risk Manager report to?', lead: 'The current line ends where the new one starts.', submit: 'Save', refused: { field: 'Position', text: 'That would make it its own parent or manager.', values: { Position: 'Credit Risk Analyst' } }, fields: [F('picker', 'Position', { placeholder: 'Search positions', hint: 'Type at least two letters of a name or title.', required: true, value: q }), F('date', 'From', { value: '2026-11-17', required: true })] },
      cancel: { alert: true, title: 'Cancel this record?', lead: 'Use this for an assignment entered by mistake. It stays visible as cancelled.', submit: 'Cancel record', dismiss: 'Keep', destructive: true, refused: { alert: 'A cancelled record cannot change.' }, fields: [] }
    };
    const d0 = defs[dlg];
    const alerts = { changed: 'Someone changed this meanwhile. It was reloaded.', failed: 'That did not work. Try again.', forbidden: 'You cannot change this part of the organisation.' };
    let alertText = '';
    const d = d0 && Object.assign({}, d0, { fields: d0.fields.map((f) => {
      const g = Object.assign({}, f);
      if (st.typed && g.id in st.typed) { if (g.kind === 'check') g.checked = st.typed[g.id]; else g.value = st.typed[g.id]; }
      if (err === 'required' && g.required) {
        if (g.kind === 'picker' || (d0.required && g.label in d0.required)) { g.value = ''; g.error = 'Required.'; }
        else if (g.kind === 'date' && !(d0.fields.some((x) => x.kind === 'picker' || (d0.required && x.label in d0.required)))) { g.value = ''; g.error = 'Required.'; }
      }
      if (err === 'refused' && d0.refused) {
        const r = d0.refused;
        if (r.values && g.label in r.values) g.value = r.values[g.label];
        if (r.field === g.label) g.error = r.text;
      }
      return g;
    }) });
    if (d0) {
      if (err === 'refused' && d0.refused && d0.refused.alert) alertText = d0.refused.alert;
      if (alerts[err]) alertText = alerts[err];
    }
    const closeDlg = () => this.setState({ dialog: 'none', err: 'none', q: '', typed: {} });
    const btn = 'height: ' + (PHONE ? '44px' : '36px') + '; padding: 0 14px; border-radius: 8px; font-size: ' + (PHONE ? '15px' : '14px') + '; font-weight: 500; cursor: pointer; ';
    const dialog = d ? {
      title: d.title, lead: d.lead || '', hasLead: !!d.lead, role: d.alert ? 'alertdialog' : 'dialog',
      fields: d.fields.map((f) => Object.assign({}, f, {
        isText: f.kind === 'text', isDate: f.kind === 'date', isPicker: f.kind === 'picker', isCheck: f.kind === 'check', isSelect: false, options: [], checked: !!f.checked,
        hasHint: !!f.hint, hintId: f.id + '-hint', errId: f.id + '-err', hasError: !!f.error,
        invalid: f.error ? 'true' : 'false', describedBy: f.error ? f.id + '-err' : (f.hint ? f.id + '-hint' : ''),
        onInput: f.kind === 'picker' ? (ev) => this.setState({ q: ev && ev.target ? ev.target.value : '', err: 'none' }) : (ev) => { const t = Object.assign({}, st.typed || {}); t[f.id] = ev && ev.target ? (f.kind === 'check' ? ev.target.checked : ev.target.value) : ''; this.setState({ typed: t }); },
        inputStyle: input + (f.kind === 'picker' ? '; padding-left: 32px' : '') + (f.error ? '; border-color: var(--destructive)' : ''),
        checkStyle: 'width: 16px; height: 16px; margin: 0; accent-color: var(--primary)' + (f.error ? '; outline: 2px solid var(--destructive); outline-offset: 1px' : '')
      })),
      hasAlert: !!alertText, alertText: alertText,
      alertTitle: 'Not saved',
      submit: d.submit, dismiss: d.dismiss || 'Cancel',
      submitStyle: btn + 'border: 0; ' + (d.destructive ? 'background: var(--destructive); color: var(--on-destructive)' : 'background: var(--primary); color: var(--primary-fg)'),
      dismissStyle: btn + 'border: 1px solid var(--border); background: var(--bg); color: var(--fg)'
    } : null;
    const rowSheet = dlg === 'row';
    const reports = closed ? null : { title: 'Head of Credit', since: 'Since 1 Mar 2023' };
    const navOpen = !!st.navOpen, collapsed = !!st.collapsed;
    const closeNav = () => this.setState({ navOpen: false });
    const sheetBtn = 'display: flex; align-items: center; min-height: 52px; padding: 0 4px; border: 0; border-top: 1px solid var(--hover); background: transparent; color: var(--fg); font-size: 15px; text-align: left; cursor: pointer';
    return {
      themeClass: (this.state && this.state.theme) || this.props.theme || 'system',
      manage: manage,
      closed: closed, closedText: 'Closed on 1 Nov 2026',
      sinceText: closed ? 'Closed on 1 Nov 2026' : 'Since 1 Jan 2018',
      holder: { filled: !vacantNow, vacant: vacantNow && !closed, none: closed, name: 'Nusrat Jahan', initials: 'NJ', since: vacantNow ? 'Since 1 Nov 2026' : 'Since 15 Aug 2024' },
      hasReports: !!reports, noReports: !reports, reports: reports || { title: '', since: '' },
      assignments: assignments,
      openAssign: () => open('assign'), openEdit: () => open('edit'), openClose: () => open('close'), openLine: () => open('line'),
      openMore: () => open('more'), moreSheet: dlg === 'more',
      menuOpen: dlg === 'more', menuAria: dlg === 'more' ? 'true' : 'false',
      rowSheet: rowSheet, rowSheetTitle: who.person,
      rowCanEnd: !who.until && who.status !== 'cancelled',
      sheetEnd: () => open('end'), sheetCancel: () => open('cancel'),
      sheetBtnStyle: sheetBtn, sheetDangerStyle: sheetBtn + '; color: var(--danger-fg)',
      dialogOpen: !!dialog, dialog: dialog || { title: '', lead: '', hasLead: false, role: 'dialog', fields: [], submit: '', dismiss: '', submitStyle: '', dismissStyle: '', hasAlert: false, alertText: '', alertTitle: '' },
      closeDlg: closeDlg,
      dlgKey: (ev) => { if (ev && ev.key === 'Escape') closeDlg(); },
      submitDlg: (ev) => {
        if (ev && ev.preventDefault) ev.preventDefault();
        const missing = d && d.fields.some((f) => f.kind === 'picker' && f.required && q.trim().length < 2);
        if (missing) { this.setState({ err: 'required' }); return; }
        if (err === 'refused' || err === 'changed' || err === 'failed' || err === 'forbidden') { this.setState({ err: err }); return; }
        this.setState({ dialog: 'none', err: 'none', q: '', toast: 'Saved.' });
      },
      toast: st.toast || '', hasToast: !!st.toast, dismissToast: () => this.setState({ toast: '' }),
      histOpen: !!st.hist, histAria: st.hist ? 'true' : 'false', toggleHist: () => this.setState({ hist: !st.hist }),
      histChev: 'transition: transform 0.15s; color: var(--muted-fg); transform: rotate(' + (st.hist ? '90deg' : '0') + ')',
      history: [
        { when: '15 Aug 2024, 11:20', change: 'Changed (holder: Rezaul Karim → Nusrat Jahan)', by: 'Rafiq Chowdhury' },
        { when: '1 Aug 2024, 16:05', change: 'Changed (assignment for Kamal Uddin cancelled)', by: 'Rafiq Chowdhury' },
        { when: '1 Jan 2018, 09:00', change: 'Created', by: 'System' }
      ].map((h, i) => Object.assign({}, h, { rowStyle: 'display: flex; flex-direction: column; gap: 2px; padding: 10px 12px' + (i ? '; border-top: 1px solid var(--hover)' : '') })),
      shortcut: (typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || '')) ? '⌘K' : 'Ctrl K',
      sidebarClass: 'sidebar' + (navOpen ? ' open' : '') + (collapsed && !navOpen ? ' collapsed' : ''),
      scrimClass: 'scrim' + (navOpen ? ' open' : ''),
      navAria: (navOpen || !collapsed) ? 'true' : 'false',
      toggleNav: () => {
        const narrow = typeof window !== 'undefined' && window.innerWidth <= 860;
        if (narrow) this.setState({ navOpen: !navOpen });
        else this.setState({ collapsed: !collapsed });
      },
      closeNav: closeNav,
      navKey: (ev) => { if (ev && ev.key === 'Escape') closeNav(); }
    };
  }
}
