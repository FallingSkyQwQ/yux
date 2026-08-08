package yux.compiler.ir

import org.junit.jupiter.api.Test
import yux.testsupport.GoldenFile
import yux.compiler.diag.DiagnosticSink
import yux.compiler.irgen.IRGen
import yux.compiler.optimizer.BasicOpt
import yux.compiler.parser.CstToAst
import yux.compiler.parser.Parser
import yux.compiler.sema.SemanticAnalyzer
import yux.compiler.source.SourceFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * T-M4-6 golden 快照：ir-samples 下各样例 → `.ir` IrModule dump（IRGen + 最小优化后）。
 * 样例必须无错误编译（正向回归）。
 */
class GoldenIrTest {

    @Test
    fun `golden ir snapshots`() {
        val golden = GoldenFile()
        val samplesDir = Path.of("src/test/resources/ir-samples")
        val files = Files.list(samplesDir).use { it.toList().sorted() }
        assertTrue(files.isNotEmpty(), "ir-samples 目录为空")
        for (file in files) {
            if (!file.toString().endsWith(".yux")) continue
            val source = SourceFile(file.toString(), Files.readString(file))
            val diags = DiagnosticSink()
            val program = Parser(source, diags).parse()
            val decls = CstToAst().convert(program)
            val analysis = SemanticAnalyzer().analyze(mapOf(file.toString() to decls), diags)
            assertTrue(
                !diags.hasErrors,
                "${file.fileName} 不应有错误: ${diags.diagnostics}",
            )
            val module = IRGen(analysis).generate(mapOf(file.toString() to decls))
            BasicOpt.optimize(module)
            val dump = IrPrinter.dump(module)
            golden.assertMatches("ir/${file.fileName}.ir", dump)
        }
    }
}
