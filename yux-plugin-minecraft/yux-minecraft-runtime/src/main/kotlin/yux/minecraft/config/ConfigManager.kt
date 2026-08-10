package yux.minecraft.config

import org.bukkit.plugin.Plugin
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import yux.minecraft.annotations.YuxConfig
import yux.minecraft.util.atomicWriteText
import java.io.File
import java.io.IOException

/**
 * 配置管理器（T-M8-8 / 03-§3.5、§4.3）：YAML 配置文件 ↔ data 类实例。
 *
 * 约定：
 * - 配置类为 Kotlin data 类，可带 [YuxConfig] 注解；[YuxConfig.name] 为缓存名（缺省类简单名），
 *   [YuxConfig.path] 为相对插件数据目录的路径（缺省 "config.yml"）。
 * - 读取：磁盘 YAML 值覆盖 data 类默认字段值（嵌套 data 类按子映射递归合并）。
 * - data 类 ↔ Map 转换约定见 [ConfigMapping]。
 * - YAML 解析用 SnakeYAML 默认 SafeConstructor（不解析任意 Java 类型）。
 * - 落盘为原子写（同目录 .tmp + ATOMIC_MOVE），[save] 经内部锁串行化，崩溃/并发不产生半写文件。
 * - 读取容错：文件缺失或损坏（IO/解析异常）时回退默认字段值并告警，不崩溃。
 */
object ConfigManager {

    /** 实例缓存：name → 最近一次 [load]/[reload] 的结果实例。 */
    private val cache = mutableMapOf<String, Any>()

    /** 保存锁：串行化 [save] 落盘，避免并发写互相覆盖临时文件。 */
    private val saveLock = Any()

    /** 读取 `<dataFolder>/<path>` 配置文件，YAML 值覆盖默认字段值，缓存后按 name 返回实例。 */
    fun <T : Any> load(plugin: Plugin, defaults: T): T {
        val type = defaults.javaClass
        val meta = metaOf(type)
        val file = configFile(plugin, meta.path)
        val base = ConfigMapping.toMap(defaults)
        val overlay = if (file.isFile) {
            try {
                readYaml(file)
            } catch (e: IOException) {
                plugin.logger?.warning("读取配置文件失败，回退默认值: ${file.path}: ${e.message}")
                emptyMap()
            } catch (e: RuntimeException) {
                plugin.logger?.warning("配置文件损坏，回退默认值: ${file.path}: ${e.message}")
                emptyMap()
            }
        } else {
            emptyMap()
        }
        val instance = type.cast(ConfigMapping.fromMap(merge(base, overlay), type))
        cache[meta.name] = instance
        return instance
    }

    /** 序列化 data 为 YAML 写回 `<dataFolder>/<path>`（父目录自动创建，原子写）。 */
    fun <T : Any> save(plugin: Plugin, data: T) {
        val meta = metaOf(data.javaClass)
        val file = configFile(plugin, meta.path)
        synchronized(saveLock) {
            file.parentFile?.mkdirs()
            atomicWriteText(file, dumpYaml(ConfigMapping.toMap(data)))
        }
    }

    /** 重新从磁盘读取并覆盖默认值，更新缓存。 */
    fun <T : Any> reload(plugin: Plugin, defaults: T): T {
        cache.remove(metaOf(defaults.javaClass).name)
        return load(plugin, defaults)
    }

    /** 按 name 取缓存实例（未加载返回 null）。@JvmStatic 供 Yux 侧调用（M9）。 */
    @JvmStatic
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
