package io.github.cobylinweiqi.fakeloc.xposed.hooks

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import io.github.cobylinweiqi.fakeloc.core.Datum
import io.github.cobylinweiqi.fakeloc.xposed.HookKit
import io.github.cobylinweiqi.fakeloc.xposed.HookState
import java.lang.reflect.Method
import java.util.ArrayList

/**
 * The attendance mini program shows 定位失败 when `onLocationChange` never
 * arrives, or when the fix it does receive carries a non-zero error code.
 * `startLocationUpdate` delivers that fix through these listeners. Rewriting
 * the object here is what the page's GPS node treats as a success.
 */
internal object MiniAppLocationHooks {

    private const val TAG = "FakeLoc/MiniApp"
    private const val LARK_LOCATION = "com.ss.android.lark.location.dto.LarkLocation"

    /** Public `ic5.d` listeners. `y76.j` is the one on the live stack. */
    private val LISTENERS = arrayOf("y76.a", "y76.c", "y76.g", "y76.j")

    /** `startLocationUpdate` entry. The last argument is the page callback. */
    private val STARTERS = arrayOf("y76.b", "y76.d")

    /** Error callbacks that would otherwise emit `onLocationChangeError`. */
    private val ERRORS = arrayOf(
        "com.ss.android.lark.openplatform.openapi.api.plugin.messenger.location.LocationUpdatePlugin\$a",
        "com.ss.android.lark.openplatform.openapi.api.plugin.messenger.location.LocationUpdatePluginV2\$a",
        "com.ss.android.lark.openplatform.openapi.api.plugin.messenger.location.GetLocationAsyncHandlerPlugin\$a",
        "com.ss.android.lark.openplatform.openapi.api.plugin.messenger.location.GetLocationAsyncHandlerPluginV2\$a",
    )

    /**
     * The punch page subscribes to `onLocationChange` before it calls these.
     * Emitting from the plugin instance is the event that leaves the timeout banner.
     */
    private val PLUGINS = arrayOf(
        "com.ss.android.lark.openplatform.openapi.api.plugin.messenger.location.LocationUpdatePlugin",
        "com.ss.android.lark.openplatform.openapi.api.plugin.messenger.location.LocationUpdatePluginV2",
    )

    /** `tt.getLocation` — the punch page's fallback, and the call that times out. */
    private val GET_LOCATION = arrayOf(
        "com.ss.android.lark.openplatform.openapi.api.plugin.messenger.location.GetLocationAsyncHandlerPlugin",
        "com.ss.android.lark.openplatform.openapi.api.plugin.messenger.location.GetLocationAsyncHandlerPluginV2",
    )

    /** V1 manager. A stale or failed fix here becomes the page's timeout. */
    private val GATES = arrayOf("y76.h", "y76.k")

    fun install(loader: ClassLoader) {
        for (name in LISTENERS) {
            val clazz = HookKit.findClass(loader, name) ?: continue
            if (!HookKit.firstTime("miniapp-listen-$name")) continue
            for (method in locationCallbacks(clazz)) {
                method.isAccessible = true
                HookKit.hookMethod(method, TAG) { chain ->
                    if (!HookState.active()) return@hookMethod chain.proceed()
                    val good = spoof(chain.thisObject) ?: return@hookMethod chain.proceed()
                    val args = chain.args.copyOf()
                    args[0] = good
                    args[1] = ArrayList<Location>(listOf(good))
                    chain.proceed(args)
                }
            }
        }
        for (name in STARTERS) {
            val clazz = HookKit.findClass(loader, name) ?: continue
            if (!HookKit.firstTime("miniapp-start-$name")) continue
            HookKit.hookAll(clazz, "j", TAG) { chain ->
                val result = chain.proceed()
                if (HookState.active()) {
                    val callback = chain.args.lastOrNull { it != null && it !is Context && it !is String }
                    if (callback != null) {
                        runCatching { send(callback) }
                            .onFailure { HookKit.error("MiniApp", "start push failed", it) }
                    }
                }
                result
            }
        }
        for (name in PLUGINS) {
            val clazz = HookKit.findClass(loader, name) ?: continue
            if (!HookKit.firstTime("miniapp-plugin-$name")) continue
            HookKit.hookAll(clazz, "startLocationUpdate", TAG) { chain ->
                val result = chain.proceed()
                val plugin = chain.thisObject
                if (HookState.active() && plugin != null) {
                    val handler = Handler(Looper.getMainLooper())
                    handler.post { emit(plugin) }
                    handler.postDelayed({ emit(plugin) }, 400)
                }
                result
            }
        }
        for (name in GATES) {
            val clazz = HookKit.findClass(loader, name) ?: continue
            if (!HookKit.firstTime("miniapp-gate-$name")) continue
            for (method in clazz.declaredMethods) {
                if (method.parameterTypes.none { Location::class.java.isAssignableFrom(it) }) continue
                method.isAccessible = true
                HookKit.hookMethod(method, TAG) { chain -> rewrite(chain) }
            }
        }
        for (name in GET_LOCATION) {
            val clazz = HookKit.findClass(loader, name) ?: continue
            if (!HookKit.firstTime("miniapp-get-$name")) continue
            HookKit.hookAll(clazz, "getLocationAsync", TAG) { chain ->
                val result = chain.proceed()
                val plugin = chain.thisObject
                val callback = chain.args.lastOrNull()
                if (HookState.active() && plugin != null && callback != null) {
                    Handler(Looper.getMainLooper()).post {
                        runCatching { completeGetLocation(plugin, callback) }
                            .onFailure { HookKit.error("MiniApp", "complete getLocation failed", it) }
                    }
                }
                result
            }
        }
        for (name in ERRORS) {
            val clazz = HookKit.findClass(loader, name) ?: continue
            if (!HookKit.firstTime("miniapp-error-$name")) continue
            HookKit.hookAll(clazz, "b", TAG) { chain ->
                if (!HookState.active()) return@hookAll chain.proceed()
                val callback = chain.thisObject
                if (callback != null && send(callback)) return@hookAll null
                chain.proceed()
            }
        }
    }

    private fun locationCallbacks(clazz: Class<*>): List<Method> =
        HookKit.methodsNamed(clazz, "a").filter { method ->
            val types = method.parameterTypes
            types.size == 2 &&
                Location::class.java.isAssignableFrom(types[0]) &&
                java.util.List::class.java.isAssignableFrom(types[1])
        }

    private fun rewrite(chain: HookKit.Chain): Any? {
        if (!HookState.active()) return chain.proceed()
        val good = spoof(chain.thisObject) ?: return chain.proceed()
        val args = chain.args.copyOf()
        var replaced = false
        for (index in args.indices) {
            if (args[index] is Location) {
                args[index] = good
                replaced = true
            }
        }
        if (!replaced) return chain.proceed()
        return chain.proceed(args)
    }

    private fun completeGetLocation(plugin: Any, callback: Any) {
        val responseClass = plugin.javaClass.declaredClasses.firstOrNull { clazz ->
            clazz.simpleName.contains("LocationResponse")
        } ?: run {
            HookKit.error("MiniApp", "no LocationResponse on ${plugin.javaClass.name}")
            return
        }
        val constructor = responseClass.declaredConstructors.minBy { it.parameterTypes.size }
        constructor.isAccessible = true
        val response = when (constructor.parameterTypes.size) {
            0 -> constructor.newInstance()
            1 -> constructor.newInstance(plugin)
            else -> return
        }
        val (lat, lng) = HookState.positionIn(Datum.GCJ02)
        setNumber(response, "mLatitude", lat)
        setNumber(response, "mLongitude", lng)
        setNumber(response, "mAccuracy", 15f)
        setNumber(response, "mHorizontalAccuracy", 15f)
        setNumber(response, "mTimestamp", System.currentTimeMillis().toFloat())
        setNumber(response, "mVerticalAccuracy", 0)
        setText(response, "mType", "gcj02")
        setText(response, "type", "gcj02")
        val deliver = callback.javaClass.methods.firstOrNull { method ->
            method.parameterTypes.size == 1 && !method.parameterTypes[0].isPrimitive
        } ?: return
        deliver.isAccessible = true
        deliver.invoke(callback, response)
        HookKit.error("MiniApp", "getLocation answered ${lat}, ${lng}")
    }

    private fun setNumber(target: Any, name: String, value: Number) {
        val field = target.javaClass.declaredFields.firstOrNull { it.name == name } ?: return
        field.isAccessible = true
        when (field.type) {
            java.lang.Float.TYPE -> field.setFloat(target, value.toFloat())
            java.lang.Double.TYPE -> field.setDouble(target, value.toDouble())
            java.lang.Integer.TYPE -> field.setInt(target, value.toInt())
            java.lang.Long.TYPE -> field.setLong(target, value.toLong())
        }
    }

    private fun setText(target: Any, name: String, value: String) {
        val field = target.javaClass.declaredFields.firstOrNull { it.name == name } ?: return
        field.isAccessible = true
        if (field.type == String::class.java) field.set(target, value)
    }

    private fun emit(plugin: Any) {
        val callback = plugin.javaClass.declaredFields.firstNotNullOfOrNull { field ->
            runCatching {
                field.isAccessible = true
                field.get(plugin)
            }.getOrNull()?.takeIf { value ->
                value.javaClass.methods.any { it.name == "c" && it.parameterTypes.size == 4 }
            }
        } ?: run {
            HookKit.error("MiniApp", "no location callback on ${plugin.javaClass.name}")
            return
        }
        runCatching { send(callback) }
            .onSuccess { sent ->
                if (!sent) HookKit.error("MiniApp", "callback ${callback.javaClass.name} has no onLocationChange method")
            }
            .onFailure { HookKit.error("MiniApp", "emit failed", it) }
    }

    /** @return true when a success callback was invoked. */
    private fun send(callback: Any): Boolean {
        val method = callback.javaClass.methods.firstOrNull { candidate ->
            val types = candidate.parameterTypes
            types.size == 4 &&
                java.util.List::class.java.isAssignableFrom(types[0]) &&
                Location::class.java.isAssignableFrom(types[1]) &&
                types[2] == String::class.java &&
                types[3] == Integer.TYPE
        } ?: return false
        val good = spoof(callback) ?: return false
        method.isAccessible = true
        method.invoke(callback, ArrayList<Location>(listOf(good)), good, "gcj02", 1)
        return true
    }

    private fun spoof(owner: Any?): Location? {
        val loader = owner?.javaClass?.classLoader ?: return null
        val larkClass = HookKit.findClass(loader, LARK_LOCATION) ?: return null
        val fix = HookState.buildLocation(null, "gps")
        val (lat, lng) = HookState.positionIn(Datum.GCJ02)
        fix.latitude = lat
        fix.longitude = lng
        val created = larkClass.getConstructor(Location::class.java).newInstance(fix) as Location
        created.latitude = lat
        created.longitude = lng
        created.accuracy = fix.accuracy
        larkClass.methods.firstOrNull { method ->
            method.name == "u" && method.parameterTypes.contentEquals(arrayOf(Integer.TYPE))
        }?.apply { isAccessible = true }?.invoke(created, 0)
        return created
    }
}
