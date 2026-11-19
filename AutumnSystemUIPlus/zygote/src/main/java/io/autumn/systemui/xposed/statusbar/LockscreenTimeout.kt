package io.autumn.systemui.xposed.statusbar

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import io.autumn.systemui.ModuleConfig
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 锁屏亮着时，触摸不再把息屏倒计时往后推。
 *
 * 通知栏窗口在锁屏上会把 userActivityTimeout 收成约 10 秒，并且带了
 * DISABLE_USER_ACTIVITY。输入管道因此不会因为触摸续时，但按下时
 * NotificationShadeWindowView 仍会调用 CentralSurfacesImpl.userActivity，
 * 密码盘、指纹图标也会直接调用 PowerManager.userActivity。
 * 锁屏期间丢掉系统界面里的这些调用，并保住窗口上的禁用标记。
 * 变暗之后的触摸仍会从系统服务里把倒计时续上，所以亮屏时按模块里的超时
 * 自己去睡。亮屏或刚锁上时记一次，作为这一轮的起点；解开锁屏后取消，
 * 再记一次，恢复正常息屏。
 */
object LockscreenTimeout {
    private const val TAG = ModuleConfig.TAG
    private const val CONTROLLER = "com.android.systemui.shade.NotificationShadeWindowControllerImpl"
    private const val INPUT_FEATURE_DISABLE_USER_ACTIVITY = 2
    private const val GO_TO_SLEEP_REASON_TIMEOUT = 2

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var timeoutMs: Long = ModuleConfig.LOCKSCREEN_TIMEOUT_DEFAULT_MS

    @Volatile
    private var locked = false

    @Volatile
    private var interactive = false

    private val started = AtomicBoolean(false)
    private val powerHooked = AtomicBoolean(false)
    private val windowHooked = AtomicBoolean(false)
    private val loggedDrop = AtomicBoolean(false)
    private val loggedWindow = AtomicBoolean(false)
    private val allowing = ThreadLocal.withInitial { false }
    private val fields = HashMap<String, Field>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val sleeper = Runnable { sleepIfLocked() }
    private var powerManager: PowerManager? = null
    private var appContext: Context? = null
    private var pokeMethod: Method? = null
    private var sleepMethod: Method? = null

    val status: String
        get() = "enabled=$enabled timeout=$timeoutMs locked=$locked interactive=$interactive power=${powerHooked.get()} window=${windowHooked.get()}"

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
        appContext = app
        locked = keyguard.isKeyguardLocked
        interactive = power.isInteractive
        installWindowHook(app.classLoader)
        register(app, keyguard)
        Log.i(TAG, "lockscreen timeout watching locked=$locked interactive=$interactive")
    }

    private fun shouldBlock(): Boolean {
        return enabled && locked && interactive && allowing.get() != true
    }

    private fun register(context: Context, keyguard: KeyguardManager) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            keyguard.addKeyguardLockedStateListener(context.mainExecutor) { now ->
                setLocked(now)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> setInteractive(true)
                Intent.ACTION_SCREEN_OFF -> setInteractive(false)
            }
        }
    }

    private fun setInteractive(now: Boolean) {
        val was = interactive
        interactive = now
        if (!now) {
            loggedDrop.set(false)
            disarm()
            return
        }
        if (!was && locked) poke("lockscreen countdown started", true)
    }

    private fun setLocked(now: Boolean) {
        val was = locked
        locked = now
        if (!now) disarm()
        if (was == now || !interactive) return
        poke(if (now) "lockscreen countdown started" else "lockscreen unlocked, countdown resumed", now)
    }

    private fun poke(reason: String, scheduleSleep: Boolean) {
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
            loggedDrop.set(false)
            if (scheduleSleep) arm()
            Log.i(TAG, reason)
        } catch (error: Throwable) {
            Log.e(TAG, reason, error)
        } finally {
            allowing.set(false)
        }
    }

    private fun arm() {
        val timeout = currentTimeout()
        if (timeout <= 0L || timeout >= Int.MAX_VALUE || pluggedStayOn()) {
            disarm()
            return
        }
        handler.removeCallbacks(sleeper)
        handler.postDelayed(sleeper, timeout)
        Log.i(TAG, "lockscreen sleep in ${timeout}ms")
    }

    private fun disarm() {
        handler.removeCallbacks(sleeper)
    }

    private fun currentTimeout(): Long {
        return timeoutMs.coerceIn(
            ModuleConfig.LOCKSCREEN_TIMEOUT_MIN_MS,
            ModuleConfig.LOCKSCREEN_TIMEOUT_MAX_MS,
        )
    }

    private fun pluggedStayOn(): Boolean {
        val context = appContext ?: return false
        val mode = Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0)
        if (mode == 0) return false
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0 && (mode and plugged) != 0
    }

    private fun sleepIfLocked() {
        if (!enabled || !locked || !interactive) return
        val power = powerManager ?: return
        if (!power.isInteractive) return
        try {
            val method = sleepMethod ?: resolveSleep().also { sleepMethod = it }
            val now = SystemClock.uptimeMillis()
            if (method.parameterTypes.size >= 4) {
                method.invoke(power, now, GO_TO_SLEEP_REASON_TIMEOUT, 0, 0)
            } else {
                method.invoke(power, now, GO_TO_SLEEP_REASON_TIMEOUT, 0)
            }
            Log.i(TAG, "lockscreen timeout goToSleep")
        } catch (error: Throwable) {
            Log.e(TAG, "goToSleep", error)
        }
    }

    private fun resolveSleep(): Method {
        val type = PowerManager::class.java
        val methods = type.declaredMethods.filter { it.name == "goToSleep" }
        return methods.firstOrNull { it.parameterTypes.size == 3 }
            ?: methods.firstOrNull { it.parameterTypes.size >= 3 }
            ?: throw NoSuchMethodException("goToSleep")
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
