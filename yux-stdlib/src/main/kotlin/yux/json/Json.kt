package yux.json

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * JSON 序列化运行时（S-8.3：`serialize`/`deserialize` 的 JVM 载体）。
 *
 * v0.1 实现：JDK 反射手写，零第三方依赖。支持：
 * - 标量：Int/Long/Float/Double/Boolean/Char/String/Byte（Char → JSON 字符串，Byte → 数字）；
 * - data 类：按声明顺序反射字段（字段名 = JSON 键），嵌套 data 类递归；
 * - List / Array：JSON 数组；Map：JSON 对象。
 *
 * 反序列化按 `type` 标记递归构造：顶层类型为 data 类的 Class 时，按构造器参数顺序
 * 匹配字段名逐个构造（含私有字段）。v0.1 限制：List 的元素类型在运行时不可知
 * （Yux 编译器不产出泛型 Signature），嵌套 data 类的 List 元素反序列化为
 * `Map<String, Any?>`，由调用方按需处理。
 */
object Json {

    /** 序列化为 JSON 文本（S-8.3 顶层 `serialize(x)`）。 */
    @JvmStatic
    fun serialize(x: Any?): String {
        val out = StringBuilder()
        writeValue(x, out)
        return out.toString()
    }

    /**
     * 反序列化 JSON 文本为 `type` 标记的实例（S-8.3 顶层 `deserialize(json, Type)`）。
     *
     * [type] 为 data 类的 Class（Yux 侧 `deserialize(json, PlayerData)` 传递类字面量）；
     * 标量类型标记（Int/Long/Float/Double/Boolean/Char/Byte/String 的 Class）直接解码标量。
     */
    @JvmStatic
    fun deserialize(text: String, type: Any): Any? {
        val clazz = type as? Class<*> ?: throw IllegalArgumentException(
            "deserialize 类型标记必须是 Class（Yux 侧传入 data 类名），实际: $type",
        )
        val value = JsonParser(text).parse()
        return buildValue(value, clazz)
    }

    // ── 序列化 ───────────────────────────────────────────────────────────────

    private fun writeValue(x: Any?, out: StringBuilder) {
        when (x) {
            null -> out.append("null")
            is String -> writeString(x, out)
            is Char -> writeString(x.toString(), out)
            is Boolean, is Int, is Long, is Float, is Double, is Byte -> out.append(x)
            is Short -> out.append(x)
            is Map<*, *> -> writeObject(x, out)
            is Iterable<*> -> writeArray(x, out)
            is Array<*> -> writeArray(x.asList(), out)
            is IntArray -> writeArray(x.toList(), out)
            is LongArray -> writeArray(x.toList(), out)
            is FloatArray -> writeArray(x.toList(), out)
            is DoubleArray -> writeArray(x.toList(), out)
            is BooleanArray -> writeArray(x.toList(), out)
            is CharArray -> writeArray(x.toList(), out)
            is ByteArray -> writeArray(x.toList(), out)
            else -> writeDataClass(x, out)
        }
    }

    private fun writeObject(map: Map<*, *>, out: StringBuilder) {
        out.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) out.append(',')
            first = false
            writeString(k?.toString() ?: "null", out)
            out.append(':')
            writeValue(v, out)
        }
        out.append('}')
    }

    private fun writeArray(items: Iterable<*>, out: StringBuilder) {
        out.append('[')
        var first = true
        for (item in items) {
            if (!first) out.append(',')
            first = false
            writeValue(item, out)
        }
        out.append(']')
    }

    /** data 类：声明顺序的非静态字段（字段名 = JSON 键；与 deserialize 构造参数顺序一致）。 */
    private fun writeDataClass(x: Any, out: StringBuilder) {
        val fields = dataFields(x.javaClass)
        if (fields.isEmpty()) {
            throw IllegalArgumentException("serialize 不支持类型 ${x.javaClass.name}（非 data 类/无字段）")
        }
        out.append('{')
        for ((i, f) in fields.withIndex()) {
            if (i > 0) out.append(',')
            writeString(f.name, out)
            out.append(':')
            writeValue(f.get(x), out)
        }
        out.append('}')
    }

    /** 可序列化字段：自身声明的非静态非合成字段（含私有，Kotlin/Java 属性均覆盖）。 */
    private fun dataFields(clazz: Class<*>): List<Field> = clazz.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic && !it.name.startsWith('$') }
        .onEach { it.isAccessible = true }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    // ── 反序列化 ─────────────────────────────────────────────────────────────

    private fun buildValue(value: Any?, target: Class<*>): Any? = when {
        value == null -> null
        target == java.lang.Integer::class.java || target == java.lang.Integer.TYPE -> intValue(value)
        target == java.lang.Long::class.java || target == java.lang.Long.TYPE -> longValue(value)
        target == java.lang.Double::class.java || target == java.lang.Double.TYPE -> doubleValue(value)
        target == java.lang.Float::class.java || target == java.lang.Float.TYPE -> floatValue(value)
        target == java.lang.Boolean::class.java || target == java.lang.Boolean.TYPE -> booleanValue(value)
        target == java.lang.Character::class.java || target == java.lang.Character.TYPE -> charValue(value)
        target == java.lang.Byte::class.java || target == java.lang.Byte.TYPE -> byteValue(value)
        target == String::class.java -> stringValue(value)
        target == java.util.List::class.java || target == java.util.ArrayList::class.java ->
            arrayListValue(value)
        target == java.util.Map::class.java || target == java.util.LinkedHashMap::class.java ||
            target == java.util.HashMap::class.java -> mapValue(value)
        value is Map<*, *> -> buildDataClass(value, target)
        else -> throw IllegalArgumentException("deserialize 无法构造类型 ${target.name}: $value")
    }

    /** data 类构造：按构造器参数顺序匹配声明字段，逐个递归构造后调用构造器。 */
    private fun buildDataClass(map: Map<*, *>, clazz: Class<*>): Any {
        val fields = dataFields(clazz)
        val ctor = clazz.declaredConstructors.maxByOrNull { it.parameterCount }
            ?: throw IllegalArgumentException("deserialize 类型无构造器: ${clazz.name}")
        if (ctor.parameterCount != fields.size) {
            throw IllegalArgumentException(
                "deserialize 类型 ${clazz.name} 构造器参数数（${ctor.parameterCount}）与字段数（${fields.size}）不一致",
            )
        }
        ctor.isAccessible = true
        val args = fields.map { field ->
            if (!map.containsKey(field.name)) {
                throw IllegalArgumentException("deserialize: JSON 缺少字段 '${field.name}'（${clazz.name}）")
            }
            buildValue(map[field.name], field.type)
        }
        return ctor.newInstance(*args.toTypedArray())
    }

    private fun arrayListValue(value: Any): Any = when (value) {
        is List<*> -> java.util.ArrayList(value)
        else -> throw IllegalArgumentException("deserialize 期望 JSON 数组: $value")
    }

    private fun mapValue(value: Any): Any = when (value) {
        is Map<*, *> -> java.util.LinkedHashMap(value)
        else -> throw IllegalArgumentException("deserialize 期望 JSON 对象: $value")
    }

    private fun intValue(value: Any): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.toInt()
        else -> throw IllegalArgumentException("deserialize 期望数字: $value")
    }

    private fun longValue(value: Any): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.toLong()
        else -> throw IllegalArgumentException("deserialize 期望数字: $value")
    }

    private fun doubleValue(value: Any): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDouble()
        else -> throw IllegalArgumentException("deserialize 期望数字: $value")
    }

    private fun floatValue(value: Any): Float = when (value) {
        is Number -> value.toFloat()
        is String -> value.toFloat()
        else -> throw IllegalArgumentException("deserialize 期望数字: $value")
    }

    private fun booleanValue(value: Any): Boolean = when (value) {
        is Boolean -> value
        is String -> value.toBoolean()
        else -> throw IllegalArgumentException("deserialize 期望布尔: $value")
    }

    private fun charValue(value: Any): Char = when (value) {
        is String -> value.firstOrNull()
            ?: throw IllegalArgumentException("deserialize 期望单字符字符串: $value")
        else -> throw IllegalArgumentException("deserialize 期望单字符字符串: $value")
    }

    private fun byteValue(value: Any): Byte = when (value) {
        is Number -> value.toByte()
        is String -> value.toByte()
        else -> throw IllegalArgumentException("deserialize 期望数字: $value")
    }

    private fun stringValue(value: Any): String = when (value) {
        is String -> value
        is Char -> value.toString()
        else -> throw IllegalArgumentException("deserialize 期望字符串: $value")
    }
}
