const ui = new WebUI();

const $ = (id) => document.getElementById(id);
const conn = $('conn');
const lamp = $('lamp');
const lampLabel = $('lamp-label');
const mmEl = $('mm');
const subEl = $('sub');

const distStatus = $('dist-status');
const rawMm = $('raw-mm');
const rawCount = $('raw-count');
const rawHz = $('raw-hz');
const rawAge = $('raw-age');
const rawThr = $('raw-thr');

const motStatus = $('mot-status');
const motCmd = $('mot-cmd');
const motApplied = $('mot-applied');
const motSpeed = $('mot-speed');
const caneDaemon = $('cane-daemon');
const motMaA = $('mot-ma-a');
const motMaB = $('mot-ma-b');
const motDuty = $('mot-duty');
const motBusy = $('mot-busy');

const vibStatus = $('vib-status');
const vibActive = $('vib-active');

const btState = $('bt-state');
const btDevice = $('bt-device');
const btMsg = $('bt-msg');

const eventsEl = $('events');

const btnLeft = $('btn-left');
const btnStop = $('btn-stop');
const btnRight = $('btn-right');
const btnBuzz = $('btn-buzz');

function fmtAge(ms) {
  if (ms == null) return '—';
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

function fmtMa(ma) {
  return ma == null ? '—' : `${ma.toFixed(1)} mA`;
}

let vmV = 5;  // VM supply volts; refreshed from state (vm_v, set in main.py)

function fmtVolt(v) {
  return `${v > 0 ? '+' : ''}${v.toFixed(1)} V`;
}

function fmtDutyRow(duty) {
  if (duty == null) return '—';
  return `${fmtVolt((duty / 100) * vmV)} (${duty > 0 ? '+' : ''}${duty} %)`;
}

function motorWord(dir) {
  return dir < 0 ? 'left' : (dir > 0 ? 'right' : 'stop');
}

function badge(el, ok, okText, badText) {
  el.innerHTML = `<span class="badge ${ok ? 'ok' : 'warn'}">${ok ? okText : badText}</span>`;
}

/* ---- motor telemetry strip charts (current mA, applied duty %) ---------- */

const CHART_W = 520;
const CHART_H = 110;
const CHART_PAD = 4;
const HIST_MAX = 240;   // matches the server-side buffer (2 Hz x 2 min)
const WINDOW_S = 120;

const history = [];     // [{t, ma, duty}] — seeded from the server, then live
let lastMotorT = null;
let seeded = false;
let graphEpoch = null;  // server bumps this on "clear graphs"

const charts = {
  ma: {
    svg: $('chart-ma'), now: $('chart-ma-now'),
    yMax: $('chart-ma-max'), yMin: $('chart-ma-min'),
    value: (p) => p.ma, fmt: fmtMa, yFmt: (v) => String(v), cls: 'line-ma',
    scale: (data) => ({ min: 0, max: niceCeil(Math.max(50, ...data.map((p) => p.ma))) }),
  },
  duty: {
    svg: $('chart-duty'), now: $('chart-duty-now'),
    yMax: $('chart-duty-max'), yMin: $('chart-duty-min'),
    value: (p) => (p.duty / 100) * vmV, fmt: fmtVolt, yFmt: fmtVolt, cls: 'line-duty',
    scale: () => ({ min: -vmV, max: vmV, zero: true }),
  },
};

function niceCeil(v) {
  const steps = [50, 100, 200, 500, 1000, 2000, 5000];
  for (const s of steps) if (v <= s) return s;
  return Math.ceil(v / 5000) * 5000;
}

function pushSample(t, ma, duty) {
  if (ma == null) return;
  history.push({ t, ma, duty: duty || 0 });
  while (history.length > HIST_MAX) history.shift();
}

function drawChart(c) {
  const tNow = history.length ? history[history.length - 1].t : 0;
  const t0 = tNow - WINDOW_S;
  const data = history.filter((p) => p.t >= t0);
  const { min, max, zero } = c.scale(data.length ? data : [{ ma: 0, duty: 0, t: 0 }]);

  const x = (t) => CHART_PAD + (CHART_W - 2 * CHART_PAD) * (t - t0) / WINDOW_S;
  const y = (v) => {
    const f = (v - min) / (max - min);
    return CHART_H - CHART_PAD - (CHART_H - 2 * CHART_PAD) * f;
  };

  let parts = '';
  for (const gv of [min, (min + max) / 2, max]) {
    parts += `<line class="grid" x1="0" x2="${CHART_W}" y1="${y(gv).toFixed(1)}" y2="${y(gv).toFixed(1)}"/>`;
  }
  if (zero) {
    parts += `<line class="grid-zero" x1="0" x2="${CHART_W}" y1="${y(0).toFixed(1)}" y2="${y(0).toFixed(1)}"/>`;
  }
  if (data.length) {
    const pts = data.map((p) => `${x(p.t).toFixed(1)},${y(c.value(p)).toFixed(1)}`).join(' ');
    parts += `<polyline class="${c.cls}" points="${pts}"/>`;
  }
  c.svg.innerHTML = parts;
  c.yMax.textContent = c.yFmt(max);
  c.yMin.textContent = c.yFmt(min);
  c.data = data;
  c.t0 = t0;
  if (!c.hovering) {
    c.now.textContent = data.length ? c.fmt(c.value(data[data.length - 1])) : '—';
  }
}

function drawCharts() {
  drawChart(charts.ma);
  drawChart(charts.duty);
}

function attachHover(c) {
  c.svg.addEventListener('mousemove', (ev) => {
    if (!c.data || !c.data.length) return;
    const rect = c.svg.getBoundingClientRect();
    const tAt = c.t0 + ((ev.clientX - rect.left) / rect.width) * WINDOW_S;
    let best = c.data[0];
    for (const p of c.data) {
      if (Math.abs(p.t - tAt) < Math.abs(best.t - tAt)) best = p;
    }
    c.hovering = true;
    const ago = Math.max(0, (c.data[c.data.length - 1].t - best.t)).toFixed(0);
    c.now.textContent = `${c.fmt(c.value(best))} · ${ago}s ago`;
  });
  c.svg.addEventListener('mouseleave', () => {
    c.hovering = false;
    c.now.textContent = c.data && c.data.length ? c.fmt(c.value(c.data[c.data.length - 1])) : '—';
  });
}

attachHover(charts.ma);
attachHover(charts.duty);

/* ------------------------------------------------------------------------ */

function render(d) {
  const hasReading = d.mm != null;

  mmEl.textContent = hasReading ? d.mm.toFixed(0) : '—';
  if (hasReading) {
    subEl.textContent = `= ${(d.mm / 10).toFixed(1)} cm`;
  } else {
    subEl.textContent = d.sensor_ok ? 'no object in range' : 'sensor not found';
  }

  lamp.classList.toggle('on', !!d.present);
  lamp.classList.toggle('warn', !d.sensor_ok);
  lampLabel.textContent = !d.sensor_ok
    ? 'SENSOR NOT FOUND'
    : (d.present ? 'OBJECT DETECTED' : 'NO OBJECT');

  // Distance card
  badge(distStatus, !!d.sensor_ok, 'detected', 'not found');
  rawMm.textContent = hasReading ? `${d.mm.toFixed(0)} mm` : 'no data';
  rawCount.textContent = String(d.count);
  rawHz.textContent = d.hz ? `${d.hz.toFixed(1)} Hz` : '—';
  rawAge.textContent = fmtAge(d.age_ms);
  rawThr.textContent = d.threshold_mm != null ? `< ${d.threshold_mm.toFixed(0)} mm` : '—';

  // Motors card + button states
  if (d.vm_v) vmV = d.vm_v;
  badge(motStatus, !!d.motors_ok, 'detected', 'not found');
  motCmd.textContent = motorWord(d.motor);
  motApplied.textContent = motorWord(d.motor_applied);
  motSpeed.textContent = d.motor === 0 && d.motor_applied === 0
    ? 'stopped'
    : 'full scale (100% duty)';
  motMaA.textContent = fmtMa(d.motor_ma_a);
  motMaB.textContent = fmtMa(d.motor_ma_b);
  motDuty.textContent = fmtDutyRow(d.motor_duty_pct);
  motBusy.textContent = d.motor_busy ? 'yes' : 'no';
  btnLeft.setAttribute('aria-pressed', String(d.motor === -1));
  btnStop.setAttribute('aria-pressed', String(d.motor === 0));
  btnRight.setAttribute('aria-pressed', String(d.motor === 1));

  // Graphs cleared server-side: drop the local buffer and start fresh
  if (graphEpoch !== null && d.graph_epoch !== undefined && d.graph_epoch !== graphEpoch) {
    history.length = 0;
    lastMotorT = null;
    drawCharts();
  }
  if (d.graph_epoch !== undefined) graphEpoch = d.graph_epoch;

  // Graphs: seed once from server history, then append fresh samples
  if (!seeded && Array.isArray(d.motor_history)) {
    for (const p of d.motor_history) pushSample(p.t, p.ma, p.duty);
    if (d.motor_history.length) {
      lastMotorT = d.motor_history[d.motor_history.length - 1].t;
    }
    seeded = true;
    drawCharts();
  } else if (d.motor_t != null && d.motor_t !== lastMotorT) {
    lastMotorT = d.motor_t;
    pushSample(d.motor_t, d.motor_ma_a, d.motor_duty_pct);
    drawCharts();
  }

  // Vibro card
  badge(vibStatus, !!d.vibro_ok, 'detected', 'not found');
  vibActive.textContent = d.vibro_active ? 'buzzing (proximity alert)' : 'idle';

  // Bluetooth card
  badge(caneDaemon, !!d.cane_daemon, 'connected', 'not running');
  const bt = d.bt || {};
  btState.textContent = bt.connected
    ? 'connected'
    : (bt.advertising ? 'advertising' : 'off');
  btDevice.textContent = bt.device || '—';
  btMsg.textContent = d.phone_msg || '—';

  // Event log, newest first
  const events = d.events || [];
  if (events.length === 0) {
    eventsEl.innerHTML = '<li class="ev-empty">no events yet</li>';
  } else {
    eventsEl.innerHTML = events
      .slice()
      .reverse()
      .map((e) => `<li><span class="ev-t">${e.t}</span><span class="ev-msg"></span></li>`)
      .join('');
    // textContent per node: event text comes from phone/BLE, never trust it as HTML
    const msgs = eventsEl.querySelectorAll('.ev-msg');
    events.slice().reverse().forEach((e, i) => { msgs[i].textContent = e.msg; });
  }
}

async function post(path, body) {
  try {
    await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch (e) {
    /* board unreachable; the connection pill already says so */
  }
}

btnLeft.addEventListener('click', () => post('/api/motor', { dir: -1 }));
btnStop.addEventListener('click', () => post('/api/motor', { dir: 0 }));
btnRight.addEventListener('click', () => post('/api/motor', { dir: 1 }));
btnBuzz.addEventListener('click', () => post('/api/vibro', { ms: 600 }));
$('btn-clear-graphs').addEventListener('click', () => post('/api/graphs/clear', {}));

// Arrow-key driving: hold ← / → to spin (release stops), ↓ or Space stops,
// ↑ fires the buzz. Same /api/motor calls as the buttons, so the lit button
// states and the board can never disagree. keyDir tracks what the keys are
// commanding, so a keyup that never arrives (tab switch, focus loss) still
// stops the wheel instead of leaving it spinning.
let keyDir = 0;

function keyDrive(dir) {
  if (dir === keyDir) return;
  keyDir = dir;
  post('/api/motor', { dir });
}

window.addEventListener('keydown', (e) => {
  const t = e.target;
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
  switch (e.key) {
    case 'ArrowLeft':  e.preventDefault(); if (!e.repeat) keyDrive(-1); break;
    case 'ArrowRight': e.preventDefault(); if (!e.repeat) keyDrive(1); break;
    case 'ArrowDown':
    case ' ':          e.preventDefault(); if (!e.repeat) keyDrive(0); break;
    case 'ArrowUp':    e.preventDefault(); if (!e.repeat) post('/api/vibro', { ms: 600 }); break;
  }
});

window.addEventListener('keyup', (e) => {
  if ((e.key === 'ArrowLeft' && keyDir === -1)
      || (e.key === 'ArrowRight' && keyDir === 1)) {
    keyDrive(0);
  }
});

// Focus lost mid-hold means the keyup will never fire — stop the wheel.
window.addEventListener('blur', () => keyDrive(0));
document.addEventListener('visibilitychange', () => {
  if (document.hidden) keyDrive(0);
});

ui.on_connect(() => {
  conn.textContent = 'Live';
  conn.className = 'pill ok';
});

ui.on_disconnect(() => {
  conn.textContent = 'Offline';
  conn.className = 'pill bad';
  // Reseed the graphs from server history on the next connect — the app may
  // have restarted while we were away.
  seeded = false;
  history.length = 0;
  lastMotorT = null;
});

ui.on_message('distance', render);
