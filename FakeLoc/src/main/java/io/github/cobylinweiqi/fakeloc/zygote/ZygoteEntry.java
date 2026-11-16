package io.github.cobylinweiqi.fakeloc.zygote;

import android.util.Log;

import com.v7878.zygisk.ZygoteLoader;

public final class ZygoteEntry {
    private ZygoteEntry() {
    }

    public static void premain() {
        try {
            HookRuntime.preload(ZygoteLoader.getModuleDir());
        } catch (Throwable error) {
            Log.e("FakeLoc", "premain", error);
        }
    }

    public static void main() {
        try {
            HookRuntime.install(ZygoteLoader.getPackageName(), ZygoteLoader.getProcessName());
        } catch (Throwable error) {
            Log.e("FakeLoc", "main", error);
        }
    }
}
