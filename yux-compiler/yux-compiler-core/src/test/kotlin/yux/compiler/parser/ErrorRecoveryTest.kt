package yux.compiler.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M2-8 错误恢复（02-§12.3）：缺括号/缺 `}` 时同步恢复并继续收集后续错误，
 * 坏输入不抛异常。
 */
class ErrorRecoveryTest {

    @Test
    fun `missing rparen recovers and reports later errors`() {
        val r = ParseTestSupport.parse("fun main() {\n  x = f(1\n  y = 2\n}")
        assertTrue(r.hasErrors, "expected errors: ${r.errors}")
        assertTrue(r.decls.isNotEmpty(), "expected partial AST to survive")
    }

    @Test
    fun `missing brace recovers to end of file`() {
        val r = ParseTestSupport.parse("fun main() {\n  print 1\n")
        assertTrue(r.hasErrors)
        assertTrue(r.decls.isNotEmpty())
    }

    @Test
    fun `unexpected token in class body reports error`() {
        val r = ParseTestSupport.parse("Player {\n  ???\n  health:Int\n}")
        assertTrue(r.hasErrors)
        // health 属性仍被解析
        val dump = AstPrinter.dump(r.decls)
        assertTrue(dump.contains("property health"), dump)
    }

    @Test
    fun `unexpected character never throws`() {
        val r = ParseTestSupport.parse("fun main() {\n  x = @ # $\n  y = 1\n}")
        assertTrue(r.hasErrors)
        assertTrue(r.decls.isNotEmpty())
    }

    @Test
    fun `missing type in parameter reports error`() {
        val r = ParseTestSupport.parse("fun f(a) {\n}")
        assertTrue(r.hasErrors)
    }

    @Test
    fun `stray else reports error`() {
        val r = ParseTestSupport.parse("fun main() {\n  x = 1\n  else y = 2\n}")
        assertTrue(r.hasErrors)
    }

    @Test
    fun `empty input parses to empty program`() {
        val r = ParseTestSupport.parse("")
        assertFalse(r.hasErrors)
        assertEquals(0, r.decls.size)
    }

    @Test
    fun `error diagnostics carry positions`() {
        val sink = yux.compiler.diag.DiagnosticSink()
        val parser = Parser(yux.compiler.source.SourceFile("t.yux", "fun main() {\n  x = f(1\n}"), sink)
        parser.parse()
        val errs = sink.diagnostics.filter { it.severity == yux.compiler.diag.Severity.ERROR }
        assertTrue(errs.isNotEmpty(), "expected errors")
        assertTrue(errs.all { it.position != null && it.position.line >= 1 }, errs.toString())
    }
}
