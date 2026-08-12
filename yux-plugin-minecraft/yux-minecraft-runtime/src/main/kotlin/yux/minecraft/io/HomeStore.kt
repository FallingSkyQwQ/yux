package yux.minecraft.io

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import yux.minecraft.util.atomicWriteText
import java.io.File
import java.io.IOException
import java.util.logging.Logger

/**
 * Home 插件数据存取桥接（M9，T-M9-1）：把 data 类以 YAML 落盘/读回。
 *
 * 设计偏差（04-§6）：设计文档用 JSON（`serialize`/`deserialize`），v0.1 未实现
 * yux.io 序列化器；本类以 runtime 已有的 SnakeYAML 提供等价存取（data 类 ↔ 映射）。
 *
 * 方法与 ConfigManager 的反射约定一致（getter → 小驼峰属性名、主构造器反序列化），
 * 供 Yux 侧 `@JvmStatic` 调用（04-§6 的 `HomeData.save`/`loadHomeData` 桥接）。
 *
 * 落盘为原子写（同目录 .tmp + ATOMIC_MOVE）；读取容错：文件缺失或损坏
 * （IO/解析异常）时回退默认值并告警，不崩溃。
 */
object HomeStore {

    private val logger = Logger.getLogger(HomeStore::class.java.name)

    /** data 类 → YAML 文本（getter → 属性名；嵌套 data 类递归为映射）。 */
    @JvmStatic
    fun toYaml(data: Any): String {
        val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
        return Yaml(options).dump(toMap(data))
    }

    /** YAML 文本 → data 类实例（主构造器按参数名取值；缺省字段用零值）。 */
    @JvmStatic
    fun fromYaml(text: String, type: Class<*>): Any {
        val loaded = Yaml().load<Any>(text)
        val map = if (loaded is Map<*, *>) loaded.entries.associate { it.key.toString() to it.value } else emptyMap()
        return fromMap(map, type)
    }

    /** 写 `<dataFolder>/<relPath>` YAML（父目录自动创建，原子写）。 */
    @JvmStatic
    fun save(dataFolder: File, relPath: String, data: Any) {
        val file = File(dataFolder, relPath)
        file.parentFile?.mkdirs()
        atomicWriteText(file, toYaml(data))
    }

    /** 读 `<dataFolder>/<relPath>` YAML 为 data 类；文件缺失或损坏返回 null。 */
    @JvmStatic
    fun load(dataFolder: File, relPath: String, type: Class<*>): Any? {
        val file = File(dataFolder, relPath)
        if (!file.isFile) return null
        return try {
            fromYaml(file.readText(), type)
        } catch (e: IOException) {
            logger.warning("读取数据文件失败，回退默认值: $file: ${e.message}")
            null
        } catch (e: RuntimeException) {
            logger.warning("数据文件损坏，回退默认值: $file: ${e.message}")
            null
        }
    }

    /** Yux 侧友好加载（M9）：以 defaults 实例类型反序列化，文件缺失或损坏返回 null。 */
    @JvmStatic
    fun loadLike(dataFolder: File, relPath: String, defaults: Any): Any? {
        val file = File(dataFolder, relPath)
        if (!file.isFile) return null
        return try {
            fromYaml(file.readText(), defaults.javaClass)
        } catch (e: IOException) {
            logger.warning("读取数据文件失败，回退默认值: $file: ${e.message}")
            null
        } catch (e: RuntimeException) {
            logger.warning("数据文件损坏，回退默认值: $file: ${e.message}")
            null
        }
    }

    /** 加载 `<dataFolder>/<relPath>` 为 `Map<String, V>`（M9）：值类型由 defaults 元素示例确定。 */
    @JvmStatic
    fun loadMap(dataFolder: File, relPath: String, valueExample: Any): MutableMap<String, Any> {
        val file = File(dataFolder, relPath)
        // 返回可变映射：调用方（如 HomeData.homes）会直接写入，只读 emptyMap 会抛 UnsupportedOperationException
        if (!file.isFile) return linkedMapOf()
        val loaded = try {
            Yaml().load<Any>(file.readText())
        } catch (e: IOException) {
            logger.warning("读取数据文件失败，回退空映射: $file: ${e.message}")
            return linkedMapOf()
        } catch (e: RuntimeException) {
            logger.warning("数据文件损坏，回退空映射: $file: ${e.message}")
            return linkedMapOf()
        }
        if (loaded !is Map<*, *>) return linkedMapOf()
        val result = linkedMapOf<String, Any>()
        for ((k, v) in loaded) {
            val value = when {
                v is Map<*, *> -> fromMap(v.entries.associate { it.key.toString() to it.value }, valueExample.javaClass)
                else -> v
            }
            if (value != null) result[k.toString()] = value
        }
        return result
    }

    /** 保存 `Map<String, V>` 到 `<dataFolder>/<relPath>`（M9）：值按 data 类递归为映射，原子写。 */
    @JvmStatic
    fun saveMap(dataFolder: File, relPath: String, map: Map<String, Any>) {
        val file = File(dataFolder, relPath)
        file.parentFile?.mkdirs()
        atomicWriteText(file, dumpYaml(map.entries.associate { it.key to toYamlValue(it.value) }))
    }

    private fun dumpYaml(map: Map<String, Any?>): String {
        val options = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }
        return Yaml(options).dump(map)
    }

    /** 文件是否存在。 */
    @JvmStatic
    fun exists(dataFolder: File, relPath: String): Boolean = File(dataFolder, relPath).isFile

    // ── data 类 ↔ Map（与 ConfigManager 的 toMap/fromMap 相同约定）────────────

    private fun toMap(data: Any): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        for (name in propertyNames(data.javaClass)) {
            result[name] = toYamlValue(getterOf(data.javaClass, name).invoke(data))
        }
        return result
    }

    private fun toYamlValue(value: Any?): Any? = when (value) {
        null, is String, is Number, is Boolean -> value
        is List<*> -> value.map { toYamlValue(it) }
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to toYamlValue(v) }
        else -> toMap(value)
    }

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

    private fun convertParam(value: Any, target: Class<*>, name: String, generic: java.lang.reflect.Type?): Any = when {
        target == String::class.java -> if (value is String) value else value.toString()
        target == java.lang.Boolean::class.java || target == java.lang.Boolean.TYPE -> when (value) {
            is Boolean -> value
            is String -> value.toBoolean()
            else -> false
        }
        target == java.lang.Integer::class.java || target == java.lang.Integer.TYPE -> (value as Number).toInt()
        target == java.lang.Long::class.java || target == java.lang.Long.TYPE -> (value as Number).toLong()
        target == java.lang.Double::class.java || target == java.lang.Double.TYPE -> (value as Number).toDouble()
        target == java.lang.Float::class.java || target == java.lang.Float.TYPE -> (value as Number).toFloat()
        List::class.java.isAssignableFrom(target) -> convertList(value, generic)
        Map::class.java.isAssignableFrom(target) -> convertMap(value, generic)
        value is Map<*, *> -> fromMap(value.entries.associate { it.key.toString() to it.value }, target)
        else -> value
    }

    /** List 元素递归转换（元素为 Map 且目标是 data 类时构造）。 */
    private fun convertList(value: Any, generic: java.lang.reflect.Type?): List<Any?> {
        val elementType = elementType(generic, 0)
        return (value as List<*>).map { element ->
            when {
                element is Map<*, *> && elementType != null && !List::class.java.isAssignableFrom(elementType) &&
                    !Map::class.java.isAssignableFrom(elementType) ->
                    fromMap(element.entries.associate { it.key.toString() to it.value }, elementType)
                else -> element
            }
        }
    }

    /** Map 值递归转换（值为 Map 且泛型值是 data 类时构造）。 */
    private fun convertMap(value: Any, generic: java.lang.reflect.Type?): Map<String, Any?> {
        val valueType = elementType(generic, 1)
        return (value as Map<*, *>).entries.associate { (k, v) ->
            k.toString() to when {
                v is Map<*, *> && valueType != null && !List::class.java.isAssignableFrom(valueType) &&
                    !Map::class.java.isAssignableFrom(valueType) ->
                    fromMap(v.entries.associate { it.key.toString() to it.value }, valueType)
                else -> v
            }
        }
    }

    /** 从参数化类型取 List 元素 / Map 值类型（List 取 0，Map 取 1）。 */
    private fun elementType(generic: java.lang.reflect.Type?, index: Int): Class<*>? {
        if (generic !is java.lang.reflect.ParameterizedType) return null
        val args = generic.actualTypeArguments
        if (index >= args.size) return null
        return args[index] as? Class<*>
    }

    private fun primaryConstructor(type: Class<*>): java.lang.reflect.Constructor<*> =
        type.declaredConstructors
            .filterNot { it.isSynthetic }
            .maxByOrNull { it.parameterCount }
            ?: throw IllegalArgumentException("类 ${type.name} 没有可用的构造函数")

    private fun paramNames(constructor: java.lang.reflect.Constructor<*>, type: Class<*>): List<String> {
        val present = constructor.parameters.all { it.isNamePresent && !it.name.startsWith("arg") }
        return if (present) constructor.parameters.map { it.name } else propertyNames(type)
    }

    private fun propertyNames(type: Class<*>): List<String> {
        val fieldNames = type.declaredFields
            .filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
        if (fieldNames.isNotEmpty()) return fieldNames
        return type.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.parameterCount == 0 && it.returnType != java.lang.Void.TYPE }
            .map { method ->
                when {
                    method.name.startsWith("get") && method.name.length > 3 -> method.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
                    method.name.startsWith("is") && method.name.length > 2 && method.returnType == java.lang.Boolean.TYPE ->
                        method.name.removePrefix("is").replaceFirstChar { it.lowercaseChar() }
                    else -> method.name
                }
            }
    }

    private fun getterOf(type: Class<*>, property: String): java.lang.reflect.Method {
        val cap = property.replaceFirstChar { it.uppercaseChar() }
        return try {
            type.getMethod("get$cap")
        } catch (_: NoSuchMethodException) {
            type.getMethod("is$cap")
        }
    }

    private fun zeroValue(target: Class<*>): Any? = when {
        target == String::class.java -> ""
        target == java.lang.Boolean::class.java || target == java.lang.Boolean.TYPE -> false
        target == java.lang.Integer::class.java || target == java.lang.Integer.TYPE -> 0
        target == java.lang.Long::class.java || target == java.lang.Long.TYPE -> 0L
        target == java.lang.Double::class.java || target == java.lang.Double.TYPE -> 0.0
        target == java.lang.Float::class.java || target == java.lang.Float.TYPE -> 0f
        List::class.java.isAssignableFrom(target) -> emptyList<Any>()
        Map::class.java.isAssignableFrom(target) -> emptyMap<String, Any>()
        else -> null
    }
}
