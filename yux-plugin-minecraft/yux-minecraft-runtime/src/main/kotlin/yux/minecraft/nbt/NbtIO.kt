package yux.minecraft.nbt

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass

/**
 * NBT 序列化器（T-M8-9 / 03-§6.2）：data 类 ↔ NBT 复合标签。
 *
 * 类型映射表（03-§6.2）：
 * | Kotlin 类型 | NBT 标签 |
 * |---|---|
 * | Int / Byte / Long / Float / Double | NbtInt / NbtByte / NbtLong / NbtFloat / NbtDouble |
 * | String | NbtString |
 * | Boolean | NbtByte(1/0) |
 * | List\<T\> | NbtList |
 * | Map\<String, T\> / data 类 | NbtCompound |
 *
 * 序列化约定与 ConfigManager 一致：经 `getXxx()`/`isXxx()` 访问器取属性值，
 * 属性名按小驼峰（剥离 get/is 前缀）。本文件不依赖 ConfigManager 代码，也不依赖 Paper API。
 *
 * v0.1 简化说明：
 * - 反序列化使用主构造器（非合成、参数最多）按参数名取数；构造器参数名不可用时按声明字段序回退。
 * - 不读取构造器默认值：标签缺失的字段回退为零值（0 / false / "" / 空集合 / 全零嵌套实例）。
 */
object NbtIO {

    private const val GET_PREFIX = "get"
    private const val IS_PREFIX = "is"

    /** 对象 → NBT 复合标签（03-§6.2 映射表）。 */
    fun serializeNbt(obj: Any): NbtCompound = toCompound(obj)

    /** NBT 复合标签 → 对象（type 为 data 类的 Class；Boolean 字段与 NbtByte 互转）。 */
    fun deserializeNbt(tag: NbtCompound, type: Class<*>): Any = construct(type, tag)

    /** data 类属性名列表（getXxx/isXxx 访问器约定，供外部校验/工具使用）。 */
    fun properties(type: Class<*>): List<String> = propertyNames(type)

    // ── 序列化方向 ──────────────────────────────────────────────────────────

    /** data 类实例 → NbtCompound（属性 → 标签；null 字段跳过）。 */
    private fun toCompound(obj: Any): NbtCompound {
        val type = obj.javaClass
        val compound = NbtCompound()
        for (name in propertyNames(type)) {
            val value = accessorOf(type, name).invoke(obj)
            if (value != null) compound.entries[name] = toTag(value)
        }
        return compound
    }

    /** 字段值 → NBT 标签（null 已在复合标签层跳过）。 */
    private fun toTag(value: Any): NbtTag = when (value) {
        is Byte -> NbtByte(value)
        is Short -> NbtShort(value)
        is Int -> NbtInt(value)
        is Long -> NbtLong(value)
        is Float -> NbtFloat(value)
        is Double -> NbtDouble(value)
        is Boolean -> NbtByte(if (value) 1 else 0)
        is String -> NbtString(value)
        is List<*> -> NbtList(value.map { toTagElement(it) }.toMutableList())
        is Map<*, *> -> NbtCompound(
            value.entries.associate { (key, v) -> key.toString() to toTagElement(v) }.toMutableMap(),
        )
        else -> toCompound(value) // 嵌套 data 类 → 子复合标签
    }

    /** 列表/映射元素 → NBT 标签（null 元素无对应标签类型，抛异常说明）。 */
    private fun toTagElement(value: Any?): NbtTag {
        if (value == null) throw IllegalArgumentException("NBT 序列化不支持 null 元素")
        return toTag(value)
    }

    // ── 反序列化方向 ────────────────────────────────────────────────────────

    /** NbtCompound → data 类实例（主构造器按参数名取标签值）。 */
    private fun construct(type: Class<*>, compound: NbtCompound): Any {
        val constructor = primaryConstructor(type)
        val names = paramNames(constructor, type)
        val args = Array<Any?>(names.size) { i ->
            val tag = compound.entries[names[i]]
            val param = constructor.parameters[i]
            if (tag == null) zeroValue(param.type) else fromTag(tag, param.type, param.parameterizedType)
        }
        constructor.isAccessible = true
        return type.cast(constructor.newInstance(*args))
    }

    /** NBT 标签 → 字段值（target 为字段类型；generic 携带 List/Map 元素类型信息）。 */
    private fun fromTag(tag: NbtTag, target: Class<*>, generic: Type?): Any? = when {
        tag is NbtByte && matches(target, Boolean::class) -> tag.value != 0.toByte()
        tag is NbtByte -> tag.value
        tag is NbtShort -> tag.value
        tag is NbtInt -> tag.value
        tag is NbtLong -> tag.value
        tag is NbtFloat -> tag.value
        tag is NbtDouble -> tag.value
        tag is NbtString -> tag.value
        tag is NbtList -> tag.values.map { element -> convertElement(element, elementType(generic, 0)) }
        tag is NbtCompound && Map::class.java.isAssignableFrom(target) ->
            tag.entries.mapValues { (_, v) -> convertElement(v, elementType(generic, 1)) }
        tag is NbtCompound -> construct(target, tag) // 嵌套 data 类
        else -> throw IllegalArgumentException("不支持的 NBT 标签类型：${tag::class.simpleName}")
    }

    /** NbtList/NbtCompound 元素 → 目标元素类型值（元素可能是嵌套 data 类）。 */
    private fun convertElement(tag: NbtTag, elementType: Class<*>?): Any? =
        fromTag(tag, elementType ?: tagTarget(tag), null)

    /** 无泛型信息时，按标签类型取自然标量类型。 */
    private fun tagTarget(tag: NbtTag): Class<*> = when (tag) {
        is NbtByte -> Byte::class.javaObjectType
        is NbtShort -> Short::class.javaObjectType
        is NbtInt -> Int::class.javaObjectType
        is NbtLong -> Long::class.javaObjectType
        is NbtFloat -> Float::class.javaObjectType
        is NbtDouble -> Double::class.javaObjectType
        is NbtString -> String::class.java
        is NbtList -> List::class.java
        is NbtCompound -> throw IllegalArgumentException("缺少泛型类型信息，无法解析 NbtCompound 元素")
    }

    /** 从泛型类型取 List 元素 / Map 值类型（index：List 取 0，Map 取值位置 1）。 */
    private fun elementType(generic: Type?, index: Int): Class<*>? {
        if (generic !is ParameterizedType) return null
        val args = generic.actualTypeArguments
        if (index >= args.size) return null
        return args[index] as? Class<*>
    }

    // ── 反射约定（与 ConfigManager 相同，独立实现）────────────────────────────

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

    /** 缺失字段的类型零值（0 / false / "" / 空集合 / 全零嵌套实例）。 */
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
        else -> construct(target, NbtCompound()) // 嵌套 data 类：空复合全零构造
    }
}
