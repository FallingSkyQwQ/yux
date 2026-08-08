package yux.buildtool

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * build.yml 解析器（T-M7-1，02-§11.2 / 05-§3）。
 *
 * 读取项目根目录下的 build.yml，用 SnakeYAML 2.x（SafeConstructor 默认安全）解析为
 * [BuildConfig] 模型；schema 校验失败一律抛出带中文说明的 [BuildConfigException]。
 * 未知顶层字段静默忽略（前向兼容）。
 */
class BuildYmlParser {

    companion object {
        /** `target.jvm` 缺省值（02-§11.2）。 */
        private const val DEFAULT_JVM = 21
    }

    /** 解析 `<projectDir>/build.yml` 为 [BuildConfig]。 */
    fun parse(projectDir: Path): BuildConfig {
        val file = projectDir.resolve("build.yml")
        if (!Files.exists(file)) {
            throw BuildConfigException("未找到 build.yml: ${file.toAbsolutePath()}")
        }
        val root = Yaml().load<Any?>(Files.readString(file))
        if (root !is Map<*, *>) {
            throw BuildConfigException("build.yml 顶层必须是映射")
        }
        val language = nestedMap(root.yamlGet("language"), "language")
        val target = nestedMap(root.yamlGet("target"), "target")
        val source = nestedMap(root.yamlGet("source"), "source")
        val dependencies = nestedMap(root.yamlGet("dependencies"), "dependencies")
        val minecraft = nestedMap(dependencies?.yamlGet("minecraft"), "dependencies.minecraft")
        return BuildConfig(
            name = requireString(root, "name", allowNumber = false),
            version = requireString(root, "version", allowNumber = true),
            languageYux = string(language, "yux", "language.yux") ?: "1.0",
            targetJvm = jvm(target?.yamlGet("jvm")),
            sourceMain = string(source, "main", "source.main") ?: "src",
            sourceKotlin = string(source, "kotlin", "source.kotlin"),
            sourceJava = string(source, "java", "source.java"),
            resourcesDir = string(root, "resources") ?: "resources",
            testsDir = string(root, "tests") ?: "tests",
            mavenDeps = mavenDeps(dependencies?.yamlGet("maven")),
            paperVersion = string(minecraft, "paper", "dependencies.minecraft.paper"),
            pluginsEnabled = plugins(root.yamlGet("plugins")),
            mainClass = string(root, "main"),
        )
    }

    /** 按键取值（`Map<*, *>` 星投影禁止 get，改走 entries 线性查找）。 */
    private fun Map<*, *>.yamlGet(key: String): Any? =
        entries.firstOrNull { it.key == key }?.value

    /** 必填字段：必须是非空白字符串；[allowNumber] 时接受 YAML 数字（`version: 1.0`），否则报缺失。 */
    private fun requireString(map: Map<*, *>, key: String, allowNumber: Boolean): String {
        val value = map.yamlGet(key)
        if (value is Number) {
            if (allowNumber) return value.toString()
            throw BuildConfigException("build.yml 缺少必填字段: $key")
        }
        if (value !is String || value.isBlank()) {
            throw BuildConfigException("build.yml 缺少必填字段: $key")
        }
        return value
    }

    /**
     * 可选字符串字段：按本地键查找，缺省/显式 null → null；类型错误按路径报错。
     * YAML 数字（`yux: 1.0`、`paper: 1.21`）按文档示例转为字符串接受。
     */
    private fun string(map: Map<*, *>?, key: String, path: String = key): String? {
        val value = map?.yamlGet(key) ?: return null
        if (value is Number) return value.toString()
        if (value !is String) throw BuildConfigException("$path 必须是字符串")
        return value
    }

    /** 子映射字段：缺省 → null；类型错误抛异常。 */
    private fun nestedMap(value: Any?, key: String): Map<*, *>? {
        if (value == null) return null
        if (value !is Map<*, *>) throw BuildConfigException("$key 必须是映射")
        return value
    }

    /** `target.jvm`：仅接受整数 Number，缺省 [DEFAULT_JVM]。 */
    private fun jvm(value: Any?): Int = when {
        value == null -> DEFAULT_JVM
        value is Number && value.toDouble() % 1.0 == 0.0 -> value.toInt()
        else -> throw BuildConfigException("target.jvm 必须是整数")
    }

    /** `dependencies.maven`：坐标列表，每项须含非空白 group/artifact/version（version 允许 YAML 数字）。 */
    private fun mavenDeps(value: Any?): List<MavenCoord> {
        if (value == null) return emptyList()
        if (value !is List<*>) throw BuildConfigException("dependencies.maven 必须是列表")
        return value.mapIndexed { index, entry ->
            if (entry !is Map<*, *>) {
                throw BuildConfigException("dependencies.maven 第 ${index + 1} 项必须是映射")
            }
            val group = entry.yamlGet("group") as? String
            val artifact = entry.yamlGet("artifact") as? String
            val version = (entry.yamlGet("version") as? String) ?: (entry.yamlGet("version") as? Number)?.toString()
            if (group.isNullOrBlank() || artifact.isNullOrBlank() || version.isNullOrBlank()) {
                throw BuildConfigException("dependencies.maven 第 ${index + 1} 项缺少 group/artifact/version")
            }
            MavenCoord(group, artifact, version)
        }
    }

    /** `plugins`：值为 true 的键集合；false 与非布尔值忽略。 */
    private fun plugins(value: Any?): Set<String> {
        if (value == null) return emptySet()
        if (value !is Map<*, *>) throw BuildConfigException("plugins 必须是映射")
        return value.entries
            .filter { it.value == true }
            .mapNotNull { it.key as? String }
            .toSet()
    }
}
