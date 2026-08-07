package yux.compiler.sema

import org.junit.jupiter.api.Test
import yux.compiler.diag.ErrorCodes
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M3-7 空安全守卫（01-S-8.1 / ADR-10）：读路径自动守卫 + R0001；
 * 写路径不守卫（E0023）；Java 类型视为可空。
 */
class NullGuardTest {

    @Test
    fun `nullable property read emits remind`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                name:String? = null
                x = name.length
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.NULL_GUARD_INSERTED), r.diagnostics.toString())
        assertTrue(r.reminds.isNotEmpty())
    }

    @Test
    fun `nullable member read emits remind`() {
        val r = SemaTestSupport.analyze(
            """
            Player {
                name:String = ""
            }
            fun main() {
                p:Player? = null
                n = p.name
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.NULL_GUARD_INSERTED), r.diagnostics.toString())
    }

    @Test
    fun `non-null read no remind`() {
        val r = SemaTestSupport.analyze("fun main() {\n  s = \"abc\"\n  n = s.length\n}")
        assertFalse(r.hasCode(ErrorCodes.NULL_GUARD_INSERTED), r.diagnostics.toString())
    }

    @Test
    fun `write on nullable receiver error`() {
        val r = SemaTestSupport.analyze(
            """
            Player {
                name:String = ""
            }
            fun main() {
                p:Player? = null
                p.name = "Alex"
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.NULLABLE_WRITE_NEEDS_CHECK), r.diagnostics.toString())
    }

    @Test
    fun `smart cast suppresses guard after null check`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                s:String?
                if s != null {
                    n = s.length
                }
            }
            """.trimIndent(),
        )
        // 分支内已转型为非空，不产生守卫
        assertFalse(r.hasCode(ErrorCodes.NULL_GUARD_INSERTED), r.diagnostics.toString())
    }

    @Test
    fun `java property treated nullable`() {
        val r = SemaTestSupport.analyze(
            """
            import java.util.Date
            fun main() {
                d = Date()
                t = d.time
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `guard points recorded`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                name:String? = null
                x = name.length
            }
            """.trimIndent(),
        )
        assertTrue(r.analysis.guardPoints.isNotEmpty())
    }

    @Test
    fun `nullable string length via guard`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                s:String? = "hi"
                n = s.length
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.NULL_GUARD_INSERTED), r.diagnostics.toString())
    }

    @Test
    fun `nullable receiver value-returning method call guarded`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                s:String? = "abc"
                n = s.length()
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.NULL_GUARD_INSERTED), r.diagnostics.toString())
    }

    @Test
    fun `unit returning method call not guarded`() {
        val r = SemaTestSupport.analyze(
            """
            StringBuilder {
            }
            fun main() {
                sb:StringBuilder? = null
                sb.append("x")
            }
            """.trimIndent(),
        )
        assertFalse(r.hasCode(ErrorCodes.NULL_GUARD_INSERTED), r.diagnostics.toString())
    }
}
