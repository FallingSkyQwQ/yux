package yux.compiler.sema

import org.junit.jupiter.api.Test
import yux.compiler.diag.ErrorCodes
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M3 冒烟：hello 程序应无错误；负向样例应命中对应诊断码。
 */
class SemaSmokeTest {

    @Test
    fun `hello world has no errors`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                print "Hello Yux"
                name = "Steve"
                age = 18
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `basic declarations and inference`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                x:Int = 1
                y = "str"
                z:Long = 42
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `condition must be boolean`() {
        val r = SemaTestSupport.analyze("fun main() {\n  if 1 {\n  }\n}")
        assertTrue(r.hasCode(ErrorCodes.CONDITION_NOT_BOOLEAN), r.diagnostics.toString())
    }

    @Test
    fun `type mismatch on assignment`() {
        val r = SemaTestSupport.analyze("fun main() {\n  x:Int = \"str\"\n}")
        assertTrue(r.hasCode(ErrorCodes.TYPE_MISMATCH), r.diagnostics.toString())
    }

    @Test
    fun `data class constructor call`() {
        val r = SemaTestSupport.analyze(
            """
            data User {
                id:Int
                name:String
            }
            fun main() {
                u = User(1, "Steve")
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `null guard on nullable receiver read`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                name:String? = null
                x = name.length
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.NULL_GUARD_INSERTED), r.diagnostics.toString())
    }
}
