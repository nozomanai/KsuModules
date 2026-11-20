package io.autumn.systemui

import android.util.Log
import org.json.JSONObject
import java.io.File

object ModuleConfig {
    const val TAG = "AutumnSystemUI"
    const val MODULE_ID = "autumn_systemui"
    const val REPORT_FILE = "autumn_systemui_report.txt"

    const val KEY_HIDE_VOLTE_COLLAPSED = "hide_volte_collapsed"
    const val KEY_BLOCK_SLIDER_DETAIL = "block_slider_detail"
    const val KEY_FOD_TIMEOUT_WAKE = "fod_timeout_wake"
    const val KEY_FOD_TIMEOUT_MS = "fod_timeout_ms"
    const val KEY_STACK_CONNECTIVITY = "stack_connectivity"
    const val KEY_BLOCK_LOCKSCREEN_RIGHT_PAGE = "block_lockscreen_right_page"
    const val KEY_LOCKSCREEN_IGNORE_TOUCH = "lockscreen_ignore_touch"
    const val FOD_TIMEOUT_DEFAULT_MS = 300L
    const val FOD_TIMEOUT_MAX_MS = 60_000L
    const val SYSTEM_UI = "com.android.systemui"

    @Volatile
    var hideVolteCollapsed: Boolean = true

    @Volatile
    var blockSliderDetail: Boolean = false

    @Volatile
    var fodTimeoutWake: Boolean = true

    @Volatile
    var fodTimeoutMs: Long = FOD_TIMEOUT_DEFAULT_MS

    @Volatile
    var stackConnectivity: Boolean = true

    @Volatile
    var blockLockscreenRightPage: Boolean = true

    @Volatile
    var lockscreenIgnoreTouch: Boolean = true

    @JvmStatic
    fun load(moduleDir: String?) {
        if (moduleDir.isNullOrEmpty()) {
            Log.w(TAG, "module dir missing, using defaults")
            return
        }
        val file = File(moduleDir, "config.json")
        if (!file.isFile) {
            Log.i(TAG, "no config at ${file.path}, using defaults")
            return
        }
        try {
            val json = JSONObject(file.readText())
            hideVolteCollapsed = json.optBoolean(KEY_HIDE_VOLTE_COLLAPSED, true)
            blockSliderDetail = json.optBoolean(KEY_BLOCK_SLIDER_DETAIL, false)
            fodTimeoutWake = json.optBoolean(KEY_FOD_TIMEOUT_WAKE, true)
            fodTimeoutMs = json.optLong(KEY_FOD_TIMEOUT_MS, FOD_TIMEOUT_DEFAULT_MS)
                .coerceIn(0L, FOD_TIMEOUT_MAX_MS)
            stackConnectivity = json.optBoolean(KEY_STACK_CONNECTIVITY, true)
            blockLockscreenRightPage = json.optBoolean(KEY_BLOCK_LOCKSCREEN_RIGHT_PAGE, true)
            lockscreenIgnoreTouch = json.optBoolean(KEY_LOCKSCREEN_IGNORE_TOUCH, true)
            Log.i(
                TAG,
                "config hideVolte=$hideVolteCollapsed slider=$blockSliderDetail " +
                    "fod=$fodTimeoutWake timeout=$fodTimeoutMs stack=$stackConnectivity " +
                    "lockRight=$blockLockscreenRightPage lockTouch=$lockscreenIgnoreTouch",
            )
        } catch (error: Throwable) {
            Log.e(TAG, "config ${file.path}", error)
        }
    }
}
