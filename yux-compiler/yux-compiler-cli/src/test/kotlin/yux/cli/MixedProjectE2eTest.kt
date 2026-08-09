package yux.cli

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-M10-1/2/3：混合项目集成测试（05 全篇，05-§4 编译顺序 / 05-§9 余额系统）。
 *
 * 1. `yuxc run -p samples/mixed`：Yux 调用 Kotlin（Currency.format）+ Java（Logger.log），
 *    输出「Kotlin 格式化 + Java 日志 + Yux 输出」三方协作结果。
 * 2. `yuxc build -p samples/mixed`：jar 同时含三语言产物类。
 * 3. 反向互操作（Kotlin/Java → Yux）：临时项目 Yux 先行编译，Kotlin/Java 在 yux-classes
 *    之上编译并构造 Yux data 类 / 调用 Yux 顶层函数（05-§5.3/5.4）。
 */
class MixedProjectE2eTest {

    /** 捕获 stdout/stderr 并执行 [runCli]（与 ProjectCommandTest 相同模式）。 */
    private fun capture(vararg args: String): Triple<String, String, Int> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val prevOut = System.out
        val prevErr = System.err
        System.setOut(PrintStream(out, true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(err, true, StandardCharsets.UTF_8))
        try {
            val code = runCli(arrayOf(*args))
            return Triple(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), code)
        } finally {
            System.setOut(prevOut)
            System.setErr(prevErr)
        }
    }

    /** 定位仓库根下的 samples/mixed（测试工作目录是模块目录，向上查找）。 */
    private fun locateMixedProject(): Path {
        for (candidate in listOf(
            Path.of("samples/mixed"),
            Path.of("../samples/mixed"),
            Path.of("../../samples/mixed"),
        )) {
            val abs = candidate.toAbsolutePath().normalize()
            if (Files.isDirectory(abs)) return abs
        }
        throw AssertionError("samples/mixed 不存在（从 ${Path.of(".").toAbsolutePath()} 查找）")
    }

    /** Gradle 可用性探测（镜像 ProjectCommandTest）。 */
    private fun gradleAvailable(projectDir: Path): Boolean {
        val located = yux.buildtool.GradleRunner.locateGradle(projectDir)
        if (located != "gradle") {
            val resolved = Path.of(located)
            if (Files.exists(resolved)) return true
        }
        return try {
            val proc = ProcessBuilder("gradle", "--version").redirectErrorStream(true).start()
            proc.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /** 递归删除（不存在时静默）。 */
    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `mixed project run outputs kotlin format java log and yux output`() {
        val project = locateMixedProject()
        val (out, err, code) = capture("run", "-p", project.toString())
        assertEquals(0, code, "run 应成功，stderr: $err")
        // Java 日志（Logger.log）+ Kotlin 格式化（Currency.format）
        assertTrue(out.contains("[MyServer] Steve 存入 \$50.00"), out)
        assertTrue(out.contains("[MyServer] Alex 存入 \$100.00"), out)
        // Yux 输出 + Kotlin 格式化
        assertTrue(out.contains("=== Yux 余额系统"), out)
        assertTrue(out.contains("Steve 余额: \$75.50"), out)
        assertTrue(out.contains("Alex 余额: \$100.00"), out)
        assertTrue(out.contains("Kotlin 格式化样例: \$12,345.68"), out)
    }

    @Test
    fun `mixed project build produces jar with all three languages`() {
        val project = locateMixedProject()
        assumeTrue(gradleAvailable(project), "Gradle 不可用，跳过")
        val (_, err, code) = capture("build", "--clean", "-p", project.toString())
        assertEquals(0, code, "build 应成功，stderr: $err")
        val jar = project.resolve("build/libs/MixedServer-1.0.0.jar")
        assertTrue(Files.isRegularFile(jar), "jar 应存在: $jar")
        val entries = ZipFile(jar.toFile()).use { z ->
            z.entries().asSequence().map { it.name }.toSet()
        }
        for (required in listOf(
            "com/example/Logger.class",
            "com/example/Currency.class",
            "Account.class",
            "Economy.class",
            "Main.class",
        )) {
            assertTrue(entries.contains(required), "jar 应包含 $required（三方产物），实际: $entries")
        }
    }

    @Test
    fun `kotlin and java can reference yux types`() {
        val project = Files.createTempDirectory("yux-mixed-reverse")
        // Yux：纯 Yux 先编译（data + 顶层函数），不引用 Java/Kotlin
        Files.createDirectories(project.resolve("src"))
        Files.writeString(
            project.resolve("src/YuxCore.yux"),
            """
            data Account {
                player: String
                balance: Double
            }

            fun createAccount(player: String, balance: Double): Account {
                return Account(player, balance)
            }
            """.trimIndent(),
        )
        // Kotlin：构造 Yux data 类 + 调用 Yux 顶层函数（文件类静态方法）；顶层 main 为运行入口。
        // 注：Kotlin/Java 须与 Yux 同处默认包——Kotlin 具名包不可见默认包类（作用域规则）。
        Files.createDirectories(project.resolve("kotlin"))
        Files.writeString(
            project.resolve("kotlin/Main.kt"),
            """
            object KotlinBridge {
                @JvmStatic
                fun useYux(): String {
                    val data = Account("Steve", 50.0)
                    val created = YuxCore.createAccount("Alex", 100.0)
                    return "Kotlin->Yux: " + data.getPlayer() + "/" + data.getBalance() +
                        ", " + created.getPlayer() + "/" + created.getBalance() + " | " + JavaSide.useYux()
                }
            }

            fun main() {
                println(KotlinBridge.useYux())
            }
            """.trimIndent(),
        )
        // Java：构造 Yux data 类（Java → Yux）
        Files.createDirectories(project.resolve("java"))
        Files.writeString(
            project.resolve("java/JavaSide.java"),
            """
            public class JavaSide {
                public static String useYux() {
                    Account d = new Account("Bob", 7.5);
                    return "Java->Yux: " + d.getPlayer() + "/" + d.getBalance();
                }
            }
            """.trimIndent(),
        )
        Files.writeString(
            project.resolve("build.yml"),
            """
            name: ReverseDemo
            version: 1.0.0
            language:
              yux: 1.0
            target:
              jvm: 21
            source:
              main: src
              kotlin: kotlin
              java: java
            main: MainKt
            """.trimIndent(),
        )
        try {
            val (out, err, code) = capture("run", "-p", project.toString())
            assertEquals(0, code, "反向互操作 run 应成功，stderr: $err")
            assertTrue(out.contains("Kotlin->Yux: Steve/50.0, Alex/100.0"), out)
            assertTrue(out.contains("Java->Yux: Bob/7.5"), out)
        } finally {
            deleteRecursively(project)
        }
    }
}
