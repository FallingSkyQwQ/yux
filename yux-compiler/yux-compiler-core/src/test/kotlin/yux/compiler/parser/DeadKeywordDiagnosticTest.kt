package yux.compiler.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 死关键字诊断（S-8.6 / 01-§3 保留字）：`new`/`native`/`stack` 给出明确语义诊断
 * 而非泛化 "unexpected token"（顶级、类体、语句位置均覆盖）。
 */
class DeadKeywordDiagnosticTest {

    @Test
    fun `new at top level reports dedicated diagnostic`() {
        val r = ParseTestSupport.parse("new Player()\n")
        assertTrue(r.hasErrors)
        assertTrue(
            r.errors.any { it.contains("`new` 关键字暂不支持（v0.1）") },
            r.errors.toString(),
        )
    }

    @Test
    fun `new in statement reports dedicated diagnostic`() {
        val r = ParseTestSupport.parse("fun main() {\n  x = new Player()\n}\n")
        assertTrue(r.hasErrors)
        assertTrue(
            r.errors.any { it.contains("`new` 关键字暂不支持（v0.1）") },
            r.errors.toString(),
        )
    }

    @Test
    fun `stack decl reports dedicated diagnostic`() {
        val r = ParseTestSupport.parse("fun main() {\n  stack hot = Player()\n}\n")
        assertTrue(r.hasErrors)
        assertTrue(
            r.errors.any { it.contains("`stack` 栈分配暂不支持（v0.1）") },
            r.errors.toString(),
        )
    }

    @Test
    fun `native fun decl reports dedicated diagnostic`() {
        val r = ParseTestSupport.parse("native fun syscall(n:Int):Long\n")
        assertTrue(r.hasErrors)
        assertTrue(
            r.errors.any { it.contains("`native fun` 暂不支持（v0.1）") },
            r.errors.toString(),
        )
    }

    @Test
    fun `new in class body reports dedicated diagnostic`() {
        val r = ParseTestSupport.parse("class Player {\n  new init()\n}\n")
        assertTrue(r.hasErrors)
        assertTrue(
            r.errors.any { it.contains("`new` 关键字暂不支持（v0.1）") },
            r.errors.toString(),
        )
    }

    @Test
    fun `diagnostics do not contain generic unexpected token for dead keywords`() {
        val r = ParseTestSupport.parse("stack hot = Player()\n")
        assertTrue(r.hasErrors)
        assertTrue(r.errors.none { it.contains("unexpected token") }, r.errors.toString())
    }
}
