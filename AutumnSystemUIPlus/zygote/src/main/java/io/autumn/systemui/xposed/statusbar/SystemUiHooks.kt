package io.autumn.systemui.xposed.statusbar

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import io.autumn.systemui.ModuleConfig
import io.autumn.systemui.xposed.util.DexClassIndex
import io.autumn.systemui.xposed.util.VolteNames
import io.autumn.systemui.zygote.ArtHooks
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 始终隐藏状态栏 VoLTE / VoNR，下拉通知栏或控制中心也不显示。
 * 类名来自 SystemUI dex。
 */
object SystemUiHooks {
    private const val TAG = ModuleConfig.TAG
    private val hooked = ConcurrentHashMap.newKeySet<Executable>()
    private val prepared = ConcurrentHashMap.newKeySet<ClassLoader>()
    private val loadClassHooked = AtomicBoolean(false)
    private val seenClasses = ConcurrentHashMap.newKeySet<String>()
    private val pending = ArrayDeque<Class<*>>()
    private val draining = AtomicBoolean(false)
    private val volteMethods = HashMap<String, MutableSet<String>>()
    private val indexedClasses = LinkedHashSet<String>()
    private val notes = ArrayDeque<String>()
    private val resourceNames = HashMap<Int, String?>()
    private var frameworkReady = false
    private var reportScheduled = false
    private var reportRetries = 0
    private var appContext: Application? = null

    private val booleanExpand = setOf(
        "setExpanded",
        "setQsExpanded",
        "setPanelExpanded",
        "setControlCenterExpanded",
        "onExpandChange",
        "setExpandedState",
    )
    private val floatExpand = setOf(
        "setExpandedFraction",
        "setExpandFraction",
        "setExpandProgress",
    )

    @JvmStatic
    fun install(packageName: String) {
        if (!packageName.contains("systemui")) return
        VolteVisibility.hideCollapsed = ModuleConfig.hideVolteCollapsed
        SliderDetail.enabled = ModuleConfig.blockSliderDetail
        FodTimeoutWake.enabled = ModuleConfig.fodTimeoutWake
        FodTimeoutWake.timeoutMs = ModuleConfig.fodTimeoutMs
        ConnectivityStack.enabled = ModuleConfig.stackConnectivity
        LockscreenRightPage.enabled = ModuleConfig.blockLockscreenRightPage
        LockscreenTimeout.enabled = ModuleConfig.lockscreenIgnoreTouch
        LockscreenTimeout.timeoutMs = ModuleConfig.lockscreenTimeoutMs
        if (packageName == ModuleConfig.SYSTEM_UI) LockscreenTimeout.installPowerHook()
        if (!frameworkReady) {
            frameworkReady = true
            installFrameworkHooks()
            hookLoadClass()
            hookMakeApplication()
            hookAttach()
        }
    }

    private fun hookAttach() {
        try {
            var type: Class<*>? = Application::class.java
            var method: Method? = null
            while (type != null && method == null) {
                method = type.declaredMethods.firstOrNull { candidate ->
                    candidate.name == "attachBaseContext" && candidate.parameterCount == 1
                }
                type = type.superclass
            }
            if (method == null) {
                Log.e(TAG, "attachBaseContext missing")
                return
            }
            hook(method) { chain ->
                val result = chain.proceed()
                val app = chain.thisObject as? Application
                if (app != null && app.packageName.contains("systemui")) {
                    onApplication(app)
                }
                result
            }
            Log.i(TAG, "attach hooked")
        } catch (error: Throwable) {
            Log.e(TAG, "attach", error)
        }
    }

    private fun onApplication(app: Application) {
        appContext = app
        watchLockscreen(app)
        val loader = app.classLoader ?: return
        if (!prepared.add(loader)) {
            scheduleReport()
            return
        }
        indexApk(app.applicationInfo)
        if (app.packageName == ModuleConfig.SYSTEM_UI) {
            installFod(loader)
            installLockscreen(loader)
        }
        loadIndexedClasses(loader)
        installHeaderExpansion(loader)
        findLoadedControllers()
        scheduleReport()
        Log.i(TAG, "ready ${app.packageName}")
    }

    private fun hookMakeApplication() {
        try {
            val loadedApk = Class.forName("android.app.LoadedApk")
            val methods = loadedApk.declaredMethods.filter { it.name.startsWith("makeApplication") }
            if (methods.isEmpty()) {
                Log.e(TAG, "makeApplication missing")
                return
            }
            methods.forEach { method ->
                hook(method) { chain ->
                    val apk = chain.thisObject
                    if (apk != null) prepare(apk)
                    val result = chain.proceed()
                    val app = result as? Application
                    if (app != null && app.packageName.contains("systemui")) {
                        attachApplication(app)
                    }
                    result
                }
                Log.i(TAG, "hooked ${method.name}/${method.parameterCount}")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "makeApplication", error)
        }
    }

    private fun prepare(apk: Any) {
        val info = applicationInfo(apk) ?: return
        if (!info.packageName.contains("systemui")) return
        val loader = classLoader(apk) ?: return
        if (!prepared.add(loader)) return
        indexApk(info)
        if (info.packageName == ModuleConfig.SYSTEM_UI) {
            installFod(loader)
            installLockscreen(loader)
        }
        loadIndexedClasses(loader)
        installHeaderExpansion(loader)
        findLoadedControllers()
        scheduleReport()
    }

    private fun applicationInfo(apk: Any): ApplicationInfo? {
        return try {
            val method = apk.javaClass.methods.firstOrNull {
                it.name == "getApplicationInfo" && it.parameterCount == 0
            }
            if (method != null) {
                method.invoke(apk) as? ApplicationInfo
            } else {
                val field = apk.javaClass.getDeclaredField("mApplicationInfo")
                field.isAccessible = true
                field.get(apk) as? ApplicationInfo
            }
        } catch (error: Throwable) {
            note("applicationInfo: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun classLoader(apk: Any): ClassLoader? {
        return try {
            apk.javaClass.getMethod("getClassLoader").invoke(apk) as? ClassLoader
        } catch (error: Throwable) {
            note("classLoader: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun installHeaderExpansion(loader: ClassLoader) {
        try {
            val property = Class.forName("com.miui.systemui.util.FloatFlowProperty", false, loader)
            val setValue = property.getDeclaredMethod("setValue", Object::class.java, java.lang.Float.TYPE)
            hook(setValue) { chain ->
                val result = chain.proceed()
                val owner = chain.thisObject
                if (owner?.javaClass?.name == "com.android.systemui.shade.NotificationPanelExpansionAnimator") {
                    val value = (chain.getArg(1) as? Number)?.toFloat()
                    if (value != null) PanelState.onHeaderExpansion(value)
                }
                result
            }
            note("header expansion hooked")
        } catch (error: Throwable) {
            note("header expansion: ${error.javaClass.simpleName}")
        }
    }

    fun attachApplication(app: Application) {
        appContext = app
        watchLockscreen(app)
        scheduleReport()
    }

    private fun watchLockscreen(app: Application) {
        if (app.packageName == ModuleConfig.SYSTEM_UI) LockscreenTimeout.start(app)
    }

    private fun installFrameworkHooks() {
        hook(View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)) { chain ->
            val view = chain.thisObject as? View
            if (view == null || !VolteVisibility.isTracked(view)) {
                chain.proceed()
            } else {
                val incoming = (chain.getArg(0) as Number).toInt()
                val target = VolteVisibility.onSystemVisibility(view, incoming)
                if (target == incoming) chain.proceed() else chain.proceed(arrayOf<Any>(target))
                VolteVisibility.sync(view)
            }
        }
        hook(View::class.java.getDeclaredMethod("setAlpha", Float::class.javaPrimitiveType)) { chain ->
            val view = chain.thisObject as? View
            val incoming = (chain.getArg(0) as? Number)?.toFloat() ?: return@hook chain.proceed()
            if (view == null || !VolteVisibility.isTracked(view)) {
                chain.proceed()
            } else {
                val target = VolteVisibility.onSystemAlpha(view, incoming)
                if (target == incoming) chain.proceed() else chain.proceed(arrayOf<Any>(target))
            }
        }
        hook(ImageView::class.java.getDeclaredMethod("setImageResource", Int::class.javaPrimitiveType)) { chain ->
            val result = chain.proceed()
            val view = chain.thisObject as? ImageView
            val resId = (chain.getArg(0) as? Number)?.toInt() ?: 0
            if (view != null && resId != 0) {
                val name = resourceName(view, resId)
                if (name != null && VolteNames.isVolteResource(name)) {
                    VolteVisibility.track(view, TrackReason.RESOURCE)
                } else if (VolteVisibility.reason(view) == TrackReason.RESOURCE) {
                    VolteVisibility.untrack(view)
                }
            }
            result
        }
        TextView::class.java.declaredMethods
            .filter { method ->
                method.name == "setText" &&
                    method.parameterTypes.firstOrNull()?.let { CharSequence::class.java.isAssignableFrom(it) } == true
            }
            .forEach { method ->
                hook(method) { chain ->
                    val result = chain.proceed()
                    val text = chain.getArg(0)?.toString().orEmpty()
                    val view = chain.thisObject as? TextView
                    if (view != null && VolteNames.isVolteText(text)) {
                        VolteVisibility.track(view, TrackReason.TEXT)
                    }
                    result
                }
            }
        hook(View::class.java.getDeclaredMethod("setContentDescription", CharSequence::class.java)) { chain ->
            val result = chain.proceed()
            val view = chain.thisObject as? View
            val text = chain.getArg(0)?.toString().orEmpty()
            if (view != null && VolteNames.isVolteText(text)) {
                VolteVisibility.track(view, TrackReason.TEXT)
            }
            result
        }
    }

    private fun hookLoadClass() {
        if (!loadClassHooked.compareAndSet(false, true)) return
        val signatures = listOf(
            ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java),
            ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java, Boolean::class.javaPrimitiveType),
        )
        signatures.forEach { method ->
            hook(method) { chain ->
                val loaded = chain.proceed()
                val name = chain.getArg(0) as? String
                if (name != null && name.contains("GxzwIconView")) {
                    Log.i(TAG, "seen $name")
                }
                if (loaded is Class<*> && name != null && interesting(name)) enqueue(loaded)
                loaded
            }
        }
    }

    private fun indexApk(info: ApplicationInfo) {
        val paths = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
        paths.forEach { path ->
            try {
                val index = DexClassIndex.readApk(path)
                index.volteMethods.forEach { ref ->
                    volteMethods.getOrPut(ref.className) { LinkedHashSet() }.add(ref.methodName)
                }
                indexedClasses += index.panelClasses
                indexedClasses += index.iconClasses
                indexedClasses += volteMethods.keys
                note("dex ${path.substringAfterLast('/')}: panel=${index.panelClasses.size} icon=${index.iconClasses.size} volteMethods=${index.volteMethods.size}")
            } catch (error: Throwable) {
                note("dex fail $path: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun loadIndexedClasses(loader: ClassLoader) {
        val names = indexedClasses.filter { name ->
            '$' !in name || name.lowercase().contains("volte") || name.lowercase().contains("vonr")
        }.take(240)
        names.forEach { name -> loadQuietly(loader, name) }
    }

    /** 控制中心类在插件 ClassLoader 里。热重载时它们已经加载完，loadClass 不会再进来。 */
    private fun findLoadedControllers() {
        if (!processAlreadyRunning()) return
        val findLoaded = try {
            ClassLoader::class.java.getDeclaredMethod("findLoadedClass", String::class.java).apply {
                isAccessible = true
            }
        } catch (error: Throwable) {
            note("findLoadedClass: ${error.javaClass.simpleName}")
            return
        }
        val names = listOf(SliderDetail.BRIGHTNESS, SliderDetail.VOLUME)
        classLoaders().forEach { loader ->
            names.forEach { name ->
                try {
                    val cls = findLoaded.invoke(loader, name) as? Class<*> ?: return@forEach
                    enqueue(cls)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun processAlreadyRunning(): Boolean {
        return try {
            val thread = Class.forName("android.app.ActivityThread")
            val current = thread.getDeclaredMethod("currentApplication")
            current.invoke(null) != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun classLoaders(): List<ClassLoader> {
        val found = LinkedHashSet<ClassLoader>()
        appContext?.classLoader?.let { found += it }
        try {
            val vmDebug = Class.forName("dalvik.system.VMDebug")
            val method = vmDebug.getDeclaredMethod(
                "getInstancesOfClasses",
                emptyArray<Class<*>>().javaClass,
                Boolean::class.javaPrimitiveType,
            )
            val groups = method.invoke(null, arrayOf(ClassLoader::class.java), true) as? Array<*>
            val loaders = groups?.firstOrNull() as? Array<*>
            loaders?.forEach { loader ->
                if (loader is ClassLoader) found += loader
            }
        } catch (error: Throwable) {
            note("class loaders: ${error.javaClass.simpleName}")
        }
        return found.toList()
    }

    private fun loadQuietly(loader: ClassLoader, name: String) {
        try {
            val cls = Class.forName(name, false, loader)
            enqueue(cls)
        } catch (_: Throwable) {
        }
    }

    private fun interesting(name: String): Boolean {
        if (name.startsWith("java.") || name.startsWith("android.") || name.startsWith("androidx.") || name.startsWith("kotlin")) {
            return false
        }
        val lower = name.lowercase()
        return name == FodTimeoutWake.ICON ||
            name == LockscreenRightPage.CONTROLLER ||
            DexClassIndex.isPanelClass(lower) ||
            DexClassIndex.isIconClass(lower) ||
            name in volteMethods
    }

    private fun enqueue(cls: Class<*>) {
        synchronized(pending) { pending.add(cls) }
        drain()
    }

    private fun drain() {
        if (!draining.compareAndSet(false, true)) return
        try {
            while (true) {
                val cls = synchronized(pending) {
                    if (pending.isEmpty()) null else pending.removeFirst()
                } ?: break
                if (!seenClasses.add(cls.name)) continue
                try {
                    installClass(cls)
                } catch (error: Throwable) {
                    note("install ${cls.name}: ${error.javaClass.simpleName}")
                }
            }
        } finally {
            draining.set(false)
        }
        val more = synchronized(pending) { pending.isNotEmpty() }
        if (more) drain()
        scheduleReport()
    }

    private fun installClass(cls: Class<*>) {
        val lower = cls.name.lowercase()
        ConnectivityStack.install(cls)
        if (cls.name == FodTimeoutWake.ICON) installFodTouch(cls)
        if (cls.name == LockscreenRightPage.CONTROLLER) installLockscreenRight(cls)
        if (DexClassIndex.isPanelClass(lower)) installPanel(cls)
        if (cls.name == SliderDetail.BRIGHTNESS || cls.name == SliderDetail.VOLUME) {
            installSliderLongClick(cls)
        }
        if (DexClassIndex.isIconClass(lower)) installIconClass(cls)
        val methodNames = volteMethods[cls.name].orEmpty()
        if (methodNames.isNotEmpty()) installVolteMethods(cls, methodNames)
    }

    private fun installLockscreen(loader: ClassLoader) {
        try {
            enqueue(Class.forName(LockscreenRightPage.CONTROLLER, false, loader))
        } catch (error: Throwable) {
            Log.e(TAG, "lockscreen right", error)
        }
    }

    private fun installLockscreenRight(cls: Class<*>) {
        try {
            val allow = cls.getDeclaredMethod("supportMoveToRight")
            val move = cls.getDeclaredMethod("onTouchMove", java.lang.Float.TYPE, java.lang.Float.TYPE)
            hook(allow) { chain ->
                if (LockscreenRightPage.enabled) false else chain.proceed()
            }
            hook(move) { chain ->
                if (LockscreenRightPage.enabled) false else chain.proceed()
            }
            Log.i(TAG, "lockscreen right swipe blocked")
        } catch (error: Throwable) {
            Log.e(TAG, "lockscreen right", error)
        }
    }

    private fun installFod(loader: ClassLoader) {
        try {
            enqueue(Class.forName(FodTimeoutWake.ICON, false, loader))
        } catch (error: Throwable) {
            Log.e(TAG, "fod class", error)
        }
    }

    private fun installFodTouch(cls: Class<*>) {
        try {
            val down = cls.getDeclaredMethod("onTouchDown")
            val up = cls.getDeclaredMethod("onTouchUp", java.lang.Boolean.TYPE)
            hook(down) { chain ->
                val view = chain.thisObject
                val already = view != null && FodTimeoutWake.alreadyDown(view)
                val result = chain.proceed()
                if (view != null && !already) FodTimeoutWake.onPressed(view)
                result
            }
            hook(up) { chain ->
                chain.thisObject?.let { FodTimeoutWake.onReleased(it) }
                chain.proceed()
            }
            Log.i(TAG, "fod timeout wake hooked")
        } catch (error: Throwable) {
            Log.e(TAG, "fod timeout wake", error)
        }
    }

    private fun installSliderLongClick(cls: Class<*>) {
        val methods = cls.declaredMethods.filter { candidate ->
            (candidate.name == "supportLongClick" || candidate.name == "getSupportLongClick") &&
                candidate.parameterCount == 0 &&
                isBoolean(candidate.returnType) &&
                !Modifier.isAbstract(candidate.modifiers)
        }
        if (methods.isEmpty()) {
            Log.w(TAG, "slider detail: ${cls.name} 没有 supportLongClick")
            return
        }
        methods.forEach { method ->
            hook(method) { chain ->
                if (SliderDetail.enabled) false else chain.proceed()
            }
        }
        Log.i(TAG, "slider detail ${cls.simpleName}")
    }

    private fun installPanel(cls: Class<*>) {
        val controlCenter = cls.name.lowercase().let { it.contains("controlcenter") || it.contains("controlpanel") }
        val hookedNames = ArrayList<String>()
        cls.declaredMethods.forEach { method ->
            if (Modifier.isAbstract(method.modifiers) || method.parameterCount == 0) return@forEach
            val reader = expansionReader(method) ?: return@forEach
            hook(method) { chain ->
                val result = chain.proceed()
                val fraction = reader(chain.getArgs())
                if (fraction != null) PanelState.update(controlCenter, fraction)
                result
            }
            hookedNames += method.name
        }
        if (hookedNames.isNotEmpty()) {
            note("panel ${cls.name} [${hookedNames.distinct().joinToString()}]")
        }
    }

    private fun expansionReader(method: Method): ((List<Any?>) -> Float?)? {
        val types = method.parameterTypes
        val first = types.firstOrNull() ?: return null
        if (method.name in booleanExpand && isBoolean(first)) {
            return { args -> if (args.firstOrNull() as? Boolean == true) 1f else 0f }
        }
        if (method.name in floatExpand && isFloat(first)) {
            return { args -> (args.firstOrNull() as? Number)?.toFloat() }
        }
        if (method.name == "onPanelExpansionChanged" && isFloat(first)) {
            return { args -> (args.firstOrNull() as? Number)?.toFloat() }
        }
        return null
    }

    private fun installIconClass(cls: Class<*>) {
        val lower = cls.name.lowercase()
        val volteType = (lower.contains("volte") || lower.contains("vonr")) && View::class.java.isAssignableFrom(cls)
        val slotType = lower.contains("statusbariconview")
        if (!volteType && !slotType) return
        cls.declaredConstructors.forEach { constructor ->
            hook(constructor) { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? View
                if (view != null && volteType) {
                    VolteVisibility.track(view, TrackReason.CLASS)
                }
                if (view != null && slotType) {
                    val slot = chain.getArgs().firstOrNull { it is String } as? String
                    if (slot != null && VolteNames.isVolteSlot(slot)) {
                        VolteVisibility.track(view, TrackReason.SLOT)
                    }
                }
                result
            }
        }
        note("icon ${cls.name}")
    }

    private fun installVolteMethods(cls: Class<*>, names: Set<String>) {
        cls.declaredMethods
            .filter { it.name in names && !Modifier.isAbstract(it.modifiers) }
            .forEach { method ->
                val params = method.parameterTypes
                val returnsBoolean = isBoolean(method.returnType)
                when {
                    params.isEmpty() && returnsBoolean -> hook(method) { chain ->
                        val shown = chain.proceed() as? Boolean == true
                        if (shown && VolteVisibility.hideCollapsed) false else shown
                    }
                    params.size == 1 && isBoolean(params[0]) -> hook(method) { chain ->
                        val show = chain.getArg(0) as? Boolean == true
                        chain.thisObject?.let { VolteVisibility.rememberSetter(it, method, show) }
                        val hide = VolteVisibility.hideCollapsed
                        if (hide && show) chain.proceed(arrayOf<Any>(false)) else chain.proceed()
                    }
                    else -> note("skip ${cls.name}#${method.name}")
                }
            }
        note("volte ${cls.name} [${names.joinToString()}]")
    }

    internal fun hook(
        executable: Executable,
        block: (ArtHooks.Call) -> Any?,
    ) {
        if (!hooked.add(executable)) return
        try {
            ArtHooks.hook(executable) { chain ->
                try {
                    block(chain)
                } catch (error: Throwable) {
                    if (chain.proceeded || error is ClassNotFoundException || error is NoClassDefFoundError) {
                        throw error
                    }
                    Log.e(TAG, "hook ${executable.name}", error)
                    chain.proceed()
                }
            }
        } catch (error: Throwable) {
            hooked.remove(executable)
            note("hook fail ${executable.name}: ${error.javaClass.simpleName}")
        }
    }

    private fun resourceName(view: View, resId: Int): String? {
        resourceNames[resId]?.let { return it }
        val name = try {
            view.resources.getResourceEntryName(resId)
        } catch (_: Throwable) {
            null
        }
        if (resourceNames.size > 512) resourceNames.clear()
        resourceNames[resId] = name
        return name
    }

    private fun scheduleReport() {
        if (reportScheduled) return
        val looper = Looper.getMainLooper() ?: return
        reportScheduled = true
        Handler(looper).postDelayed({
            reportScheduled = false
            publishReport()
        }, 900)
    }

    private fun publishReport() {
        val lines = synchronized(notes) { notes.toList() }
        val text = buildString {
            appendLine("AutumnSystemUI")
            appendLine("hideCollapsed=${VolteVisibility.hideCollapsed}")
            appendLine("blockSliderDetail=${SliderDetail.enabled}")
            appendLine("fodWake=${FodTimeoutWake.enabled} timeout=${FodTimeoutWake.timeoutMs}")
            appendLine("stackConnectivity=${ConnectivityStack.enabled} ${ConnectivityStack.status}")
            appendLine("blockLockscreenRightPage=${LockscreenRightPage.enabled}")
            appendLine("lockscreenTimeout=${LockscreenTimeout.status}")
            lines.take(120).forEach { appendLine(it) }
        }
        Log.i(TAG, text)
        val context = appContext
        if (context == null) {
            if (reportRetries < 5) {
                reportRetries++
                reportScheduled = false
                scheduleReport()
            }
            return
        }
        writeReport(context, text)
    }

    private fun writeReport(context: Context, text: String) {
        val dirs = LinkedHashSet<File>()
        try {
            dirs += context.createDeviceProtectedStorageContext().filesDir
        } catch (_: Throwable) {
        }
        dirs += context.filesDir
        var wrote = false
        dirs.forEach { dir ->
            try {
                File(dir, ModuleConfig.REPORT_FILE).writeText(text)
                wrote = true
            } catch (error: Throwable) {
                Log.w(TAG, "report ${dir.path}", error)
            }
        }
        if (!wrote) Log.w(TAG, "report file was not written")
    }

    private fun note(line: String) {
        synchronized(notes) {
            notes.addLast(line)
            while (notes.size > 160) notes.removeFirst()
        }
    }

    private fun isBoolean(type: Class<*>): Boolean {
        return type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java
    }

    private fun isFloat(type: Class<*>): Boolean {
        return type == java.lang.Float.TYPE || type == java.lang.Float::class.java
    }
}
