export function mountGpu(api) {
  let loaded = false;
  let on = true;
  const button = document.getElementById("gpu-shade");
  const status = document.getElementById("gpu-status");

  function paint() {
    button.classList.toggle("on", on);
    button.setAttribute("aria-checked", on ? "true" : "false");
  }

  function setStatus(text, bad) {
    status.textContent = text;
    status.style.color = bad ? "var(--danger)" : "var(--muted)";
  }

  async function readOn() {
    if (!api.hasKsu) {
      const text = await api.apiGet("gpushade");
      return text.trim() !== "off";
    }
    const result = await api.exec("cat '" + api.dir() + "/gpu-shade'");
    if (result.errno !== 0 || !result.stdout) return true;
    return result.stdout.trim() !== "off";
  }

  async function applyBoost() {
    if (!api.hasKsu) {
      const outcome = await api.apiPost("boostapply", "");
      if (outcome.indexOf("boost ") < 0 && outcome.indexOf("gpu shade ") < 0) {
        throw new Error(outcome.trim() || "应用失败");
      }
      return outcome;
    }
    const result = await api.exec("sh '" + api.dir() + "/boost.sh'");
    const outcome = ((result.stdout || "") + "\n" + (result.stderr || "")).trim();
    if (result.errno !== 0) throw new Error(outcome || "应用失败");
    return outcome;
  }

  function expect(outcome, next) {
    const mark = next ? "gpu shade on" : "gpu shade off";
    if (outcome.indexOf(mark) < 0) throw new Error(outcome.trim() || "应用失败");
  }

  async function apply(next) {
    if (!loaded || button.disabled) return;
    const previous = on;
    on = next;
    paint();
    button.disabled = true;
    setStatus(next ? "正在打开 900MHz 锁" : "正在关闭 900MHz 锁");
    try {
      await api.writeFile(api.dir() + "/gpu-shade", (next ? "on" : "off") + "\n");
      const outcome = await applyBoost();
      expect(outcome, next);
      setStatus(next
        ? "已打开。下拉通知中心时，GPU 会锁在 900MHz，大约半秒。"
        : "已关闭。下拉通知中心不再把 GPU 锁在 900MHz。");
      api.toast(next ? "900MHz 锁已打开" : "900MHz 锁已关闭");
    } catch (error) {
      on = previous;
      paint();
      try {
        await api.writeFile(api.dir() + "/gpu-shade", (previous ? "on" : "off") + "\n");
      } catch (writeError) {
        setStatus(error.message, true);
        api.toast(error.message);
        return;
      }
      setStatus(error.message, true);
      api.toast(error.message);
    } finally {
      button.disabled = false;
    }
  }

  button.addEventListener("click", () => apply(!on));

  return async () => {
    if (loaded) return;
    button.disabled = true;
    setStatus("正在读取");
    try {
      on = await readOn();
      loaded = true;
      paint();
      setStatus(on
        ? "锁开着。下拉通知中心时 GPU 会到 900MHz。"
        : "锁关着。下拉通知中心不再把 GPU 固定在 900MHz。");
    } catch (error) {
      setStatus(error.message, true);
    } finally {
      button.disabled = false;
    }
  };
}
