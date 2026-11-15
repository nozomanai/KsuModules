package local.persistfreeze;

import android.util.Log;

public final class ZygoteEntry {
    public static void premain() {
    }

    public static void main() {
        try {
            ServerHook.install();
        } catch (Throwable t) {
            Log.e("PersistFreeze", "install failed", t);
        }
    }
}
