package io.autumn.systemui.xposed.statusbar

import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import io.autumn.systemui.ModuleConfig
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * 熄屏按住指纹，超时仍未解开就亮起锁屏。
 *
 * 对齐 fod-timeout-wake 对 MiuiGxzwIconView 的改动：
 * 只有 mDozing 时才向 mHandler 投递延时任务；抬手 removeCallbacks；
 * 到点后仍息屏、isUnlockByGxzw 为假、PowerManager 未交互，才
 * wakeUp(WAKE_REASON_POWER_BUTTON, "android.policy:POWER")。
 * MiuiFaceUnlockManager 只对这个详情字符串按住画面，人脸先于亮屏启动。
 * 已经按下时的提前返回不会重新计时。
 */
object FodTimeoutWake {
    const val ICON = "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView"

    private const val FACTORY = "com.miui.keyguard.biometrics.fod.MiuiFingerPrintFactory"
    private const val LOG_TAG = "MiuiGxzwViewIcon"
    private const val WAKE_DETAIL = "android.policy:POWER"
    private const val WAKE_REASON_POWER_BUTTON = 1

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var timeoutMs: Long = ModuleConfig.FOD_TIMEOUT_DEFAULT_MS

    private val pending = WeakHashMap<Any, Runnable>()
    private val fields = HashMap<String, Field>()
    private var getManager: Method? = null
    private var unlockByGxzw: Method? = null
    private var wakeUp: Method? = null

    /** 本次 onTouchDown 进来时已经是按下状态，原方法会直接返回。 */
    fun alreadyDown(view: Any): Boolean {
        return try {
            field(view, "mTouchDown") as? Boolean == true
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "mTouchDown", error)
            true
        }
    }

    fun onPressed(view: Any) {
        if (!enabled) return
        try {
            if (field(view, "mDozing") as? Boolean != true) return
            val handler = field(view, "mHandler") as? Handler ?: return
            val delay = timeoutMs
            val task = taskFor(view)
            handler.removeCallbacks(task)
            handler.postDelayed(task, delay)
            Log.i(LOG_TAG, "scheduleWakeIfNotUnlocked ${delay}ms")
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "scheduleWakeIfNotUnlocked", error)
        }
    }

    fun onReleased(view: Any) {
        val task = storedTask(view) ?: return
        try {
            val handler = field(view, "mHandler") as? Handler ?: return
            handler.removeCallbacks(task)
            Log.i(LOG_TAG, "cancelWakeIfNotUnlocked")
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "cancelWakeIfNotUnlocked", error)
        }
    }

    private fun wake(view: Any) {
        if (!enabled) return
        try {
            if (field(view, "mDozing") as? Boolean != true) return
            val manager = fingerPrintManager(view) ?: return
            if (unlockedByGxzw(manager)) return
            val power = field(view, "mPowerManager") as? PowerManager ?: return
            if (power.isInteractive) return
            Log.i(LOG_TAG, "wakeScreen fod timeout")
            wakeScreen(power)
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "wakeScreen fod timeout", error)
        }
    }

    private fun wakeScreen(power: PowerManager) {
        val method = wakeUp ?: PowerManager::class.java.getMethod(
            "wakeUp",
            java.lang.Long.TYPE,
            java.lang.Integer.TYPE,
            String::class.java,
        ).also { wakeUp = it }
        method.invoke(power, SystemClock.uptimeMillis(), WAKE_REASON_POWER_BUTTON, WAKE_DETAIL)
    }

    private fun fingerPrintManager(view: Any): Any? {
        val method = getManager ?: Class.forName(FACTORY, false, view.javaClass.classLoader)
            .getDeclaredMethod("getFingerPrintManager")
            .apply { isAccessible = true }
            .also { getManager = it }
        return method.invoke(null)
    }

    private fun unlockedByGxzw(manager: Any): Boolean {
        val method = unlockByGxzw ?: manager.javaClass.getMethod("isUnlockByGxzw").also { unlockByGxzw = it }
        return method.invoke(manager) as? Boolean == true
    }

    private fun taskFor(view: Any): Runnable = synchronized(pending) {
        pending.getOrPut(view) { Runnable { wake(view) } }
    }

    private fun storedTask(view: Any): Runnable? = synchronized(pending) { pending[view] }

    private fun field(view: Any, name: String): Any? {
        val resolved = synchronized(fields) {
            fields[name] ?: view.javaClass.getDeclaredField(name).apply {
                isAccessible = true
                fields[name] = this
            }
        }
        return resolved.get(view)
    }

    private fun readTimeout(value: Long): Long {
        return value.coerceIn(0L, ModuleConfig.FOD_TIMEOUT_MAX_MS)
    }
}
