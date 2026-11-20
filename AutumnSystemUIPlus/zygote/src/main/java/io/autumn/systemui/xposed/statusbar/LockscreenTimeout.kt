package io.autumn.systemui.xposed.statusbar

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import io.autumn.systemui.ModuleConfig
import io.autumn.systemui.zygote.ArtHooks
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 锁屏亮着时，触摸不再把息屏倒计时往后推。熄屏仍由系统电源管理决定，
 * 闹钟、来电、计时的唤醒锁和保持亮屏不会被打断。
 *
 * 通知栏窗口带了 DISABLE_USER_ACTIVITY，亮屏时的输入管道不会续时。
 * 按下时系统界面仍会调用 userActivity，这里丢掉这些调用。
 * 变暗之后 MiuiInputManagerService.pokeUserActivity 会无视窗口标记再记一次，
 * 这条在 system_server 里，锁屏显示时直接跳过。
 */
object LockscreenTimeout {
    private const val TAG = ModuleConfig.TAG
    private const val CONTROLLER = "com.android.systemui.shade.NotificationShadeWindowControllerImpl"
    private const val INPUT_FEATURE_DISABLE_USER_ACTIVITY = 2
    private const val USER_ACTIVITY_EVENT_TOUCH = 2

    @Volatile
    var enabled: Boolean = true

    @Volatile
    private var locked = false

    private val started = AtomicBoolean(false)
    private val powerHooked = AtomicBoolean(false)
    private val windowHooked = AtomicBoolean(false)
    private val serverHooked = AtomicBoolean(false)
    private val pokeHooked = AtomicBoolean(false)
    private val loggedDrop = AtomicBoolean(false)
    private val loggedPoke = AtomicBoolean(false)
    private val loggedWindow = AtomicBoolean(false)
    private val allowing = ThreadLocal.withInitial { false }
    private val fields = HashMap<String, Field>()
    private var powerManager: PowerManager? = null
    private var pokeMethod: Method? = null
    private var systemContext: Context? = null

    val status: String
        get() = "enabled=$enabled locked=$locked power=${powerHooked.get()} window=${windowHooked.get()} server=${serverHooked.get()} poke=${pokeHooked.get()}"

    @JvmStatic
    fun installInSystemServer() {
        enabled = ModuleConfig.lockscreenIgnoreTouch
        if (!enabled || !serverHooked.compareAndSet(false, true)) return
        try {
            val methods = PowerManager::class.java.declaredMethods.filter { method ->
                method.name == "userActivity" && touchEventIndex(method) != null
            }
            methods.forEach { method ->
                val eventIndex = touchEventIndex(method) ?: return@forEach
                method.isAccessible = true
                hookSafe(method) { chain ->
                    val event = (chain.getArg(eventIndex) as? Number)?.toInt()
                    if (event == USER_ACTIVITY_EVENT_TOUCH && keyguardLocked()) {
                        if (loggedPoke.compareAndSet(false, true)) {
                            Log.i(TAG, "lockscreen dim touch did not renew screen timeout")
                        }
                        null
                    } else {
                        chain.proceed()
                    }
                }
            }
            pokeHooked.set(methods.isNotEmpty())
            Log.i(TAG, "lockscreen dim poke hooked x${methods.size}")
        } catch (error: Throwable) {
            serverHooked.set(false)
            Log.e(TAG, "lockscreen dim poke", error)
        }
    }

    private fun touchEventIndex(method: Method): Int? {
        val types = method.parameterTypes
        if (types.size >= 3 && types[0] == java.lang.Long.TYPE && types[1] == java.lang.Integer.TYPE) {
            return 1
        }
        return null
    }

    fun installPowerHook() {
        if (!enabled || !powerHooked.compareAndSet(false, true)) return
        val methods = PowerManager::class.java.declaredMethods.filter { it.name == "userActivity" }
        if (methods.isEmpty()) {
            powerHooked.set(false)
            Log.e(TAG, "userActivity missing")
            return
        }
        methods.forEach { method ->
            method.isAccessible = true
            SystemUiHooks.hook(method) { chain ->
                if (shouldBlock()) {
                    if (loggedDrop.compareAndSet(false, true)) {
                        Log.i(TAG, "lockscreen touch did not renew screen timeout")
                    }
                    null
                } else {
                    chain.proceed()
                }
            }
        }
        pokeMethod = methods.firstOrNull { method ->
            val types = method.parameterTypes
            types.size == 2 && types[0] == java.lang.Long.TYPE && types[1] == java.lang.Boolean.TYPE
        } ?: methods.firstOrNull { method ->
            val types = method.parameterTypes
            types.size >= 3 && types[0] == java.lang.Long.TYPE
        }
        Log.i(TAG, "lockscreen timeout hook userActivity x${methods.size}")
    }

    fun start(context: Context) {
        if (!enabled || !started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        if (power == null || keyguard == null) {
            started.set(false)
            Log.e(TAG, "power or keyguard missing")
            return
        }
        powerManager = power
        locked = keyguard.isKeyguardLocked
        installWindowHook(app.classLoader)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            keyguard.addKeyguardLockedStateListener(app.mainExecutor) { now ->
                setLocked(now)
            }
        }
        Log.i(TAG, "lockscreen timeout watching locked=$locked")
    }

    private fun shouldBlock(): Boolean {
        return enabled && locked && allowing.get() != true
    }

    private fun setLocked(now: Boolean) {
        val was = locked
        locked = now
        if (was && !now && powerManager?.isInteractive == true) {
            noteUnlock()
        }
    }

    private fun noteUnlock() {
        val power = powerManager ?: return
        val method = pokeMethod ?: return
        allowing.set(true)
        try {
            val types = method.parameterTypes
            val now = SystemClock.uptimeMillis()
            val args = Array<Any?>(types.size) { index ->
                when {
                    index == 0 -> now
                    types[index] == java.lang.Boolean.TYPE -> false
                    types[index] == java.lang.Integer.TYPE -> 0
                    types[index] == java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
            method.invoke(power, *args)
            Log.i(TAG, "lockscreen unlocked, screen timeout resumed")
        } catch (error: Throwable) {
            Log.e(TAG, "lockscreen unlocked", error)
        } finally {
            allowing.set(false)
        }
    }

    private fun keyguardLocked(): Boolean {
        return try {
            val context = systemContext ?: lookupSystemContext()?.also { systemContext = it } ?: return false
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        } catch (error: Throwable) {
            Log.e(TAG, "keyguard", error)
            false
        }
    }

    private fun lookupSystemContext(): Context? {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null) ?: return null
        return threadClass.getMethod("getSystemContext").invoke(thread) as? Context
    }

    private fun installWindowHook(loader: ClassLoader?) {
        if (loader == null || !windowHooked.compareAndSet(false, true)) return
        try {
            val type = Class.forName(CONTROLLER, false, loader)
            val method = type.getDeclaredMethod("applyWindowLayoutParams")
            SystemUiHooks.hook(method) { chain ->
                chain.thisObject?.let { forceDisableUserActivity(it) }
                chain.proceed()
            }
            Log.i(TAG, "lockscreen timeout hook window")
        } catch (error: Throwable) {
            windowHooked.set(false)
            Log.e(TAG, "lockscreen window", error)
        }
    }

    private fun forceDisableUserActivity(controller: Any) {
        if (!locked) return
        try {
            val params = field(controller, "mLpChanged").get(controller) as? WindowManager.LayoutParams ?: return
            val features = field(params, "inputFeatures")
            val current = features.getInt(params)
            val next = current or INPUT_FEATURE_DISABLE_USER_ACTIVITY
            if (current != next) features.setInt(params, next)
        } catch (error: Throwable) {
            if (loggedWindow.compareAndSet(false, true)) Log.e(TAG, "inputFeatures", error)
        }
    }

    private fun hookSafe(method: Method, block: (ArtHooks.Call) -> Any?) {
        SystemUiHooks.hook(method) { chain ->
            try {
                block(chain)
            } catch (error: Throwable) {
                if (chain.proceeded) throw error
                Log.e(TAG, "hook ${method.name}", error)
                chain.proceed()
            }
        }
    }

    private fun field(target: Any, name: String): Field {
        synchronized(fields) {
            fields[name]?.let { return it }
            val resolved = findField(target.javaClass, name)
            fields[name] = resolved
            return resolved
        }
    }

    private fun findField(start: Class<*>, name: String): Field {
        var type: Class<*>? = start
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        throw NoSuchFieldException(name)
    }
}
