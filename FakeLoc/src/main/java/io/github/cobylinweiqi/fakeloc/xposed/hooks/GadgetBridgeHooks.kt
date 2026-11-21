package io.github.cobylinweiqi.fakeloc.xposed.hooks

import android.os.Handler
import android.os.Looper
import io.github.cobylinweiqi.fakeloc.core.Datum
import io.github.cobylinweiqi.fakeloc.xposed.HookKit
import io.github.cobylinweiqi.fakeloc.xposed.HookState
import io.github.cobylinweiqi.fakeloc.xposed.SdkDispatch
import org.json.JSONObject
import java.lang.reflect.Method

/**
 * Feishu's attendance page does not call the location plugins.
 *
 * The punch script runs in the AHA worker. `tt.getLocation` and
 * `tt.startLocationUpdate` enter `BridgeModule.invoke` / `call` /
 * `invokeNative`. A non-empty return is the sync success (`errMsg` containing
 * `:ok`). The same payload is also posted to `invokeHandler`, which is the
 * async path. The fix itself arrives as `onLocationChange`; the page paints
 * 定位失败 when that event is still missing at the timeout.
 */
internal object GadgetBridgeHooks {

    private const val TAG = "FakeLoc/Bridge"
    private const val BRIDGE = "com.ss.android.lark.gadget.engine.worker.aha.module.BridgeModule"
    private const val TT_BRIDGE = "com.ss.android.lark.gadget.engine.worker.aha.jsmodule.TTJSBridge"
    private const val JAVA_ONLY_MAP = "com.bytedance.aha.runtime.vmsdk.jsbridge.utils.JavaOnlyMap"

    fun install(loader: ClassLoader) {
        val clazz = HookKit.findClass(loader, BRIDGE) ?: return
        if (!HookKit.firstTime("gadget-bridge")) return
        var hooked = 0
        for (method in clazz.declaredMethods) {
            if (method.name != "invoke" && method.name != "call" && method.name != "invokeNative") continue
            method.isAccessible = true
            if (HookKit.hookMethod(method, TAG) { chain -> dispatch(chain) }) hooked++
        }
        HookKit.error("Bridge", "armed $hooked bridge method(s)")
    }

    private fun dispatch(chain: HookKit.Chain): Any? {
        val method = chain.executable as? Method ?: return chain.proceed()
        val api: String?
        val params: Any?
        if (method.name == "invokeNative") {
            val map = chain.args.getOrNull(0)
            api = text(map, "apiName")
            params = dataOf(map)
        } else {
            api = chain.args.getOrNull(0) as? String
            params = chain.args.getOrNull(1)
        }
        return answer(chain, method, api, params)
    }

    private fun answer(chain: HookKit.Chain, method: Method, api: String?, params: Any?): Any? {
        if (api == null || !HookState.active()) return chain.proceed()
        val kind = kind(api) ?: run {
            if (looksLikeLocation(api) && HookKit.firstTime("bridge-saw-$api")) {
                HookKit.error("Bridge", "saw $api")
            }
            return chain.proceed()
        }
        val type = requestedType(params)
        val json = when (kind) {
            Kind.FIX -> fixJson(api, type)
            Kind.START -> ack(api, type)
            Kind.STOP -> ack(api, null)
            Kind.STATUS -> statusJson()
        }
        HookKit.error("Bridge", "${method.name} $api type=$type ${json.take(180)}")
        val module = chain.thisObject
        if (module != null) {
            val callbackId = callbackId(chain)
            if (callbackId != null) complete(module, callbackId, json)
            if (kind == Kind.FIX || kind == Kind.START) {
                SdkDispatch.markDelivered()
                push(module, eventJson(type))
            }
        }
        if (method.returnType == String::class.java) return json
        val map = syncMap(method.declaringClass.classLoader, json)
        if (map != null) return map
        HookKit.error("Bridge", "no JavaOnlyMap for $api")
        return json
    }

    private enum class Kind { FIX, START, STOP, STATUS }

    private fun kind(api: String): Kind? {
        val leaf = api.substringAfterLast('.').substringAfterLast('/')
        return when (leaf) {
            "getLocation", "getLocationSync", "getLocationV2" -> Kind.FIX
            "startLocationUpdate", "startLocationUpdateV2" -> Kind.START
            "stopLocationUpdate", "stopLocationUpdateV2" -> Kind.STOP
            "getLocationStatus" -> Kind.STATUS
            else -> null
        }
    }

    private fun looksLikeLocation(api: String): Boolean {
        val leaf = api.lowercase()
        return leaf.contains("location") || leaf.contains("gps")
    }

    private fun callbackId(chain: HookKit.Chain): Int? {
        for (arg in chain.args) {
            if (arg is Int && arg >= 0) return arg
        }
        val raw = text(chain.args.getOrNull(0), "callbackID") ?: return null
        return raw.toIntOrNull()?.takeIf { it >= 0 }
    }

    private fun requestedType(params: Any?): String {
        val raw = when (params) {
            is String -> params
            else -> text(params, "type") ?: return "wgs84"
        }
        return if (raw.contains("gcj02")) "gcj02" else "wgs84"
    }

    private fun dataOf(map: Any?): Any? {
        if (map == null) return null
        val nested = runCatching {
            map.javaClass.methods.firstOrNull { it.name == "getMap" && it.parameterTypes.size == 1 }
                ?.invoke(map, "data")
        }.getOrNull()
        if (nested != null) return nested
        return text(map, "data")
    }

    private fun fixJson(api: String, type: String): String {
        val (lat, lng) = coords(type)
        val accuracy = accuracy()
        return JSONObject()
            .put("errMsg", "$api:ok")
            .put("latitude", lat)
            .put("longitude", lng)
            .put("accuracy", accuracy)
            .put("horizontalAccuracy", accuracy)
            .put("verticalAccuracy", 0)
            .put("timestamp", System.currentTimeMillis())
            .put("type", type)
            .toString()
    }

    private fun eventJson(type: String): String {
        val (lat, lng) = coords(type)
        val accuracy = accuracy()
        return JSONObject()
            .put("latitude", lat)
            .put("longitude", lng)
            .put("accuracy", accuracy)
            .put("horizontalAccuracy", accuracy)
            .put("verticalAccuracy", 0)
            .put("timestamp", System.currentTimeMillis())
            .put("type", type)
            .toString()
    }

    private fun ack(api: String, type: String?): String {
        val json = JSONObject().put("errMsg", "$api:ok")
        if (type != null) json.put("type", type)
        return json.toString()
    }

    private fun statusJson(): String {
        return JSONObject()
            .put("errMsg", "getLocationStatus:ok")
            .put("gpsStatus", "on")
            .put("wifiStatus", "on")
            .toString()
    }

    private fun coords(type: String): Pair<Double, Double> {
        val datum = if (type == "gcj02") Datum.GCJ02 else Datum.WGS84
        return HookState.positionIn(datum)
    }

    private fun accuracy(): Double {
        val cfg = HookState.config()
        return if (cfg.useAccuracy) cfg.accuracy.toDouble() else 15.0
    }

    private fun complete(module: Any, callbackId: Int, json: String) {
        val worker = worker(module) ?: return
        post(worker, 0L) { invokeHandler(worker, callbackId, json) }
    }

    private fun push(module: Any, json: String) {
        val worker = worker(module) ?: return
        post(worker, 0L) { subscribe(worker, json) }
        post(worker, 400L) { subscribe(worker, json) }
    }

    private fun worker(module: Any): Any? {
        val found = HookKit.findField(module.javaClass, "mJSWorker")?.get(module)
        if (found == null) HookKit.error("Bridge", "no mJSWorker")
        return found
    }

    private fun invokeHandler(worker: Any, callbackId: Int, json: String) {
        val bridge = javascript(worker) ?: return
        val method = bridge.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "invokeHandler" && candidate.parameterTypes.size == 2
        } ?: run {
            HookKit.error("Bridge", "invokeHandler missing on ${bridge.javaClass.name}")
            return
        }
        method.invoke(bridge, callbackId, json)
    }

    private fun subscribe(worker: Any, json: String) {
        val bridge = javascript(worker) ?: return
        val method = bridge.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "subscribeHandler" &&
                candidate.parameterTypes.size == 2 &&
                candidate.parameterTypes[1] == String::class.java
        } ?: run {
            HookKit.error("Bridge", "subscribeHandler missing on ${bridge.javaClass.name}")
            return
        }
        method.invoke(bridge, "onLocationChange", json)
        if (HookKit.firstTime("bridge-subscribed")) HookKit.error("Bridge", "onLocationChange delivered")
    }

    private fun javascript(worker: Any): Any? {
        val workerLoader = worker.javaClass.classLoader
        val ownLoader = javaClass.classLoader
        val bridgeClass = (if (workerLoader != null) HookKit.findClass(workerLoader, TT_BRIDGE) else null)
            ?: if (ownLoader != null) HookKit.findClass(ownLoader, TT_BRIDGE) else null
            ?: run {
                HookKit.error("Bridge", "TTJSBridge class missing")
                return null
            }
        val getter = worker.javaClass.methods.firstOrNull { method ->
            method.name == "getJavaScriptModule" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Class::class.java
        } ?: run {
            HookKit.error("Bridge", "no getJavaScriptModule on ${worker.javaClass.name}")
            return null
        }
        return runCatching { getter.invoke(worker, bridgeClass) }
            .onFailure { HookKit.error("Bridge", "getJavaScriptModule failed", it) }
            .getOrNull()
    }

    private fun post(worker: Any, delayMs: Long, block: () -> Unit) {
        val runnable = Runnable {
            runCatching { block() }.onFailure { HookKit.error("Bridge", "js post failed", it) }
        }
        if (delayMs <= 0L) {
            val queued = worker.javaClass.methods.firstOrNull { method ->
                method.name == "postOnJSRunner" && method.parameterTypes.size == 1
            }
            if (queued != null && runCatching { queued.invoke(worker, runnable) }.isSuccess) return
        }
        val delayed = worker.javaClass.methods.firstOrNull { method ->
            method.name == "postOnJSRunnerDelay" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == Runnable::class.java
        }
        if (delayed != null && runCatching { delayed.invoke(worker, runnable, delayMs) }.isSuccess) return
        Handler(Looper.getMainLooper()).postDelayed(runnable, delayMs)
    }

    private fun syncMap(loader: ClassLoader?, json: String): Any? {
        if (loader == null) return null
        val clazz = HookKit.findClass(loader, JAVA_ONLY_MAP) ?: return null
        val map = runCatching {
            clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull() ?: return null
        val put = clazz.methods.firstOrNull { method ->
            method.name == "putString" && method.parameterTypes.size == 2
        } ?: return null
        put.invoke(map, "sync_response", json)
        return map
    }

    private fun text(target: Any?, key: String): String? {
        if (target == null) return null
        val method = target.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "getString" && candidate.parameterTypes.size == 1
        } ?: return null
        return runCatching { method.invoke(target, key) as? String }.getOrNull()
    }
}
