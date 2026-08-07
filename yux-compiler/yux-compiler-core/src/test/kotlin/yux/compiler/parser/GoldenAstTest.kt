package yux.compiler.parser

import org.junit.jupiter.api.Test
import yux.compiler.diag.Severity
import yux.compiler.source.SourceFile
import yux.testsupport.GoldenFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * T-M2-10 golden 快照（.ast）：01 语法示例 100% 可解析，AST dump 与快照一致。
 */
class GoldenAstTest {

    @Test
    fun `parser samples match golden ast`() {
        val samplesDir = Path.of("src/test/resources/parser-samples")
        assertTrue(Files.isDirectory(samplesDir), "parser-samples dir not found: $samplesDir")

        val golden = GoldenFile(GoldenFile.DEFAULT_ROOT.resolve("ast"))
        val sampleFiles = Files.list(samplesDir).use { stream ->
            stream.filter { it.toString().endsWith(".yux") }.sorted().toList()
        }
        assertTrue(sampleFiles.isNotEmpty(), "no parser sample files found")

        for (file in sampleFiles) {
            val source = SourceFile(file.fileName.toString(), file.readText())
            val diagnostics = yux.compiler.diag.DiagnosticSink()
            val parser = Parser(source, diagnostics)
            val program = parser.parse()
            val decls = CstToAst().convert(program)
            val dump = AstPrinter.dump(decls)
            assertTrue(
                diagnostics.diagnostics.none { it.severity == Severity.ERROR },
                "parse errors in ${file.fileName}: ${diagnostics.diagnostics}",
            )
            golden.assertMatches("${file.fileName}.ast", dump)
        }
    }
}
