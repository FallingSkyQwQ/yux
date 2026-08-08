package yux.buildtool

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator

/**
 * 文件级增量缓存（T-M7-6，02-§12.3）：以「全部源文件 SHA-256 + 编译器版本」为键，
 * 判定既有产物是否过期。清单 `manifest.json` 为单行确定性 JSON：
 * `{"version": "0.1.0-SNAPSHOT", "files": {"src/main.yux": "sha256hex"}}`。
 *
 * 清单键为相对项目根的正斜杠路径（绝对输入相对化、相对输入直接采用——与
 * [YuxSymbols] 的 relativePath 一致），按键排序保证确定性。清单解析走 SnakeYAML
 * （SafeConstructor，兼容 JSON 流式写法）；文件缺失 / 解析失败 / 结构非法一律视为过期。
 */
class YuxCache(
    private val projectDir: Path,
    val cacheDir: Path = BuildPaths.resolve(projectDir, BuildPaths.CACHE_DIR),
) {

    /** 全部源文件哈希与清单一致 → 增量命中。 */
    fun isUpToDate(sources: List<Path>, compilerVersion: String = COMPILER_VERSION): Boolean {
        val manifest = cacheDir.resolve(MANIFEST_FILE)
        if (!Files.isRegularFile(manifest)) return false
        val parsed = readManifest(manifest) ?: return false
        if (parsed.version != compilerVersion) return false
        val current = try {
            hash(sources)
        } catch (e: IOException) {
            return false
        }
        return parsed.files == current
    }

    /** 写入新清单（源文件 → SHA-256）；缓存目录不存在时自动创建。 */
    fun save(sources: List<Path>, compilerVersion: String = COMPILER_VERSION) {
        val hashes = hash(sources)
        Files.createDirectories(cacheDir)
        Files.writeString(cacheDir.resolve(MANIFEST_FILE), renderManifest(compilerVersion, hashes))
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
        val files = root["files"] as? Map<*, *> ?: return null
        val fileHashes = sortedMapOf<String, String>()
        for ((key, value) in files.entries) {
            if (key !is String || value !is String) return null
            fileHashes[key] = value
        }
        return ManifestData(version, fileHashes)
    }

    /** 源路径 → 清单键：绝对路径相对 [projectDir] 规约，相对路径直接采用。 */
    private fun relativeKey(path: Path): String {
        val rel = if (path.isAbsolute) projectDir.relativize(path) else path
        return rel.toString().replace('\\', '/')
    }

    /** 渲染单行确定性清单（冒号后空两格；文件项按键排序）。 */
    private fun renderManifest(version: String, files: Map<String, String>): String {
        val entries = files.entries.joinToString(", ") { "${quote(it.key)}: ${quote(it.value)}" }
        return "{\"version\": ${quote(version)}, \"files\": {$entries}}"
    }

    /** JSON 字符串转义（与 [YuxSymbols] 一致：仅处理 `\` 与 `"`）。 */
    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 解析后的清单模型。 */
    private data class ManifestData(val version: String, val files: Map<String, String>)

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
