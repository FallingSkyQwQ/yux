package yux.compiler.sema

import org.junit.jupiter.api.Test
import yux.testsupport.GoldenFile
import yux.compiler.parser.CstToAst
import yux.compiler.parser.Parser
import yux.compiler.diag.DiagnosticSink
import yux.compiler.source.SourceFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * T-M3 golden 快照：sema-samples 下各样例 → `.sema` 类型推导 dump。
 * 样例必须无错误编译（正向回归）。
 */
class GoldenSemaTest {

    @Test
    fun `golden type inference snapshots`() {
        val golden = GoldenFile()
        val samplesDir = Path.of("src/test/resources/sema-samples")
        val files = Files.list(samplesDir).use { it.toList().sorted() }
        assertTrue(files.isNotEmpty(), "sema-samples 目录为空")
        for (file in files) {
            if (!file.toString().endsWith(".yux")) continue
            val source = SourceFile(file.toString(), Files.readString(file))
            val diags = DiagnosticSink()
            val program = Parser(source, diags).parse()
            val decls = CstToAst().convert(program)
            val result = SemanticAnalyzer().analyze(mapOf(file.toString() to decls), diags)
            assertTrue(
                !diags.hasErrors,
                "${file.fileName} 不应有错误: ${diags.diagnostics}",
            )
            val dump = AnalysisPrinter.dumpTypes(result.exprTypes)
            golden.assertMatches("sema/${file.fileName}.sema", dump)
        }
    }
}
