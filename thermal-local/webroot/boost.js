const BOOST_MODES = ["性能", "均衡", "省电"];

function boostName(hint, scence) {
  if (hint === "4224" && scence === "2") return "折叠屏滑动";
  if (hint === "4224" && scence === "1") return "滑动 · 场景1";
  if (hint === "4224") return "滑动";
  if (hint === "4225") return "启动";
  if (hint === "4226") return "启动 · 4226";
  if (hint === "4227") return "动画";
  if (hint === "4512" && scence === "2") return "解锁";
  if (hint === "4512" && scence === "11") return "上滑";
  if (hint === "4512") return "解锁上滑";
  return "Hint " + hint + " · 场景" + scence;
}

function displayTemp(raw) {
  const value = Number(raw);
  if (!Number.isFinite(value)) return "";
  const shown = Math.round(value / 10) / 100;
  return String(shown);
}

function storeTemp(text) {
  const value = Number(String(text).trim());
  if (!Number.isFinite(value)) throw new Error("温度要是数字");
  return String(Math.round(value * 1000));
}

function storeHz(text) {
  const trimmed = String(text).trim();
  if (!/^\d+$/.test(trimmed) || Number(trimmed) <= 0) throw new Error("频率要是正整数，单位 Hz");
  return trimmed;
}

function setCpuFreq(raw, cpu, hz) {
  const pattern = new RegExp(cpu + ":\\d+");
  if (pattern.test(raw)) return raw.replace(pattern, cpu + ":" + hz);
  return (raw ? raw.replace(/\s+$/, "") + " " : "") + cpu + ":" + hz;
}

function parseBoost(text) {
  const doc = new DOMParser().parseFromString(text, "text/xml");
  if (doc.querySelector("parsererror")) throw new Error("升频表解析失败");
  const configs = Array.from(doc.querySelectorAll("Config")).map((node) => ({
    hint: node.getAttribute("Hint") || "",
    scence: node.getAttribute("Scence") || "0",
    levels: Array.from(node.children).filter((child) => child.tagName === "Child").map((child) => {
      const templates = [0, 1, 2].map((index) => child.getAttribute("Target" + index) || "");
      return {
        trigC: displayTemp(child.getAttribute("Trig") || ""),
        templates,
        freqs: templates.map((template) => ({
          cpu0: ((template.match(/cpu0:(\d+)/) || [])[1]) || "",
          cpu6: ((template.match(/cpu6:(\d+)/) || [])[1]) || "",
        })),
      };
    }),
  }));
  if (!configs.length) throw new Error("升频表是空的");
  return configs;
}

function serializeBoost(configs) {
  const lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    "<BoostConfigs>",
    "  <PerfBreak>",
  ];
  for (const config of configs) {
    lines.push('    <Config Hint="' + config.hint + '" Scence="' + config.scence + '">');
    for (const level of config.levels) {
      const targets = level.templates.map((template, index) => {
        const freq = level.freqs[index];
        return setCpuFreq(setCpuFreq(template, "cpu0", storeHz(freq.cpu0)), "cpu6", storeHz(freq.cpu6));
      });
      lines.push(
        '      <Child Trig="' + storeTemp(level.trigC) + '"'
        + ' Target0="' + targets[0] + '"'
        + ' Target1="' + targets[1] + '"'
        + ' Target2="' + targets[2] + '"/>'
      );
    }
    lines.push("    </Config>");
  }
  lines.push("  </PerfBreak>", "</BoostConfigs>", "");
  return lines.join("\n");
}

function freqChoices(table, current) {
  const values = (table || []).map((item) => String(item));
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

function field(label, value, dataset, table) {
  const wrap = document.createElement("label");
  wrap.className = "field";
  const caption = document.createElement("span");
  caption.textContent = label;
  const control = table ? document.createElement("select") : document.createElement("input");
  if (table) {
    for (const hz of freqChoices(table, value)) {
      const option = document.createElement("option");
      option.value = hz;
      option.textContent = freqOptionLabel(hz);
      control.appendChild(option);
    }
    control.value = String(value || "");
  } else {
    control.inputMode = "decimal";
    control.value = value;
  }
  Object.keys(dataset).forEach((key) => {
    control.dataset[key] = dataset[key];
  });
  wrap.appendChild(caption);
  wrap.appendChild(control);
  return wrap;
}

export function mountBoost(api) {
  let configs = [];
  let loaded = false;
  const status = document.getElementById("boost-status");
  const cards = document.getElementById("boost-cards");
  const power = document.getElementById("boost-power");
  const buttons = ["boost-reload", "boost-defaults", "boost-save"].map((id) => document.getElementById(id));

  function setStatus(text, bad) {
    status.textContent = text;
    status.style.color = bad ? "var(--danger)" : "var(--muted)";
  }

  function setBusy(busy) {
    buttons.forEach((button) => {
      button.disabled = busy;
    });
  }

  function render() {
    const mode = Number(power.value);
    cards.innerHTML = "";
    configs.forEach((config, configIndex) => {
      const card = document.createElement("section");
      card.className = "card";
      const heading = document.createElement("h2");
      heading.textContent = boostName(config.hint, config.scence);
      const meta = document.createElement("div");
      meta.className = "device";
      meta.textContent = "Hint " + config.hint + " · 场景 " + config.scence + " · 正在改" + BOOST_MODES[mode];
      card.appendChild(heading);
      card.appendChild(meta);
      const list = document.createElement("div");
      list.className = "levels";
      config.levels.forEach((level, levelIndex) => {
        const row = document.createElement("div");
        row.className = "level";
        const index = document.createElement("div");
        index.className = "index";
        index.textContent = String(levelIndex + 1);
        row.appendChild(index);
        row.appendChild(field("触发 °C", level.trigC, { config: String(configIndex), level: String(levelIndex), field: "trig" }));
        row.appendChild(field("小核", level.freqs[mode].cpu0, { config: String(configIndex), level: String(levelIndex), field: "cpu0", mode: String(mode) }, api.freqs("cpu0")));
        row.appendChild(field("大核", level.freqs[mode].cpu6, { config: String(configIndex), level: String(levelIndex), field: "cpu6", mode: String(mode) }, api.freqs("cpu6")));
        list.appendChild(row);
      });
      card.appendChild(list);
      cards.appendChild(card);
    });
  }

  function remember(event) {
    const input = event.target;
    if (!input.dataset || input.dataset.config == null) return;
    const level = configs[Number(input.dataset.config)].levels[Number(input.dataset.level)];
    if (input.dataset.field === "trig") level.trigC = input.value;
    else level.freqs[Number(input.dataset.mode)][input.dataset.field] = input.value;
  }
  cards.addEventListener("input", remember);
  cards.addEventListener("change", remember);
  power.addEventListener("change", render);

  async function readText(kind) {
    const name = kind === "default" ? "boost.default.xml" : "boost.xml";
    if (!api.hasKsu) {
      try {
        return await api.apiGet(kind === "default" ? "boostdefault" : "boost");
      } catch (error) {
        if (kind === "default") throw error;
        return api.apiGet("boostdefault");
      }
    }
    const path = api.dir() + "/" + name;
    let result = await api.exec("cat '" + path + "'");
    if ((result.errno !== 0 || !result.stdout) && name !== "boost.default.xml") {
      result = await api.exec("cat '" + api.dir() + "/boost.default.xml'");
    }
    if (result.errno !== 0 || !result.stdout) throw new Error((result.stderr || "读取升频表失败").trim());
    return result.stdout;
  }

  function hasConfig(list, item) {
    return list.some((config) => config.hint === item.hint && config.scence === item.scence);
  }

  async function load(kind) {
    setBusy(true);
    try {
      configs = parseBoost(await readText(kind));
      let added = 0;
      if (kind !== "default") {
        const defaults = parseBoost(await readText("default"));
        for (const item of defaults) {
          if (hasConfig(configs, item)) continue;
          configs.push(item);
          added += 1;
        }
      }
      loaded = true;
      render();
      if (kind === "default") {
        setStatus("已载入初始升频表，还没有写入手机。到达触发温度后，临时上限改成这一档。");
      } else if (added) {
        setStatus("已读取升频表，并补上 " + added + " 项新场景。保存后才会写到手机。");
      } else {
        setStatus("已读取升频表。到达触发温度后，临时上限改成这一档。");
      }
    } catch (error) {
      setStatus(error.message, true);
    } finally {
      setBusy(false);
    }
  }

  async function save() {
    if (!loaded) return;
    setBusy(true);
    setStatus("正在保存并挂上升频表");
    try {
      const text = serializeBoost(configs);
      await api.writeFile(api.dir() + "/boost.xml", text);
      let outcome = "";
      if (!api.hasKsu) {
        outcome = await api.apiPost("boostapply", "");
        if (outcome.indexOf("boost ") < 0) throw new Error(outcome.trim() || "应用失败");
      } else {
        const result = await api.exec("sh '" + api.dir() + "/boost.sh'");
        outcome = ((result.stdout || "") + "\n" + (result.stderr || "")).trim();
        if (result.errno !== 0) throw new Error(outcome || "应用失败");
      }
      if (outcome.indexOf("boost off") >= 0) setStatus("已保存。当前是关闭覆盖，升频表已从手机撤下。");
      else setStatus("已挂上。性能服务已重启，滑动、启动、动画、解锁和上滑会按这张表限制升频。");
      api.toast("升频表已应用");
    } catch (error) {
      setStatus(error.message, true);
      api.toast(error.message);
    } finally {
      setBusy(false);
    }
  }

  document.getElementById("boost-reload").addEventListener("click", () => load("current"));
  document.getElementById("boost-defaults").addEventListener("click", () => load("default"));
  document.getElementById("boost-save").addEventListener("click", save);
  return () => {
    if (!loaded) load("current");
  };
}
