// Waypoint visualizer: scenarios, live architecture diagram, request log. No build step, no dependencies.

const STAGES = [
  ['listener', 'Listener'], ['size', 'Size'], ['normalize', 'Normalize'], ['authenticate', 'Auth'],
  ['allowlist', 'Allowlist'], ['route', 'Route'], ['method', 'Method'], ['scope', 'Scope'],
  ['quota', 'Quota'], ['proxy', 'Proxy'],
];
const STAGE_LABEL = Object.fromEntries([...STAGES, ['load_balancer', 'Load balancer'], ['upstream', 'Upstream']]);
// Two diagram lanes; gateway instances claim them as they appear (App Platform instance IDs change per deploy).
const LANES = ['lane-0', 'lane-1'];
const laneOwner = new Map();
const SVG_NS = 'http://www.w3.org/2000/svg';
const SPEED = 0.95;          // px per ms on the way in
const MAX_DOTS = 90;
const LOG_LIMIT = 300;

const state = {
  config: null,
  scenarios: new Map(),
  results: new Map(),
  run: null,
  runningScenario: new Map(), // runId -> scenarioId
};

// ---------- DOM helpers ----------

const $ = (id) => document.getElementById(id);

function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

function s(tag, attrs = {}, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) if (v != null) el.setAttribute(k, v);
  for (const child of children) el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  return el;
}

const statusClass = (status) => `s${status === 0 ? 0 : Math.floor(status / 100)}`;
const outcomeClass = (outcome) => ({ ALLOWED: 'ok', REJECTED: 'deny', ERROR: 'err' }[outcome] || 'err');
const time = (iso) => new Date(iso).toLocaleTimeString([], { hour12: false });

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const body = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
  if (!response.ok) throw new Error(body?.detail || `${response.status} ${response.statusText}`);
  return body;
}

// ---------- Diagram ----------

const L = {
  client: { x: 12, y: 175, w: 112, h: 60 },
  lb: { x: 158, y: 175, w: 122, h: 60 },
  laneX: 310, laneW: 710, laneH: 120,
  laneY: { 'lane-0': 20, 'lane-1': 270 },
  chipX0: 322, chipW: 62, chipGap: 7, chipH: 44, chipTop: 52,
  upstream: { x: 1058, y: 165, w: 132, h: 80 },
  audit: { x: 395, y: 180, w: 150, h: 50 },
  postgres: { x: 610, y: 180, w: 150, h: 50 },
  valkey: { x: 832, y: 180, w: 146, h: 50 },
};
const center = (b) => ({ x: b.x + b.w / 2, y: b.y + b.h / 2 });
const chipY = (lane) => L.laneY[lane] + L.chipTop + L.chipH / 2;
const chipCenter = (lane, i) => ({ x: L.chipX0 + i * (L.chipW + L.chipGap) + L.chipW / 2, y: chipY(lane) });

const diagram = {
  svg: null, dots: null, chips: {}, nodes: {}, badges: {}, edges: {},
};

function buildDiagram() {
  const svg = $('diagram');
  svg.replaceChildren();
  const edges = s('g'); const nodes = s('g'); const dots = s('g');
  svg.append(edges, nodes, dots);
  diagram.svg = svg; diagram.dots = dots;

  const line = (id, points, cls = 'edge') => {
    const el = s('polyline', { points: points.map((p) => `${p.x},${p.y}`).join(' '), class: cls });
    edges.append(el);
    if (id) diagram.edges[id] = el;
    return el;
  };
  const label = (x, y, text, anchor = 'middle') => edges.append(s('text', { x, y, class: 'edge-label', 'text-anchor': anchor }, text));

  const c = center(L.client); const hp = center(L.lb); const up = center(L.upstream);
  line('client-lb', [{ x: L.client.x + L.client.w, y: c.y }, { x: L.lb.x, y: hp.y }]);
  for (const gw of LANES) {
    const y = chipY(gw);
    line(`lb-${gw}`, [{ x: L.lb.x + L.lb.w, y: hp.y }, { x: L.laneX, y }]);
    const top = gw === LANES[0];
    line(`direct-${gw}`, [{ x: c.x, y: top ? L.client.y : L.client.y + L.client.h }, { x: c.x, y }, { x: L.laneX, y }], 'edge direct');
    label(c.x + 6, y - 6, 'direct', 'start');
    line(`${gw}-up`, [{ x: L.laneX + L.laneW, y }, { x: L.upstream.x, y: up.y + (top ? -14 : 14) }]);
    const laneBottom = L.laneY[gw] + L.laneH;
    for (const [id, box] of [['audit', L.audit], ['postgres', L.postgres], ['valkey', L.valkey]]) {
      const bx = box.x + box.w / 2;
      line(`${gw}-${id}`, top
        ? [{ x: bx, y: laneBottom }, { x: bx, y: box.y }]
        : [{ x: bx, y: box.y + box.h }, { x: bx, y: L.laneY[gw] }], 'edge dashed');
    }
  }
  label(L.lb.x + L.lb.w + 4, hp.y - 46, 'load balanced', 'start');
  label(L.upstream.x + L.upstream.w / 2, L.upstream.y + L.upstream.h + 16, 'X-Gateway-Identity', 'middle');

  const node = (id, box, name, sub) => {
    const g = s('g', { class: 'node state-unknown', id: `node-${id}` });
    g.append(
      s('rect', { x: box.x, y: box.y, width: box.w, height: box.h, rx: 10, class: 'box' }),
      s('circle', { cx: box.x + box.w - 11, cy: box.y + 11, r: 5, class: 'led' }),
      s('text', { x: box.x + 12, y: box.y + 24, class: 'name', id: `name-${id}` }, name),
      s('text', { x: box.x + 12, y: box.y + 42, class: 'sub', id: `sub-${id}` }, sub),
    );
    nodes.append(g);
    diagram.nodes[id] = g;
    return g;
  };
  node('client', L.client, 'Client', 'this visualizer').classList.replace('state-unknown', 'state-up');
  node('lb', L.lb, 'Load balancer', 'waiting for status…');
  node('upstream', L.upstream, 'Upstream', 'WireMock stub');
  node('audit', L.audit, 'Audit log', 'every request').classList.replace('state-unknown', 'state-up');
  node('postgres', L.postgres, 'PostgreSQL', 'routes · tenants');
  node('valkey', L.valkey, 'Valkey', 'shared quota');

  for (const gw of LANES) {
    const y = L.laneY[gw];
    const g = s('g', { class: 'lane node state-unknown', id: `node-${gw}` });
    g.append(
      s('rect', { x: L.laneX, y, width: L.laneW, height: L.laneH, rx: 12, class: 'lane-box' }),
      s('circle', { cx: L.chipX0 + 5, cy: y + 22, r: 5, class: 'led' }),
      s('text', { x: L.chipX0 + 16, y: y + 27, class: 'name', id: `name-${gw}` }, 'gateway'),
      s('text', { x: L.laneX + L.laneW - 14, y: y + 27, class: 'sub', 'text-anchor': 'end', id: `sub-${gw}` }, 'waiting for status…'),
    );
    diagram.chips[gw] = {}; diagram.badges[gw] = {};
    STAGES.forEach(([id, text], i) => {
      const p = chipCenter(gw, i);
      const chip = s('g', { class: 'chip' });
      const badge = s('g', { visibility: 'hidden' });
      const count = s('text', { x: p.x + L.chipW / 2 - 6, y: p.y - L.chipH / 2 + 3, class: 'count', 'text-anchor': 'middle' }, '0');
      badge.append(s('circle', { cx: p.x + L.chipW / 2 - 6, cy: p.y - L.chipH / 2 - 1, r: 9, class: 'badge' }), count);
      chip.append(
        s('rect', { x: p.x - L.chipW / 2, y: p.y - L.chipH / 2, width: L.chipW, height: L.chipH, rx: 8 }),
        s('text', { x: p.x, y: p.y + 4, 'text-anchor': 'middle' }, text),
        badge,
      );
      g.append(chip);
      diagram.chips[gw][id] = chip;
      diagram.badges[gw][id] = { el: badge, text: count, n: 0 };
    });
    nodes.append(g);
    diagram.nodes[gw] = g;
  }
}

function setNodeState(id, nodeState, sub) {
  const g = diagram.nodes[id];
  if (!g) return;
  g.classList.remove('state-up', 'state-degraded', 'state-down', 'state-unknown');
  g.classList.add(`state-${nodeState}`);
  const subEl = $(`sub-${id}`);
  if (subEl && sub != null) subEl.textContent = sub;
}

function flash(el, cls, ms = 380) {
  if (!el) return;
  el.classList.add(cls);
  clearTimeout(el[`_t_${cls}`]);
  el[`_t_${cls}`] = setTimeout(() => el.classList.remove(cls), ms);
}

function resetBadges() {
  for (const gw of LANES) {
    for (const badge of Object.values(diagram.badges[gw])) {
      badge.n = 0; badge.el.setAttribute('visibility', 'hidden');
    }
  }
}

function bumpBadge(gw, stage) {
  const badge = diagram.badges[gw]?.[stage];
  if (!badge) return;
  badge.n += 1;
  badge.text.textContent = badge.n > 99 ? '99+' : String(badge.n);
  badge.el.setAttribute('visibility', 'visible');
}

// ---------- Animation ----------

const active = new Set();
let frame = null;

/** The lane an instance owns, claiming a free one for a newly seen instance. */
function laneOf(instance) {
  if (!instance) return null;
  for (const [lane, owner] of laneOwner) if (owner === instance) return lane;
  const free = LANES.find((lane) => !laneOwner.has(lane));
  if (!free) return null;
  laneOwner.set(free, instance);
  $(`name-${free}`).textContent = instance;
  return free;
}

function releaseLanes(liveInstances) {
  for (const [lane, owner] of [...laneOwner]) {
    if (!liveInstances.has(owner)) {
      laneOwner.delete(lane);
      $(`name-${lane}`).textContent = 'gateway';
      setNodeState(lane, 'unknown', 'waiting for an instance…');
    }
  }
}

function laneFor(result) {
  return laneOf(result.servedBy) || LANES[result.seq % LANES.length];
}

/** Waypoints the request visits, each with an optional action when the dot gets there. */
function route(result) {
  const lane = laneFor(result);
  const viaLb = result.target === 'lb';
  const c = center(L.client); const hp = center(L.lb); const up = center(L.upstream);
  const y = chipY(lane);
  const pts = [{ x: L.client.x + L.client.w, y: c.y }];
  if (viaLb) {
    pts.push({ x: L.lb.x, y: hp.y, on: () => flash(diagram.nodes.lb, 'flash') });
    if (result.stage === 'load_balancer') { pts.at(-1).stop = true; return { pts, lane }; }
    pts.push({ x: L.lb.x + L.lb.w, y: hp.y });
  } else {
    pts.push({ x: c.x, y: lane === LANES[0] ? L.client.y : L.client.y + L.client.h });
    pts.push({ x: c.x, y });
  }
  pts.push({ x: L.laneX, y });
  const stopIndex = STAGES.findIndex(([id]) => id === result.stage);
  const last = result.stage === 'upstream' ? STAGES.length - 1 : stopIndex;
  for (let i = 0; i <= Math.max(last, 0); i++) {
    const [id] = STAGES[i];
    const p = chipCenter(lane, i);
    const chip = diagram.chips[lane][id];
    const isStop = i === stopIndex && result.stage !== 'upstream';
    pts.push({
      ...p,
      stop: isStop,
      on: () => {
        if (isStop) {
          flash(chip, result.outcome === 'ERROR' ? 'fail' : 'stop', 1100);
          bumpBadge(lane, id);
        } else {
          flash(chip, 'pass', 300);
        }
        if (id === 'quota') flash(diagram.edges[`${lane}-valkey`], 'hot', 300);
      },
    });
  }
  if (result.stage === 'upstream') {
    pts.push({ x: L.laneX + L.laneW, y });
    pts.push({ x: L.upstream.x, y: up.y + (lane === LANES[0] ? -14 : 14) });
    pts.push({ ...up, stop: true, on: () => flash(diagram.nodes.upstream, 'flash-ok', 500) });
  }
  return { pts, lane };
}

function animate(result) {
  if (!diagram.svg) return;
  if (active.size >= MAX_DOTS) { applyWithoutAnimation(result); return; }
  const { pts, lane } = route(result);
  const cls = outcomeClass(result.outcome);
  const keyframes = [];
  let t = 0;
  pts.forEach((p, i) => {
    if (i > 0) t += Math.hypot(p.x - pts[i - 1].x, p.y - pts[i - 1].y) / SPEED;
    keyframes.push({ ...p, t });
    if (p.stop) {
      t += 650;
      keyframes.push({ x: p.x, y: p.y, t, dwellEnd: true });
    }
  });
  const back = [...pts].reverse().slice(1);
  let prev = keyframes.at(-1);
  for (const p of back) {
    t += Math.hypot(p.x - prev.x, p.y - prev.y) / (SPEED * 2.2);
    keyframes.push({ x: p.x, y: p.y, t, back: true });
    prev = p;
  }
  const dot = s('circle', { r: 6, class: 'dot-req', cx: pts[0].x, cy: pts[0].y });
  diagram.dots.append(dot);
  active.add({ dot, keyframes, start: performance.now(), next: 0, cls, result, lane, bubble: null });
  if (!frame) frame = requestAnimationFrame(tick);
}

function tick(now) {
  for (const item of active) {
    const elapsed = now - item.start;
    while (item.next < item.keyframes.length && item.keyframes[item.next].t <= elapsed) {
      const k = item.keyframes[item.next];
      k.on?.();
      if (k.stop) {
        item.dot.classList.add(item.cls);
        flash(diagram.nodes.audit, 'flash', 400);
        item.bubble = statusBubble(k, item.result, item.cls);
      }
      if (k.dwellEnd) { item.bubble?.remove(); item.bubble = null; item.dot.style.opacity = '0.45'; }
      item.next += 1;
    }
    if (item.next >= item.keyframes.length) {
      item.dot.remove(); item.bubble?.remove(); active.delete(item);
      continue;
    }
    const a = item.keyframes[Math.max(item.next - 1, 0)];
    const b = item.keyframes[item.next];
    const f = b.t === a.t ? 1 : (elapsed - a.t) / (b.t - a.t);
    item.dot.setAttribute('cx', a.x + (b.x - a.x) * f);
    item.dot.setAttribute('cy', a.y + (b.y - a.y) * f);
  }
  frame = active.size ? requestAnimationFrame(tick) : null;
}

function statusBubble(p, result, cls) {
  const text = result.status === 0 ? 'no response' : String(result.status);
  const w = 10 + text.length * 7.5;
  const g = s('g', { class: `bubble ${cls}` });
  g.append(s('rect', { x: p.x - w / 2, y: p.y - 40, width: w, height: 18 }), s('text', { x: p.x, y: p.y - 27, 'text-anchor': 'middle' }, text));
  diagram.dots.append(g);
  return g;
}

function applyWithoutAnimation(result) {
  if (result.stage !== 'upstream' && result.stage !== 'load_balancer') bumpBadge(laneFor(result), result.stage);
}

// ---------- Health ----------

function applyHealth(statuses) {
  if (!statuses) return;
  releaseLanes(new Set(statuses.filter((st) => st.kind === 'gateway').map((st) => st.id)));
  for (const st of statuses) {
    const info = st.info || {};
    let sub = st.detail;
    let id = st.id;
    if (st.kind === 'gateway') {
      id = laneOf(st.id);
      if (!id) continue;
      const lb = info.lb ? ` · LB ${info.lb}` : '';
      sub = st.state === 'down' && !('revision' in info)
        ? `${st.detail}${lb}`
        : `${st.detail} · rev ${info.revision} · limiter ${info.rateLimiter}${lb}`;
    } else if (st.kind === 'load_balancer') {
      const backends = Object.entries(info.backends || {}).map(([k, v]) => `${k.replace('gateway-', 'g')} ${v}`);
      sub = backends.length ? backends.join(' · ') : st.detail;
    } else if (st.id === 'postgres') {
      sub = st.state === 'up' ? 'config store up' : st.state === 'down' ? 'config store DOWN' : st.detail;
    } else if (st.id === 'valkey') {
      sub = st.state === 'up' ? 'shared quota' : st.state === 'down' ? 'fallback: local quota' : st.detail;
    } else if (st.id === 'upstream') {
      sub = st.state === 'up' ? 'WireMock stub' : 'unreachable';
    }
    setNodeState(id, st.state, sub);
  }
}

// ---------- Run panel ----------

function startRun({ runId, scenarioId, title, expect, total }) {
  const scenario = scenarioId ? state.scenarios.get(scenarioId) : null;
  state.run = { runId, scenarioId, title, expect, total, completed: 0, statuses: {}, servedBy: {}, finished: false, durationMs: 0 };
  resetBadges();
  $('run-strip').replaceChildren();
  $('run-watch').textContent = scenario ? scenario.watch : '';
  renderRun();
}

function recordInRun(result) {
  let run = state.run;
  if (!result.runId) {
    startRun({ runId: null, title: `${result.method} ${result.path} as ${result.tenant}`, expect: null, total: 1 });
    run = state.run;
  } else if (!run || run.runId !== result.runId) {
    startRun({ runId: result.runId, scenarioId: result.scenarioId, title: state.scenarios.get(result.scenarioId)?.title || 'Run', total: 0 });
    run = state.run;
  }
  run.completed += 1;
  const key = result.status === 0 ? 'no response' : String(result.status);
  run.statuses[key] = (run.statuses[key] || 0) + 1;
  if (result.servedBy) run.servedBy[result.servedBy] = (run.servedBy[result.servedBy] || 0) + 1;
  if (!result.runId) run.finished = true;
  $('run-strip').append(h('i', { class: statusClass(result.status), title: `#${result.seq} ${result.status} ${result.code || ''}` }));
  renderRun();
}

function finishRun(update) {
  if (state.run?.runId === update.runId) {
    state.run.finished = true;
    state.run.durationMs = update.durationMs;
    state.run.total = update.total;
    renderRun();
  }
  const scenarioId = state.runningScenario.get(update.runId);
  if (scenarioId) {
    state.runningScenario.delete(update.runId);
    document.querySelector(`[data-scenario="${CSS.escape(scenarioId)}"]`)?.classList.remove('running');
  }
}

function pills(map, classify) {
  const entries = Object.entries(map);
  if (!entries.length) return ['–'];
  return entries.sort().map(([k, v]) => h('span', { class: `pill ${classify(k)}` }, `${v} × ${k}`));
}

function renderRun() {
  const run = state.run;
  if (!run) return;
  $('run-title').textContent = run.title;
  const total = Math.max(run.total, run.completed);
  $('run-meta').textContent = run.finished
    ? `done · ${run.completed} request${run.completed === 1 ? '' : 's'}${run.durationMs ? ` in ${(run.durationMs / 1000).toFixed(1)}s` : ''}`
    : `running · ${run.completed}/${total || '?'}`;
  $('run-bar').style.width = `${total ? Math.round((run.completed / total) * 100) : 0}%`;
  $('run-expect').textContent = run.expect || '–';
  $('run-actual').replaceChildren(...pills(run.statuses, (k) => (k === 'no response' ? 's0' : `s${k[0]}`)));
  $('run-served').replaceChildren(...pills(run.servedBy, () => ''));
}

// ---------- Log & drawer ----------

function addToLog(result, fresh = true) {
  state.results.set(result.seq, result);
  const body = $('log-body');
  const row = h('tr', { class: fresh ? 'fresh' : null, onclick: () => openDrawer(result.seq) },
    h('td', { class: 'mono' }, time(result.ts)),
    h('td', {}, result.tenant),
    h('td', { class: 'mono path', title: `${result.method} ${result.path}` }, `${result.method} ${result.path}`),
    h('td', {}, result.target === 'lb' ? `LB → ${result.servedBy || '?'}` : result.target),
    h('td', {}, h('span', { class: `pill ${statusClass(result.status)}` }, result.status || '—'), ' ', h('span', { class: 'code' }, result.code || '')),
    h('td', {}, STAGE_LABEL[result.stage] || result.stage),
    h('td', { class: 'num' }, result.latencyMs),
  );
  body.prepend(row);
  while (body.children.length > LOG_LIMIT) {
    body.lastElementChild.remove();
  }
}

function kv(pairs) {
  const dl = h('dl', { class: 'kv' });
  for (const [k, v, hl] of pairs) {
    if (v == null || v === '') continue;
    dl.append(h('dt', {}, k), h('dd', { class: hl ? 'hl' : null }, typeof v === 'object' ? JSON.stringify(v) : String(v)));
  }
  return dl;
}

function verdict(result) {
  const where = STAGE_LABEL[result.stage] || result.stage;
  if (result.outcome === 'ALLOWED') return h('div', { class: 'verdict ok' }, `Passed every stage and reached the upstream (${result.status}).`);
  if (result.outcome === 'REJECTED') return h('div', { class: 'verdict deny' }, `Rejected at ${where}: ${result.status} ${result.code || ''}`);
  return h('div', { class: 'verdict err' }, `Failed at ${where}: ${result.status || 'no response'} ${result.code || ''}`);
}

function upstreamSection(result) {
  const up = result.upstream;
  const section = h('section', {}, h('h3', {}, 'What the upstream saw'));
  if (!up?.checked) {
    section.append(h('div', { class: 'verdict muted' }, 'Upstream request journal unavailable.'));
    return section;
  }
  if (!up.received) {
    section.append(h('div', { class: 'verdict ok' }, 'Nothing. The upstream received zero requests for this request ID.'));
    return section;
  }
  section.append(kv([['URL', up.url, true]]));
  const highlighted = new Set(['x-gateway-identity', 'x-forwarded-for', 'x-forwarded-proto', 'x-forwarded-host', 'x-request-id']);
  const headers = Object.entries(up.headers || {}).sort(([a], [b]) => a.localeCompare(b));
  section.append(h('h3', { style: 'margin-top:10px' }, 'Headers received'), kv(headers.map(([k, v]) => [k, v, highlighted.has(k.toLowerCase())])));
  const hasAuth = headers.some(([k]) => k.toLowerCase() === 'authorization');
  section.append(h('div', { class: `verdict ${hasAuth ? 'deny' : 'ok'}`, style: 'margin-top:10px' },
    hasAuth ? 'Authorization header leaked to the upstream!' : 'Customer Authorization header was stripped.'));
  if (up.identity) {
    const id = up.identity;
    section.append(
      h('h3', { style: 'margin-top:10px' }, 'X-Gateway-Identity'),
      h('div', { class: `verdict ${id.verified ? 'ok' : 'deny'}` },
        id.verified ? `Signature verified with the gateway's JWKS (kid ${id.kid}, ${id.algorithm}).` : `Not verified: ${id.error}`),
      h('pre', {}, JSON.stringify(id.claims, null, 2)),
    );
  }
  return section;
}

function openDrawer(seq) {
  const r = state.results.get(seq);
  if (!r) return;
  const fault = state.config?.faults.find((f) => f.id === r.fault);
  $('drawer-title').textContent = `${r.method} ${r.path}`;
  $('drawer-body').replaceChildren(
    verdict(r),
    h('section', {}, h('h3', {}, 'Request'), kv([
      ['Tenant', r.tenant], ['Token', fault ? fault.description : r.fault], ['Sent to', r.target],
      ['Served by', r.servedBy], ['Request ID', r.requestId], ['Latency', `${r.latencyMs} ms`],
    ])),
    h('section', {}, h('h3', {}, 'Response'), kv([
      ['Status', r.status || 'no response'], ['Code', r.code, true], ['Detail', r.detail],
      ['Retry-After', r.retryAfter, true], ['Allow', r.allow, true], ['WWW-Authenticate', r.responseHeaders?.['www-authenticate']],
    ]), r.responseBody ? h('pre', { style: 'margin-top:8px' }, r.responseBody) : null),
    upstreamSection(r),
  );
  $('drawer').classList.add('open');
  $('drawer').setAttribute('aria-hidden', 'false');
}

function closeDrawer() {
  $('drawer').classList.remove('open');
  $('drawer').setAttribute('aria-hidden', 'true');
}

// ---------- Scenarios & composer ----------

function renderScenarios(scenarios) {
  const root = $('scenarios');
  root.replaceChildren();
  let category = null;
  for (const sc of scenarios) {
    state.scenarios.set(sc.id, sc);
    if (sc.category !== category) {
      category = sc.category;
      root.append(h('div', { class: 'category' }, category));
    }
    root.append(h('button', {
      type: 'button', class: 'scenario', 'data-scenario': sc.id, title: sc.watch, onclick: (e) => runScenario(sc, e.currentTarget),
    },
    h('span', { class: 'title' }, sc.title),
    h('span', { class: 'play', 'aria-hidden': 'true' }, '▶'),
    h('span', { class: 'summary' }, sc.summary),
    h('span', { class: 'expect' }, `expect ${sc.expect}`)));
  }
}

async function runScenario(sc, button) {
  button.classList.add('running');
  try {
    const { runId } = await api(`api/scenarios/${encodeURIComponent(sc.id)}/run`, { method: 'POST' });
    state.runningScenario.set(runId, sc.id);
    if (!state.run || state.run.runId !== runId) {
      startRun({ runId, scenarioId: sc.id, title: sc.title, expect: sc.expect, total: sc.steps.reduce((n, st) => n + st.count, 0) });
    }
  } catch (e) {
    button.classList.remove('running');
    $('run-title').textContent = sc.title;
    $('run-meta').textContent = `could not start: ${e.message}`;
  }
}

function fillComposer(config) {
  const form = $('composer-form');
  form.tenant.replaceChildren(...config.tenants.map((t) => h('option', {}, t)));
  form.target.replaceChildren(...config.targets.map((t) => h('option', { value: t, selected: t === config.defaultTarget }, t === 'lb' ? `${config.loadBalancerName} (load balanced)` : t)));
  form.fault.replaceChildren(...config.faults.map((f) => h('option', { value: f.id }, f.description)));
  form.count.max = config.maxRequestsPerRun;
  form.concurrency.max = config.maxConcurrency;
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    $('composer-error').textContent = '';
    const headers = {};
    for (const line of form.headers.value.split('\n')) {
      const i = line.indexOf(':');
      if (i > 0) headers[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    }
    const request = {
      target: form.target.value, method: form.method.value, path: form.path.value.trim(), tenant: form.tenant.value,
      subject: 'composer', scope: form.scope.value, fault: form.fault.value, headers, body: form.body.value || null,
    };
    try {
      await api('api/requests', {
        method: 'POST',
        body: JSON.stringify({ request, count: Number(form.count.value), concurrency: Number(form.concurrency.value) }),
      });
    } catch (err) {
      $('composer-error').textContent = err.message;
    }
  });
}

// ---------- Events ----------

function handleRequest(result) {
  addToLog(result);
  recordInRun(result);
  animate(result);
}

function connect() {
  const conn = $('conn');
  const events = new EventSource('api/events');
  events.onopen = () => { conn.className = 'conn live'; $('conn-text').textContent = 'live'; };
  events.onerror = () => { conn.className = 'conn down'; $('conn-text').textContent = 'reconnecting…'; };
  events.addEventListener('request', (e) => handleRequest(JSON.parse(e.data)));
  events.addEventListener('health', (e) => applyHealth(JSON.parse(e.data)));
  events.addEventListener('run', (e) => {
    const update = JSON.parse(e.data);
    if (update.phase === 'STARTED') {
      if (!state.run || state.run.runId !== update.runId) startRun(update);
    } else {
      finishRun(update);
    }
  });
}

async function init() {
  buildDiagram();
  $('drawer-close').addEventListener('click', closeDrawer);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeDrawer(); });
  $('clear-log').addEventListener('click', () => { $('log-body').replaceChildren(); state.results.clear(); });
  try {
    const [config, recent, health] = await Promise.all([api('api/config'), api('api/requests'), api('api/health')]);
    state.config = config;
    STAGE_LABEL.load_balancer = config.loadBalancerName;
    $('name-lb').textContent = config.loadBalancerName;
    renderScenarios(config.scenarios);
    fillComposer(config);
    recent.forEach((r) => addToLog(r, false));
    applyHealth(health);
  } catch (e) {
    $('run-title').textContent = 'Visualizer backend unreachable';
    $('run-meta').textContent = e.message;
  }
  connect();
}

init();
