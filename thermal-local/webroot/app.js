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
  modeEl.value = ["all", "normal", "off"].includes(text) ? text : "all";
}

async function refresh() {
  setBusy(true);
  setStatus("正在读取策略");
  try {
    dir = moduleDir();
    await loadMode();
    await loadProfile(dir + "/profile.conf");
    setStatus("已读取 " + state.sections.length + " 段");
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    setBusy(false);
  }
}

async function save() {
  setBusy(true);
  setStatus("正在保存并重启温控");
  try {
    const parsed = collect();
    await writeFile(dir + "/profile.conf", serialize(parsed));
    await writeFile(dir + "/mode", modeEl.value + "\n");
    const result = await exec("sh '" + dir + "/apply.sh'");
    if (result.errno !== 0) {
      const log = await exec("tail -n 8 /data/adb/thermal-local.log");
      throw new Error((log.stdout || result.stderr || "应用失败").trim());
    }
    state = parsed;
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
    await loadProfile(dir + "/profile.default");
    setStatus("已载入初始值，还没有写入手机");
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    setBusy(false);
  }
});
saveEl.addEventListener("click", save);
refresh();
