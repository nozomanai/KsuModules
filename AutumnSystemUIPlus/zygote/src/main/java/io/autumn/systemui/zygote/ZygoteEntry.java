package io.autumn.systemui.zygote;

import android.util.Log;

import com.v7878.zygisk.ZygoteLoader;

import io.autumn.systemui.ModuleConfig;
import io.autumn.systemui.xposed.statusbar.SystemUiHooks;

public final class ZygoteEntry {
    private ZygoteEntry() {
    }

    public static void premain() {
        try {
            ModuleConfig.load(ZygoteLoader.getModuleDir());
        } catch (Throwable error) {
            Log.e(ModuleConfig.TAG, "premain", error);
        }
    }

    public static void main() {
        try {
            SystemUiHooks.install(ZygoteLoader.getPackageName());
        } catch (Throwable error) {
            Log.e(ModuleConfig.TAG, "main", error);
        }
    }
}
