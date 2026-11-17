package io.github.cobylinweiqi.fakeloc.xposed.hooks

import android.os.Handler
import android.os.Looper
import io.github.cobylinweiqi.fakeloc.xposed.HookKit
import java.util.Collections
import java.util.WeakHashMap

/**
 * Remembers a dex loader created after startup and arms map SDK channels on it
 * once that constructor has returned to its caller.
 *
 * Doing the arm inside the constructor, or from `ClassLoader.loadClass`, runs
 * `Class.forName` while a loader or a class is still initializing. Feishu then
 * crashes in the mini-app permission scan with
 * `java.lang.Class cannot be cast to java.lang.String`. The app's own loader
 * is armed by [LateArmHooks].
 */
internal object DynamicLoaderHooks {

    private const val TAG = "Loader"

    private const val BASE_DEX_CLASS_LOADER = "dalvik.system.BaseDexClassLoader"

    private val pending: MutableSet<ClassLoader> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>()))

    private val resolving = ThreadLocal<Boolean>()

    fun install() {
        val loaderClass = HookKit.findClass(
            DynamicLoaderHooks::class.java.classLoader ?: return,
            BASE_DEX_CLASS_LOADER,
        ) ?: run {
            HookKit.warn(TAG, "$BASE_DEX_CLASS_LOADER not hookable")
            return
        }
        val hooked = HookKit.hookConstructors(loaderClass, TAG) { chain ->
            val created = chain.proceed()
            val source = chain.thisObject as? ClassLoader
            if (source != null) schedule(source)
            created
        }
        if (hooked > 0) {
            HookKit.info(TAG, "watching new dex class loaders ($hooked constructor(s))")
        }
    }

    private fun schedule(source: ClassLoader) {
        if (!pending.add(source)) return
        val looper = Looper.getMainLooper()
        if (looper == null) {
            arm(source)
            return
        }
        Handler(looper).post { arm(source) }
    }

    private fun arm(source: ClassLoader) {
        if (resolving.get() == true) return
        resolving.set(true)
        try {
            val armed = MapSdkHooks.install(source)
            if (armed > 0) {
                HookKit.info(TAG, "armed $armed channel(s) on ${source.javaClass.name}")
            }
        } catch (t: Throwable) {
            HookKit.error(TAG, "arming channels on ${source.javaClass.name} failed", t)
        } finally {
            resolving.set(false)
        }
    }
}
