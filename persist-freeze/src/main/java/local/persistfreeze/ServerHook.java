package local.persistfreeze;

import static com.v7878.unsafe.Reflection.getDeclaredField;
import static com.v7878.unsafe.Reflection.getDeclaredMethod;
import static com.v7878.unsafe.Reflection.getHiddenExecutables;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.v7878.unsafe.invoke.EmulatedStackFrame;
import com.v7878.unsafe.invoke.Transformers;
import com.v7878.vmtools.Hooks;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs inside system_server. A disabled persistent package is killed on
 * disable, and is not started again.
 */
public final class ServerHook {
    private static final String TAG = "PersistFreeze";
    private static final String PROCESS_LIST = "com.android.server.am.ProcessList";
    private static final String SYSTEM_SERVER = "com.android.server.SystemServer";
    private static final int ENABLED = 1;
    private static final int EVEN_PERSISTENT = 8;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private static volatile ClassLoader sLoader;
    private static volatile Method sEnabledSetting;
    private static volatile Object sPackageManager;
    private static volatile Method sIsPersistent;
    private static volatile java.lang.reflect.Field sInfo;
    private static volatile java.lang.reflect.Field sUserId;

    private ServerHook() {
    }

    public static void install() throws Throwable {
        try {
            hook(systemServerLoader());
        } catch (Throwable direct) {
            Log.e(TAG, "system server loader not ready", direct);
            hookWhenSystemServerStarts();
        }
    }

    private static ClassLoader systemServerLoader() throws Throwable {
        Class<?> zygoteInit = Class.forName("com.android.internal.os.ZygoteInit");
        Method method = getDeclaredMethod(zygoteInit, "getOrCreateSystemServerClassLoader");
        return (ClassLoader) method.invoke(null);
    }

    private static void hookWhenSystemServerStarts() throws Throwable {
        Class<?> runtimeInit = Class.forName("com.android.internal.os.RuntimeInit");
        Method findMain = getDeclaredMethod(
                runtimeInit,
                "findStaticMain",
                String.class,
                String[].class,
                ClassLoader.class);
        Hooks.hook(findMain, Hooks.EntryPointType.DIRECT, (original, frame) -> {
            try {
                EmulatedStackFrame.StackFrameAccessor accessor = frame.accessor();
                if (SYSTEM_SERVER.equals(accessor.getReference(0))) {
                    hook((ClassLoader) accessor.getReference(2));
                }
            } catch (Throwable t) {
                Log.e(TAG, "findStaticMain", t);
            }
            Transformers.invokeExactNoChecks(original, frame);
        }, Hooks.EntryPointType.DIRECT);
        Log.i(TAG, "waiting for system server main");
    }

    private static void hook(ClassLoader loader) throws Throwable {
        if (loader == null || !INSTALLED.compareAndSet(false, true)) {
            return;
        }
        sLoader = loader;
        Class<?> processList = Class.forName(PROCESS_LIST, false, loader);
        Method start = findStart(processList);
        Method kill = findKill(processList);
        if (start == null || kill == null) {
            INSTALLED.set(false);
            Log.e(TAG, "method missing start=" + start + " kill=" + kill);
            return;
        }
        Hooks.hook(start, Hooks.EntryPointType.DIRECT, ServerHook::onStart, Hooks.EntryPointType.DIRECT);
        Hooks.hook(kill, Hooks.EntryPointType.DIRECT, ServerHook::onKill, Hooks.EntryPointType.DIRECT);
        Log.i(TAG, "hooked " + start);
        Log.i(TAG, "hooked " + kill);
    }

    private static void onStart(java.lang.invoke.MethodHandle original, EmulatedStackFrame frame) throws Throwable {
        try {
            Object app = frame.accessor().getReference(1);
            if (isBlocked(app)) {
                frame.accessor().setValue(EmulatedStackFrame.RETURN_VALUE_IDX, false);
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "start", t);
        }
        Transformers.invokeExactNoChecks(original, frame);
    }

    private static void onKill(java.lang.invoke.MethodHandle original, EmulatedStackFrame frame) throws Throwable {
        try {
            EmulatedStackFrame.StackFrameAccessor accessor = frame.accessor();
            String pkg = (String) accessor.getReference(1);
            int userId = accessor.getInt(3);
            if (isDisabled(pkg, userId)) {
                accessor.setBoolean(EVEN_PERSISTENT, true);
                Log.i(TAG, "kill persistent " + pkg + " user " + userId);
            }
        } catch (Throwable t) {
            Log.e(TAG, "kill", t);
        }
        Transformers.invokeExactNoChecks(original, frame);
    }

    private static boolean isBlocked(Object app) throws Throwable {
        if (app == null) {
            return false;
        }
        if (sIsPersistent == null) {
            sIsPersistent = getDeclaredMethod(app.getClass(), "isPersistent");
            sInfo = getDeclaredField(app.getClass(), "info");
            sUserId = getDeclaredField(app.getClass(), "userId");
        }
        if (!((Boolean) sIsPersistent.invoke(app))) {
            return false;
        }
        ApplicationInfo info = (ApplicationInfo) sInfo.get(app);
        if (info == null) {
            return false;
        }
        int userId = sUserId.getInt(app);
        if (!isDisabled(info.packageName, userId)) {
            return false;
        }
        Log.i(TAG, "skip start " + info.packageName + " user " + userId);
        return true;
    }

    private static boolean isDisabled(String pkg, int userId) throws Throwable {
        if (pkg == null || pkg.isEmpty() || "android".equals(pkg)) {
            return false;
        }
        int user = userId >= 0 ? userId : 0;
        int state = enabledSetting(pkg, user);
        return state > ENABLED;
    }

    private static int enabledSetting(String pkg, int userId) throws Throwable {
        if (sEnabledSetting == null) {
            ClassLoader loader = sLoader;
            Class<?> globals = Class.forName("android.app.AppGlobals", false, loader);
            Method getPm = getDeclaredMethod(globals, "getPackageManager");
            sPackageManager = getPm.invoke(null);
            if (sPackageManager == null) {
                throw new IllegalStateException("package manager missing");
            }
            Class<?> ipm = Class.forName("android.content.pm.IPackageManager", false, loader);
            sEnabledSetting = getDeclaredMethod(ipm, "getApplicationEnabledSetting", String.class, int.class);
        }
        return (Integer) sEnabledSetting.invoke(sPackageManager, pkg, userId);
    }

    private static Method findStart(Class<?> processList) throws ClassNotFoundException {
        ClassLoader loader = processList.getClassLoader();
        String record = "com.android.server.am.ProcessRecord";
        String hosting = "com.android.server.am.HostingRecord";
        for (Executable executable : getHiddenExecutables(processList)) {
            if (!(executable instanceof Method method) || !"startProcessLocked".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 6
                    && params[0].getName().equals(record)
                    && params[1].getName().equals(hosting)
                    && params[2] == int.class
                    && params[3] == boolean.class
                    && params[4] == boolean.class
                    && params[5] == String.class) {
                return method;
            }
        }
        Class.forName(record, false, loader);
        Class.forName(hosting, false, loader);
        return null;
    }

    private static Method findKill(Class<?> processList) {
        for (Executable executable : getHiddenExecutables(processList)) {
            if (!(executable instanceof Method method) || !"killPackageProcessesLSP".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 13
                    && params[0] == String.class
                    && params[7] == boolean.class
                    && params[12] == String.class) {
                return method;
            }
        }
        return null;
    }
}
