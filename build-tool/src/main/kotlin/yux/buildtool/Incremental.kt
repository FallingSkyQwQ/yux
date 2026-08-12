package yux.buildtool

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator

/**
 * 文件级增量缓存（T-M7-6，02-§12.3）：以「全部源文件 SHA-256 + 编译器版本 + build 配置
 * 哈希」为键，判定既有产物是否过期。清单 `manifest.json` 为单行确定性 JSON：
 * `{"version": "0.1.0-SNAPSHOT", "config": "...", "files": {"src/main.yux": {"sha": "...", "deps": [...], "classes": [...]}}}`。
 *
 * 类级增量（M13）：每个源文件记录「依赖文件集（deps）」与「产出的类名集（classes）」。
 * 变更文件 + 其反向依赖需要重新生成字节码；其余文件复用缓存类字节（选择性 codegen）。
 *
 * 清单键为相对项目根的正斜杠路径（绝对输入相对化、相对输入直接采用——与
 * [YuxSymbols] 的 relativePath 一致），按键排序保证确定性。清单解析走 SnakeYAML
 * （SafeConstructor，兼容 JSON 流式写法）；文件缺失 / 解析失败 / 结构非法一律视为过期。
 */
class YuxCache(
    private val projectDir: Path,
    val cacheDir: Path = BuildPaths.resolve(projectDir, BuildPaths.CACHE_DIR),
) {

    /** 全部源文件哈希与清单一致、且 build 配置哈希与编译器版本匹配 → 增量命中。 */
    fun isUpToDate(
        sources: List<Path>,
        compilerVersion: String = COMPILER_VERSION,
        configHash: String = "",
    ): Boolean {
        val manifest = cacheDir.resolve(MANIFEST_FILE)
        if (!Files.isRegularFile(manifest)) return false
        val parsed = readManifest(manifest) ?: return false
        if (parsed.version != compilerVersion) return false
        if (parsed.config != configHash) return false
        val current = try {
            hash(sources)
        } catch (e: IOException) {
            return false
        }
        return parsed.files == current
    }

    /** 写入新清单（源文件 → SHA-256 + build 配置哈希）；缓存目录不存在时自动创建。 */
    fun save(
        sources: List<Path>,
        compilerVersion: String = COMPILER_VERSION,
        configHash: String = "",
    ) {
        val hashes = hash(sources)
        Files.createDirectories(cacheDir)
        Files.writeString(
            cacheDir.resolve(MANIFEST_FILE),
            renderManifest(compilerVersion, configHash, hashes),
        )
    }

    /** 读取各文件的增量元数据（SHA + 依赖 + 产出类）；清单缺失/旧格式/损坏 → null。 */
    fun loadFileEntries(): Map<String, FileCacheEntry>? {
        val manifest = cacheDir.resolve(MANIFEST_FILE)
        if (!Files.isRegularFile(manifest)) return null
        val parsed = readManifest(manifest) ?: return null
        return parsed.entries
    }

    /** 以文件粒度写入清单（deps/classes 随构建分析结果更新）。 */
    fun save(
        entries: Map<String, FileCacheEntry>,
        compilerVersion: String = COMPILER_VERSION,
        configHash: String = "",
    ) {
        Files.createDirectories(cacheDir)
        Files.writeString(
            cacheDir.resolve(MANIFEST_FILE),
            renderManifestDetailed(compilerVersion, configHash, entries),
        )
    }

    /**
     * 需要重新生成字节码的文件集：源文件哈希变化（新增/修改/删除）+ 其**反向依赖**闭包
     * （前次构建记录的 deps）。返回 null 表示无缓存元数据 → 全量重编。
     */
    fun changedFiles(
        sources: List<Path>,
        previous: Map<String, FileCacheEntry>,
    ): Set<String> {
        val current = try {
            hash(sources)
        } catch (e: IOException) {
            return sources.map { relativeKey(it) }.toSet()
        }
        val changed = LinkedHashSet<String>()
        // 修改/新增：哈希不一致
        for ((key, sha) in current) {
            if (previous[key]?.sha != sha) changed += key
        }
        // 删除：前次清单中有而当前源码缺失 → 其反向依赖须重编
        for (key in previous.keys) {
            if (key !in current) changed += key
        }
        // 反向依赖闭包（BFS）
        val reverse = mutableMapOf<String, MutableSet<String>>()
        for ((file, entry) in previous) {
            for (dep in entry.deps) reverse.getOrPut(dep) { mutableSetOf() } += file
        }
        val affected = LinkedHashSet<String>()
        val queue = ArrayDeque(changed)
        while (queue.isNotEmpty()) {
            val file = queue.removeFirst()
            if (!affected.add(file)) continue
            reverse[file]?.forEach { queue.addLast(it) }
        }
        return affected
    }

    /** 从磁盘读取类字节（增量复用：未被重新生成的文件产出类）。 */
    fun loadCachedClasses(classNames: Set<String>, classesDir: Path = BuildPaths.resolve(projectDir, BuildPaths.YUX_CLASSES_DIR)): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        for (name in classNames) {
            val file = classesDir.resolve(name.replace('.', '/') + ".class")
            if (!Files.isRegularFile(file)) continue
            out += name to Files.readAllBytes(file)
        }
        return out
    }

    /** 删除缓存目录（不存在时静默）。 */
    fun clear() {
        if (Files.exists(cacheDir)) {
            Files.walk(cacheDir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /** 计算全部源文件哈希，键为相对项目根的清单键；读取失败抛出 [IOException]。 */
    private fun hash(sources: List<Path>): Map<String, String> {
        val hashes = sortedMapOf<String, String>()
        for (source in sources) {
            hashes[relativeKey(source)] = sha256(Files.readAllBytes(source))
        }
        return hashes
    }

    /** 读取并解析清单；不存在 / 解析失败 / 结构非法 → null。 */
    private fun readManifest(manifest: Path): ManifestData? {
        val text = try {
            Files.readString(manifest)
        } catch (e: IOException) {
            return null
        }
        val root = try {
            Yaml().load<Any?>(text)
        } catch (e: YAMLException) {
            return null
        }
        if (root !is Map<*, *>) return null
        val version = root["version"] as? String ?: return null
        val config = root["config"] as? String ?: return null
        val files = root["files"] as? Map<*, *> ?: return null
        val entries = sortedMapOf<String, FileCacheEntry>()
        for ((key, value) in files.entries) {
            if (key !is String) return null
            // 兼容旧格式（v0.1：files 值为纯 SHA 字符串）与新格式（M13：值为 {sha, deps, classes}）
            entries[key] =
                when (value) {
                    is String -> FileCacheEntry(value, emptyList(), emptyList())
                    is Map<*, *> -> {
                        val sha = value["sha"] as? String ?: return null
                        val deps = stringList(value["deps"])
                        val classes = stringList(value["classes"])
                        if (deps == null || classes == null) return null
                        FileCacheEntry(sha, deps, classes)
                    }
                    else -> return null
                }
        }
        return ManifestData(version, config, entries)
    }

    private fun stringList(value: Any?): List<String>? =
        (value as? List<*>)?.map { it as? String ?: return null }

    /** 源路径 → 清单键：绝对路径相对 [projectDir] 规约，相对路径直接采用。 */
    private fun relativeKey(path: Path): String {
        val rel = if (path.isAbsolute) projectDir.relativize(path) else path
        return rel.toString().replace('\\', '/')
    }

    /** 渲染单行确定性清单（文件项按键排序）。 */
    private fun renderManifest(version: String, configHash: String, files: Map<String, String>): String {
        val entries = files.entries.joinToString(", ") { "${quote(it.key)}: ${quote(it.value)}" }
        return "{\"version\": ${quote(version)}, \"config\": ${quote(configHash)}, \"files\": {$entries}}"
    }

    /** 渲染文件粒度清单（deps/classes 排序后序列化为 JSON 数组文本）。 */
    private fun renderManifestDetailed(version: String, configHash: String, files: Map<String, FileCacheEntry>): String {
        val entries =
            files.entries.joinToString(", ") { (key, entry) ->
                val deps = entry.deps.sorted().joinToString(", ") { quote(it) }
                val classes = entry.classes.sorted().joinToString(", ") { quote(it) }
                val body = "\"sha\": ${quote(entry.sha)}, \"deps\": [$deps], \"classes\": [$classes]"
                "${quote(key)}: {$body}"
            }
        return "{\"version\": ${quote(version)}, \"config\": ${quote(configHash)}, \"files\": {$entries}}"
    }

    /** JSON 字符串转义（与 [YuxSymbols] 一致：仅处理 `\` 与 `"`）。 */
    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 解析后的清单模型。 */
    private data class ManifestData(val version: String, val config: String, val entries: Map<String, FileCacheEntry>) {
        val files: Map<String, String>
            get() = entries.mapValues { it.value.sha }
    }

    companion object {
        /** 编译器版本：清单键的一部分；编译器变更后强制全量重编。 */
        const val COMPILER_VERSION = "0.1.0-SNAPSHOT"
        /** 清单文件名。 */
        const val MANIFEST_FILE = "manifest.json"

        /** 计算 SHA-256 十六进制摘要。 */
        fun sha256(content: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(content)
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

/** 单文件增量元数据（类级增量，M13）。 */
data class FileCacheEntry(
    /** 源文件 SHA-256。 */
    val sha: String,
    /** 依赖的其它源文件清单键（用于反向依赖重编）。 */
    val deps: List<String>,
    /** 本文件产出的类名（JVM 点分名；增量时复用未变更文件的类字节）。 */
    val classes: List<String>,
)
