package io.autumn.systemui.xposed.statusbar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.autumn.systemui.ModuleConfig
import io.autumn.systemui.zygote.ArtHooks
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * 控制中心播放卡片占亮度左边两格宽、两行高。
 * 打开后这块位置改成 WiFi 在上、移动数据在下，原来那一行大卡片不再占位。
 * 亮度、音量滑条的位置不动。
 *
 * 播放卡片会在主题或模糊就绪后再刷一次背景，给整块铺上灰色默认底，
 * 卡片自己的纯色背景和按下反馈也会被这次刷新换掉。刷完后清掉这块底。
 */
object ConnectivityStack {
    private const val TAG = ModuleConfig.TAG
    private const val MEDIA = "miui.systemui.controlcenter.panel.main.media.MediaPlayerController"
    private const val MEDIA_HOLDER =
        "miui.systemui.controlcenter.panel.main.media.MediaPlayerController\$MediaPlayerViewHolder"
    private const val HOLDER =
        "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelItemViewHolder"
    private const val CARDS = "miui.systemui.controlcenter.panel.main.qs.QSCardsController"
    private const val CARD_VIEW = "miui.systemui.controlcenter.qs.tileview.QSCardItemView"
    private const val ICON_VIEW = "miui.systemui.controlcenter.qs.tileview.QSCardItemIconView"
    private val specs = arrayOf("wifi", "cell")

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var status: String = "off"

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val roots = Collections.synchronizedMap(WeakHashMap<ViewGroup, Boolean>())
    private val mounted = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val cardSpecs = Collections.synchronizedMap(WeakHashMap<View, String>())
    private val boundCards = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val wiredTiles = Collections.synchronizedMap(WeakHashMap<View, Any>())
    @Volatile
    private var cards: Any? = null
    @Volatile
    private var panelStarted = false
    private var availableMethod: Method? = null

    fun install(cls: Class<*>) {
        when (cls.name) {
            MEDIA -> hookNamed(cls, "createViewHolder", 2, ::onCreateHolder)
            MEDIA_HOLDER -> {
                hookNamed(cls, "updateResources", 0, ::onMediaPaint)
                hookNamed(cls, "onConfigurationChanged", 1, ::onMediaPaint)
            }
            HOLDER -> hookNamed(cls, "updateBlendBlur", 0, ::onMediaPaint)
            CARDS -> {
                hookNamed(cls, "getListItems", 0, ::onListItems)
                hookNamed(cls, "onStart", 0, ::onPanelStart)
                hookNamed(cls, "onStop", 0, ::onPanelStop)
            }
            CARD_VIEW -> {
                hookNamed(cls, "updateSize", 0, ::onUpdateSize)
                hookNamed(cls, "getBackgroundBlendEnabled", 0, ::onBlendEnabled)
            }
        }
    }

    private fun onMediaPaint(chain: ArtHooks.Call): Any? {
        val result = chain.proceed()
        val holder = chain.thisObject
        if (holder != null && enabled) {
            if (Looper.myLooper() == Looper.getMainLooper()) restore(holder) else main.post { restore(holder) }
        }
        return result
    }

    private fun onBlendEnabled(chain: ArtHooks.Call): Any? {
        val card = chain.thisObject as? View
        if (enabled && card != null && cardSpecs.containsKey(card)) return false
        return chain.proceed()
    }

    private fun onCreateHolder(chain: ArtHooks.Call): Any? {
        val holder = chain.proceed()
        if (!enabled || holder == null) return holder
        val root = itemView(holder) as? ViewGroup ?: return holder
        if (Looper.myLooper() == Looper.getMainLooper()) mount(root) else main.post { mount(root) }
        return holder
    }

    private fun onListItems(chain: ArtHooks.Call): Any? {
        val listed = chain.proceed()
        val controller = chain.thisObject
        if (!enabled || controller == null || listed !is List<*>) return listed
        cards = controller
        main.post { bindWaiting() }
        if (!cardsOnScreen(controller)) return listed
        val kept = ArrayList<Any?>(listed.size)
        listed.forEach { item ->
            val spec = item?.let { specOf(it) }
            if (spec == null || spec !in specs) kept.add(item)
        }
        return kept
    }

    private fun onPanelStart(chain: ArtHooks.Call): Any? {
        val result = chain.proceed()
        panelStarted = true
        if (enabled) {
            cards = chain.thisObject ?: cards
            listen(true)
            main.post { bindWaiting() }
        }
        return result
    }

    private fun onPanelStop(chain: ArtHooks.Call): Any? {
        panelStarted = false
        return chain.proceed()
    }

    private fun onUpdateSize(chain: ArtHooks.Call): Any? {
        val result = chain.proceed()
        val card = chain.thisObject as? View
        if (enabled && card != null && cardSpecs.containsKey(card)) fit(card)
        return result
    }

    private fun mount(root: ViewGroup) {
        if (mounted.contains(root)) {
            roots[root] = true
            bind(root)
            return
        }
        try {
            root.contentDescription = null
            root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            quiet(root)
            val built = ArrayList<View>(specs.size)
            specs.forEach { spec ->
                val card = inflateCard(root) ?: error("card $spec")
                cardSpecs[card] = spec
                built.add(card)
            }
            while (root.childCount > 0) root.removeViewAt(root.childCount - 1)
            val column = LinearLayout(root.context).apply {
                orientation = LinearLayout.VERTICAL
                isBaselineAligned = false
                clipChildren = false
            }
            val gap = px(root, "control_center_universal_margin", 7f) * 2
            built.forEachIndexed { index, card ->
                if (index > 0) {
                    column.addView(
                        View(root.context),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gap),
                    )
                }
                column.addView(card, tileParams())
            }
            root.addView(column)
            fillParent(column)
            mounted.add(root)
            roots[root] = true
            root.post { if (mounted.contains(root)) quiet(root) }
            status = "mounted"
            bind(root)
            Log.i(TAG, "connectivity stack $status")
        } catch (error: Throwable) {
            status = "mount ${error.javaClass.simpleName}"
            Log.e(TAG, "connectivity stack", error)
        }
    }

    private fun bindWaiting() {
        val pending = synchronized(roots) { ArrayList(roots.keys) }
        pending.forEach { root ->
            if (mounted.contains(root)) bind(root)
        }
    }

    private fun bind(root: ViewGroup) {
        val controller = cards ?: return
        val column = root.getChildAt(0) as? ViewGroup ?: return
        for (index in 0 until column.childCount) {
            val card = column.getChildAt(index)
            val spec = cardSpecs[card] ?: continue
            val record = findRecord(controller, spec)
            if (record == null) {
                status = "no $spec"
                Log.i(TAG, "connectivity missing $spec")
                continue
            }
            val tile = call(record, "getTile")
            if (tile == null) continue
            if (wiredTiles[card] === tile) continue
            wiredTiles[card]?.let { unwatch(it) }
            if (wire(card, record, tile)) {
                boundCards.add(card)
                wiredTiles[card] = tile
                status = "live"
                Log.i(TAG, "connectivity wired $spec")
            }
        }
    }

    private fun restore(holder: Any) {
        try {
            val targets = rootsOf(holder).filter { mounted.contains(it) }
            if (targets.isEmpty()) return
            targets.forEach { view ->
                if (view is ViewGroup) reclaim(view)
                quiet(view)
            }
            (targets.first() as? ViewGroup)?.let { repaint(it) }
            main.post {
                targets.forEach { view ->
                    if (!mounted.contains(view)) return@forEach
                    if (view is ViewGroup) reclaim(view)
                    quiet(view)
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "connectivity restore", error)
        }
    }

    private fun reclaim(root: ViewGroup) {
        var column: View? = null
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child is ViewGroup && holdsStack(child)) {
                column = child
                break
            }
        }
        if (column == null) return
        for (index in root.childCount - 1 downTo 0) {
            if (root.getChildAt(index) !== column) root.removeViewAt(index)
        }
    }

    private fun holdsStack(group: ViewGroup): Boolean {
        for (index in 0 until group.childCount) {
            if (cardSpecs.containsKey(group.getChildAt(index))) return true
        }
        return false
    }

    private fun rootsOf(holder: Any): List<View> {
        val found = ArrayList<View>(2)
        itemView(holder)?.let { found.add(it) }
        val binding = readField(holder, "binding")
        val root = binding?.let { call(it, "getRoot") as? View }
        if (root != null && root !in found) found.add(root)
        return found
    }

    private fun quiet(root: View) {
        root.setOnClickListener(null)
        root.setOnLongClickListener(null)
        root.isClickable = false
        root.isLongClickable = false
        root.isFocusable = false
        root.background = null
        root.foreground = null
        clearBlur(root)
    }

    private fun repaint(root: ViewGroup) {
        val column = root.getChildAt(0) as? ViewGroup ?: return
        for (index in 0 until column.childCount) {
            val card = column.getChildAt(index)
            if (!cardSpecs.containsKey(card)) continue
            val state = readField(card, "state") ?: continue
            val connected = readField(card, "connected") as? Boolean ?: false
            val update = card.javaClass.methods.firstOrNull { method ->
                method.name == "updateState" && method.parameterCount == 3
            } ?: continue
            update.invoke(card, state, connected, false)
            fit(card)
        }
    }

    private var clearBlurMethod: Method? = null
    private var clearBlurTarget: Any? = null
    private var blurLogged = false

    private fun clearBlur(view: View) {
        try {
            val method = clearBlurMethod ?: run {
                val utils = Class.forName(
                    "miui.systemui.util.CommonUtils",
                    false,
                    view.context.classLoader,
                )
                val found = utils.getDeclaredMethod("clearMiBlurBlendEffect", View::class.java)
                found.isAccessible = true
                clearBlurTarget = utils.getField("INSTANCE").get(null)
                clearBlurMethod = found
                found
            }
            method.invoke(clearBlurTarget, view)
        } catch (error: Throwable) {
            if (!blurLogged) {
                blurLogged = true
                Log.e(TAG, "connectivity blur", error)
            }
        }
    }

    private class TileWatch(var card: View)

    private val watches = HashMap<Any, TileWatch>()
    private val callbacks = HashMap<Any, Any>()

    private fun wire(card: View, record: Any, tile: Any): Boolean {
        return try {
            watch(tile, card)
            val click = View.OnClickListener { view ->
                view.performHapticFeedback(1)
                invokeTile(tile, "click")
            }
            val longClick = View.OnLongClickListener { view ->
                view.performHapticFeedback(0)
                val state = call(tile, "getState")
                val dual = state?.let { boolField(it, "dualTarget") } == true
                if (dual) invokeTile(tile, "secondaryClick") else invokeTile(tile, "longClick")
                true
            }
            card.setOnClickListener(click)
            card.setOnLongClickListener(longClick)
            setField(card, "clickAction", function(card) { view ->
                if (view != null) click.onClick(view)
                null
            })
            setField(card, "longClickAction", function(card) { view ->
                if (view == null) false else longClick.onLongClick(view)
            })
            pushState(tile, card)
            if (panelStarted) call(record, "setListening", true)
            fit(card)
            true
        } catch (error: Throwable) {
            status = "wire ${error.javaClass.simpleName}"
            Log.e(TAG, "connectivity wire", error)
            false
        }
    }

    private fun watch(tile: Any, card: View) {
        val existing = watches[tile]
        if (existing != null) {
            existing.card = card
            return
        }
        val watch = TileWatch(card)
        val callbackClass = Class.forName(
            "com.android.systemui.plugins.qs.QSTile\$Callback",
            false,
            tile.javaClass.classLoader,
        )
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
            if (method.name == "onStateChanged") {
                val state = args?.firstOrNull()
                if (state != null) main.post { showState(watch.card, tile, state) }
            }
            defaultValue(method.returnType)
        }
        findMethod(tile.javaClass, "addCallback", arrayOf(callback))?.apply {
            isAccessible = true
            invoke(tile, callback)
        }
        watches[tile] = watch
        callbacks[tile] = callback
    }

    private fun unwatch(tile: Any) {
        val callback = callbacks.remove(tile)
        watches.remove(tile)
        if (callback == null) return
        findMethod(tile.javaClass, "removeCallback", arrayOf(callback))?.apply {
            isAccessible = true
            invoke(tile, callback)
        }
    }

    private fun pushState(tile: Any, card: View) {
        val state = call(tile, "getState") ?: return
        showState(card, tile, state)
    }

    private fun showState(card: View, tile: Any, state: Any) {
        val connected = call(tile, "isConnected") as? Boolean ?: true
        val update = card.javaClass.methods.firstOrNull { method ->
            method.name == "updateState" && method.parameterCount == 3
        } ?: return
        update.invoke(card, state, connected, false)
        fit(card)
    }

    private fun invokeTile(tile: Any, name: String) {
        val method = findMethod(tile.javaClass, name, emptyArray()) ?: return
        method.isAccessible = true
        method.invoke(tile)
    }

    private fun function(card: View, block: (View?) -> Any?): Any {
        val type = Class.forName("kotlin.jvm.functions.Function1", false, card.javaClass.classLoader)
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> block(args?.firstOrNull() as? View)
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "stack"
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = findField(target.javaClass, name) ?: return
        field.set(target, value)
    }

    private fun readField(target: Any, name: String): Any? {
        val field = findField(target.javaClass, name) ?: return null
        return field.get(target)
    }

    private fun findField(start: Class<*>, name: String): java.lang.reflect.Field? {
        var type: Class<*>? = start
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                return field
            }
            type = type.superclass
        }
        return null
    }

    private fun boolField(target: Any, name: String): Boolean {
        val field = target.javaClass.fields.firstOrNull { it.name == name } ?: return false
        return field.getBoolean(target)
    }

    private fun defaultValue(type: Class<*>): Any? {
        return when (type) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            else -> null
        }
    }

    private fun listen(on: Boolean) {
        val controller = cards ?: return
        specs.forEach { spec ->
            val record = findRecord(controller, spec) ?: return@forEach
            call(record, "setListening", on)
        }
    }

    private fun inflateCard(root: View): View? {
        val context = root.context
        val layout = resId(context, "layout", "qs_card_item_view")
        if (layout == 0) {
            status = "layout missing"
            return null
        }
        val card = android.view.LayoutInflater.from(context).inflate(layout, null)
        val loader = card.javaClass.classLoader ?: return null
        val iconClass = Class.forName(ICON_VIEW, false, loader)
        val ctor = iconClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterCount == 3 && candidate.parameterTypes[0].name == "android.content.Context"
        } ?: return null
        ctor.isAccessible = true
        val sysUi = sysUiContext(context)
        val icon = ctor.newInstance(context, sysUi, null)
        val init = card.javaClass.methods.firstOrNull { it.name == "init" && it.parameterCount == 1 }
            ?: return null
        init.invoke(card, icon)
        return card
    }

    private fun sysUiContext(fallback: Context): Context {
        val controller = cards ?: return fallback
        val field = controller.javaClass.declaredFields.firstOrNull { it.name == "sysUIContext" }
            ?: return fallback
        field.isAccessible = true
        return field.get(controller) as? Context ?: fallback
    }

    private fun findRecord(controller: Any, spec: String): Any? {
        val method = controller.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "getTile" &&
                candidate.parameterCount == 1 &&
                candidate.parameterTypes[0] == String::class.java
        } ?: return null
        return method.invoke(controller, spec)
    }

    private fun cardsOnScreen(controller: Any): Boolean {
        val method = availableMethod ?: controller.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "available" &&
                candidate.parameterCount == 1 &&
                candidate.parameterTypes[0] == java.lang.Boolean.TYPE
        }?.also { availableMethod = it } ?: return true
        return method.invoke(controller, false) as? Boolean == true
    }

    private fun fillParent(child: View) {
        val parent = child.parent as? ViewGroup ?: return
        val loader = parent.javaClass.classLoader ?: return
        val paramsClass = Class.forName(
            "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams",
            false,
            loader,
        )
        val params = paramsClass.getConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).newInstance(0, 0)
        val parentId = paramsClass.getField("PARENT_ID").getInt(null)
        arrayOf("topToTop", "bottomToBottom", "startToStart", "endToEnd").forEach { name ->
            paramsClass.getField(name).setInt(params, parentId)
        }
        child.layoutParams = params as ViewGroup.LayoutParams
    }

    private fun fit(card: View) {
        if (card.parent !is LinearLayout) return
        val params = (card.layoutParams as? LinearLayout.LayoutParams) ?: tileParams()
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = 0
        params.weight = 1f
        params.setMargins(0, 0, 0, 0)
        card.layoutParams = params
    }

    private fun tileParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }

    private fun specOf(item: Any): String? {
        return try {
            item.javaClass.methods.firstOrNull { it.name == "getSpec" && it.parameterCount == 0 }
                ?.invoke(item) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun itemView(holder: Any): View? {
        var type: Class<*>? = holder.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == "itemView" }
            if (field != null) {
                field.isAccessible = true
                return field.get(holder) as? View
            }
            type = type.superclass
        }
        return null
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        val method = findMethod(target.javaClass, name, args) ?: return null
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    private fun findMethod(type: Class<*>, name: String, args: Array<out Any?>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            val found = current.declaredMethods.firstOrNull { candidate ->
                candidate.name == name && compatible(candidate.parameterTypes, args)
            }
            if (found != null) return found
            current = current.superclass
        }
        return type.methods.firstOrNull { candidate ->
            candidate.name == name && compatible(candidate.parameterTypes, args)
        }
    }

    private fun compatible(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        return types.indices.all { index ->
            val arg = args[index]
            val type = types[index]
            when {
                arg == null -> !type.isPrimitive
                type == java.lang.Boolean.TYPE -> arg is Boolean
                type == Integer.TYPE -> arg is Int
                type == java.lang.Float.TYPE -> arg is Float
                else -> type.isInstance(arg)
            }
        }
    }

    private fun px(view: View, name: String, fallbackDp: Float): Int {
        val id = resId(view.context, "dimen", name)
        if (id != 0) return view.resources.getDimensionPixelSize(id)
        return (fallbackDp * view.resources.displayMetrics.density).toInt()
    }

    private fun resId(context: Context, type: String, name: String): Int {
        val id = context.resources.getIdentifier(name, type, context.packageName)
        if (id != 0) return id
        return context.resources.getIdentifier(name, type, "miui.systemui.plugin")
    }

    private fun hookNamed(
        cls: Class<*>,
        name: String,
        params: Int,
        block: (ArtHooks.Call) -> Any?,
    ) {
        cls.declaredMethods.filter { method ->
            method.name == name &&
                method.parameterCount == params &&
                !Modifier.isAbstract(method.modifiers)
        }.forEach { method ->
            SystemUiHooks.hook(method, block)
        }
    }
}
