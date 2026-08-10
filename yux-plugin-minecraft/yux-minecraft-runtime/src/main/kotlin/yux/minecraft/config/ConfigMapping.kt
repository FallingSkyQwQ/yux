package yux.minecraft.config

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass

/**
 * data 类 ↔ Map 双向转换（与 HomeStore 同一反射约定，独立实现）。
 *
 * 序列化：`getXxx()`/`isXxx()` 访问器 → 小驼峰属性名；嵌套 data 类递归为嵌套映射。
 * 反序列化：主构造器（非合成、参数最多）按参数名取数；参数名不可用时按声明字段序回退。
 */
internal object ConfigMapping {

    private const val GET_PREFIX = "get"
    private const val IS_PREFIX = "is"

    /** data 类实例 → 映射（访问器 → 小驼峰属性名）。 */
    fun toMap(data: Any): Map<String, Any?> {
        val type = data.javaClass
        val result = linkedMapOf<String, Any?>()
        for (name in propertyNames(type)) {
            result[name] = toYamlValue(accessorOf(type, name).invoke(data))
        }
        return result
    }

    /** 任意字段值 → YAML 可表示值（data 类递归为映射）。 */
    private fun toYamlValue(value: Any?): Any? = when (value) {
        null, is String, is Number, is Boolean -> value
        is List<*> -> value.map { toYamlValue(it) }
        is Map<*, *> -> value.entries.associate { (key, v) -> key.toString() to toYamlValue(v) }
        else -> toMap(value) // 嵌套 data 类
    }

    /** 映射 → data 类实例（主构造器按参数名取值）。 */
    fun fromMap(map: Map<String, Any?>, type: Class<*>): Any {
        val constructor = primaryConstructor(type)
        val names = paramNames(constructor, type)
        val args = Array<Any?>(names.size) { i ->
            val value = map[names[i]]
            val param = constructor.parameters[i]
            if (value == null) zeroValue(param.type) else convertParam(value, param.type, param.name, param.parameterizedType)
        }
        constructor.isAccessible = true
        return type.cast(constructor.newInstance(*args))
    }

    /** YAML 值 → 目标字段类型（含 List/Map 与嵌套 data 类）。 */
    private fun convertParam(value: Any, target: Class<*>, name: String, generic: Type?): Any = when {
        target == String::class.java -> if (value is String) value else value.toString()
        matches(target, Boolean::class) -> when (value) {
            is Boolean -> value
            is String -> value.toBoolean()
            else -> false
        }
        matches(target, Int::class) -> asNumber(value, name).toInt()
        matches(target, Long::class) -> asNumber(value, name).toLong()
        matches(target, Double::class) -> asNumber(value, name).toDouble()
        matches(target, Float::class) -> asNumber(value, name).toFloat()
        matches(target, Short::class) -> asNumber(value, name).toShort()
        matches(target, Byte::class) -> asNumber(value, name).toByte()
        List::class.java.isAssignableFrom(target) -> convertList(value, generic)
        Map::class.java.isAssignableFrom(target) -> convertMap(value, generic)
        value is Map<*, *> -> fromMap(value.entries.associate { it.key.toString() to it.value }, target)
        else -> value
    }

    /** List 字段：按元素类型逐个转换（元素可能是嵌套 data 类）。 */
    private fun convertList(value: Any, generic: Type?): List<Any?> {
        val elementType = elementType(generic, 0)
        return (value as List<*>).map { element ->
            if (elementType == null) element else convertElement(element, elementType)
        }
    }

    /** Map 字段：值按泛型值类型逐个转换。 */
    private fun convertMap(value: Any, generic: Type?): Map<String, Any?> {
        val valueType = elementType(generic, 1)
        return (value as Map<*, *>).entries.associate { (key, v) ->
            key.toString() to if (valueType == null) v else convertElement(v, valueType)
        }
    }

    /** List/Map 元素转换：值为 Map 且目标为嵌套 data 类时递归构造。 */
    private fun convertElement(value: Any?, elementType: Class<*>): Any? = when {
        value is Map<*, *> && !List::class.java.isAssignableFrom(elementType) && !Map::class.java.isAssignableFrom(elementType) ->
            fromMap(value.entries.associate { it.key.toString() to it.value }, elementType)
        value == null -> null
        else -> convertParam(value, elementType, "元素", null)
    }

    /** 从泛型类型取 List 元素 / Map 值类型（index：List 取 0，Map 取值位置 1）。 */
    private fun elementType(generic: Type?, index: Int): Class<*>? {
        if (generic !is ParameterizedType) return null
        val args = generic.actualTypeArguments
        if (index >= args.size) return null
        return args[index] as? Class<*>
    }

    /** 数字类型转换前的类型校验。 */
    private fun asNumber(value: Any, name: String): Number =
        value as? Number ?: throw IllegalArgumentException("配置字段 $name 需要数字类型，实际为 ${value.javaClass.simpleName}")

    /** 主构造器：非合成、参数最多（data 类主构造器即参数最多的非合成构造器）。 */
    private fun primaryConstructor(type: Class<*>): Constructor<*> =
        type.declaredConstructors
            .filterNot { it.isSynthetic }
            .maxByOrNull { it.parameterCount }
            ?: throw IllegalArgumentException("类 ${type.name} 没有可用的构造函数")

    /** 构造器参数名：不可用（未开启 -parameters）时按声明字段序回退。 */
    private fun paramNames(constructor: Constructor<*>, type: Class<*>): List<String> {
        val present = constructor.parameters.all { it.isNamePresent && !it.name.startsWith("arg") }
        return if (present) constructor.parameters.map { it.name } else propertyNames(type)
    }

    /** 属性名列表：优先声明字段（保序），无 backing field 时从访问器方法推导。 */
    private fun propertyNames(type: Class<*>): List<String> {
        val fieldNames = type.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .map { it.name }
        if (fieldNames.isNotEmpty()) return fieldNames
        return accessorMethods(type).map { method ->
            val prefix = when {
                method.name.startsWith(GET_PREFIX) -> GET_PREFIX
                method.name.startsWith(IS_PREFIX) && method.name.length > IS_PREFIX.length -> IS_PREFIX
                else -> return@map method.name
            }
            method.name.removePrefix(prefix).replaceFirstChar { it.lowercaseChar() }
        }
    }

    /** 公开无参访问器方法（getXxx / isXxx）。 */
    private fun accessorMethods(type: Class<*>): List<Method> =
        type.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && it.parameterCount == 0 && it.returnType != java.lang.Void.TYPE
        }

    /** 按属性名查找 getXxx()/isXxx() 访问器。 */
    private fun accessorOf(type: Class<*>, property: String): Method {
        val capitalized = property.replaceFirstChar { it.uppercaseChar() }
        return try {
            type.getMethod("$GET_PREFIX$capitalized")
        } catch (e: NoSuchMethodException) {
            try {
                type.getMethod("$IS_PREFIX$capitalized")
            } catch (e2: NoSuchMethodException) {
                throw IllegalArgumentException("类 ${type.name} 的属性 $property 缺少 getXxx()/isXxx() 访问器", e2)
            }
        }
    }

    /** target 是否为 Kotlin 值类型的原始或包装类型（如 int / java.lang.Integer）。 */
    private fun matches(target: Class<*>, k: KClass<*>): Boolean =
        target == k.java || target == k.javaObjectType || target == k.javaPrimitiveType

    /** 缺失字段的类型零值（0 / false / "" / 空集合）。 */
    private fun zeroValue(target: Class<*>): Any? = when {
        target == String::class.java -> ""
        matches(target, Boolean::class) -> false
        matches(target, Byte::class) -> 0.toByte()
        matches(target, Short::class) -> 0.toShort()
        matches(target, Int::class) -> 0
        matches(target, Long::class) -> 0L
        matches(target, Float::class) -> 0f
        matches(target, Double::class) -> 0.0
        List::class.java.isAssignableFrom(target) -> emptyList<Any>()
        Map::class.java.isAssignableFrom(target) -> emptyMap<String, Any>()
        else -> null
    }
}
