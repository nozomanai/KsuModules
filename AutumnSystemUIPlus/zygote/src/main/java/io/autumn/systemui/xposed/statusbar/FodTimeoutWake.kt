package io.autumn.systemui.xposed.statusbar

import android.os.CancellationSignal
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
 * 系统里的 scheduleWakeIfNotUnlocked 会用 WAKE_REASON_LIFT 和
 * "com.android.systemui:FOD_TIMEOUT" 先把屏幕点亮。锁屏先露出来，
 * 人脸要等相机起来，看起来就是亮屏后再过大约 0.2 秒才成功。
 * 功能打开时那条唤醒被跳过。
 *
 * 按下后马上 requestFaceAuth。到点时若人脸还在跑，屏幕保持指纹的暗屏，
 * 直到成功、失败，或再等 550 毫秒，才
 * wakeUp(WAKE_REASON_POWER_BUTTON, "android.policy:POWER")。
 * 官方请求被守门条件丢掉时，直接走 FaceManager.authenticate。
 * 抬手时若亮屏还没发生，取消这次人脸。
 */
object FodTimeoutWake {
    const val ICON = "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView"

    private const val FACTORY = "com.miui.keyguard.biometrics.fod.MiuiFingerPrintFactory"
    private const val LOG_TAG = "MiuiGxzwViewIcon"
    private const val FACE_EVENT = "com.miui.sysuiinterfaces.assist.IFaceAuthUiEvent"
    private const val FACE_UTILS = "com.miui.keyguard.biometrics.faceunlock.MiuiFaceUnlockUtils"
    private const val WAKE_DETAIL = "android.policy:POWER"
    private const val WAKE_REASON_POWER_BUTTON = 1
    private const val FACE_DONE_WAIT_MS = 550L
    private const val FACE_POLL_MS = 16L
    private const val FACE_ENSURE_MS = 40L

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var timeoutMs: Long = ModuleConfig.FOD_TIMEOUT_DEFAULT_MS

    private val pending = WeakHashMap<Any, Runnable>()
    private val faceTasks = WeakHashMap<Any, Runnable>()
    private val ensureTasks = WeakHashMap<Any, Runnable>()
    private val primed = WeakHashMap<Any, Boolean>()
    private val waits = WeakHashMap<Any, Wait>()
    private val dropped = WeakHashMap<Any, Boolean>()
    private val fields = HashMap<String, Field>()
    private var getManager: Method? = null
    private var unlockByGxzw: Method? = null
    private var wakeUp: Method? = null
    private var faceEvent: Any? = null
    private var requestFace: Method? = null
    private var setScreenDelayed: Method? = null
    private var cancelFace: Method? = null
    private var clearPending: Method? = null
    private var authenticate: Method? = null

    private class Wait {
        var since: Long = 0L
        var sawRunning: Boolean = false
        var directTried: Boolean = false
        var logged: Boolean = false
    }

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
            val wakeTask = taskFor(view)
            val faceTask = faceTaskFor(view)
            handler.removeCallbacks(wakeTask)
            handler.removeCallbacks(faceTask)
            handler.removeCallbacks(ensureTaskFor(view))
            resetWait(view)
            handler.post(faceTask)
            handler.postDelayed(wakeTask, delay)
            Log.i(LOG_TAG, "scheduleWakeIfNotUnlocked ${delay}ms")
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "scheduleWakeIfNotUnlocked", error)
        }
    }

    fun onReleased(view: Any) {
        val wakeTask = storedTask(view)
        val faceTask = storedFaceTask(view)
        if (wakeTask == null && faceTask == null) return
        try {
            val handler = field(view, "mHandler") as? Handler ?: return
            synchronized(dropped) { dropped[view] = true }
            if (wakeTask != null) handler.removeCallbacks(wakeTask)
            if (faceTask != null) handler.removeCallbacks(faceTask)
            handler.removeCallbacks(ensureTaskFor(view))
            val started = takePrimed(view)
            if (started && stillDim(view)) {
                try {
                    stopFace(view)
                } catch (error: Throwable) {
                    Log.e(LOG_TAG, "cancelFace", error)
                }
                holdScreen(view, false)
            }
            Log.i(LOG_TAG, "cancelWakeIfNotUnlocked")
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "cancelWakeIfNotUnlocked", error)
        }
    }

    private fun primeFace(view: Any) {
        if (!enabled) return
        try {
            if (!stillDim(view)) return
            Log.i(LOG_TAG, "primeFace before fod wake")
            holdScreen(view, true)
            synchronized(primed) { primed[view] = true }
            requestFaceAuth(view)
            val handler = field(view, "mHandler") as? Handler ?: return
            handler.postDelayed(ensureTaskFor(view), FACE_ENSURE_MS)
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "primeFace before fod wake", error)
        }
    }

    private fun ensureFace(view: Any) {
        if (isDropped(view) || !stillDim(view)) return
        try {
            val repository = faceRepository(view) ?: return
            val running = flowFlag(repository, "_isAuthRunning")
            if (flowFlag(repository, "_isAuthenticated") || running) {
                if (running) waitFor(view).sawRunning = true
                return
            }
            val wait = waitFor(view)
            if (wait.directTried) return
            wait.directTried = true
            val started = startDirect(repository)
            if (started) wait.sawRunning = true
            Log.i(LOG_TAG, "startFace before fod wake started=$started")
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "startFace before fod wake", error)
        }
    }

    private fun wake(view: Any) {
        if (!enabled) return
        try {
            if (field(view, "mDozing") as? Boolean != true) return
            val manager = fingerPrintManager(view) ?: return
            if (unlockedByGxzw(manager)) {
                stopFace(view)
                holdScreen(view, false)
                return
            }
            val power = field(view, "mPowerManager") as? PowerManager ?: return
            if (power.isInteractive) return
            if (isDropped(view)) return
            if (!isPrimed(view)) primeFace(view)
            if (faceStillPending(view)) return
            Log.i(LOG_TAG, "wakeScreen fod timeout")
            wakeScreen(power)
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "wakeScreen fod timeout", error)
        }
    }

    private enum class FaceOutcome { NONE, FAILED, CANCELED }

    private fun faceStillPending(view: Any): Boolean {
        val wait = waitFor(view)
        if (wait.since == 0L) wait.since = SystemClock.uptimeMillis()
        val elapsed = SystemClock.uptimeMillis() - wait.since
        val repository = faceRepository(view) ?: return false
        if (flowFlag(repository, "_isAuthenticated")) return false
        val running = flowFlag(repository, "_isAuthRunning")
        if (running) wait.sawRunning = true
        val outcome = faceOutcome(repository)
        if (outcome == FaceOutcome.FAILED && wait.sawRunning) return false
        if (running && elapsed < FACE_DONE_WAIT_MS) {
            noteWait(wait)
            repostWake(view)
            return true
        }
        if (!running && !wait.directTried && elapsed < FACE_DONE_WAIT_MS &&
            (!wait.sawRunning || outcome == FaceOutcome.NONE || outcome == FaceOutcome.CANCELED)
        ) {
            wait.directTried = true
            val started = startDirect(repository)
            if (started) wait.sawRunning = true
            Log.i(LOG_TAG, "startFace before fod wake started=$started")
            if (started) {
                noteWait(wait)
                repostWake(view)
                return true
            }
        }
        if (!running && wait.sawRunning && outcome == FaceOutcome.CANCELED && elapsed < FACE_DONE_WAIT_MS) {
            noteWait(wait)
            repostWake(view)
            return true
        }
        return false
    }

    private fun noteWait(wait: Wait) {
        if (wait.logged) return
        wait.logged = true
        Log.i(LOG_TAG, "waitFace before fod wake")
    }

    private fun faceOutcome(repository: Any): FaceOutcome {
        val flow = repository.javaClass.getDeclaredField("_authenticationStatus").apply { isAccessible = true }.get(repository)
            ?: return FaceOutcome.NONE
        val status = flow.javaClass.methods.first { candidate ->
            candidate.name == "getValue" && candidate.parameterCount == 0
        }.invoke(flow) ?: return FaceOutcome.NONE
        val name = status.javaClass.simpleName
        if (name == "FailedFaceAuthenticationStatus") return FaceOutcome.FAILED
        if (name != "ErrorFaceAuthenticationStatus") return FaceOutcome.NONE
        val msgId = status.javaClass.getDeclaredField("msgId").apply { isAccessible = true }.getInt(status)
        if (msgId == 1 || msgId == 2 || msgId == 5) return FaceOutcome.CANCELED
        return FaceOutcome.FAILED
    }

    private fun repostWake(view: Any) {
        if (isDropped(view)) return
        val handler = field(view, "mHandler") as? Handler ?: return
        val task = taskFor(view)
        handler.removeCallbacks(task)
        handler.postDelayed(task, FACE_POLL_MS)
    }

    private fun startDirect(repository: Any): Boolean {
        val signalField = repository.javaClass.getDeclaredField("authCancellationSignal").apply { isAccessible = true }
        if (signalField.get(repository) != null || flowFlag(repository, "_isAuthRunning")) return true
        val faceManager = repository.javaClass.getDeclaredField("faceManager").apply { isAccessible = true }.get(repository)
            ?: return false
        val callback = repository.javaClass.getDeclaredField("faceAuthCallback").apply { isAccessible = true }.get(repository)
            ?: return false
        val method = authenticate ?: faceManager.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "authenticate" &&
                candidate.parameterCount == 6 &&
                CancellationSignal::class.java.isAssignableFrom(candidate.parameterTypes[1])
        }?.also { authenticate = it } ?: return false
        val signal = CancellationSignal()
        signalField.set(repository, signal)
        setFlow(repository, "_isAuthRunning", true)
        try {
            method.invoke(faceManager, null, signal, 0, callback, null, selectedUserId(repository))
        } catch (error: Throwable) {
            signal.cancel()
            signalField.set(repository, null)
            setFlow(repository, "_isAuthRunning", false)
            Log.e(LOG_TAG, "startFace before fod wake", error)
            return false
        }
        return true
    }

    private fun selectedUserId(repository: Any): Int {
        val users = repository.javaClass.getDeclaredField("userRepository").apply { isAccessible = true }.get(repository)
        val info = users.javaClass.methods.first { candidate ->
            candidate.name == "getSelectedUserInfo" && candidate.parameterCount == 0
        }.invoke(users)
        val getter = info.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "getId" && candidate.parameterCount == 0 && candidate.returnType == Int::class.javaPrimitiveType
        }
        if (getter != null) return getter.invoke(info) as Int
        return info.javaClass.getField("id").getInt(info)
    }

    private fun faceRepository(view: Any): Any? {
        val monitor = field(view, "mKeyguardUpdateMonitor") ?: return null
        val lazy = monitor.javaClass.getDeclaredField("keyguardUpdateMonitor").apply { isAccessible = true }.get(monitor)
            ?: return null
        val update = lazy.javaClass.getMethod("get").invoke(lazy) ?: return null
        val interactor = update.javaClass.getDeclaredField("mFaceAuthInteractor").apply { isAccessible = true }.get(update)
            ?: return null
        return interactor.javaClass.getDeclaredField("repository").apply { isAccessible = true }.get(interactor)
    }

    private fun flowFlag(target: Any, name: String): Boolean {
        val flow = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) ?: return false
        val value = flow.javaClass.methods.first { candidate ->
            candidate.name == "getValue" && candidate.parameterCount == 0
        }.invoke(flow)
        return value as? Boolean == true
    }

    private fun setFlow(target: Any, name: String, value: Boolean) {
        val flow = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) ?: return
        val update = flow.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "updateState" && candidate.parameterCount == 2
        }
        if (update != null) {
            update.invoke(flow, null, value)
            return
        }
        flow.javaClass.methods.first { candidate ->
            candidate.name == "setValue" && candidate.parameterCount == 1
        }.invoke(flow, value)
    }

    private fun stillDim(view: Any): Boolean {
        if (field(view, "mDozing") as? Boolean != true) return false
        val power = field(view, "mPowerManager") as? PowerManager ?: return false
        return !power.isInteractive
    }

    private fun holdScreen(view: Any, held: Boolean) {
        val method = setScreenDelayed ?: Class.forName(FACE_UTILS, false, view.javaClass.classLoader)
            .getDeclaredMethod("setScreenTurnOnDelayed", java.lang.Boolean.TYPE)
            .apply { isAccessible = true }
            .also { setScreenDelayed = it }
        method.invoke(null, held)
    }

    private fun requestFaceAuth(view: Any) {
        val monitor = field(view, "mKeyguardUpdateMonitor") ?: return
        val event = faceEvent ?: Class.forName(FACE_EVENT, false, view.javaClass.classLoader)
            .enumConstants
            ?.first { (it as Enum<*>).name == "FACE_AUTH_UPDATED_STARTED_WAKING_UP" }
            ?.also { faceEvent = it }
            ?: return
        val method = requestFace ?: monitor.javaClass.getMethod("requestFaceAuth", event.javaClass)
            .also { requestFace = it }
        method.invoke(monitor, event)
    }

    private fun stopFace(view: Any) {
        val repository = faceRepository(view) ?: return
        val cancel = cancelFace ?: repository.javaClass.getDeclaredMethod("cancel").apply {
            isAccessible = true
            cancelFace = this
        }
        cancel.invoke(repository)
        val pendingFlow = repository.javaClass.getDeclaredField("pendingAuthenticateRequest").apply {
            isAccessible = true
        }.get(repository) ?: return
        val clear = clearPending ?: pendingFlow.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "setValue" && candidate.parameterCount == 1
        }?.also { clearPending = it }
        if (clear != null) {
            clear.invoke(pendingFlow, null)
            return
        }
        pendingFlow.javaClass.methods.first { candidate ->
            candidate.name == "updateState" && candidate.parameterCount == 2
        }.invoke(pendingFlow, null, null)
    }

    private fun resetWait(view: Any) {
        synchronized(waits) { waits.remove(view) }
        synchronized(dropped) { dropped[view] = false }
    }

    private fun waitFor(view: Any): Wait = synchronized(waits) {
        waits.getOrPut(view) { Wait() }
    }

    private fun isDropped(view: Any): Boolean = synchronized(dropped) { dropped[view] == true }

    private fun isPrimed(view: Any): Boolean = synchronized(primed) { primed[view] == true }

    private fun takePrimed(view: Any): Boolean = synchronized(primed) { primed.remove(view) == true }

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

    private fun faceTaskFor(view: Any): Runnable = synchronized(faceTasks) {
        faceTasks.getOrPut(view) { Runnable { primeFace(view) } }
    }

    private fun storedFaceTask(view: Any): Runnable? = synchronized(faceTasks) { faceTasks[view] }

    private fun ensureTaskFor(view: Any): Runnable = synchronized(ensureTasks) {
        ensureTasks.getOrPut(view) { Runnable { ensureFace(view) } }
    }

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
