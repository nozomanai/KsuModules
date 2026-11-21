package io.github.cobylinweiqi.fakeloc.zygote

import android.app.Application
import android.app.Instrumentation
import io.github.cobylinweiqi.fakeloc.xposed.HookKit
import io.github.cobylinweiqi.fakeloc.xposed.HookState
import io.github.cobylinweiqi.fakeloc.xposed.hooks.AntiMockHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.DynamicLoaderHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.FrameworkLocationHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.LateArmHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.ListenerHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.LocationHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.MapSdkHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.MiniAppLocationHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.PlayServicesHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.SourceLockHooks
import io.github.cobylinweiqi.fakeloc.xposed.hooks.WebBridgeHooks
import java.lang.reflect.Method

/**
 * Decides which hook set this process gets. ZygoteLoader has already refused
 * every package that is not named in the module's `packages/` directory, so
 * reaching this class means the user asked for this process.
 */
object HookRuntime {
    private const val TAG = "Entry"
    private const val MANAGER = "io.github.cobylinweiqi.fakeloc"
    private const val SHADOW_KLASS = "shadow" + '$' + "_klass_"
    private const val SYSTEM_SERVER = "com.android.server.SystemServer"

    @JvmStatic
    fun preload(moduleDir: String?) {
        HookState.preload(moduleDir)
    }

    @JvmStatic
    fun install(packageName: String?, processName: String?) {
        val pkg = packageName ?: return
        HookState.init(pkg)
        HookKit.info(TAG, "install $pkg process=$processName")
        when (pkg) {
            MANAGER -> HookKit.info(TAG, "manager process left alone")
            "android" -> installSystem()
            "com.android.phone" -> HookKit.info(TAG, "phone process left alone")
            "com.android.providers.settings" -> installWhenApplicationCreated { loader ->
                AntiMockHooks.installSettingsProvider(loader)
            }
            else -> installApp()
        }
    }

    private fun installApp() {
        val loader = HookRuntime::class.java.classLoader ?: return
        if (!HookKit.firstTime("app")) return
        hideShadowKlassField()
        LocationHooks.install(loader)
        ListenerHooks.install(loader)
        PlayServicesHooks.install(loader)
        AntiMockHooks.installApp(loader)
        SourceLockHooks.install(loader)
        MapSdkHooks.install(loader)
        MiniAppLocationHooks.install(loader)
        WebBridgeHooks.install()
        DynamicLoaderHooks.install()
        LateArmHooks.install(loader)
    }

    /**
     * The ART hook runtime publishes `shadow$_klass_` as a public field on
     * `Object`. `Class.getFields()` then returns it from every class, and
     * Feishu's permission scan does `(String) field.get("")` on each one.
     * That field's value is a `Class`, so opening a mini app dies with
     * `Class cannot be cast to String` and the location page never comes up.
     */
    private fun hideShadowKlassField() {
        HookKit.hookAll(Class::class.java, "getFields", "Shadow") { chain ->
            val result = chain.proceed()
            val fields = result as? Array<*> ?: return@hookAll result
            if (fields.none { it is java.lang.reflect.Field && it.name == SHADOW_KLASS }) {
                return@hookAll result
            }
            fields.filter {
                it !is java.lang.reflect.Field || it.name != SHADOW_KLASS
            }.toTypedArray()
        }
    }

    private fun installSystem() {
        val loader = runCatching { systemServerLoader() }.getOrNull()
        if (loader != null) {
            armSystem(loader)
            return
        }
        HookKit.warn(TAG, "system server loader not ready; waiting for main")
        val runtimeInit = Class.forName("com.android.internal.os.RuntimeInit")
        val findMain = runtimeInit.getDeclaredMethod(
            "findStaticMain",
            String::class.java,
            Array<String>::class.java,
            ClassLoader::class.java,
        )
        HookKit.hookMethod(findMain, TAG) { chain ->
            if (SYSTEM_SERVER == chain.args.getOrNull(0)) {
                (chain.args.getOrNull(2) as? ClassLoader)?.let { armSystem(it) }
            }
            chain.proceed()
        }
    }

    private fun armSystem(loader: ClassLoader) {
        if (!HookKit.firstTime("system")) return
        HookKit.info(TAG, "system_server hooks")
        FrameworkLocationHooks.install(loader)
        AntiMockHooks.installSystemServer(loader)
    }

    private fun systemServerLoader(): ClassLoader {
        val zygoteInit = Class.forName("com.android.internal.os.ZygoteInit")
        val method: Method = zygoteInit.getDeclaredMethod("getOrCreateSystemServerClassLoader")
        method.isAccessible = true
        return method.invoke(null) as ClassLoader
    }

    private fun installWhenApplicationCreated(block: (ClassLoader) -> Unit) {
        if (!HookKit.firstTime("settings-provider")) return
        var armed = false
        val hooked = HookKit.hookAll(
            Instrumentation::class.java,
            "callApplicationOnCreate",
            TAG,
        ) { chain ->
            val forwarded = chain.proceed()
            val application = chain.args.getOrNull(0) as? Application
            val loader = application?.classLoader
            if (loader != null && !armed) {
                armed = true
                runCatching { block(loader) }
                    .onFailure { HookKit.error(TAG, "settings provider hooks failed", it) }
            }
            forwarded
        }
        if (hooked == 0) {
            HookKit.warn(TAG, "settings provider will not be hooked; application callback missing")
        }
    }
}
