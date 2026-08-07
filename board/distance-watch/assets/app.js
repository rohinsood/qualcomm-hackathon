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
const motMaA = $('mot-ma-a');
const motMaB = $('mot-ma-b');
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

function motorWord(dir) {
  return dir < 0 ? 'left' : (dir > 0 ? 'right' : 'stop');
}

function badge(el, ok, okText, badText) {
  el.innerHTML = `<span class="badge ${ok ? 'ok' : 'warn'}">${ok ? okText : badText}</span>`;
}

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
  badge(motStatus, !!d.motors_ok, 'detected', 'not found');
  motCmd.textContent = motorWord(d.motor);
  motApplied.textContent = motorWord(d.motor_applied);
  motMaA.textContent = fmtMa(d.motor_ma_a);
  motMaB.textContent = fmtMa(d.motor_ma_b);
  motBusy.textContent = d.motor_busy ? 'yes' : 'no';
  btnLeft.setAttribute('aria-pressed', String(d.motor === -1));
  btnStop.setAttribute('aria-pressed', String(d.motor === 0));
  btnRight.setAttribute('aria-pressed', String(d.motor === 1));

  // Vibro card
  badge(vibStatus, !!d.vibro_ok, 'detected', 'not found');
  vibActive.textContent = d.vibro_active ? 'buzzing (proximity alert)' : 'idle';

  // Bluetooth card
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

ui.on_connect(() => {
  conn.textContent = 'Live';
  conn.className = 'pill ok';
});

ui.on_disconnect(() => {
  conn.textContent = 'Offline';
  conn.className = 'pill bad';
});

ui.on_message('distance', render);
