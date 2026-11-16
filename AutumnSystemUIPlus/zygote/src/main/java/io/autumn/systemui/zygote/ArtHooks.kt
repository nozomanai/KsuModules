package io.autumn.systemui.zygote

import com.v7878.unsafe.invoke.EmulatedStackFrame
import com.v7878.unsafe.invoke.Transformers
import com.v7878.vmtools.HookTransformer
import com.v7878.vmtools.Hooks
import java.lang.invoke.MethodHandle
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object ArtHooks {
    fun hook(executable: Executable, block: (Call) -> Any?) {
        val receiver = when (executable) {
            is Constructor<*> -> true
            is Method -> !Modifier.isStatic(executable.modifiers)
            else -> false
        }
        Hooks.hook(
            executable,
            Hooks.EntryPointType.DIRECT,
            HookTransformer { original, frame ->
                val call = Call(original, frame, receiver)
                frame.setReturned(block(call))
            },
            Hooks.EntryPointType.DIRECT,
        )
    }

    class Call(
        private val original: MethodHandle,
        private val frame: EmulatedStackFrame,
        private val receiver: Boolean,
    ) {
        var proceeded: Boolean = false
            private set

        val thisObject: Any?
            get() = if (receiver) frame.argument(0) else null

        fun getArg(index: Int): Any? = frame.argument(index + if (receiver) 1 else 0)

        fun getArgs(): List<Any?> {
            val start = if (receiver) 1 else 0
            val count = frame.type().parameterCount()
            return (start until count).map { frame.argument(it) }
        }

        fun proceed(): Any? = invoke(null)

        fun proceed(args: Array<out Any?>): Any? = invoke(args)

        private fun invoke(args: Array<out Any?>?): Any? {
            if (args != null) {
                val start = if (receiver) 1 else 0
                args.forEachIndexed { index, value ->
                    frame.setArgument(start + index, value)
                }
            }
            proceeded = true
            Transformers.invokeExactNoChecks(original, frame)
            return frame.returned()
        }
    }
}

private fun EmulatedStackFrame.argument(index: Int): Any? {
    val accessor = accessor()
    return when (accessor.getArgumentShorty(index)) {
        'L' -> accessor.getReference(index)
        'Z' -> accessor.getBoolean(index)
        'B' -> accessor.getByte(index)
        'C' -> accessor.getChar(index)
        'S' -> accessor.getShort(index)
        'I' -> accessor.getInt(index)
        'J' -> accessor.getLong(index)
        'F' -> accessor.getFloat(index)
        'D' -> accessor.getDouble(index)
        'V' -> null
        else -> throw IllegalArgumentException("unknown shorty")
    }
}

private fun EmulatedStackFrame.setArgument(index: Int, value: Any?) {
    val accessor = accessor()
    when (accessor.getArgumentShorty(index)) {
        'L' -> accessor.setReference(index, value)
        'Z' -> accessor.setBoolean(index, value as Boolean)
        'B' -> accessor.setByte(index, (value as Number).toByte())
        'C' -> accessor.setChar(index, value as Char)
        'S' -> accessor.setShort(index, (value as Number).toShort())
        'I' -> accessor.setInt(index, (value as Number).toInt())
        'J' -> accessor.setLong(index, (value as Number).toLong())
        'F' -> accessor.setFloat(index, (value as Number).toFloat())
        'D' -> accessor.setDouble(index, (value as Number).toDouble())
        else -> throw IllegalArgumentException("unknown shorty")
    }
}

private fun EmulatedStackFrame.returned(): Any? = argument(EmulatedStackFrame.RETURN_VALUE_IDX)

private fun EmulatedStackFrame.setReturned(value: Any?) {
    val accessor = accessor()
    val index = EmulatedStackFrame.RETURN_VALUE_IDX
    when (accessor.getArgumentShorty(index)) {
        'V' -> Unit
        'L' -> accessor.setReference(index, value)
        'Z' -> accessor.setBoolean(index, value as Boolean)
        'B' -> accessor.setByte(index, (value as Number).toByte())
        'C' -> accessor.setChar(index, value as Char)
        'S' -> accessor.setShort(index, (value as Number).toShort())
        'I' -> accessor.setInt(index, (value as Number).toInt())
        'J' -> accessor.setLong(index, (value as Number).toLong())
        'F' -> accessor.setFloat(index, (value as Number).toFloat())
        'D' -> accessor.setDouble(index, (value as Number).toDouble())
        else -> Unit
    }
}
