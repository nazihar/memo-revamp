    // Dialogs: one engine for every short form on this page (fields, required checks, server refusals).
    const PHONE = /*PHONE*/false;
    const dlg = st.dialog !== undefined ? st.dialog : (this.props.dialog || 'none');
    const err = st.err !== undefined ? st.err : (this.props.error || 'none');
    const input = 'width: 100%; height: ' + (PHONE ? '44px' : '36px') + '; padding: 0 10px; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--fg); font-family: inherit; font-size: ' + (PHONE ? '16px' : '14px') + '; outline: none; box-shadow: 0 1px 2px rgba(0,0,0,0.05)';
    const F = (kind, label, extra) => Object.assign({ kind: kind, label: label, id: 'of-' + label.replace(/\W+/g, '').toLowerCase(), value: '', hint: '', placeholder: '', required: false, error: '', options: [] }, extra || {});
    const sub = {}; sub[u.id] = true;
    let grew = true;
    while (grew) { grew = false; units.forEach((x) => { if (x.parent && sub[x.parent] && !sub[x.id]) { sub[x.id] = true; grew = true; } }); }
    const parents = units.filter((x) => !sub[x.id] && x.id !== u.parent).sort((a, b) => a.name.localeCompare(b.name)).map((x) => ({ value: x.id, label: x.name + ' (' + x.code + ')' }));
    const unitFields = (name, code) => [F('text', 'Name', { value: name, required: true }), F('text', 'Code', { value: code, required: true })];
    const dupCode = { field: 'Code', text: 'Another open unit or position has this code.', values: { Code: 'CR-01' } };
    const defs = {
      newTop: { title: 'New top unit', submit: 'Save', refused: { field: 'Code', text: 'Another open unit or position has this code.', values: { Name: 'Treasury', Code: 'FIN-01' } }, fields: unitFields('', '') },
      addUnit: { title: 'New unit under ' + u.name, submit: 'Save', refused: { field: 'Code', text: 'Another open unit or position has this code.', values: { Name: 'Credit Monitoring', Code: 'CR-CA' } }, fields: unitFields('', '') },
      rename: { title: 'Rename', submit: 'Save', refused: dupCode, fields: unitFields(u.name, u.code) },
      move: { title: 'Move ' + u.name, lead: 'Its sub-units and positions move with it.', submit: 'Move', refused: { field: 'New parent unit', text: 'You cannot change this part of the organisation.', values: { 'New parent unit': 'fin' } }, fields: [F('select', 'New parent unit', { required: true, options: parents })] },
      close: { title: 'Close ' + u.name, lead: 'It stays in the history. Give the first day it no longer exists.', submit: 'Close', refused: { alert: 'Close or end what is still inside it first.' }, fields: [F('date', 'Closes on', { value: '2026-12-01', required: true })] },
      addPosition: { title: 'New position in ' + u.name, submit: 'Save', refused: { field: 'Head of the unit', text: 'The unit already has a head position.', values: { Title: 'Head of Credit Risk', 'Head of the unit': true } }, fields: [F('text', 'Title', { required: true }), F('text', 'Code (optional)'), F('check', 'Head of the unit', { value: false })] }
    };
    const d0 = defs[dlg];
    const alerts = { changed: 'Someone changed this meanwhile. It was reloaded.', failed: 'That did not work. Try again.' };
    const typed = st.typed || {};
    let alertText = '';
    const fields = d0 ? d0.fields.map((f) => {
      const g = Object.assign({}, f);
      if (g.id in typed) g.value = typed[g.id];
      if (err === 'required' && g.required) {
        if (st.err === undefined) g.value = '';
        if (g.value === '' || g.value == null) g.error = 'Required.';
      }
      if (err === 'refused' && d0.refused) {
        const r = d0.refused;
        if (r.values && g.label in r.values) g.value = r.values[g.label];
        if (r.field === g.label) g.error = r.text;
      }
      return g;
    }) : [];
    if (d0 && err === 'refused' && d0.refused && d0.refused.alert) alertText = d0.refused.alert;
    if (d0 && alerts[err]) alertText = alerts[err];
    const btn = 'height: ' + (PHONE ? '44px' : '36px') + '; padding: 0 14px; border-radius: 8px; font-size: ' + (PHONE ? '15px' : '14px') + '; font-weight: 500; cursor: pointer; ';
    const openDlg = (k) => this.setState({ dialog: k, err: 'none', typed: {}, menu: false, sheet: false, toast: '' });
    const closeDlg = () => this.setState({ dialog: 'none', err: 'none', typed: {} });
    const dialog = d0 ? {
      title: d0.title, lead: d0.lead || '', hasLead: !!d0.lead, role: 'dialog',
      fields: fields.map((f) => Object.assign({}, f, {
        isText: f.kind === 'text', isDate: f.kind === 'date', isPicker: f.kind === 'picker', isCheck: f.kind === 'check', isSelect: f.kind === 'select',
        hasHint: !!f.hint, hintId: f.id + '-hint', errId: f.id + '-err', hasError: !!f.error,
        invalid: f.error ? 'true' : 'false', describedBy: f.error ? f.id + '-err' : (f.hint ? f.id + '-hint' : ''),
        checked: f.kind === 'check' ? !!f.value : false,
        onInput: (ev) => { const t = Object.assign({}, typed); t[f.id] = ev && ev.target ? (f.kind === 'check' ? ev.target.checked : ev.target.value) : ''; this.setState({ typed: t, err: 'none' }); },
        options: f.options.map((o) => ({ value: o.value, label: o.label, selected: o.value === f.value })),
        inputStyle: input + (f.kind === 'picker' ? '; padding-left: 32px' : '') + (f.kind === 'select' ? '; appearance: none; padding-right: 32px' : '') + (f.error ? '; border-color: var(--destructive)' : ''),
        checkStyle: 'width: 16px; height: 16px; margin: 0; accent-color: var(--primary)' + (f.error ? '; outline: 2px solid var(--destructive); outline-offset: 1px' : '')
      })),
      hasAlert: !!alertText, alertText: alertText, alertTitle: 'Not saved',
      submit: d0.submit, dismiss: 'Cancel',
      submitStyle: btn + 'border: 0; background: var(--primary); color: var(--primary-fg)',
      dismissStyle: btn + 'border: 1px solid var(--border); background: var(--bg); color: var(--fg)'
    } : { title: '', lead: '', hasLead: false, role: 'dialog', fields: [], submit: '', dismiss: '', submitStyle: '', dismissStyle: '', hasAlert: false, alertText: '', alertTitle: '' };
    const dialogVals = {
      dialogOpen: !!d0, dialog: dialog, closeDlg: closeDlg,
      dlgKey: (ev) => { if (ev && ev.key === 'Escape') closeDlg(); },
      submitDlg: (ev) => {
        if (ev && ev.preventDefault) ev.preventDefault();
        const missing = fields.some((f) => f.required && (f.value === '' || f.value == null));
        if (missing) { this.setState({ err: 'required' }); return; }
        if (err !== 'none' && err !== 'required') { this.setState({ err: err }); return; }
        this.setState({ dialog: 'none', err: 'none', typed: {}, toast: 'Saved.' });
      },
      toast: st.toast || '', hasToast: !!st.toast, dismissToast: () => this.setState({ toast: '' }),
      openNewTop: () => openDlg('newTop'), openAddPosition: () => openDlg('addPosition')
    };
    const menuKeys = { 'Add sub-unit': 'addUnit', Rename: 'rename', Move: 'move', Close: 'close' };
