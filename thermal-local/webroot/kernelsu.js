let callbackCounter = 0;

function callbackName(prefix) {
  callbackCounter += 1;
  return prefix + "_callback_" + Date.now() + "_" + callbackCounter;
}

function bridge() {
  const ksu = globalThis.ksu;
  if (!ksu) {
    throw new Error("KernelSU bridge missing");
  }
  return ksu;
}

export function exec(command, options) {
  return new Promise((resolve, reject) => {
    const name = callbackName("exec");
    globalThis[name] = (errno, stdout, stderr) => {
      delete globalThis[name];
      resolve({ errno, stdout, stderr });
    };
    try {
      bridge().exec(command, JSON.stringify(options || {}), name);
    } catch (error) {
      delete globalThis[name];
      reject(error);
    }
  });
}

export function toast(message) {
  bridge().toast(message);
}

export function moduleInfo() {
  return bridge().moduleInfo();
}
