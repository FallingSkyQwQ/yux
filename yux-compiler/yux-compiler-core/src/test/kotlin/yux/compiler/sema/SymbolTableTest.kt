package yux.compiler.sema

import org.junit.jupiter.api.Test
import yux.compiler.diag.ErrorCodes
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-M3-2 符号表：作用域层级、导入解析、跨文件、冲突检测（02-§6）。
 */
class SymbolTableTest {

    @Test
    fun `declarations registered per file`() {
        val r = SemaTestSupport.analyze(
            """
            data User {
                id:Int
            }
            service Database {
                connect() {
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
        val file = r.analysis.symbolTable.files.single()
        assertNotNull(file.types["User"])
        assertNotNull(file.types["Database"])
    }

    @Test
    fun `duplicate type declaration in same file`() {
        val r = SemaTestSupport.analyze("Player {}\nPlayer {}")
        assertTrue(r.hasCode(ErrorCodes.DUPLICATE_DECLARATION), r.diagnostics.toString())
    }

    @Test
    fun `type and function name conflict`() {
        val r = SemaTestSupport.analyze("Player {}\nfun Player() {\n}")
        assertTrue(r.hasCode(ErrorCodes.DUPLICATE_DECLARATION), r.diagnostics.toString())
    }

    @Test
    fun `duplicate class member property`() {
        val r = SemaTestSupport.analyze("Player {\n  name:String\n  name:Int\n}")
        assertTrue(r.hasCode(ErrorCodes.DUPLICATE_DECLARATION), r.diagnostics.toString())
    }

    @Test
    fun `same signature function overload rejected`() {
        val r = SemaTestSupport.analyze(
            """
            fun f(a:Int) {
            }
            fun f(b:Int) {
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.DUPLICATE_DECLARATION), r.diagnostics.toString())
    }

    @Test
    fun `imported simple name usable as type`() {
        val r = SemaTestSupport.analyze(
            """
            import java.util.UUID
            fun main() {
                u:UUID? = UUID.randomUUID()
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `star import usable as type`() {
        val r = SemaTestSupport.analyze(
            """
            import java.util.*
            fun pick(l:List String) = l
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `same package cross file resolution`() {
        val r = SemaTestSupport.analyzeAll(
            "a.yux" to "package pkg\nWidget {\n}\n",
            "b.yux" to "package pkg\nfun main() {\n  w = Widget()\n}\n",
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `cross file function call same package`() {
        val r = SemaTestSupport.analyzeAll(
            "a.yux" to "package pkg\nfun helper(x:Int) = x + 1\n",
            "b.yux" to "package pkg\nfun main() {\n  y = helper(1)\n}\n",
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `java type via builtin classpath`() {
        val r = SemaTestSupport.analyze("fun main() {\n  m = Math.max(1, 2)\n}")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `unresolved type reports error`() {
        val r = SemaTestSupport.analyze("fun main() {\n  x:NoSuchType = 1\n}")
        assertTrue(r.hasCode(ErrorCodes.UNRESOLVED_TYPE), r.diagnostics.toString())
    }

    @Test
    fun `unresolved name reports error`() {
        val r = SemaTestSupport.analyze("fun main() {\n  y = unknownName\n}")
        assertTrue(r.hasCode(ErrorCodes.UNRESOLVED_REFERENCE), r.diagnostics.toString())
    }

    @Test
    fun `variable shadowing in nested block`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                x = 1
                if true {
                    x = 2
                    y = x
                }
                z = x
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `class member resolution`() {
        val r = SemaTestSupport.analyze(
            """
            Player {
                name:String
                fun describe() = name
            }
            fun main() {
                p = Player("Steve")
                n = p.describe()
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }
}
