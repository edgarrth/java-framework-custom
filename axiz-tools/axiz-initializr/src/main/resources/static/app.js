// Interfaz web de axiz initializr: sin estructura, sin código en línea (CSP estricta), CSRF mediante la cookie XSRF-TOKEN.
(() => {
  'use strict';
  const form = document.getElementById('project');
  const result = document.getElementById('result');
  let metadata;

  const csrf = () => {
    const c = document.cookie.split('; ').find((x) => x.startsWith('XSRF-TOKEN='));
    return c ? decodeURIComponent(c.split('=')[1]) : '';
  };

  const option = (value, text) => {
    const o = document.createElement('option');
    o.value = value;
    o.textContent = text || value;
    return o;
  };

  const fill = (select, values) => {
    const current = select.value;
    select.replaceChildren(...values.map((v) => option(v)));
    if (values.includes(current)) select.value = current;
    select.disabled = values.length <= 1;
  };

  const archetype = () => metadata.archetypes.find((a) => a.name === form.archetype.value);

  const refresh = () => {
    const a = archetype();
    document.getElementById('role').textContent = a.role;
    fill(form.structure, a.structures);
    const fw = metadata.structures.find((f) => f.id === form.structure.value);
    fill(form.extensions, ['', ...(form.structure.value === 'hexagonal' ? a.extensions : [])]);
    fill(form.stack, a.stacks.filter((s) => !fw || fw.stacks.includes(s)));
    fill(form.persistence, a.persistence.length ? a.persistence : ['none']);
    document.getElementById('openapi-required').hidden = !a.rest;
    document.getElementById('included').textContent = a.starters.join(', ') || 'nada';
    const service = a.kind === 'service';
    // Aurora desde el stack reactivo se alcanza mediante R2DBC: las capacidades declaran ese almacén por separado
    const store = form.persistence.value === 'aurora' && form.stack.value === 'reactive'
      ? 'r2dbc' : form.persistence.value;
    document.querySelectorAll('#capabilities label').forEach((label) => {
      const cap = metadata.capabilities.find((c) => c.name === label.dataset.name);
      const supported = service && cap.stacks.includes(form.stack.value) && (!cap.persistence.length || cap.persistence.includes(store))
        && !a.starters.includes(cap.name);
      label.classList.toggle('disabled', !supported);
      label.querySelector('input').disabled = !supported;
      if (!supported) label.querySelector('input').checked = false;
    });
  };

  const readFile = (input) => new Promise((resolve, reject) => {
    const file = input.files[0];
    if (!file) return resolve(null);
    if (file.size > 2 * 1024 * 1024) return reject(new Error(`${file.name} supera 2 MB`));
    const r = new FileReader();
    r.onload = () => resolve(r.result);
    r.onerror = () => reject(r.error);
    r.readAsText(file);
  });

  const request = async () => ({
    name: form.name.value.trim(),
    groupId: form.groupId.value.trim(),
    basePackage: form.basePackage.value.trim(),
    description: form.description.value.trim(),
    archetype: form.archetype.value,
    structure: form.structure.value,
    extensions: form.extensions.value ? [form.extensions.value] : [],
    stack: form.stack.value,
    persistence: form.persistence.value,
    capabilities: [...document.querySelectorAll('#capabilities input:checked')].map((i) => i.value),
    openApi: await readFile(form.openapiFile),
    asyncApi: await readFile(form.asyncapiFile),
  });

  const post = (url, body, headers = {}) => fetch(url, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf(), ...headers },
    body: JSON.stringify(body),
  });

  const show = (title, problems = [], findings = []) => {
    result.replaceChildren();
    const h = document.createElement('h2');
    h.textContent = title;
    const ul = document.createElement('ul');
    problems.forEach((p) => { const li = document.createElement('li'); li.className = 'error'; li.textContent = p; ul.append(li); });
    findings.forEach((f) => {
      const li = document.createElement('li');
      li.className = f.severity === 'error' ? 'error' : 'warn';
      li.textContent = `${f.rule} ${f.location}: ${f.message}`;
      ul.append(li);
    });
    result.append(h, ul);
  };

  const busy = async (fn) => {
    const buttons = form.querySelectorAll('button');
    buttons.forEach((b) => { b.disabled = true; });
    try { await fn(); } catch (e) { show(e.message); } finally { buttons.forEach((b) => { b.disabled = false; }); }
  };

  const problem = async (response) => {
    if (response.status === 401) { window.location.assign('/oauth2/authorization/sso'); return; }
    const body = await response.json().catch(() => ({}));
    show(body.title || `Error ${response.status}`, body.problems || [body.detail].filter(Boolean), body.findings || []);
  };

  document.getElementById('validate').addEventListener('click', () => busy(async () => {
    const response = await post('/v1/projects/validate', await request());
    if (!response.ok) return problem(response);
    const r = await response.json();
    show(r.valid ? 'Listo para generar' : 'Hay problemas', r.problems, r.findings);
  }));

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    busy(async () => {
      const body = await request();
      const response = await post('/v1/projects', body);
      if (!response.ok) return problem(response);
      const url = URL.createObjectURL(await response.blob());
      const a = document.createElement('a');
      a.href = url;
      a.download = `${body.name}.zip`;
      a.click();
      URL.revokeObjectURL(url);
      show(`Generado ${body.name}.zip`);
    });
  });

  const publicationKey = 'axiz-initializr-publication';
  let resumeAfterAuthorization = new URLSearchParams(window.location.search).get('github') === 'authorized';
  form.addEventListener('input', () => { resumeAfterAuthorization = false; });
  document.getElementById('github').addEventListener('click', () => busy(async () => {
    let pending;
    try { pending = JSON.parse(sessionStorage.getItem(publicationKey) || 'null'); }
    catch { sessionStorage.removeItem(publicationKey); }
    const body = resumeAfterAuthorization && pending ? pending.body : await request();
    if (body.description && body.description.length > 160) {
      show('Para publicar en GitHub, la descripción debe tener como máximo 160 caracteres.', true);
      return;
    }
    const operationId = pending && JSON.stringify(pending.body) === JSON.stringify(body)
      ? pending.operationId : crypto.randomUUID();
    try { sessionStorage.setItem(publicationKey, JSON.stringify({ operationId, body })); }
    catch {
      show('No hay espacio para conservar esta publicación durante OAuth. Reduce los contratos o libera almacenamiento de esta pestaña y reintenta.', true);
      return;
    }
    const response = await post('/v1/projects/github', body, { 'Idempotency-Key': operationId });
    // GitHub aún no autorizado: 403 con la URL que inicia la autorización; el navegador vuelve
    // a /?github=authorized y el usuario crea el repositorio de nuevo
    if (response.status === 403) {
      const body = await response.clone().json().catch(() => ({}));
      if (body.authorize) { window.location.assign(body.authorize); return; }
    }
    if (!response.ok) return problem(response);
    const r = await response.json();
    sessionStorage.removeItem(publicationKey);
    resumeAfterAuthorization = false;
    result.replaceChildren();
    const p = document.createElement('p');
    p.className = 'ok';
    const link = document.createElement('a');
    link.href = r.url;
    link.textContent = r.repository;
    link.rel = 'noopener';
    p.append('Repositorio creado: ', link);
    result.append(p);
  }));

  form.name.addEventListener('input', () => {
    const [domain, ...rest] = form.name.value.split('-');
    if (!domain) return;
    form.groupId.placeholder = `pe.axiz.${domain}`;
    form.basePackage.placeholder = `pe.axiz.${domain}.${rest.join('') || domain}`;
  });
  form.groupId.addEventListener('blur', () => { if (!form.groupId.value) form.groupId.value = form.groupId.placeholder; });
  form.basePackage.addEventListener('blur', () => { if (!form.basePackage.value) form.basePackage.value = form.basePackage.placeholder; });
  ['archetype', 'structure', 'stack', 'persistence'].forEach((n) => form[n].addEventListener('change', refresh));

  fetch('/v1/metadata', { credentials: 'same-origin' })
    .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`metadata ${r.status}`))))
    .then((m) => {
      metadata = m;
      form.archetype.replaceChildren(...m.archetypes.map((a) => option(a.name, `${a.name}: ${a.role}`)));
      const caps = document.getElementById('capabilities');
      caps.replaceChildren(...m.capabilities.map((c) => {
        const label = document.createElement('label');
        label.dataset.name = c.name;
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.value = c.name;
        const text = document.createElement('span');
        const strong = document.createElement('strong');
        strong.textContent = c.name;
        text.append(strong, c.description);
        label.append(input, text);
        return label;
      }));
      form.archetype.value = 'business';
      refresh();
      if (new URLSearchParams(window.location.search).get('github') === 'authorized') {
        window.history.replaceState(null, '', '/');
        show('GitHub autorizado: completa el formulario y vuelve a pulsar «Crear repositorio en GitHub».');
      }
    })
    .catch((e) => show(`No se pudo cargar el catálogo: ${e.message}`));
})();
