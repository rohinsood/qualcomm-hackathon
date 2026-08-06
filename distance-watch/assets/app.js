const ui = new WebUI();

const $ = (id) => document.getElementById(id);
const conn = $('conn');
const lamp = $('lamp');
const lampLabel = $('lamp-label');
const mmEl = $('mm');
const subEl = $('sub');
const rawMm = $('raw-mm');
const rawCount = $('raw-count');
const rawHz = $('raw-hz');
const rawAge = $('raw-age');
const rawSensor = $('raw-sensor');
const rawThr = $('raw-thr');

function fmtAge(ms) {
  if (ms == null) return '—';
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
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

  rawMm.textContent = hasReading ? String(d.mm) : 'no data';
  rawCount.textContent = String(d.count);
  rawHz.textContent = d.hz ? `${d.hz.toFixed(1)} Hz` : '—';
  rawAge.textContent = fmtAge(d.age_ms);
  rawSensor.textContent = d.sensor_ok ? 'detected' : 'not detected';
  rawThr.textContent = d.threshold_mm != null ? `< ${d.threshold_mm.toFixed(0)} mm` : '—';
}

ui.on_connect(() => {
  conn.textContent = 'Live';
  conn.className = 'pill ok';
});

ui.on_disconnect(() => {
  conn.textContent = 'Offline';
  conn.className = 'pill bad';
});

ui.on_message('distance', render);
