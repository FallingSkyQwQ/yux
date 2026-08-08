package yux.buildtool

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import yux.compiler.Compiler
import yux.compiler.ast.YxDecl
import yux.compiler.source.SourceFile
import yux.testsupport.GoldenFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M7-7：IDE 映射——`yux-symbols.json` 结构 / 排序 / golden 快照（05-§8）。
 */
class YuxSymbolsTest {

    private val config = BuildConfig(name = "demo", version = "1.0.0")
    private val golden = GoldenFile()

    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `符号映射包含各顶层声明种类且跳过 import`() {
        val source = projectDir.resolve("src/main.yux")
        Files.createDirectories(source.parent)
        source.writeText(
            """
            import java.util.UUID
            name:String = "x"
            fun main() { print 1 }
            User {}
            data Point { x:Int }
            """.trimIndent() + "\n",
        )
        val decls = parse(source.toString(), source.readText())

        val json = YuxSymbols.build(projectDir, config, mapOf(source.toString() to decls))

        assertTrue(json.contains("\"file\": \"src/main.yux\""), json)
        assertTrue(json.contains("\"class\": \"Main\""), json)
        assertTrue(json.contains("{ \"name\": \"name\", \"kind\": \"property\" }"), json)
        assertTrue(json.contains("{ \"name\": \"main\", \"kind\": \"function\" }"), json)
        assertTrue(json.contains("{ \"name\": \"User\", \"kind\": \"class\" }"), json)
        assertTrue(json.contains("{ \"name\": \"Point\", \"kind\": \"data\" }"), json)
        assertTrue(!json.contains("UUID"), "import 不应进入符号表")
    }

    @Test
    fun `golden 快照：稳定输入输出固定 JSON`() {
        val mainText = """
            import java.util.UUID
            name:String = "x"
            fun main() { print 1 }
            User {}
            data Point { x:Int }
            """.trimIndent() + "\n"
        val economyText = "fun deposit(amount:Double):Double = amount\n"
        val mainFile = projectDir.resolve("src/main.yux")
        val economyFile = projectDir.resolve("src/sub/Economy.yux")
        Files.createDirectories(economyFile.parent)
        mainFile.writeText(mainText)
        economyFile.writeText(economyText)

        val json = YuxSymbols.build(
            projectDir,
            config,
            mapOf(
                "src/main.yux" to parse("src/main.yux", mainText),
                "src/sub/Economy.yux" to parse("src/sub/Economy.yux", economyText),
            ),
        )

        golden.assertMatches("yux-symbols.json.txt", json)
    }

    @Test
    fun `多文件条目按相对路径排序`() {
        val json = YuxSymbols.build(
            projectDir,
            config,
            mapOf(
                "src/sub/Economy.yux" to parse("src/sub/Economy.yux", "fun g() {}\n"),
                "src/main.yux" to parse("src/main.yux", "fun f() {}\n"),
            ),
        )

        assertTrue(
            json.indexOf("\"file\": \"src/main.yux\"") < json.indexOf("\"file\": \"src/sub/Economy.yux\""),
            json,
        )
    }

    private fun parse(path: String, text: String): List<YxDecl> {
        val compiler = Compiler()
        val decls = compiler.parseToDecls(SourceFile(path, text))
        assertFalse(compiler.diagnostics.hasErrors, compiler.diagnostics.diagnostics.toString())
        return decls
    }
}
