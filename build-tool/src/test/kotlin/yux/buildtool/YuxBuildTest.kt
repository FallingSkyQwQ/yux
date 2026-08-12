package yux.buildtool

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * YuxBuild 编排器集成测试（T-M7-3/5/7）：解析 → 计划 → 增量 → Yux 编译 →
 * 生成 Gradle → 托管打包全链路。嵌套 Gradle 较慢，仅覆盖本里程碑验收场景。
 */
class YuxBuildTest {

    @TempDir
    lateinit var projectDir: Path

    /** repo 根 gradlew 绝对路径（显式传入，绕过环境变量定位）。 */
    private val repoGradlew: String = findRepoGradlew()

    /** 从测试工作目录向上找仓库根 gradlew（与测试工作目录层级解耦）。 */
    private fun findRepoGradlew(): String {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("gradlew")
            if (Files.isRegularFile(candidate)) return candidate.toString()
            dir = dir.parent
        }
        throw IllegalStateException("未找到仓库根 gradlew")
    }

    /** 写入最小纯 Yux 项目：build.yml + src/main.yux。 */
    private fun writeProject(mainText: String) {
        projectDir.resolve("build.yml").writeText(
            """
            name: demo
            version: 1.0.0
            language:
              yux: "1.0"
            target:
              jvm: 21
            """.trimIndent() + "\n",
        )
        val source = projectDir.resolve("src/main.yux")
        Files.createDirectories(source.parent)
        source.writeText(mainText)
    }

    private fun yuxBuild(): YuxBuild = YuxBuild(projectDir, gradleBinary = repoGradlew)

    @Test
    fun `完整构建产出 jar 类文件符号映射与缓存清单`() {
        writeProject("fun main() { print \"Hello Yux build\" }\n")

        val result = yuxBuild().build(offline = true)

        assertTrue(result.success, result.message)
        val jar = projectDir.resolve("build/libs/demo-1.0.0.jar")
        assertTrue(Files.isRegularFile(jar), "jar 应存在")
        assertEquals(jar, result.artifactJar)
        assertTrue(Files.isRegularFile(projectDir.resolve("build/yux-classes/Main.class")))
        val symbols = projectDir.resolve("build/yux-symbols.json").readText()
        assertTrue(symbols.contains("\"class\": \"Main\""), symbols)
        assertTrue(Files.isRegularFile(projectDir.resolve("build/yux-cache/manifest.json")))
    }

    @Test
    fun `无改动重复构建为增量命中并跳过 Gradle`() {
        writeProject("fun main() { print \"Hello Yux build\" }\n")
        val build = yuxBuild()
        assertTrue(build.build(offline = true).success)

        val second = build.build(offline = true)

        assertTrue(second.success, second.message)
        assertFalse(second.compiled, "第二次构建应增量命中（compiled = false）")
        assertTrue(Files.isRegularFile(assertNotNull(second.artifactJar)))
    }

    @Test
    fun `修改源码后重新编译`() {
        writeProject("fun main() { print \"Hello Yux build\" }\n")
        val build = yuxBuild()
        assertTrue(build.build(offline = true).success)

        projectDir.resolve("src/main.yux").writeText("fun main() { print \"Hello again\" }\n")
        val result = build.build(offline = true)

        assertTrue(result.success, result.message)
        assertTrue(result.compiled, "源码变更后应重新编译")
    }

    @Test
    fun `修改构建配置后缓存失效并重新编译`() {
        writeProject("fun main() { print \"Hello Yux build\" }\n")
        val build = yuxBuild()
        assertTrue(build.build(offline = true).success)

        // 改 mainClass（jar 名不变，仅影响生成的 Gradle 脚本与 plugin.yml 内容）
        projectDir.resolve("build.yml").writeText(
            """
            name: demo
            version: 1.0.0
            language:
              yux: "1.0"
            target:
              jvm: 21
            main: com.example.Main
            """.trimIndent() + "\n",
        )
        val result = build.build(offline = true)

        assertTrue(result.success, result.message)
        assertTrue(result.compiled, "build.yml 变更后应重新编译（不再 upToDate）")
    }

    @Test
    fun `clean 构建强制重新编译并重建缓存`() {
        writeProject("fun main() { print \"Hello Yux build\" }\n")
        val build = yuxBuild()
        assertTrue(build.build(offline = true).success)

        val result = build.build(clean = true, offline = true)

        assertTrue(result.success, result.message)
        assertTrue(result.compiled, "clean 构建应重新编译")
        assertTrue(Files.isRegularFile(projectDir.resolve("build/yux-cache/manifest.json")))
    }

    /** 双文件项目：src/main.yux 调用 src/util.yux 的顶层函数（跨文件依赖）。 */
    private fun writeTwoFileProject(mainText: String, utilText: String) {
        writeProject(mainText)
        projectDir.resolve("src/util.yux").writeText(utilText)
    }

    private fun classBytes(name: String): ByteArray =
        Files.readAllBytes(projectDir.resolve("build/yux-classes/$name.class"))

    @Test
    fun `类级增量仅重编变更文件及其反向依赖`() {
        writeTwoFileProject(
            "fun main() { print greeting() }\n",
            "fun greeting():String {\n  return \"hi\"\n}\n",
        )
        val build = yuxBuild()
        assertTrue(build.build(offline = true).success)

        // 清单含文件粒度元数据：main.yux 依赖 util.yux，且记录了各自产出类
        val entries = YuxCache(projectDir).loadFileEntries()
        assertNotNull(entries, "应写入文件粒度缓存")
        assertTrue(entries!!["src/main.yux"]!!.deps.contains("src/util.yux"), "main 应依赖 util: ${entries["src/main.yux"]}")
        assertTrue(entries["src/util.yux"]!!.classes.contains("Util"), "util.yux 应产出 Util 类: ${entries["src/util.yux"]}")
        assertTrue(entries["src/main.yux"]!!.classes.contains("Main"), "main.yux 应产出 Main 类")

        val utilBytesV1 = classBytes("Util")
        val mainBytesV1 = classBytes("Main")

        // 改 util.yux 的 greeting 签名（String → Int）：调用点描述符变化 → 反向依赖 main 的
        // 字节码必然重编（若只重编 util，main 的 invokestatic 描述符过期 → 运行期 NoSuchMethodError）
        writeTwoFileProject(
            "fun main() { print greeting() }\n",
            "fun greeting():Int {\n  return 42\n}\n",
        )
        assertTrue(build.build(offline = true).success)
        assertTrue(!utilBytesV1.contentEquals(classBytes("Util")), "util.yux 变更后 Util 应重编")
        assertTrue(!mainBytesV1.contentEquals(classBytes("Main")), "util.yux 变更后反向依赖 main 应重编")

        val utilBytesV2 = classBytes("Util")
        val mainBytesV2 = classBytes("Main")

        // 只改 main.yux：util 无变化且非反向依赖 → Util 字节码应复用缓存（逐字节不变）
        writeTwoFileProject(
            "fun main() { print (greeting() + 1) }\n",
            "fun greeting():Int {\n  return 42\n}\n",
        )
        assertTrue(build.build(offline = true).success)
        assertTrue(!mainBytesV2.contentEquals(classBytes("Main")), "main.yux 变更后 Main 应重编")
        assertTrue(
            utilBytesV2.contentEquals(classBytes("Util")),
            "main.yux 变更不影响 util → Util 应复用缓存字节码（类级增量）",
        )
    }

    @Test
    fun `类级增量产物可运行`() {
        writeTwoFileProject(
            "fun main() { print greeting() }\n",
            "fun greeting():String {\n  return \"incremental\"\n}\n",
        )
        val build = yuxBuild()
        assertTrue(build.build(offline = true).success)
        writeTwoFileProject(
            "fun main() { print greeting() }\n",
            "fun greeting():String {\n  return \"incremental-v2\"\n}\n",
        )
        assertTrue(build.build(offline = true).success)
        // 增量后进程内编译（compile(clean=false) 走缓存命中 → 空产物），从磁盘类目录加载合并结果运行
        val result = build.compile(clean = false)
        assertTrue(result.success, result.diagnostics.diagnostics.toString())
        val classes = result.classes
        assertTrue(classes.containsKey("Util"), "增量 compile 应提供 Util 类")
        assertTrue(classes.containsKey("Main"), "增量 compile 应提供 Main 类")
        val out = runMain(classes)
        assertTrue(out.contains("incremental-v2"), "增量构建产物应包含最新 util 逻辑，实际输出: $out")
    }

    /** 反射调用编译产物 main() 捕获 stdout（父加载器含 yux-stdlib 运行时）。 */
    private fun runMain(classes: Map<String, ByteArray>): String {
        val loader =
            object : ClassLoader(yux.compiler.Compiler::class.java.classLoader) {
                override fun findClass(name: String): Class<*> {
                    val bytes = classes[name] ?: throw ClassNotFoundException(name)
                    return defineClass(name, bytes, 0, bytes.size)
                }
            }
        val cls = Class.forName("Main", true, loader)
        val prev = System.out
        val out = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8))
        try {
            cls.getMethod("main").invoke(null)
        } finally {
            System.setOut(prev)
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8)
    }

    @Test
    fun `compile 返回进程内类字节与主类名`() {
        writeProject("fun main() { print \"Hello Yux build\" }\n")

        val compiled = yuxBuild().compile()

        assertTrue(compiled.success)
        assertEquals("Main", compiled.mainClassName)
        assertTrue(compiled.classes.containsKey("Main"))
        assertTrue(assertNotNull(compiled.classes["Main"]).isNotEmpty())
    }
}
