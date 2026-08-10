package yux.json

import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import java.lang.reflect.Type
import java.util.IdentityHashMap

/**
 * JSON 序列化运行时（S-8.3：`serialize`/`deserialize` 的 JVM 载体）。
 *
 * v0.1 实现：JDK 反射手写，零第三方依赖。支持：
 * - 标量：Int/Long/Float/Double/Boolean/Char/String/Byte（Char → JSON 字符串，Byte → 数字）；
 * - data 类：按声明顺序反射字段（字段名 = JSON 键），嵌套 data 类递归；
 * - List / Array：JSON 数组；Map：JSON 对象；
 * - 枚举：JSON 字符串（常量名），反序列化按常量名还原；
 * - 可空字段：JSON null ↔ Kotlin null（含 List/Map 元素级 null）。
 *
 * 反序列化按 `type` 标记递归构造：顶层类型为 data 类的 Class 时，按构造器参数顺序
 * 匹配字段名逐个构造（含私有字段）。元素类型转换优先取构造器泛型签名
 * （`ParameterizedType`/`GenericArrayType`，Kotlin data 类携带），使
 * `List<PlayerData>`/`Map<String, PlayerData>` 往返还原为真实元素类型；
 * 无泛型 Signature 时（如 Yux 编译器 v0.1 产物）回退到原始字段类型（裸 List/Map）。
 *
 * 序列化含循环引用守卫：data 类/集合/映射在展开路径上重复出现时明确报错，
 * 而非栈溢出；共享引用（非循环）正常重复输出。
 */
object Json {

    /** 序列化为 JSON 文本（S-8.3 顶层 `serialize(x)`）。 */
    @JvmStatic
    fun serialize(x: Any?): String {
        val out = StringBuilder()
        writeValue(x, out, IdentityHashMap())
        return out.toString()
    }

    /**
     * 反序列化 JSON 文本为 `type` 标记的实例（S-8.3 顶层 `deserialize(json, Type)`）。
     *
     * [type] 为 data 类的 Class（Yux 侧 `deserialize(json, PlayerData)` 传递类字面量）；
     * 标量/枚举类型标记（Int/Long/Float/Double/Boolean/Char/Byte/String 及枚举的 Class）
     * 直接解码。
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

    private fun writeValue(x: Any?, out: StringBuilder, path: IdentityHashMap<Any, Boolean>) {
        when (x) {
            null -> out.append("null")
            is String -> writeString(x, out)
            is Char -> writeString(x.toString(), out)
            is Boolean, is Int, is Long, is Float, is Double, is Byte -> out.append(x)
            is Short -> out.append(x)
            is Map<*, *> -> writeObject(x, out, path)
            is Iterable<*> -> writeArray(x, out, path)
            is Array<*> -> guarded(x, path) { writeArray(x.asList(), out, path) }
            is IntArray -> writeArray(x.toList(), out, path)
            is LongArray -> writeArray(x.toList(), out, path)
            is FloatArray -> writeArray(x.toList(), out, path)
            is DoubleArray -> writeArray(x.toList(), out, path)
            is BooleanArray -> writeArray(x.toList(), out, path)
            is CharArray -> writeArray(x.toList(), out, path)
            is ByteArray -> writeArray(x.toList(), out, path)
            is Enum<*> -> writeString(x.name, out)
            else -> writeDataClass(x, out, path)
        }
    }

    private fun writeObject(map: Map<*, *>, out: StringBuilder, path: IdentityHashMap<Any, Boolean>) {
        guarded(map, path) {
            out.append('{')
            var first = true
            for ((k, v) in map) {
                if (!first) out.append(',')
                first = false
                writeString(k?.toString() ?: "null", out)
                out.append(':')
                writeValue(v, out, path)
            }
            out.append('}')
        }
    }

    private fun writeArray(items: Iterable<*>, out: StringBuilder, path: IdentityHashMap<Any, Boolean>) {
        guarded(items, path) {
            out.append('[')
            var first = true
            for (item in items) {
                if (!first) out.append(',')
                first = false
                writeValue(item, out, path)
            }
            out.append(']')
        }
    }

    /** data 类：声明顺序的非静态字段（字段名 = JSON 键；与 deserialize 构造参数顺序一致）。 */
    private fun writeDataClass(x: Any, out: StringBuilder, path: IdentityHashMap<Any, Boolean>) {
        val fields = dataFields(x.javaClass)
        if (fields.isEmpty()) {
            throw IllegalArgumentException("serialize 不支持类型 ${x.javaClass.name}（非 data 类/无字段）")
        }
        guarded(x, path) {
            out.append('{')
            for ((i, f) in fields.withIndex()) {
                if (i > 0) out.append(',')
                writeString(f.name, out)
                out.append(':')
                writeValue(f.get(x), out, path)
            }
            out.append('}')
        }
    }

    /**
     * 循环引用守卫：对象在展开路径上再次出现（自引用/互引用）→ 明确报错；
     * 非循环的共享引用不受影响（仅按当前路径判断，展开完即退出）。
     */
    private inline fun guarded(x: Any, path: IdentityHashMap<Any, Boolean>, body: () -> Unit) {
        if (path.containsKey(x)) {
            throw IllegalArgumentException("serialize 检测到循环引用（无法序列化自引用结构）: ${x.javaClass.name}")
        }
        path[x] = true
        try {
            body()
        } finally {
            path.remove(x)
        }
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
        target.isEnum -> enumValue(value, target)
        value is Map<*, *> -> buildDataClass(value, target)
        else -> throw IllegalArgumentException("deserialize 无法构造类型 ${target.name}: $value")
    }

    /**
     * 泛型感知的反序列化：优先按 [type]（构造器参数泛型签名）逐层转换元素类型，
     * 无法解析（TypeVariable/Wildcard 等）时回退到 [fallback] 原始字段类型。
     */
    private fun buildValue(value: Any?, type: Type, fallback: Class<*>?): Any? {
        if (value == null) return null
        return when (type) {
            is Class<*> -> buildValue(value, type)
            is ParameterizedType -> buildParameterized(value, type)
            is GenericArrayType -> buildGenericArray(value, type)
            // Kotlin 协变声明（`List<out T>` → 字节码 `List<? extends T>`）产生通配符：
            // 取上界（`T`）继续转换，否则 `List<List<Int>>` 内层元素不会按 Int 还原
            is WildcardType -> {
                val bound = type.upperBounds.firstOrNull()
                if (bound != null) buildValue(value, bound, fallback) else buildValue(value, fallback ?: Any::class.java)
            }
            else -> buildValue(value, fallback ?: Any::class.java)
        }
    }

    /** `List<T>`/`Map<K,V>` 等泛型：按实际类型参数逐元素转换（List<PlayerData> 还原为 PlayerData）。 */
    private fun buildParameterized(value: Any, type: ParameterizedType): Any? {
        val raw = type.rawType as? Class<*> ?: throw IllegalArgumentException(
            "deserialize 泛型原始类型不可解析: $type",
        )
        return when {
            Map::class.java.isAssignableFrom(raw) -> buildGenericMap(value, type)
            Iterable::class.java.isAssignableFrom(raw) -> buildGenericCollection(value, type, raw)
            else -> buildValue(value, raw) // 其它泛型（如 Optional）→ 按原始类型处理
        }
    }

    private fun buildGenericMap(value: Any, type: ParameterizedType): Any {
        val keyType = type.actualTypeArguments[0]
        val valueType = type.actualTypeArguments[1]
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("deserialize 期望 JSON 对象: $value")
        val result = java.util.LinkedHashMap<Any?, Any?>()
        for ((k, v) in map) {
            // JSON 键恒为字符串：目标键类型为 String 时直用，否则按目标类型转换（如 Int 键）
            val key = if (keyType == String::class.java) {
                k
            } else {
                buildValue(k, keyType, k?.javaClass)
            }
            result[key] = buildValue(v, valueType, v?.javaClass)
        }
        return result
    }

    private fun buildGenericCollection(value: Any, type: ParameterizedType, raw: Class<*>): Any {
        val elementType = type.actualTypeArguments[0]
        val list = value as? List<*> ?: throw IllegalArgumentException("deserialize 期望 JSON 数组: $value")
        val elements = list.map { buildValue(it, elementType, it?.javaClass) }
        return if (Set::class.java.isAssignableFrom(raw)) {
            java.util.LinkedHashSet(elements) // 声明为 Set 时还原为 Set，保持往返相等
        } else {
            java.util.ArrayList(elements)
        }
    }

    /** `Array<T>` 字段（Kotlin 编译为引用数组 + GenericArrayType 签名）。 */
    private fun buildGenericArray(value: Any, type: GenericArrayType): Any {
        val component = type.genericComponentType
        val list = value as? List<*> ?: throw IllegalArgumentException("deserialize 期望 JSON 数组: $value")
        val componentClass = when (component) {
            is Class<*> -> component
            is ParameterizedType -> component.rawType as Class<*>
            else -> Any::class.java
        }
        val array = java.lang.reflect.Array.newInstance(componentClass, list.size)
        list.forEachIndexed { i, item ->
            java.lang.reflect.Array.set(array, i, buildValue(item, component, item?.javaClass))
        }
        return array
    }

    /** data 类构造：按构造器参数顺序匹配声明字段，逐个递归构造后调用构造器。 */
    private fun buildDataClass(map: Map<*, *>, clazz: Class<*>): Any {
        val fields = dataFields(clazz)
        if (fields.isEmpty()) {
            throw IllegalArgumentException("deserialize 类型 ${clazz.name} 无可序列化字段（字段数为 0）")
        }
        val ctor = clazz.declaredConstructors.maxByOrNull { it.parameterCount }
            ?: throw IllegalArgumentException("deserialize 类型无构造器: ${clazz.name}")
        if (ctor.parameterCount != fields.size) {
            throw IllegalArgumentException(
                "deserialize 类型 ${clazz.name} 构造器参数数（${ctor.parameterCount}）与字段数（${fields.size}）不一致",
            )
        }
        ctor.isAccessible = true
        val genericParams = ctor.genericParameterTypes
        val args = fields.mapIndexed { i, field ->
            if (!map.containsKey(field.name)) {
                throw IllegalArgumentException("deserialize: JSON 缺少字段 '${field.name}'（${clazz.name}）")
            }
            buildValue(map[field.name], genericParams[i], field.type)
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

    private fun enumValue(value: Any, target: Class<*>): Any {
        val name = stringValue(value)
        val match = target.enumConstants?.firstOrNull { it is Enum<*> && it.name == name }
            ?: throw IllegalArgumentException("deserialize 枚举 ${target.name} 无常量 '$name'")
        return match
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
