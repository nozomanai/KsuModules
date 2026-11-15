export function mountPerf(api) {
  const items = [
    {
      button: "cpu-shade",
      status: "cpu-shade-status",
      file: "cpu-shade",
      op: "cpushade",
      mark: "cpu shade",
      busyOn: "正在打开下拉通知的 CPU 升频",
      busyOff: "正在关闭下拉通知的 CPU 升频",
      doneOn: "已打开。下拉通知中心时，小核会到 2745MHz，大核会到 2649MHz。",
      doneOff: "已关闭。下拉通知中心不再写这组 CPU 频率。",
      toastOn: "下拉通知 CPU 升频已打开",
      toastOff: "下拉通知 CPU 升频已关闭",
      idleOn: "开着。下拉通知中心时，小核会到 2745MHz，大核会到 2649MHz。",
      idleOff: "关着。下拉通知中心不再写这组 CPU 频率。",
    },
    {
      button: "gpu-shade",
      status: "gpu-shade-status",
      file: "gpu-shade",
      op: "gpushade",
      mark: "gpu shade",
      busyOn: "正在打开下拉通知的 900MHz 锁",
      busyOff: "正在关闭下拉通知的 900MHz 锁",
      doneOn: "已打开。下拉通知中心时，GPU 会锁在 900MHz，大约半秒。",
      doneOff: "已关闭。下拉通知中心不再把 GPU 锁在 900MHz。",
      toastOn: "下拉通知 GPU 锁已打开",
      toastOff: "下拉通知 GPU 锁已关闭",
      idleOn: "开着。下拉通知中心时 GPU 会到 900MHz。",
      idleOff: "关着。下拉通知中心不再把 GPU 固定在 900MHz。",
    },
    {
      button: "cpu-recents",
      status: "cpu-recents-status",
      file: "cpu-recents",
      op: "cpurecents",
      mark: "cpu recents",
      busyOn: "正在打开多任务的 CPU 升频",
      busyOff: "正在关闭多任务的 CPU 升频",
      doneOn: "已打开。从应用进入多任务时，大核最低 1996MHz，超大核最低 1689MHz。",
      doneOff: "已关闭。从应用进入多任务不再写这组 CPU 频率。",
      toastOn: "多任务 CPU 升频已打开",
      toastOff: "多任务 CPU 升频已关闭",
      idleOn: "开着。从应用进入多任务时，大核最低会到 1996MHz，超大核最低会到 1689MHz。",
      idleOff: "关着。从应用进入多任务不再写这组 CPU 频率。",
    },
    {
      button: "gpu-recents",
      status: "gpu-recents-status",
      file: "gpu-recents",
      op: "gpurecents",
      mark: "gpu recents",
      busyOn: "正在打开多任务的 GPU 升频",
      busyOff: "正在关闭多任务的 GPU 升频",
      doneOn: "已打开。从应用进入多任务时，GPU 地板是 734MHz，天花板是 967MHz。",
      doneOff: "已关闭。从应用进入多任务不再抬 GPU。",
      toastOn: "多任务 GPU 升频已打开",
      toastOff: "多任务 GPU 升频已关闭",
      idleOn: "开着。从应用进入多任务时，GPU 地板是 734MHz，天花板是 967MHz。",
      idleOff: "关着。从应用进入多任务不再抬 GPU。",
    },
    {
      button: "cpu-unlock",
      status: "cpu-unlock-status",
      file: "cpu-unlock",
      op: "cpuunlock",
      mark: "cpu unlock",
      busyOn: "正在打开解锁的 CPU 升频",
      busyOff: "正在关闭解锁的 CPU 升频",
      doneOn: "已打开。解锁时，大核最低 1996MHz，超大核最低 1689MHz。",
      doneOff: "已关闭。解锁不再写这组 CPU 频率。",
      toastOn: "解锁 CPU 升频已打开",
      toastOff: "解锁 CPU 升频已关闭",
      idleOn: "开着。解锁时，大核最低会到 1996MHz，超大核最低会到 1689MHz。",
      idleOff: "关着。解锁不再写这组 CPU 频率。",
    },
    {
      button: "cpu-anim",
      status: "cpu-anim-status",
      file: "cpu-anim",
      op: "cpuanim",
      mark: "cpu anim",
      busyOn: "正在打开动画的 CPU 升频",
      busyOff: "正在关闭动画的 CPU 升频",
      doneOn: "已打开。动画期间小核最低 1996MHz，大核最低 1689MHz。",
      doneOff: "已关闭。窗口动画不再写这组 CPU 频率。",
      toastOn: "动画 CPU 升频已打开",
      toastOff: "动画 CPU 升频已关闭",
      idleOn: "开着。解锁动画和其他窗口动画都会把小核最低抬到 1996MHz。",
      idleOff: "关着。窗口动画不再写这组 CPU 频率。",
    },
  ];
  const controls = items.map((item) => ({
    ...item,
    button: document.getElementById(item.button),
    status: document.getElementById(item.status),
    on: true,
    loaded: false,
  }));
  let busy = false;

  function paint(control) {
    control.button.classList.toggle("on", control.on);
    control.button.setAttribute("aria-checked", control.on ? "true" : "false");
    control.button.disabled = busy || !control.loaded;
  }

  function setStatus(control, text, bad) {
    control.status.textContent = text;
    control.status.style.color = bad ? "var(--danger)" : "var(--muted)";
  }

  async function readOn(control) {
    if (!api.hasKsu) {
      const text = await api.apiGet(control.op);
      return text.trim() !== "off";
    }
    const result = await api.exec("cat '" + api.dir() + "/" + control.file + "'");
    if (result.errno !== 0 || !result.stdout) return true;
    return result.stdout.trim() !== "off";
  }

  async function applyBoost() {
    if (!api.hasKsu) {
      const outcome = await api.apiPost("boostapply", "");
      if (outcome.indexOf("shade ") < 0 && outcome.indexOf("recents ") < 0 && outcome.indexOf("boost ") < 0) {
        throw new Error(outcome.trim() || "应用失败");
      }
      return outcome;
    }
    const result = await api.exec("sh '" + api.dir() + "/boost.sh'");
    const outcome = ((result.stdout || "") + "\n" + (result.stderr || "")).trim();
    if (result.errno !== 0) throw new Error(outcome || "应用失败");
    return outcome;
  }

  async function apply(control, next) {
    if (!control.loaded || busy) return;
    const previous = control.on;
    control.on = next;
    busy = true;
    controls.forEach(paint);
    setStatus(control, next ? control.busyOn : control.busyOff);
    try {
      await api.writeFile(api.dir() + "/" + control.file, (next ? "on" : "off") + "\n");
      const outcome = await applyBoost();
      const mark = control.mark + (next ? " on" : " off");
      if (outcome.indexOf(mark) < 0) throw new Error(outcome.trim() || "应用失败");
      setStatus(control, next ? control.doneOn : control.doneOff);
      api.toast(next ? control.toastOn : control.toastOff);
    } catch (error) {
      control.on = previous;
      try {
        await api.writeFile(api.dir() + "/" + control.file, (previous ? "on" : "off") + "\n");
      } catch (writeError) {
        setStatus(control, error.message, true);
        api.toast(error.message);
        return;
      }
      setStatus(control, error.message, true);
      api.toast(error.message);
    } finally {
      busy = false;
      controls.forEach(paint);
    }
  }

  controls.forEach((control) => {
    control.button.addEventListener("click", () => apply(control, !control.on));
  });

  return async () => {
    await Promise.all(controls.map(async (control) => {
      if (control.loaded) return;
      paint(control);
      setStatus(control, "正在读取");
      try {
        control.on = await readOn(control);
        control.loaded = true;
        setStatus(control, control.on ? control.idleOn : control.idleOff);
      } catch (error) {
        setStatus(control, error.message, true);
      } finally {
        paint(control);
      }
    }));
  };
}
