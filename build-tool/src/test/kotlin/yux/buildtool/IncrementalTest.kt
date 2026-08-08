package yux.buildtool

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 文件级增量缓存测试（T-M7-6，02-§12.3）：清单写入 / 内容变更 / 版本不匹配 /
 * 清理 / 键相对化。
 */
class IncrementalTest {

    @TempDir
    lateinit var projectDir: Path

    private fun cache(): YuxCache = YuxCache(projectDir)

    @Test
    fun `保存后增量命中，修改文件内容后失效`() {
        val source = projectDir.resolve("src/main.yux")
        Files.createDirectories(source.parent)
        source.writeText("fun main() {}\n")
        val yuxCache = cache()
        yuxCache.save(listOf(source))

        assertTrue(yuxCache.isUpToDate(listOf(source)))

        source.writeText("fun main() { print 1 }\n")

        assertFalse(yuxCache.isUpToDate(listOf(source)))
    }

    @Test
    fun `编译器版本不匹配时增量失效`() {
        val source = projectDir.resolve("src/main.yux")
        Files.createDirectories(source.parent)
        source.writeText("fun main() {}\n")
        val yuxCache = cache()
        yuxCache.save(listOf(source))

        assertFalse(yuxCache.isUpToDate(listOf(source), compilerVersion = "9.9.9"))
    }

    @Test
    fun `clear 后增量失效且缓存目录被删除`() {
        val source = projectDir.resolve("src/main.yux")
        Files.createDirectories(source.parent)
        source.writeText("fun main() {}\n")
        val yuxCache = cache()
        yuxCache.save(listOf(source))

        yuxCache.clear()

        assertFalse(yuxCache.isUpToDate(listOf(source)))
        assertFalse(Files.exists(projectDir.resolve("build/yux-cache")))
    }

    @Test
    fun `绝对路径保存时清单键为相对路径`() {
        val source = projectDir.resolve("src/main.yux")
        Files.createDirectories(source.parent)
        source.writeText("fun main() {}\n")
        cache().save(listOf(source.toAbsolutePath()))

        val manifest = projectDir.resolve("build/yux-cache/manifest.json").readText()
        assertTrue(manifest.contains("\"src/main.yux\""), manifest)
        assertFalse(manifest.contains(projectDir.toString()), manifest)
    }
}
