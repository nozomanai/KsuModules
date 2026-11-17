package io.github.cobylinweiqi.fakeloc.xposed

import android.util.Log
import io.github.cobylinweiqi.fakeloc.zygote.ArtHooks
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections

/**
 * Same surface the hook installers already call, backed by ART hooks instead of
 * libxposed. A failure to install one method is logged and skipped.
 */
internal object HookKit {

    private const val ROOT_TAG = "FakeLoc"

    private val installedKeys: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    fun firstTime(key: String): Boolean = installedKeys.add(key)

    fun info(tag: String, message: String) = log(Log.INFO, tag, message)
    fun warn(tag: String, message: String) = log(Log.WARN, tag, message)
    fun error(tag: String, message: String) = log(Log.ERROR, tag, message)
    fun error(tag: String, message: String, throwable: Throwable) =
        log(Log.ERROR, tag, "$message: ${throwable.javaClass.simpleName}: ${throwable.message}")

    fun log(priority: Int, tag: String, message: String) {
        if (!HookState.mayLog()) return
        runCatching { Log.println(priority, "$ROOT_TAG/$tag", message) }
    }

    fun findClass(classLoader: ClassLoader, vararg names: String): Class<*>? {
        for (name in names) {
            try {
                return Class.forName(name, false, classLoader)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    fun findField(clazz: Class<*>, vararg names: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (name in names) {
                try {
                    return current.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: NoSuchFieldException) {
                } catch (_: Throwable) {
                    return null
                }
            }
            current = current.superclass
        }
        return null
    }

    fun findMethod(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
            } catch (_: Throwable) {
                return null
            }
            current = current.superclass
        }
        return null
    }

    fun methodsNamed(clazz: Class<*>, name: String): List<Method> {
        val found = LinkedHashMap<String, Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val declared = try {
                current.declaredMethods
            } catch (_: Throwable) {
                emptyArray<Method>()
            }
            for (method in declared) {
                if (method.name != name) continue
                val signature = buildString {
                    append(method.name)
                    append('(')
                    append(method.parameterTypes.joinToString(",") { it.name })
                    append(')')
                }
                found.putIfAbsent(signature, method)
            }
            current = current.superclass
        }
        return found.values.toList()
    }

    fun hookAll(clazz: Class<*>, methodName: String, tag: String, hooker: (Chain) -> Any?): Int {
        val methods = methodsNamed(clazz, methodName)
        if (methods.isEmpty()) return 0
        var hooked = 0
        for (method in methods) {
            if (hookMethod(method, tag, hooker)) hooked++
        }
        return hooked
    }

    fun hookAllLogged(clazz: Class<*>, methodName: String, tag: String, hooker: (Chain) -> Any?) {
        val methods = methodsNamed(clazz, methodName)
        if (methods.isEmpty()) {
            warn(tag, "${clazz.simpleName}#$methodName not present on this ROM")
            return
        }
        val hooked = hookAll(clazz, methodName, tag, hooker)
        if (hooked < methods.size) {
            warn(tag, "${clazz.simpleName}#$methodName: hooked $hooked/${methods.size} overload(s)")
        } else {
            info(tag, "${clazz.simpleName}#$methodName hooked ($hooked overload(s))")
        }
    }

    fun hookMethod(method: Method, tag: String, hooker: (Chain) -> Any?): Boolean {
        return try {
            ArtHooks.hook(method) { call -> hooker(Chain(call, method)) }
            true
        } catch (t: Throwable) {
            error(tag, "hook ${method.declaringClass.simpleName}#${method.name} failed", t)
            false
        }
    }

    fun hookConstructors(clazz: Class<*>, tag: String, hooker: (Chain) -> Any?): Int {
        val constructors = try {
            clazz.declaredConstructors
        } catch (_: Throwable) {
            emptyArray<Constructor<*>>()
        }
        var hooked = 0
        for (constructor in constructors) {
            try {
                constructor.isAccessible = true
                ArtHooks.hook(constructor) { call -> hooker(Chain(call, constructor)) }
                hooked++
            } catch (t: Throwable) {
                error(tag, "hook ${clazz.simpleName} constructor failed", t)
            }
        }
        return hooked
    }

    fun defaultValueFor(method: Method?): Any? = when (method?.returnType) {
        null -> null
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    class Chain(private val call: ArtHooks.Call, val executable: Executable) {
        private var argsCache: Array<Any?>? = null

        val thisObject: Any?
            get() = call.thisObject

        val args: Array<Any?>
            get() = argsCache ?: call.getArgs().toTypedArray().also { argsCache = it }

        fun proceed(): Any? {
            val cached = argsCache
            return if (cached == null) call.proceed() else call.proceed(cached)
        }

        fun proceed(newArgs: Array<out Any?>): Any? = call.proceed(newArgs)
    }
}
