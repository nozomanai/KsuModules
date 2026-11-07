import { exec, toast, moduleInfo } from "./kernelsu.js";

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
  if (key === "target" && kind === "hz") return "频率 Hz";
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

  for (const section of state.sections) {
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
          const input = document.createElement("input");
          input.inputMode = valueKind === "temp" ? "decimal" : "numeric";
          input.value = displayValue(section.fields[key][i] || "", valueKind);
          input.dataset.sec = section.name;
          input.dataset.key = key;
          input.dataset.index = String(i);
          input.dataset.kind = valueKind;
          wrap.appendChild(caption);
          wrap.appendChild(input);
          row.appendChild(wrap);
        }
        list.appendChild(row);
      }
      card.appendChild(list);
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

    if (card.querySelector("input")) cardsEl.appendChild(card);
  }
}

function collect() {
  const next = parseProfile(serialize(state));
  const byName = new Map(next.sections.map((section) => [section.name, section]));
  for (const input of cardsEl.querySelectorAll("input")) {
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
  const encoded = toBase64(text);
  const result = await exec("printf '%s' '" + encoded + "' | base64 -d > '" + path + ".tmp' && mv -f '" + path + ".tmp' '" + path + "'");
  if (result.errno !== 0) {
    throw new Error(result.stderr || "写入失败");
  }
}

async function readFile(path) {
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
  modeEl.value = ["all", "normal", "scene", "off"].includes(text) ? text : "all";
  showPanels();
}

let stockAssign = new Map();
let sceneFiles = [];
let sceneRows = [];
let activeScene = "";

function showPanels() {
  const scene = modeEl.value === "scene";
  document.getElementById("scenes").hidden = !scene;
  document.getElementById("cards").hidden = scene;
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
    if (!grouped.has(id)) grouped.set(id, { names: new Set(), hints: new Set() });
    grouped.get(id).names.add(raw.split("~")[0]);
    grouped.get(id).hints.add(explainName(raw));
    match = pattern.exec(xml);
  }
  return Array.from(grouped.entries())
    .map(([id, item]) => ({
      id,
      names: Array.from(item.names).sort(),
      hint: Array.from(item.hints).join("；"),
    }))
    .sort((a, b) => Number(a.id) - Number(b.id));
}

function renderScenes() {
  const list = document.getElementById("scene-list");
  const query = document.getElementById("scene-filter").value.trim().toLowerCase();
  list.innerHTML = "";
  for (const row of sceneRows) {
    const chosen = row.chosen || stockAssign.get(row.id) || sceneFiles[0] || "";
    const blob = (row.id + " " + row.names.join(" ") + " " + (row.hint || "") + " " + chosen).toLowerCase();
    const card = document.createElement("article");
    card.className = "card scene-card";
    card.dataset.id = row.id;
    if (query && !blob.includes(query)) card.hidden = true;
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

async function decryptStockMap() {
  const out = dir + "/generated/stock-map.txt";
  const result = await exec("mkdir -p '" + dir + "/generated'; CLASSPATH='" + dir + "/thermal-crypt.dex' /system/bin/app_process /system/bin ThermalCrypt d /vendor/etc/thermal-map.conf '" + out + "'");
  if (result.errno !== 0) throw new Error(result.stderr || "场景表解密失败");
  return readFile(out);
}

async function loadScenes() {
  const xml = await readFile("/odm/etc/setting.xml");
  const listed = await exec("ls -1 /odm/etc/thermal-*.conf /vendor/etc/thermal-*.conf");
  if (listed.errno !== 0) throw new Error(listed.stderr || "读取温控文件失败");
  sceneFiles = Array.from(new Set(listed.stdout.split("\n").map((line) => line.trim().split("/").pop()).filter((name) => name && !name.includes("map")))).sort();
  stockAssign = parseMap(await decryptStockMap());
  let chosen = new Map();
  const saved = await exec("cat '" + dir + "/scene-map.txt'");
  if (saved.errno === 0) chosen = parseMap(saved.stdout);
  sceneRows = parseScenes(xml).map((row) => ({
    id: row.id,
    names: row.names,
    hint: row.hint,
    chosen: chosen.get(row.id) || stockAssign.get(row.id) || "",
  }));
  const now = await exec("cat /sys/class/thermal/thermal_message/sconfig");
  activeScene = now.errno === 0 ? now.stdout.trim() : "";
  renderScenes();
}

async function refresh() {
  setBusy(true);
  setStatus("正在读取策略");
  try {
    dir = moduleDir();
    await loadMode();
    if (modeEl.value === "scene") {
      await loadScenes();
      setStatus("已读取 " + sceneRows.length + " 个场景，当前场景号 " + (activeScene || "未知"));
    } else {
      await loadProfile(dir + "/profile.conf");
      setStatus("已读取 " + state.sections.length + " 段");
    }
  } catch (error) {
    setStatus(error.message, true);
  } finally {
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
    const result = await exec("sh '" + dir + "/apply.sh'");
    if (result.errno !== 0) {
      const log = await exec("tail -n 8 /data/adb/thermal-local.log");
      throw new Error((log.stdout || result.stderr || "应用失败").trim());
    }
    if (modeEl.value === "scene") {
      const now = await exec("cat /sys/class/thermal/thermal_message/sconfig");
      activeScene = now.errno === 0 ? now.stdout.trim() : activeScene;
      markActiveScene();
    }
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
  if (modeEl.value === "scene" && sceneRows.length === 0) {
    setBusy(true);
    try {
      await loadScenes();
      setStatus("已读取 " + sceneRows.length + " 个场景，当前场景号 " + (activeScene || "未知"));
    } catch (error) {
      setStatus(error.message, true);
    } finally {
      setBusy(false);
    }
  }
});
document.getElementById("scene-filter").addEventListener("input", () => {
  const query = document.getElementById("scene-filter").value.trim().toLowerCase();
  for (const card of document.querySelectorAll(".scene-card")) {
    const select = card.querySelector("select");
    const text = (card.dataset.id + " " + card.innerText + " " + (select ? select.value : "")).toLowerCase();
    card.hidden = query !== "" && !text.includes(query);
  }
});
setInterval(async () => {
  if (modeEl.value !== "scene" || document.getElementById("scenes").hidden) return;
  const now = await exec("cat /sys/class/thermal/thermal_message/sconfig");
  if (now.errno !== 0) return;
  const next = now.stdout.trim();
  if (next === activeScene) return;
  activeScene = next;
  markActiveScene();
  setStatus("已读取 " + sceneRows.length + " 个场景，当前场景号 " + activeScene);
}, 2000);
saveEl.addEventListener("click", save);
refresh();
