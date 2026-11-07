import { exec, toast, moduleInfo } from "./kernelsu.js";
import { mountBoost } from "./boost.js?v=freq1";

const hasKsu = typeof globalThis.ksu === "object" && globalThis.ksu && typeof globalThis.ksu.exec === "function";

function remoteOpForPath(path) {
  if (path.endsWith("/profile.conf")) return "profile";
  if (path.endsWith("/profile.default")) return "profiledefault";
  if (path.endsWith("/mode")) return "mode";
  if (path.endsWith("/scene-map.txt")) return "scenemap";
  if (path.endsWith("/setting.xml")) return "setting";
  if (path.endsWith("/stock-map.txt")) return "stockmap";
  if (path.endsWith("/boost.xml")) return "boost";
  return "";
}

function apiUrl(op, params) {
  let url = "/cgi-bin/api?op=" + encodeURIComponent(op);
  if (params) {
    for (const key of Object.keys(params)) {
      url += "&" + encodeURIComponent(key) + "=" + encodeURIComponent(params[key]);
    }
  }
  return url;
}

async function apiGet(op, params) {
  const response = await fetch(apiUrl(op, params), { cache: "no-store" });
  const text = await response.text();
  if (!response.ok) throw new Error(text.trim() || "读取失败");
  return text;
}

async function apiPost(op, body, params) {
  const response = await fetch(apiUrl(op, params), {
    method: "POST",
    cache: "no-store",
    body: body == null ? "" : body,
  });
  const text = await response.text();
  if (!response.ok) throw new Error(text.trim() || "请求失败");
  return text;
}

const TITLES = {
  "SS-CPU0": "小核",
  "SS-CPU6": "大核",
  "SS-LMH_CPU6": "大核簇",
  "SIC-BAT": "有线充电",
  "MONITOR-WIRELESS": "无线充电",
  "MONITOR-GPU": "GPU",
  "MONITOR-BACKLIGHT": "亮度上限",
  "MONITOR-CCC": "热拔核",
  "MONITOR-BCL": "低电量限制",
  "MONITOR-BCL-AWARE": "温感限制",
  "MONITOR-WIFI-LIMIT": "Wi-Fi",
  "MONITOR-MODEM-LEVEL": "调制解调器等级",
  "MONITOR-MODEM-5GTO4G": "5G 退回 4G",
  "MONITOR-PARALLEL-TO-SERIAL": "商店下载",
  "MONITOR-TORCH-LIMIT": "手电",
  "MONITOR-FLASH": "闪光灯",
  "MONITOR-NPU": "NPU",
  "MONITOR-VOICE-LIMIT": "听筒",
  "MONITOR-BOOST_LIMIT": "加速限制",
  "MONITOR-BAT": "电池",
  "MONITOR-TEMP_STATE": "温控状态",
  "VIRTUAL-SENSOR-FORMULA": "皮肤温度合成",
  "VIRTUAL-SENSOR-FORMULA-FRONT": "正面温度",
  "VIRTUAL-SENSOR-FORMULA-BACK": "背面温度",
  "VIRTUAL-SENSOR-FORMULA-LEFT": "左侧温度",
  "VIRTUAL-SENSOR-FORMULA-RIGHT": "右侧温度",
  "VIRTUAL-SENSOR-FORMULA-TOP": "顶部温度",
  "BAT_SOC": "电量输入",
  "TEMP_AWARE": "温感输入",
};

const statusEl = document.querySelector("#status");
const cardsEl = document.querySelector("#cards");
const modeEl = document.querySelector("#mode");
const saveEl = document.querySelector("#save");

let dir = "/data/adb/modules/thermal-local";
let state = null;

function setStatus(text, bad) {
  statusEl.textContent = text;
  statusEl.style.color = bad ? "var(--danger)" : "var(--muted)";
}

function setBusy(busy) {
  for (const id of ["reload", "defaults", "save", "mode"]) {
    document.getElementById(id).disabled = busy;
  }
}

function moduleDir() {
  try {
    const info = JSON.parse(moduleInfo());
    return info.moduleDir || info.dir || dir;
  } catch (error) {
    return dir;
  }
}

function parseProfile(text) {
  const normalized = text.replace(/\r\n/g, "\n");
  const trailingNewline = normalized.endsWith("\n");
  const lines = normalized.replace(/\n$/, "").split("\n");
  const sections = [];
  let current = null;
  for (const line of lines) {
    if (line.startsWith("[") && line.endsWith("]")) {
      current = { name: line.slice(1, -1), order: [], fields: {} };
      sections.push(current);
      continue;
    }
    if (!line || !current) continue;
    const parts = line.split("\t");
    current.order.push(parts[0]);
    current.fields[parts[0]] = parts.slice(1);
  }
  return { sections, trailingNewline };
}

function serialize(parsed) {
  const body = parsed.sections.map((section) => {
    const lines = ["[" + section.name + "]"];
    for (const key of section.order) {
      lines.push([key].concat(section.fields[key]).join("\t"));
    }
    return lines.join("\n");
  }).join("\n\n");
  return parsed.trailingNewline ? body + "\n" : body;
}

function numbers(values) {
  return (values || []).map(Number).filter((value) => !Number.isNaN(value));
}

function looksLikeTemp(values) {
  const list = numbers(values);
  return list.length > 0 && Math.max(...list.map(Math.abs)) >= 1000;
}

function targetKind(section) {
  const device = (section.fields.device || [""])[0];
  const values = section.fields.target || [];
  if (values.some((value) => String(value).includes("+"))) return "raw";
  if (device === "cpu0" || device === "cpu6" || device === "cpu-cluster1") return "hz";
  if (device === "thermal_fcc_override") return "temp";
  return "raw";
}

function fieldLabel(key, kind) {
  if (key === "trig") return kind === "temp" ? "触发 °C" : "触发";
  if (key === "clr") return kind === "temp" ? "恢复 °C" : "恢复";
  if (key === "target" && kind === "temp") return "目标 °C";
  if (key === "target" && kind === "hz") return "频率";
  if (key === "target") return "目标";
  if (key === "max") return "上限 mA";
  if (key === "min") return "下限 mA";
  if (key === "weight") return "权重";
  if (key === "compensation") return "补偿";
  return key;
}

function displayValue(raw, kind) {
  if (kind !== "temp") return raw;
  const value = Number(raw);
  if (Number.isNaN(value)) return raw;
  return String(Math.round(value / 10) / 100);
}

function storedValue(text, kind) {
  const trimmed = text.trim();
  if (trimmed === "") throw new Error("有空着的数值");
  if (kind === "raw" && trimmed.includes("+")) return trimmed;
  if (kind === "temp") {
    const value = Number(trimmed);
    if (!Number.isFinite(value)) throw new Error("温度要是数字");
    return String(Math.round(value * 1000));
  }
  if (!/^-?\d+$/.test(trimmed)) throw new Error("数值要是整数");
  return trimmed;
}

function titleOf(section) {
  return TITLES[section.name] || section.name;
}

function render() {
  cardsEl.innerHTML = "";
  const note = document.createElement("p");
  note.className = "note";
  note.textContent = "温度按 °C 显示，保存时写回毫摄氏度。频率保持 Hz。";
  cardsEl.appendChild(note);
  fillProfile(cardsEl, state);
}

const freqTables = {
  cpu0: [384000, 556800, 748800, 960000, 1152000, 1363200, 1555200, 1785600, 1996800, 2227200, 2400000, 2745600, 2918400, 3072000, 3321600, 3532800],
  cpu6: [1017600, 1209600, 1401600, 1689600, 1958400, 2246400, 2438400, 2649600, 2841600, 3072000, 3283200, 3513600, 3801600, 4089600, 4204800, 4320000],
};

async function loadFreqs() {
  try {
    let text = "";
    if (!hasKsu) text = await apiGet("freqs");
    else {
      const result = await exec("echo cpu0 $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies); echo cpu6 $(cat /sys/devices/system/cpu/cpu6/cpufreq/scaling_available_frequencies)");
      if (result.errno !== 0) return;
      text = result.stdout || "";
    }
    const next = {};
    for (const line of text.split(/\r?\n/)) {
      const parts = line.trim().split(/\s+/);
      if ((parts[0] === "cpu0" || parts[0] === "cpu6") && parts.length > 2) {
        next[parts[0]] = parts.slice(1).map(Number).filter((value) => value > 0);
      }
    }
    if (next.cpu0 && next.cpu0.length) freqTables.cpu0 = next.cpu0;
    if (next.cpu6 && next.cpu6.length) freqTables.cpu6 = next.cpu6;
  } catch (error) {}
}

function nearestFreq(device, hz) {
  const table = freqTables[device] || [];
  if (!table.length || !Number.isFinite(hz)) return Math.round(hz);
  return table.reduce((best, item) => Math.abs(item - hz) < Math.abs(best - hz) ? item : best, table[0]);
}

function setCurveInput(card, key, index, value) {
  const input = card.querySelector('[data-key="' + key + '"][data-index="' + index + '"]');
  if (input) input.value = String(value);
}

function curveLevels(card) {
  return Array.from(card.querySelectorAll('input[data-key="trig"]')).map((input) => {
    const index = input.dataset.index;
    const clr = card.querySelector('input[data-key="clr"][data-index="' + index + '"]');
    const target = card.querySelector('[data-key="target"][data-index="' + index + '"]');
    return {
      index,
      trig: Number(input.value),
      clr: clr ? Number(clr.value) : NaN,
      hz: target ? Number(target.value) : NaN,
    };
  }).filter((point) => Number.isFinite(point.trig) && Number.isFinite(point.hz));
}

function freqChoices(device, current) {
  const values = (freqTables[device] || []).map((item) => String(item));
  const text = String(current || "").trim();
  if (text && values.indexOf(text) < 0) values.push(text);
  values.sort((a, b) => Number(a) - Number(b));
  return values;
}

function freqOptionLabel(hz) {
  const value = Number(hz);
  if (!Number.isFinite(value)) return String(hz);
  return (value / 1000000).toFixed(2) + " GHz";
}

function freqSelect(device, current) {
  const select = document.createElement("select");
  for (const hz of freqChoices(device, current)) {
    const option = document.createElement("option");
    option.value = hz;
    option.textContent = freqOptionLabel(hz);
    select.appendChild(option);
  }
  select.value = String(current || "");
  return select;
}

const CURVE_W = 360;
const CURVE_H = 230;
const CURVE_PAD = { l: 52, r: 14, t: 14, b: 28 };
let curveClipSeq = 0;

function formatHalf(value) {
  const snapped = Math.round(value * 2) / 2;
  if (Math.abs(snapped - Math.round(snapped)) < 1e-6) return String(Math.round(snapped));
  return snapped.toFixed(1);
}

function snapTemp(value) {
  if (!Number.isFinite(value)) return 0;
  const snapped = Math.round(value * 2) / 2;
  return Math.min(125, Math.max(0, snapped));
}

function halfTicks(min, max, step) {
  const ticks = [];
  const start = Math.ceil((min - 1e-6) / step);
  const end = Math.floor((max + 1e-6) / step);
  for (let index = start; index <= end; index += 1) {
    ticks.push(Math.round(index * step * 10) / 10);
  }
  return ticks;
}

function tempTickStep(span) {
  const raw = span / 8;
  for (const step of [0.5, 1, 2, 5, 10, 20, 25]) {
    if (step + 1e-9 >= raw) return step;
  }
  return 25;
}

function curveScale(range) {
  const plotW = CURVE_W - CURVE_PAD.l - CURVE_PAD.r;
  const plotH = CURVE_H - CURVE_PAD.t - CURVE_PAD.b;
  const spanT = Math.max(1e-6, range.maxT - range.minT);
  const spanF = Math.max(1, range.maxF - range.minF);
  return {
    plotW,
    plotH,
    xOf: (temp) => CURVE_PAD.l + (temp - range.minT) / spanT * plotW,
    yOf: (hz) => CURVE_PAD.t + (1 - (hz - range.minF) / spanF) * plotH,
    tempAt: (x) => range.minT + (x - CURVE_PAD.l) / plotW * spanT,
    hzAt: (y) => range.minF + (1 - (y - CURVE_PAD.t) / plotH) * spanF,
  };
}

function fitCurveRange(points, table) {
  const temps = [];
  for (const point of points) {
    temps.push(point.trig);
    if (Number.isFinite(point.clr)) temps.push(point.clr);
  }
  return {
    minT: Math.min(...temps) - 2,
    maxT: Math.max(...temps) + 2,
    minF: Math.min(...table),
    maxF: Math.max(...table),
  };
}

function curveLimits(table) {
  const minF = Math.min(...table);
  const maxF = Math.max(...table);
  return {
    minT: 0,
    maxT: 125,
    minF,
    maxF: minF === maxF ? minF + 1 : maxF,
    minSpanT: 2,
  };
}

function zoomRange(min, max, anchor, factor, limitMin, limitMax, minSpan) {
  const full = limitMax - limitMin;
  if (!(full > 0)) return [min, max];
  let lo = anchor - (anchor - min) * factor;
  let hi = anchor + (max - anchor) * factor;
  const spanLimit = Math.min(minSpan, full);
  if (hi - lo < spanLimit) {
    lo = anchor - spanLimit / 2;
    hi = anchor + spanLimit / 2;
  }
  if (hi - lo >= full) return [limitMin, limitMax];
  if (lo < limitMin) {
    hi += limitMin - lo;
    lo = limitMin;
  }
  if (hi > limitMax) {
    lo -= hi - limitMax;
    hi = limitMax;
  }
  if (lo < limitMin) lo = limitMin;
  return [lo, hi];
}

function shiftRange(min, max, delta, limitMin, limitMax) {
  let lo = min + delta;
  let hi = max + delta;
  if (lo < limitMin) {
    hi += limitMin - lo;
    lo = limitMin;
  }
  if (hi > limitMax) {
    lo -= hi - limitMax;
    hi = limitMax;
  }
  if (lo < limitMin) lo = limitMin;
  return [lo, hi];
}

function attachCurve(card, device) {
  const box = document.createElement("div");
  box.className = "curve-box";
  const legend = document.createElement("div");
  legend.className = "curve-legend";
  legend.innerHTML = '<span><i class="trig"></i>触发</span><span><i class="clr"></i>恢复</span><span class="curve-tools"><button type="button" data-zoom="out">缩小</button><button type="button" data-zoom="in">放大</button><button type="button" data-zoom="reset">复位</button></span>';
  const readout = document.createElement("div");
  readout.className = "curve-read";
  readout.textContent = "拖动圆点。温度按 0.5°C 对齐，频率按这颗处理器的频率表对齐。滚轮或双指缩放温度轴，拖空白处平移。";
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.classList.add("curve");
  svg.setAttribute("viewBox", "0 0 " + CURVE_W + " " + CURVE_H);
  box.appendChild(legend);
  box.appendChild(svg);
  box.appendChild(readout);
  const levels = card.querySelector(".levels");
  card.insertBefore(box, levels);
  const view = { minT: 0, maxT: 0, minF: 0, maxF: 0 };
  let fitted = true;
  const pointers = new Map();
  const drag = { point: null };
  let gesture = null;

  const tableOf = () => freqTables[device] || [];
  function readRange() {
    const points = curveLevels(card);
    const table = tableOf();
    if (!points.length || !table.length) return null;
    if (fitted) return fitCurveRange(points, table);
    return { minT: view.minT, maxT: view.maxT, minF: view.minF, maxF: view.maxF };
  }
  function commitRange(next) {
    view.minT = next.minT;
    view.maxT = next.maxT;
    view.minF = next.minF;
    view.maxF = next.maxF;
    fitted = false;
  }
  const paint = () => paintCurve(svg, card, device, readRange());
  function svgPoint(event) {
    const rect = svg.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    return {
      x: (event.clientX - rect.left) / rect.width * CURVE_W,
      y: (event.clientY - rect.top) / rect.height * CURVE_H,
    };
  }
  function zoomAt(factor, anchorT) {
    const range = readRange();
    const table = tableOf();
    if (!range || !table.length) return;
    const lim = curveLimits(table);
    const [minT, maxT] = zoomRange(range.minT, range.maxT, anchorT, factor, lim.minT, lim.maxT, lim.minSpanT);
    commitRange({ minT, maxT, minF: lim.minF, maxF: lim.maxF });
    paint();
  }
  function pinchMetrics() {
    const pts = Array.from(pointers.values());
    if (pts.length < 2) return null;
    const a = pts[0];
    const b = pts[1];
    return {
      dist: Math.max(1, Math.hypot(b.x - a.x, b.y - a.y)),
      mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
    };
  }
  function beginPinch() {
    const range = readRange();
    const metrics = pinchMetrics();
    if (!range || !metrics) return;
    const scale = curveScale(range);
    drag.point = null;
    svg.classList.remove("panning");
    gesture = {
      type: "pinch",
      dist: metrics.dist,
      mid: metrics.mid,
      range,
      anchorT: scale.tempAt(metrics.mid.x),
    };
  }
  function applyPinch() {
    if (!gesture || gesture.type !== "pinch") return;
    const now = pinchMetrics();
    const table = tableOf();
    if (!now || !table.length) return;
    const lim = curveLimits(table);
    const factor = gesture.dist / now.dist;
    let [minT, maxT] = zoomRange(gesture.range.minT, gesture.range.maxT, gesture.anchorT, factor, lim.minT, lim.maxT, lim.minSpanT);
    const scale = curveScale({ minT, maxT, minF: lim.minF, maxF: lim.maxF });
    const dT = -(now.mid.x - gesture.mid.x) / scale.plotW * (maxT - minT);
    [minT, maxT] = shiftRange(minT, maxT, dT, lim.minT, lim.maxT);
    commitRange({ minT, maxT, minF: lim.minF, maxF: lim.maxF });
    paint();
  }
  function hitPoint(at) {
    let best = null;
    let bestD = 24;
    for (const point of svg._points || []) {
      if (point.x < CURVE_PAD.l - 4 || point.x > CURVE_W - CURVE_PAD.r + 4) continue;
      if (point.y < CURVE_PAD.t - 4 || point.y > CURVE_H - CURVE_PAD.b + 4) continue;
      const distance = Math.hypot(point.x - at.x, point.y - at.y);
      if (distance < bestD) {
        best = point;
        bestD = distance;
      }
    }
    return best;
  }

  svg.addEventListener("pointerdown", (event) => {
    const at = svgPoint(event);
    if (!at) return;
    pointers.set(event.pointerId, at);
    try {
      svg.setPointerCapture(event.pointerId);
    } catch (error) {}
    if (pointers.size >= 2) {
      beginPinch();
      event.preventDefault();
      return;
    }
    const hit = hitPoint(at);
    if (hit) {
      drag.point = hit;
      gesture = { type: "point" };
      svg.classList.remove("panning");
    } else {
      drag.point = null;
      gesture = { type: "pan", last: at };
      svg.classList.add("panning");
    }
    event.preventDefault();
  });
  svg.addEventListener("pointermove", (event) => {
    if (!pointers.has(event.pointerId)) return;
    const at = svgPoint(event);
    if (!at) return;
    pointers.set(event.pointerId, at);
    event.preventDefault();
    if (gesture && gesture.type === "pinch") {
      applyPinch();
      return;
    }
    if (gesture && gesture.type === "point" && drag.point) {
      const range = readRange();
      if (!range) return;
      const scale = curveScale(range);
      const x = Math.min(CURVE_W - CURVE_PAD.r, Math.max(CURVE_PAD.l, at.x));
      const y = Math.min(CURVE_H - CURVE_PAD.b, Math.max(CURVE_PAD.t, at.y));
      const temp = snapTemp(scale.tempAt(x));
      const hz = nearestFreq(device, scale.hzAt(y));
      setCurveInput(card, drag.point.key, drag.point.index, formatHalf(temp));
      setCurveInput(card, "target", drag.point.index, hz);
      const label = drag.point.key === "trig" ? "触发" : "恢复";
      readout.textContent = label + " " + formatHalf(temp) + "°C · " + (hz / 1000000).toFixed(2) + " GHz";
      paint();
      return;
    }
    if (gesture && gesture.type === "pan") {
      const range = readRange();
      const table = tableOf();
      if (!range || !table.length) return;
      const lim = curveLimits(table);
      const dx = at.x - gesture.last.x;
      gesture.last = at;
      const scale = curveScale(range);
      const dT = -dx / scale.plotW * (range.maxT - range.minT);
      const [minT, maxT] = shiftRange(range.minT, range.maxT, dT, lim.minT, lim.maxT);
      commitRange({ minT, maxT, minF: lim.minF, maxF: lim.maxF });
      paint();
    }
  });
  function endPointer(event) {
    pointers.delete(event.pointerId);
    if (gesture && gesture.type === "pinch") {
      if (pointers.size >= 2) return;
      if (pointers.size === 1) {
        gesture = { type: "pan", last: pointers.values().next().value };
        svg.classList.add("panning");
        return;
      }
      gesture = null;
      svg.classList.remove("panning");
      return;
    }
    gesture = null;
    drag.point = null;
    svg.classList.remove("panning");
  }
  svg.addEventListener("pointerup", endPointer);
  svg.addEventListener("pointercancel", endPointer);
  svg.addEventListener("wheel", (event) => {
    const at = svgPoint(event);
    const range = readRange();
    if (!at || !range || !event.deltaY) return;
    event.preventDefault();
    let delta = event.deltaY;
    if (event.deltaMode === 1) delta *= 16;
    if (event.deltaMode === 2) delta *= CURVE_H;
    const scale = curveScale(range);
    zoomAt(Math.exp(delta * 0.0015), scale.tempAt(at.x));
  }, { passive: false });
  for (const button of legend.querySelectorAll("[data-zoom]")) {
    button.addEventListener("click", () => {
      if (button.dataset.zoom === "reset") {
        fitted = true;
        paint();
        return;
      }
      const range = readRange();
      if (!range) return;
      const factor = button.dataset.zoom === "in" ? 1 / 1.5 : 1.5;
      zoomAt(factor, (range.minT + range.maxT) / 2);
    });
  }
  const onCurveEdit = (event) => {
    const key = event.target.dataset && event.target.dataset.key;
    if (key === "trig" || key === "clr" || key === "target") paint();
  };
  card.addEventListener("input", onCurveEdit);
  card.addEventListener("change", onCurveEdit);
  paint();
}

function paintCurve(svg, card, device, range) {
  while (svg.firstChild) svg.removeChild(svg.firstChild);
  const table = freqTables[device] || [];
  const points = curveLevels(card);
  svg._points = [];
  svg._scale = null;
  if (!points.length || !table.length || !range) return;
  const scale = curveScale(range);
  svg._scale = scale;
  const { minT, maxT } = range;
  const ns = "http://www.w3.org/2000/svg";
  function add(parent, name, attrs) {
    const node = document.createElementNS(ns, name);
    for (const key of Object.keys(attrs)) node.setAttribute(key, attrs[key]);
    parent.appendChild(node);
    return node;
  }
  const clipId = "curve-plot-" + (curveClipSeq += 1);
  const clip = add(svg, "clipPath", { id: clipId });
  add(clip, "rect", {
    x: CURVE_PAD.l,
    y: CURVE_PAD.t,
    width: scale.plotW,
    height: scale.plotH,
  });
  const plot = add(svg, "g", { "clip-path": "url(#" + clipId + ")" });
  const visibleHz = table.filter((hz) => {
    const y = scale.yOf(hz);
    return y >= CURVE_PAD.t - 1 && y <= CURVE_H - CURVE_PAD.b + 1;
  });
  const rows = visibleHz.length ? visibleHz : table;
  for (const hz of rows) {
    const y = scale.yOf(hz);
    add(plot, "line", { x1: CURVE_PAD.l, y1: y, x2: CURVE_W - CURVE_PAD.r, y2: y, stroke: "#313640", "stroke-width": "1" });
  }
  const spanT = Math.max(0.5, maxT - minT);
  const major = tempTickStep(spanT);
  const minorPx = scale.plotW / (spanT / 0.5);
  if (minorPx >= 12 && major > 0.5) {
    for (const temp of halfTicks(minT, maxT, 0.5)) {
      const x = scale.xOf(temp);
      add(plot, "line", { x1: x, y1: CURVE_PAD.t, x2: x, y2: CURVE_H - CURVE_PAD.b, stroke: "#242830", "stroke-width": "1" });
    }
  }
  for (const temp of halfTicks(minT, maxT, major)) {
    const x = scale.xOf(temp);
    add(plot, "line", { x1: x, y1: CURVE_PAD.t, x2: x, y2: CURVE_H - CURVE_PAD.b, stroke: "#2a2e36", "stroke-width": "1" });
    const anchor = x < CURVE_PAD.l + 14 ? "start" : (x > CURVE_W - CURVE_PAD.r - 14 ? "end" : "middle");
    add(svg, "text", { x: x, y: CURVE_H - 6, fill: "#a3acb8", "font-size": "10", "text-anchor": anchor }).textContent = formatHalf(temp);
  }
  if (minorPx >= 4) {
    for (const temp of halfTicks(minT, maxT, 0.5)) {
      const x = scale.xOf(temp);
      const whole = Math.abs(temp - Math.round(temp)) < 1e-6;
      add(svg, "line", {
        x1: x,
        y1: CURVE_H - CURVE_PAD.b,
        x2: x,
        y2: CURVE_H - CURVE_PAD.b + (whole ? 8 : 4),
        stroke: "#6d7682",
        "stroke-width": "1",
      });
    }
  }
  const stride = Math.max(1, Math.ceil(rows.length / 4));
  for (let i = 0; i < rows.length; i += stride) {
    const hz = rows[i];
    add(svg, "text", { x: 4, y: scale.yOf(hz) + 3, fill: "#a3acb8", "font-size": "10" }).textContent = (hz / 1000000).toFixed(2);
  }
  function line(key, color) {
    const cmds = [];
    for (const point of points) {
      const temp = key === "trig" ? point.trig : point.clr;
      if (!Number.isFinite(temp)) continue;
      const x = scale.xOf(temp);
      const y = scale.yOf(point.hz);
      cmds.push((cmds.length ? "L" : "M") + x + " " + y);
      svg._points.push({ key, index: point.index, x, y });
    }
    if (cmds.length) add(plot, "path", { d: cmds.join(" "), fill: "none", stroke: color, "stroke-width": "2" });
  }
  line("clr", "#3ddc84");
  line("trig", "#ff6b6b");
  for (const point of svg._points) {
    add(plot, "circle", {
      cx: point.x,
      cy: point.y,
      r: "6",
      fill: point.key === "trig" ? "#ff6b6b" : "#3ddc84",
      stroke: "#101114",
      "stroke-width": "1.5",
    });
  }
}

function fillProfile(root, parsed) {
  for (const section of parsed.sections) {
    const card = document.createElement("section");
    card.className = "card";
    const heading = document.createElement("h2");
    heading.textContent = titleOf(section);
    card.appendChild(heading);
    const device = (section.fields.device || section.fields.path || [""])[0];
    if (device) {
      const meta = document.createElement("div");
      meta.className = "device";
      meta.textContent = device;
      card.appendChild(meta);
    }

    if (section.fields.sensors && section.fields.weight) {
      const grid = document.createElement("div");
      grid.className = "weights";
      section.fields.sensors.forEach((name, index) => {
        const label = document.createElement("div");
        label.textContent = name;
        const input = document.createElement("input");
        input.inputMode = "numeric";
        input.value = section.fields.weight[index] || "0";
        input.dataset.sec = section.name;
        input.dataset.key = "weight";
        input.dataset.index = String(index);
        input.dataset.kind = "int";
        grid.appendChild(label);
        grid.appendChild(input);
      });
      card.appendChild(grid);
    }

    const trig = section.fields.trig;
    if (trig) {
      const temp = looksLikeTemp(trig);
      const kind = targetKind(section);
      const keys = ["trig", "clr", "target"].filter((key) => section.fields[key]);
      if (section.name === "SIC-BAT") keys.push("max", "min");
      const count = Math.max(...keys.map((key) => section.fields[key].length));
      const list = document.createElement("div");
      list.className = "levels";
      for (let i = 0; i < count; i += 1) {
        const row = document.createElement("div");
        row.className = "level";
        const index = document.createElement("div");
        index.className = "index";
        index.textContent = String(i + 1);
        row.appendChild(index);
        for (const key of keys) {
          const valueKind = key === "trig" || key === "clr" ? (temp ? "temp" : "int") : (key === "target" ? kind : "int");
          const wrap = document.createElement("label");
          wrap.className = "field";
          const caption = document.createElement("span");
          caption.textContent = fieldLabel(key, valueKind);
          const control = valueKind === "hz" ? freqSelect(device, section.fields[key][i] || "") : document.createElement("input");
          if (valueKind !== "hz") {
            control.inputMode = valueKind === "temp" ? "decimal" : "numeric";
            control.value = displayValue(section.fields[key][i] || "", valueKind);
          }
          control.dataset.sec = section.name;
          control.dataset.key = key;
          control.dataset.index = String(i);
          control.dataset.kind = valueKind;
          wrap.appendChild(caption);
          wrap.appendChild(control);
          row.appendChild(wrap);
        }
        list.appendChild(row);
      }
      card.appendChild(list);
      if ((device === "cpu0" || device === "cpu6") && kind === "hz" && temp && section.fields.clr) {
        attachCurve(card, device);
      }
    }

    if (section.fields.compensation) {
      const wrap = document.createElement("label");
      wrap.className = "field";
      wrap.style.marginTop = "10px";
      const caption = document.createElement("span");
      caption.textContent = "补偿";
      const input = document.createElement("input");
      input.inputMode = "numeric";
      input.value = section.fields.compensation[0];
      input.dataset.sec = section.name;
      input.dataset.key = "compensation";
      input.dataset.index = "0";
      input.dataset.kind = "int";
      wrap.appendChild(caption);
      wrap.appendChild(input);
      card.appendChild(wrap);
    }

    const coefKeys = ["ks", "ki", "kc"].filter((key) => section.fields[key]);
    if (coefKeys.length) {
      const details = document.createElement("details");
      const summary = document.createElement("summary");
      summary.textContent = "充电系数";
      details.appendChild(summary);
      const count = section.fields[coefKeys[0]].length;
      for (let i = 0; i < count; i += 1) {
        const row = document.createElement("div");
        row.className = "level";
        const index = document.createElement("div");
        index.className = "index";
        index.textContent = String(i + 1);
        row.appendChild(index);
        for (const key of coefKeys) {
          const wrap = document.createElement("label");
          wrap.className = "field";
          const caption = document.createElement("span");
          caption.textContent = key;
          const input = document.createElement("input");
          input.inputMode = "numeric";
          input.value = section.fields[key][i];
          input.dataset.sec = section.name;
          input.dataset.key = key;
          input.dataset.index = String(i);
          input.dataset.kind = "int";
          wrap.appendChild(caption);
          wrap.appendChild(input);
          row.appendChild(wrap);
        }
        details.appendChild(row);
      }
      card.appendChild(details);
    }

    if (card.querySelector("input")) root.appendChild(card);
  }
}

function collect() {
  return collectFrom(cardsEl, state);
}

function collectFrom(root, parsed) {
  const next = parseProfile(serialize(parsed));
  const byName = new Map(next.sections.map((section) => [section.name, section]));
  for (const input of root.querySelectorAll("input, select")) {
    if (!input.dataset.sec) continue;
    const section = byName.get(input.dataset.sec);
    const values = section.fields[input.dataset.key];
    values[Number(input.dataset.index)] = storedValue(input.value, input.dataset.kind);
  }
  return next;
}

function toBase64(text) {
  const bytes = new TextEncoder().encode(text);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

async function writeFile(path, text) {
  if (!hasKsu) {
    const op = remoteOpForPath(path);
    if (!op) throw new Error("不支持写入");
    await apiPost(op, text);
    return;
  }
  const encoded = toBase64(text);
  const result = await exec("printf '%s' '" + encoded + "' | base64 -d > '" + path + ".tmp' && mv -f '" + path + ".tmp' '" + path + "'");
  if (result.errno !== 0) {
    throw new Error(result.stderr || "写入失败");
  }
}

async function readFile(path) {
  if (!hasKsu) {
    const op = remoteOpForPath(path);
    if (!op) throw new Error("不支持读取");
    return apiGet(op);
  }
  const result = await exec("cat '" + path + "'");
  if (result.errno !== 0) throw new Error(result.stderr || "读取失败");
  return result.stdout;
}

async function loadProfile(path) {
  const text = await readFile(path);
  state = parseProfile(text);
  render();
}

async function loadMode() {
  const text = (await readFile(dir + "/mode")).trim();
  const value = ["all", "normal", "scene", "off"].includes(text) ? text : "all";
  modeEl.value = value;
  appliedMode = value;
  showPanels();
}

let stockAssign = new Map();
let sceneFiles = [];
let sceneRows = [];
let activeScene = "";
let sceneIndex = new Map();
let savedAssign = new Map();
let appliedMode = "all";
let mapVariant = "cn";
let liveError = "";
let liveLoaded = false;
let liveStale = false;

const MAP_SRC = {
  cn: "/vendor/etc/thermal-map.conf",
  gl: "/odm/etc/thermal-map-gl.conf",
  ind: "/odm/etc/thermal-map-ind.conf",
};

const VARIANT_LABEL = { cn: "国行", gl: "国际", ind: "印度" };

const NORMAL_FILES = {
  "thermal-normal.conf": true,
  "thermal-gl-normal.conf": true,
  "thermal-ind-normal.conf": true,
};

const VARIANT_SH = "region=$(getprop ro.miui.region 2>/dev/null | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]'); hwc=$(getprop ro.boot.hwc 2>/dev/null | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]'); case \"$hwc\" in IN|INDIA|*INDIA*) echo ind; exit 0;; esac; case \"$region\" in IN|INDIA|*INDIA*) echo ind;; CN|\"\") case \"$hwc\" in \"\"|CN) echo cn;; *) echo gl;; esac;; *) echo gl;; esac";

function showPanels() {
  const scene = modeEl.value === "scene";
  document.getElementById("scenes").hidden = !scene;
  document.getElementById("cards").hidden = scene;
  document.getElementById("live").hidden = !scene;
  document.getElementById("all-note").hidden = modeEl.value !== "all";
  document.getElementById("defaults").textContent = scene ? "恢复原厂对应" : "载入初始值";
}

function parseMap(text) {
  const map = new Map();
  for (const line of text.split("\n")) {
    const match = line.match(/^\[(\d+):([^\]\s]+)\]/);
    if (match) map.set(match[1], match[2]);
    const plain = line.match(/^(\d+)\s+(\S+)/);
    if (plain) map.set(plain[1], plain[2]);
  }
  return map;
}

const SCENE_USE = {
  normal: "日常使用，没有游戏、相机、通话这些特殊前台",
  huanji: "换机或数据迁移",
  phone: "通话",
  nolimits: "无限制性能前台",
  noimits: "无限制性能前台",
  class0: "被系统划入 class0 的前台应用",
  arvr: "AR 或 VR",
  navigation: "导航",
  video: "视频播放",
  demo: "店内演示",
  videochatOrCamera: "视频通话",
  camera: "打开相机",
  "4K": "相机 4K 录像",
  "8K": "相机 8K 录像",
  game: "普通游戏",
  pubg: "和平精英一类游戏",
  game2: "第二类游戏",
  optiGame: "优化名单里的游戏",
  yuanshen: "原神一类游戏",
  LowTempCharge: "低温环境下充电",
  abnormal: "异常发热前台",
  youtube: "YouTube 播放",
  cgame: "悬浮窗里的游戏",
  cclassvideo: "悬浮窗里的视频",
  mgame: "游戏过程中通话",
};

function explainName(raw) {
  let name = raw.trim().replace(/\s+/g, "");
  const tags = [];
  if (/-demo$/.test(name)) {
    tags.push("演示机");
    name = name.replace(/-demo$/, "");
  }
  if (name.startsWith("iec-")) {
    tags.push("能效模式");
    name = name.slice(4);
  }
  if (name.startsWith("hp-")) {
    tags.push("高性能");
    name = name.slice(3);
  }
  if (name.startsWith("per-")) {
    tags.push("性能模式");
    name = name.slice(4);
  }
  if (name.startsWith("tgame")) {
    tags.push("性能模式");
    name = name.replace(/^tgame~?/, "");
    if (name.startsWith("per-")) name = name.slice(4);
    if (!name) name = "game";
  }
  let night = false;
  if (/nightVideo/i.test(name)) {
    night = true;
    name = name.replace(/~?nightVideo/ig, "").replace(/~$/, "");
  }
  const head = name.split("~")[0].split("+")[0];
  let use = SCENE_USE[head] || head;
  if (head === "camera" && night) use = "相机夜景录像";
  if (head === "class0" && /videochat/i.test(raw)) use = "class0 前台叠加视频通话";
  if (/phone\+tmgp/i.test(name) || head === "mgame") use = "游戏过程中通话";
  return (tags.length ? tags.join("、") + "下，" : "") + use;
}

function parseScenes(xml) {
  const grouped = new Map();
  const pattern = /<scenario id="(\d+)" defined="[^"]*" name="([^"]+)"/g;
  let match = pattern.exec(xml);
  while (match) {
    const id = match[1];
    const raw = match[2];
    if (!grouped.has(id)) grouped.set(id, { names: new Set(), hints: new Set(), raws: [] });
    grouped.get(id).names.add(raw.split("~")[0]);
    grouped.get(id).hints.add(explainName(raw));
    grouped.get(id).raws.push(raw);
    match = pattern.exec(xml);
  }
  return Array.from(grouped.entries())
    .map(([id, item]) => ({
      id,
      names: Array.from(item.names).sort(),
      hint: Array.from(item.hints).join("；"),
      demo: item.raws.length > 0 && item.raws.every((raw) => /-demo$/.test(raw.trim())),
    }))
    .sort((a, b) => Number(a.id) - Number(b.id));
}

let showDemo = false;

function sceneQuery() {
  return document.getElementById("scene-filter").value.trim().toLowerCase();
}

function sceneCardVisible(card) {
  if (card.dataset.demo === "1" && !showDemo) return false;
  const query = sceneQuery();
  if (!query) return true;
  const select = card.querySelector("select");
  const text = ((card.dataset.find || "") + " " + (select ? select.value : "")).toLowerCase();
  return text.includes(query);
}

function applySceneVisibility() {
  for (const card of document.querySelectorAll(".scene-card")) {
    card.hidden = !sceneCardVisible(card);
  }
}

function renderScenes() {
  const list = document.getElementById("scene-list");
  list.innerHTML = "";
  for (const row of sceneRows) {
    const chosen = row.chosen || stockAssign.get(row.id) || sceneFiles[0] || "";
    const card = document.createElement("article");
    card.className = "card scene-card";
    card.dataset.id = row.id;
    card.dataset.demo = row.demo ? "1" : "0";
    card.dataset.find = (row.id + " " + row.names.join(" ") + " " + (row.hint || "") + " " + chosen).toLowerCase();
    const heading = document.createElement("h2");
    heading.textContent = row.id + " " + (row.names[0] || "");
    card.appendChild(heading);
    if (row.names.length > 1) {
      const meta = document.createElement("div");
      meta.className = "device";
      meta.textContent = row.names.join("、");
      card.appendChild(meta);
    }
    if (row.hint) {
      const hint = document.createElement("p");
      hint.className = "hint";
      hint.textContent = row.hint;
      card.appendChild(hint);
    }
    const flag = document.createElement("div");
    flag.className = "live-flag";
    flag.textContent = "当前场景";
    flag.hidden = true;
    card.appendChild(flag);
    const select = document.createElement("select");
    select.dataset.id = row.id;
    for (const file of sceneFiles) {
      const option = document.createElement("option");
      option.value = file;
      option.textContent = file;
      if (file === chosen) option.selected = true;
      select.appendChild(option);
    }
    if (chosen && !sceneFiles.includes(chosen)) {
      const option = document.createElement("option");
      option.value = chosen;
      option.textContent = chosen;
      option.selected = true;
      select.appendChild(option);
    }
    card.appendChild(select);
    card.hidden = !sceneCardVisible(card);
    list.appendChild(card);
  }
  markActiveScene();
}

function markActiveScene() {
  for (const card of document.querySelectorAll(".scene-card")) {
    const live = card.dataset.id === activeScene;
    card.classList.toggle("live", live);
    const flag = card.querySelector(".live-flag");
    const select = card.querySelector("select");
    if (flag) flag.hidden = !live;
    if (select) select.classList.toggle("live-file", live);
  }
}

function cleanSceneId(text) {
  const line = String(text || "").replace(/^\uFEFF/, "").trim().split(/\r?\n/)[0].trim();
  return /^\d+$/.test(line) ? line : "";
}

async function readSconfig() {
  if (!hasKsu) return cleanSceneId(await apiGet("sconfig"));
  const now = await exec("cat /sys/class/thermal/thermal_message/sconfig");
  if (now.errno !== 0) return "";
  return cleanSceneId(now.stdout);
}

async function readVariant() {
  let variant = "cn";
  if (!hasKsu) {
    variant = (await apiGet("variant")).trim();
  } else {
    const result = await exec(VARIANT_SH);
    if (result.errno !== 0) throw new Error((result.stderr || "读取机型版本失败").trim());
    variant = result.stdout.trim();
  }
  if (variant !== "gl" && variant !== "ind") return "cn";
  if (!hasKsu) return variant;
  const check = await exec("[ -f '" + MAP_SRC[variant] + "' ]");
  if (check.errno !== 0) return "cn";
  return variant;
}

async function decryptStockMap() {
  if (!hasKsu) return apiGet("stockmap");
  const src = MAP_SRC[mapVariant] || MAP_SRC.cn;
  const out = dir + "/generated/stock-map.txt";
  const result = await exec("mkdir -p '" + dir + "/generated'; CLASSPATH='" + dir + "/thermal-crypt.dex' /system/bin/app_process /system/bin ThermalCrypt d '" + src + "' '" + out + "'");
  if (result.errno !== 0) throw new Error(result.stderr || "场景表解密失败");
  return readFile(out);
}

async function readSavedAssign() {
  try {
    if (hasKsu) {
      const saved = await exec("cat '" + dir + "/scene-map.txt'");
      if (saved.errno !== 0) return new Map();
      return parseMap(saved.stdout);
    }
    return parseMap(await apiGet("scenemap"));
  } catch (error) {
    return new Map();
  }
}

async function loadCatalog() {
  liveError = "";
  const problems = [];
  const xmlPromise = readFile("/odm/etc/setting.xml").then((xml) => parseScenes(xml)).catch((error) => {
    problems.push(error.message);
    return [];
  });
  const savedPromise = readSavedAssign();
  const scenePromise = readSconfig().then((now) => now).catch((error) => {
    problems.push(error.message);
    return "";
  });
  try {
    mapVariant = await readVariant();
  } catch (error) {
    mapVariant = "cn";
    problems.push(error.message);
  }
  try {
    stockAssign = parseMap(await decryptStockMap());
  } catch (error) {
    stockAssign = new Map();
    problems.push(error.message);
  }
  const rows = await xmlPromise;
  sceneIndex = new Map(rows.map((row) => [row.id, row]));
  savedAssign = await savedPromise;
  const now = await scenePromise;
  if (now) {
    activeScene = now;
    liveStale = false;
  } else {
    liveStale = true;
  }
  liveError = problems.filter(Boolean).join("；");
  liveLoaded = true;
}

async function loadSceneEditor() {
  if (sceneIndex.size === 0) throw new Error(liveError || "读取场景说明失败");
  if (stockAssign.size === 0) throw new Error(liveError || "场景表解密失败");
  const listed = hasKsu
    ? await exec("ls -1 /odm/etc/thermal-*.conf /vendor/etc/thermal-*.conf /data/vendor/thermal/config/thermal-*.conf 2>/dev/null")
    : { errno: 0, stdout: await apiGet("files") };
  if (!listed.stdout || !listed.stdout.trim()) throw new Error(listed.stderr || "读取温控文件失败");
  sceneFiles = Array.from(new Set(listed.stdout.split(/\r?\n/).map((line) => line.trim().split("/").pop()).filter((name) => confNameOk(name) && name.indexOf("map") < 0))).sort();
  sceneRows = Array.from(sceneIndex.values()).map((row) => ({
    id: row.id,
    names: row.names,
    hint: row.hint,
    demo: row.demo,
    chosen: savedAssign.get(row.id) || stockAssign.get(row.id) || "",
  }));
  renderScenes();
}

function variantLabel() {
  return VARIANT_LABEL[mapVariant] || "国行";
}

function stockMiss(stock) {
  if (stock) return null;
  if (stockAssign.size === 0 && liveError) return { file: "", note: liveError };
  return { file: "", note: variantLabel() + "场景表里没有这个场景" };
}

function resolveActive(sceneId) {
  if (appliedMode === "all") {
    return { file: "thermal-normal.conf", note: "所有场景都在用自定义曲线" };
  }
  if (!sceneId) return { file: "", note: liveError || "还没读到场景号" };
  const stock = stockAssign.get(sceneId) || "";
  if (appliedMode === "scene") {
    const chosen = savedAssign.get(sceneId) || "";
    if (chosen) return { file: chosen, note: "动态场景里为这个场景保存的文件" };
    const missing = stockMiss(stock);
    if (missing) return missing;
    return { file: stock, note: "还没单独指定，沿用" + variantLabel() + "原厂对应" };
  }
  const missing = stockMiss(stock);
  if (missing) return missing;
  if (appliedMode === "off") {
    return { file: stock, note: "覆盖已关闭，这是" + variantLabel() + "原厂文件" };
  }
  if (NORMAL_FILES[stock]) return { file: stock, note: "仅普通场景：这份文件已换成自定义曲线" };
  return { file: stock, note: "仅普通场景：这个场景仍用原厂文件" };
}

function pendingSceneFile(sceneId) {
  if (appliedMode !== "scene" || modeEl.value !== "scene" || !sceneId) return "";
  for (const select of document.querySelectorAll("#scene-list select")) {
    if (select.dataset.id === sceneId) return select.value;
  }
  return "";
}

function renderLive() {
  const bar = document.getElementById("live");
  const sceneEl = document.getElementById("live-scene");
  const hintEl = document.getElementById("live-hint");
  const fileEl = document.getElementById("live-file");
  const noteEl = document.getElementById("live-note");
  bar.classList.toggle("wait", !liveLoaded);
  bar.classList.toggle("stale", liveStale);
  if (!activeScene) {
    sceneEl.textContent = liveLoaded ? "读不到" : "读取中";
    hintEl.textContent = "";
  } else {
    const row = sceneIndex.get(activeScene);
    sceneEl.textContent = row && row.names[0] ? activeScene + " " + row.names[0] : activeScene;
    hintEl.textContent = row ? (row.names.length > 1 ? row.names.join("、") : (row.hint.split("；")[0] || "")) : "";
  }
  if (!liveLoaded) {
    fileEl.textContent = "读取中";
    noteEl.textContent = "";
    return;
  }
  const resolved = resolveActive(activeScene);
  fileEl.textContent = resolved.file || "没有对应文件";
  let note = resolved.note;
  if (modeEl.value !== appliedMode) {
    note = note ? note + "。页面上的作用范围还没应用" : "页面上的作用范围还没应用";
  }
  const pending = pendingSceneFile(activeScene);
  if (pending && pending !== resolved.file) {
    note = note ? note + "。列表里的选择还没应用" : "列表里的选择还没应用";
  }
  noteEl.textContent = note;
}

async function refresh() {
  setBusy(true);
  setStatus("正在读取策略");
  try {
    dir = moduleDir();
    await loadFreqs();
    await loadMode();
    await loadCatalog();
    if (modeEl.value === "scene") {
      await loadSceneEditor();
      setStatus("已读取 " + sceneRows.length + " 个场景");
    } else {
      await loadProfile(dir + "/profile.conf");
      setStatus("已读取 " + state.sections.length + " 段");
    }
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    renderLive();
    setBusy(false);
  }
}

async function saveCurves() {
  const parsed = collect();
  await writeFile(dir + "/profile.conf", serialize(parsed));
  state = parsed;
}

async function saveScenes() {
  const lines = [];
  for (const select of document.querySelectorAll("#scene-list select")) {
    if (select.value) lines.push(select.dataset.id + " " + select.value);
  }
  if (!lines.length) throw new Error("没有可写入的场景");
  await writeFile(dir + "/scene-map.txt", lines.join("\n") + "\n");
}

async function save() {
  setBusy(true);
  setStatus("正在保存并重启温控");
  try {
    if (modeEl.value === "scene") await saveScenes();
    else await saveCurves();
    await writeFile(dir + "/mode", modeEl.value + "\n");
    if (!hasKsu) {
      const log = await apiPost("apply", "");
      if (!log.includes("apply done") && !log.includes("overlays removed")) {
        throw new Error(log.trim() || "应用失败");
      }
    } else {
      const result = await exec("sh '" + dir + "/apply.sh'");
      if (result.errno !== 0) {
        const log = await exec("tail -n 8 /data/adb/thermal-local.log");
        throw new Error((log.stdout || result.stderr || "应用失败").trim());
      }
    }
    appliedMode = modeEl.value;
    if (modeEl.value === "scene") {
      savedAssign = new Map();
      for (const select of document.querySelectorAll("#scene-list select")) {
        if (select.value) savedAssign.set(select.dataset.id, select.value);
      }
    }
    const now = await readSconfig();
    if (now) {
      activeScene = now;
      liveStale = false;
    }
    markActiveScene();
    renderLive();
    setStatus("已应用，温控进程已重启");
    toast("温控策略已应用");
  } catch (error) {
    setStatus(error.message, true);
    toast(error.message);
  } finally {
    setBusy(false);
  }
}

document.querySelector("#reload").addEventListener("click", refresh);
document.querySelector("#defaults").addEventListener("click", async () => {
  setBusy(true);
  try {
    if (modeEl.value === "scene") {
      sceneRows = sceneRows.map((row) => ({ ...row, chosen: stockAssign.get(row.id) || "" }));
      renderScenes();
      renderLive();
      setStatus("已恢复原厂文件对应，还没有写入手机");
    } else {
      await loadProfile(dir + "/profile.default");
      setStatus("已载入初始值，还没有写入手机");
    }
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    setBusy(false);
  }
});
modeEl.addEventListener("change", async () => {
  showPanels();
  renderLive();
  if (modeEl.value === "scene") {
    if (sceneRows.length > 0) return;
    setBusy(true);
    try {
      if (sceneIndex.size === 0 || stockAssign.size === 0) await loadCatalog();
      await loadSceneEditor();
      setStatus("已读取 " + sceneRows.length + " 个场景");
    } catch (error) {
      setStatus(error.message, true);
    } finally {
      renderLive();
      setBusy(false);
    }
    return;
  }
  if (state) return;
  setBusy(true);
  try {
    await loadProfile(dir + "/profile.conf");
    setStatus("已读取 " + state.sections.length + " 段");
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    setBusy(false);
  }
});
document.getElementById("scene-list").addEventListener("change", () => {
  renderLive();
});
document.getElementById("demo-toggle").addEventListener("click", () => {
  showDemo = !showDemo;
  const button = document.getElementById("demo-toggle");
  button.textContent = showDemo ? "隐藏演示机配置" : "显示演示机配置";
  applySceneVisibility();
});
document.getElementById("scene-filter").addEventListener("input", () => {
  applySceneVisibility();
});
let pollBusy = false;
setInterval(async () => {
  if (pollBusy) return;
  pollBusy = true;
  try {
    const next = await readSconfig();
    if (next) {
      liveStale = false;
      if (next !== activeScene) {
        activeScene = next;
        markActiveScene();
      }
    } else {
      liveStale = true;
    }
    renderLive();
  } catch (error) {
    liveStale = true;
    renderLive();
  } finally {
    pollBusy = false;
  }
}, 500);

async function refreshRemote() {
  const button = document.getElementById("remote-toggle");
  const url = document.getElementById("remote-url");
  if (!hasKsu) {
    button.textContent = "关闭远程访问";
    url.textContent = location.origin + "/";
    return;
  }
  const result = await exec("sh '" + dir + "/remote.sh' status");
  const text = result.errno === 0 ? result.stdout.trim() : "stopped";
  const lines = text.split("\n").map((line) => line.trim()).filter(Boolean);
  const on = lines[0] === "running";
  button.textContent = on ? "关闭远程访问" : "开启远程访问";
  url.textContent = on ? lines.slice(1).join("\n") : "关闭时只有手机上的 KernelSU 能打开这个页面";
}

document.getElementById("remote-toggle").addEventListener("click", async () => {
  const button = document.getElementById("remote-toggle");
  button.disabled = true;
  try {
    if (!hasKsu) {
      await apiPost("stop", "");
      setStatus("远程访问已关闭");
      document.getElementById("remote-url").textContent = "已关闭";
      return;
    }
    const action = button.textContent.indexOf("关闭") === 0 ? "stop" : "start";
    const result = await exec("sh '" + dir + "/remote.sh' " + action);
    if (result.errno !== 0) throw new Error((result.stderr || result.stdout || "远程访问切换失败").trim());
    await refreshRemote();
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    button.disabled = false;
  }
});

function startLinkProbe() {
  const box = document.getElementById("link");
  const text = document.getElementById("link-text");
  if (hasKsu) return;
  box.hidden = false;
  let probing = false;
  const tick = async () => {
    if (probing) return;
    probing = true;
    const started = performance.now();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 2000);
    try {
      const response = await fetch("/cgi-bin/api?op=ping&t=" + Date.now(), {
        cache: "no-store",
        signal: controller.signal,
      });
      const body = await response.text();
      if (!response.ok || body.trim() !== "ok") throw new Error("bad");
      const ms = Math.max(0, Math.round(performance.now() - started));
      box.className = "link ok";
      text.textContent = "连接正常 " + ms + " ms";
    } catch (error) {
      box.className = "link bad";
      text.textContent = "连接中断";
    } finally {
      clearTimeout(timer);
      probing = false;
    }
  };
  tick();
  setInterval(tick, 1000);
}

let fileNames = [];
let editedNames = new Set();
let stockNames = new Set();
let filesLoaded = false;
let editorName = "";
let editorParsed = null;
let editorRaw = false;

function confNameOk(name) {
  return /^thermal-[A-Za-z0-9._-]+\.conf$/.test(name) && name.indexOf("..") < 0;
}

const openBoost = mountBoost({
  hasKsu,
  exec,
  apiGet,
  apiPost,
  writeFile,
  toast,
  dir: () => dir,
  freqs: (device) => freqTables[device] || [],
});

function showPage(page) {
  document.getElementById("page-control").hidden = page !== "control";
  document.getElementById("page-files").hidden = page !== "files";
  document.getElementById("page-boost").hidden = page !== "boost";
  document.getElementById("control-head").hidden = page !== "control";
  document.getElementById("boost-bar").hidden = page !== "boost";
  document.getElementById("tab-control").classList.toggle("on", page === "control");
  document.getElementById("tab-files").classList.toggle("on", page === "files");
  document.getElementById("tab-boost").classList.toggle("on", page === "boost");
  if (page === "files" && !filesLoaded) loadFileList();
  if (page === "boost") openBoost();
}

async function listConfText() {
  if (!hasKsu) return apiGet("files");
  const result = await exec("ls -1 /odm/etc/thermal-*.conf /vendor/etc/thermal-*.conf /data/vendor/thermal/config/thermal-*.conf 2>/dev/null");
  return result.stdout || "";
}

async function stockConfText() {
  if (!hasKsu) {
    try {
      return await apiGet("stockfiles");
    } catch (error) {
      return "";
    }
  }
  const result = await exec("ls -1 /odm/etc/thermal-*.conf /vendor/etc/thermal-*.conf 2>/dev/null");
  return result.stdout || "";
}

function confNamesIn(text) {
  return text.split(/\r?\n/).map((line) => line.trim().split("/").pop()).filter(confNameOk);
}

async function editedConfText() {
  if (!hasKsu) {
    try {
      return await apiGet("edited");
    } catch (error) {
      return "";
    }
  }
  const result = await exec("sh '" + dir + "/conf.sh' list");
  return result.errno === 0 ? result.stdout : "";
}

function renderFileList() {
  const list = document.getElementById("file-list");
  const template = document.getElementById("file-template");
  const selected = template.value;
  const query = document.getElementById("file-filter").value.trim().toLowerCase();
  template.innerHTML = "";
  for (const name of fileNames) {
    const option = document.createElement("option");
    option.value = name;
    option.textContent = name;
    if (name === selected) option.selected = true;
    template.appendChild(option);
  }
  list.innerHTML = "";
  const names = fileNames.filter((name) => !query || name.toLowerCase().indexOf(query) >= 0);
  if (!names.length) {
    const empty = document.createElement("p");
    empty.className = "note";
    empty.textContent = fileNames.length ? "没有匹配的温控文件" : "没有读到温控文件";
    list.appendChild(empty);
    return;
  }
  for (const name of names) {
    const row = document.createElement("div");
    row.className = "file-row";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "file-open";
    let mark = "";
    if (editedNames.has(name) && stockNames.has(name)) mark = "  已覆盖";
    else if (editedNames.has(name)) mark = "  新建";
    button.textContent = name + mark;
    button.addEventListener("click", () => openEditor(name));
    row.appendChild(button);
    if (editedNames.has(name)) {
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "file-delete";
      remove.textContent = "删除";
      remove.addEventListener("click", () => deleteConf(name));
      row.appendChild(remove);
    }
    list.appendChild(row);
  }
}

function normalizeConfName(raw) {
  let name = raw.trim().replace(/\s+/g, "");
  if (!name) throw new Error("先写一个名字");
  if (!name.endsWith(".conf")) name += ".conf";
  if (name.indexOf("thermal-") !== 0) name = "thermal-" + name;
  if (!confNameOk(name)) throw new Error("名字只能用字母、数字、点、下划线和横线");
  if (fileNames.indexOf(name) >= 0) throw new Error("已经有这个文件");
  return name;
}

function rememberSceneChoices() {
  for (const select of document.querySelectorAll("#scene-list select")) {
    for (const row of sceneRows) {
      if (row.id === select.dataset.id) row.chosen = select.value;
    }
  }
}

function adoptConfName(name) {
  if (sceneFiles.indexOf(name) < 0) {
    sceneFiles.push(name);
    sceneFiles.sort();
  }
  if (!sceneRows.length) return;
  rememberSceneChoices();
  renderScenes();
}

async function dropConfFromScenes(name) {
  sceneFiles = sceneFiles.filter((item) => item !== name);
  for (const row of sceneRows) {
    if (row.chosen === name) row.chosen = stockAssign.get(row.id) || "";
  }
  let text = "";
  if (hasKsu) {
    const saved = await exec("cat '" + dir + "/scene-map.txt'");
    text = saved.errno === 0 ? saved.stdout : "";
  } else {
    try {
      text = await apiGet("scenemap");
    } catch (error) {
      text = "";
    }
  }
  const lines = [];
  let changed = false;
  for (const line of text.split(/\r?\n/)) {
    const match = line.match(/^(\d+)\s+(\S+)/);
    if (!match) continue;
    if (match[2] === name) {
      changed = true;
      const fallback = stockAssign.get(match[1]) || "";
      savedAssign.set(match[1], fallback);
      if (fallback && fallback !== name) lines.push(match[1] + " " + fallback);
      continue;
    }
    lines.push(match[1] + " " + match[2]);
    savedAssign.set(match[1], match[2]);
  }
  if (changed) {
    await writeFile(dir + "/scene-map.txt", (lines.length ? lines.join("\n") + "\n" : ""));
    if (appliedMode === "scene") {
      if (!hasKsu) await apiPost("apply", "");
      else {
        const result = await exec("sh '" + dir + "/apply.sh'");
        if (result.errno !== 0) throw new Error((result.stderr || result.stdout || "场景表更新失败").trim());
      }
    }
  }
  if (sceneRows.length) renderScenes();
}

async function loadFileList() {
  const list = document.getElementById("file-list");
  list.textContent = "正在读取温控文件";
  try {
    const listed = await listConfText();
    const edited = await editedConfText();
    const stock = await stockConfText();
    fileNames = Array.from(new Set(confNamesIn(listed))).sort();
    editedNames = new Set(confNamesIn(edited));
    stockNames = new Set(confNamesIn(stock));
    filesLoaded = true;
    renderFileList();
  } catch (error) {
    filesLoaded = false;
    list.textContent = error.message;
  }
}

async function readConf(name) {
  if (!confNameOk(name)) throw new Error("文件名不对");
  if (!hasKsu) return apiGet("readconf", { name: name });
  const result = await exec("sh '" + dir + "/conf.sh' read '" + name + "'");
  if (result.errno !== 0) throw new Error((result.stderr || result.stdout || "读取失败").trim());
  return result.stdout;
}

async function deleteConfFile(name) {
  if (!confNameOk(name)) throw new Error("文件名不对");
  if (!hasKsu) {
    const log = await apiPost("dropconf", "", { name: name });
    return log.trim();
  }
  const result = await exec("sh '" + dir + "/conf.sh' delete '" + name + "'");
  if (result.errno !== 0) throw new Error((result.stderr || result.stdout || "删除失败").trim());
  return result.stdout.trim();
}

async function createConf() {
  const status = document.getElementById("file-create-status");
  const button = document.getElementById("file-create");
  button.disabled = true;
  status.textContent = "正在按模板新建";
  try {
    const template = document.getElementById("file-template").value;
    if (!template) throw new Error("先选一个模板");
    const name = normalizeConfName(document.getElementById("file-new-name").value);
    const text = await readConf(template);
    await writeConf(name, text);
    fileNames.push(name);
    fileNames.sort();
    editedNames.add(name);
    adoptConfName(name);
    document.getElementById("file-new-name").value = "";
    renderFileList();
    status.textContent = "已新建 " + name + "，控制页的动态场景里可以选它";
  } catch (error) {
    status.textContent = error.message;
  } finally {
    button.disabled = false;
  }
}

async function deleteConf(name) {
  const kind = "删除 " + name + " 的覆盖？新建文件会从列表和场景选择里去掉，原厂文件会恢复成系统自带的那份。";
  if (!window.confirm(kind)) return;
  const status = document.getElementById("file-create-status");
  status.textContent = "正在删除 " + name;
  try {
    const result = await deleteConfFile(name);
    editedNames.delete(name);
    if (result.indexOf("deleted") >= 0) {
      fileNames = fileNames.filter((item) => item !== name);
      await dropConfFromScenes(name);
    } else if (sceneRows.length) {
      rememberSceneChoices();
      renderScenes();
    }
    if (editorName === name) closeEditor();
    renderFileList();
    status.textContent = result.indexOf("deleted") >= 0 ? "已删除 " + name : "已恢复原厂 " + name;
  } catch (error) {
    status.textContent = error.message;
  }
}

async function writeConf(name, text) {
  if (!confNameOk(name)) throw new Error("文件名不对");
  if (!hasKsu) {
    const log = await apiPost("writeconf", text, { name: name });
    if (log.indexOf("saved") < 0) throw new Error(log.trim() || "保存失败");
    return;
  }
  await writeFile("/data/local/tmp/thermal-conf-incoming", text);
  const result = await exec("sh '" + dir + "/conf.sh' write '" + name + "' < /data/local/tmp/thermal-conf-incoming");
  if (result.errno !== 0 || result.stdout.indexOf("saved") < 0) {
    throw new Error((result.stderr || result.stdout || "保存失败").trim());
  }
}

function closeEditor() {
  document.getElementById("editor").hidden = true;
  document.getElementById("editor-body").innerHTML = "";
  editorName = "";
  editorParsed = null;
  editorRaw = false;
}

async function openEditor(name) {
  const modal = document.getElementById("editor");
  const body = document.getElementById("editor-body");
  const status = document.getElementById("editor-status");
  document.getElementById("editor-title").textContent = name;
  document.getElementById("editor-save").disabled = true;
  body.innerHTML = "";
  status.textContent = "正在读取";
  modal.hidden = false;
  editorName = name;
  try {
    await loadFreqs();
    const text = await readConf(name);
    if (editorName !== name) return;
    editorParsed = parseProfile(text);
    fillProfile(body, editorParsed);
    editorRaw = !body.querySelector("input");
    if (editorRaw) {
      const area = document.createElement("textarea");
      area.className = "raw-editor";
      area.value = text;
      body.appendChild(area);
    }
    status.textContent = "温度按 °C 显示。保存会加密写入温控目录，并重启温控进程。";
    document.getElementById("editor-save").disabled = false;
  } catch (error) {
    status.textContent = error.message;
  }
}

async function saveEditor() {
  const button = document.getElementById("editor-save");
  const status = document.getElementById("editor-status");
  const name = editorName;
  if (!name) return;
  button.disabled = true;
  status.textContent = "正在保存并重启温控";
  try {
    let text = "";
    if (editorRaw) {
      const area = document.getElementById("editor-body").querySelector("textarea");
      text = area ? area.value : "";
    } else {
      editorParsed = collectFrom(document.getElementById("editor-body"), editorParsed);
      text = serialize(editorParsed);
    }
    await writeConf(name, text);
    editedNames.add(name);
    renderFileList();
    status.textContent = "已保存，温控进程已重启";
  } catch (error) {
    status.textContent = error.message;
  } finally {
    button.disabled = false;
  }
}

document.getElementById("tab-control").addEventListener("click", () => showPage("control"));
document.getElementById("tab-files").addEventListener("click", () => showPage("files"));
document.getElementById("tab-boost").addEventListener("click", () => showPage("boost"));
document.getElementById("file-filter").addEventListener("input", () => {
  if (filesLoaded) renderFileList();
});
document.getElementById("file-create").addEventListener("click", createConf);
document.getElementById("editor-close").addEventListener("click", closeEditor);
document.getElementById("editor").addEventListener("click", (event) => {
  if (event.target.id === "editor") closeEditor();
});
document.getElementById("editor-save").addEventListener("click", saveEditor);
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !document.getElementById("editor").hidden) closeEditor();
});

saveEl.addEventListener("click", save);
refresh().then(refreshRemote);
startLinkProbe();
