/* Cameras card — MJPEG streams from the qhack-cam-streams host service.
   The streams come straight from port 8080 (same host, different port), not
   through the web_ui websocket: <img> loads are exempt from CORS, and the
   dashboard stays usable when the camera service is down. */
(() => {
  const BASE = `http://${location.hostname}:8080`;
  const RETRY_MS = 5000;
  const CAMS = [
    { id: "cam-raw", path: "/raw" },
    { id: "cam-terrain", path: "/terrain" },
    { id: "cam-depth", path: "/depth" },
    { id: "cam-phone", path: "/phone" },
  ];

  for (const { id, path } of CAMS) {
    const img = document.getElementById(id);
    if (!img) continue;
    const fig = img.closest(".cam");
    const url = BASE + path;
    let timer = null;
    const load = () => { img.src = url + "?t=" + Date.now(); };
    img.addEventListener("error", () => {
      fig.classList.add("cam-off");
      clearTimeout(timer);
      timer = setTimeout(load, RETRY_MS);
    });
    img.addEventListener("load", () => fig.classList.remove("cam-off"));
    img.addEventListener("click", () => window.open(url, "_blank"));
    load();
  }

  const pill = document.getElementById("cam-status");
  async function poll() {
    try {
      const r = await fetch(BASE + "/stats",
                            { signal: AbortSignal.timeout(2500) });
      const s = await r.json();
      const terrain = s.terrain?.emitted ?? "?";
      const verdict = s.navigator?.decision?.verdict ?? "?";
      pill.textContent =
        `${(s.camera_fps ?? 0).toFixed(1)} fps · ${terrain} · ${verdict}`;
      pill.classList.add("ok");
      pill.classList.remove("bad");
    } catch {
      pill.textContent = "streams offline";
      pill.classList.add("bad");
      pill.classList.remove("ok");
    }
  }
  poll();
  setInterval(poll, 5000);
})();
