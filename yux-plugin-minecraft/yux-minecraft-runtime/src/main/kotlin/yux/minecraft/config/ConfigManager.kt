package yux.minecraft.config

import org.bukkit.plugin.Plugin
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import yux.minecraft.annotations.YuxConfig
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.KClass

/**
 * 配置管理器（T-M8-8 / 03-§3.5、§4.3）：YAML 配置文件 ↔ data 类实例。
 *
 * 约定：
 * - 配置类为 Kotlin data 类，可带 [YuxConfig] 注解；[YuxConfig.name] 为缓存名（缺省类简单名），
 *   [YuxConfig.path] 为相对插件数据目录的路径（缺省 "config.yml"）。
 * - 读取：磁盘 YAML 值覆盖 data 类默认字段值（嵌套 data 类按子映射递归合并）。
 * - 序列化：`getXxx()`/`isXxx()` 访问器 → 小驼峰属性名；嵌套 data 类递归为嵌套映射。
 * - 反序列化：主构造器（非合成、参数最多）按参数名取数；参数名不可用时按声明字段序回退。
 * - YAML 解析用 SnakeYAML 默认 SafeConstructor（不解析任意 Java 类型）。
 * - 线程安全：v0.1 无锁，约定仅在单线程（onEnable）中调用。
 */
object ConfigManager {

    private const val GET_PREFIX = "get"
    private const val IS_PREFIX = "is"

    /** 实例缓存：name → 最近一次 [load]/[reload] 的结果实例。 */
    private val cache = mutableMapOf<String, Any>()

    /** 读取 `<dataFolder>/<path>` 配置文件，YAML 值覆盖默认字段值，缓存后按 name 返回实例。 */
    fun <T : Any> load(plugin: Plugin, defaults: T): T {
        val type = defaults.javaClass
        val meta = metaOf(type)
        val file = configFile(plugin, meta.path)
        val base = toMap(defaults)
        val overlay = if (file.isFile) readYaml(file) else emptyMap()
        val instance = type.cast(fromMap(merge(base, overlay), type))
        cache[meta.name] = instance
        return instance
    }

    /** 序列化 data 为 YAML 写回 `<dataFolder>/<path>`（父目录自动创建）。 */
    fun <T : Any> save(plugin: Plugin, data: T) {
        val meta = metaOf(data.javaClass)
        val file = configFile(plugin, meta.path)
        file.parentFile?.mkdirs()
        file.writeText(dumpYaml(toMap(data)))
    }

    /** 重新从磁盘读取并覆盖默认值，更新缓存。 */
    fun <T : Any> reload(plugin: Plugin, defaults: T): T {
        cache.remove(metaOf(defaults.javaClass).name)
        return load(plugin, defaults)
    }

    /** 按 name 取缓存实例（未加载返回 null）。 */
    fun get(name: String): Any? = cache[name]

    // ── 注解与路径 ──────────────────────────────────────────────────────────

    private class Meta(val name: String, val path: String)

    private fun metaOf(type: Class<*>): Meta {
        val annotation = type.getAnnotation(YuxConfig::class.java)
        val name = annotation?.name?.takeIf { it.isNotBlank() } ?: type.simpleName
        val path = annotation?.path?.takeIf { it.isNotBlank() } ?: "config.yml"
        return Meta(name, path)
    }

    private fun configFile(plugin: Plugin, path: String): File = File(plugin.dataFolder, path)

    // ── 序列化方向（data 类 → Map）──────────────────────────────────────────

    /** data 类实例 → 映射（访问器 → 小驼峰属性名）。 */
    private fun toMap(data: Any): Map<String, Any?> {
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

    // ── 反序列化方向（Map → data 类）────────────────────────────────────────

    /** 映射 → data 类实例（主构造器按参数名取值）。 */
    private fun fromMap(map: Map<String, Any?>, type: Class<*>): Any {
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

    // ── 反射约定（与 NbtIO 相同，独立实现）──────────────────────────────────

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

    // ── YAML 读写 ───────────────────────────────────────────────────────────

    /** 读取 YAML 文件为字符串键映射（SafeConstructor，顶层非映射返回空映射）。 */
    private fun readYaml(file: File): Map<String, Any?> {
        val loaded = file.inputStream().use { Yaml().load<Any>(it) }
        return when (loaded) {
            null -> emptyMap()
            is Map<*, *> -> loaded.entries.associate { it.key.toString() to it.value }
            else -> emptyMap()
        }
    }

    /** 默认值映射与磁盘映射深合并（磁盘值覆盖，嵌套映射递归合并）。 */
    private fun merge(base: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> {
        val result = base.toMutableMap()
        for ((key, value) in overlay) {
            val baseValue = result[key]
            result[key] = if (baseValue is Map<*, *> && value is Map<*, *>) {
                merge(
                    baseValue.entries.associate { it.key.toString() to it.value },
                    value.entries.associate { it.key.toString() to it.value },
                )
            } else {
                value
            }
        }
        return result
    }

    /** 映射 → 可读 YAML 文本（块式流样式）。 */
    private fun dumpYaml(map: Map<String, Any?>): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        }
        return Yaml(options).dump(map)
    }
}
