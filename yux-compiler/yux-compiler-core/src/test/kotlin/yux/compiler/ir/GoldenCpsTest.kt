package yux.compiler.ir

import org.junit.jupiter.api.Test
import yux.compiler.diag.DiagnosticSink
import yux.compiler.irgen.AsyncCpsLowering
import yux.compiler.irgen.IRGen
import yux.compiler.optimizer.BasicOpt
import yux.compiler.optimizer.SsaOpt
import yux.compiler.parser.CstToAst
import yux.compiler.parser.Parser
import yux.compiler.sema.SemanticAnalyzer
import yux.compiler.source.SourceFile
import yux.testsupport.GoldenFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * T-M14 golden 快照：cps-samples 下各样例 → `.cps` IrModule dump
 * （IRGen + AsyncCpsLowering 降级 + 完整 SSA 优化 + 最小优化后）。
 * 覆盖 async fun 的同步门面 / 挂起入口 / 状态机类结构（状态划分、await 挂起点、try/finally 路由）。
 */
class GoldenCpsTest {

    @Test
    fun `golden cps snapshots`() {
        val golden = GoldenFile()
        val samplesDir = Path.of("src/test/resources/cps-samples")
        val files = Files.list(samplesDir).use { it.toList().sorted() }
        assertTrue(files.isNotEmpty(), "cps-samples 目录为空")
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
            AsyncCpsLowering(module).transform()
            SsaOpt.optimize(module)
            BasicOpt.optimize(module)
            val dump = IrPrinter.dump(module)
            golden.assertMatches("cps/${file.fileName}.cps", dump)
        }
    }
}
