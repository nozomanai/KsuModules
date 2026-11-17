package io.github.cobylinweiqi.fakeloc.zygote;

import android.util.Log;

import com.v7878.zygisk.ZygoteLoader;

import io.github.cobylinweiqi.fakeloc.xposed.HookState;

public final class ZygoteEntry {
    private ZygoteEntry() {
    }

    public static void premain() {
        try {
            HookRuntime.preload(ZygoteLoader.getModuleDir());
        } catch (Throwable error) {
            // Zygote, before the app uid exists. The target app cannot read this.
            Log.e("FakeLoc", "premain", error);
        }
    }

    public static void main() {
        try {
            HookRuntime.install(ZygoteLoader.getPackageName(), ZygoteLoader.getProcessName());
        } catch (Throwable error) {
            if (HookState.INSTANCE.mayLog()) Log.e("FakeLoc", "main", error);
        }
    }
}
