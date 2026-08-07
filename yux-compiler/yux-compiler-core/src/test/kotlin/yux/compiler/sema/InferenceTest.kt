package yux.compiler.sema

import org.junit.jupiter.api.Test
import yux.compiler.diag.ErrorCodes
import yux.compiler.sema.SemaType.Basic
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M3-5 局部双向推断（ADR-08 / 01-§4.5）：变量、函数返回、字面量收窄。
 */
class InferenceTest {

    @Test
    fun `var inference from initializer`() {
        val r = SemaTestSupport.analyze("fun main() {\n  name = \"Steve\"\n  age = 18\n}")
        assertFalse(r.hasErrors, r.errors.toString())
        val types = r.analysis.exprTypes
        val varTypes = r.analysis.symbolTable.files.single().topLevelFunctions
        assertTrue(types.values.any { it is Basic && it.name == "String" })
        assertTrue(types.values.any { it is Basic && it.name == "Int" })
    }

    @Test
    fun `int literal narrows to long`() {
        val r = SemaTestSupport.analyze("fun main() {\n  x:Long = 42\n  y:Double = 42\n}")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `long literal cannot narrow to int`() {
        val r = SemaTestSupport.analyze("fun main() {\n  x:Int = 42L\n}")
        assertTrue(r.hasCode(ErrorCodes.TYPE_MISMATCH), r.diagnostics.toString())
    }

    @Test
    fun `float literal narrows to float`() {
        val r = SemaTestSupport.analyze("fun main() {\n  x:Float = 3.14\n}")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `int literal to float and double`() {
        val r = SemaTestSupport.analyze("fun main() {\n  x:Float = 3\n  y:Double = 3\n}")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `function return inference from expr body`() {
        val r = SemaTestSupport.analyze(
            """
            fun square(n:Int) = n * n
            fun main() {
                x = square(4)
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `function return inference from block`() {
        val r = SemaTestSupport.analyze(
            """
            fun max(a:Int, b:Int) {
                if a > b {
                    return a
                }
                return b
            }
            fun main() {
                m = max(1, 2)
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `inferred function used as call arg`() {
        val r = SemaTestSupport.analyze(
            """
            fun twice(x:Int) = x * 2
            fun consume(x:Int) {
            }
            fun main() {
                consume(twice(3))
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `recursive function with inferred return`() {
        val r = SemaTestSupport.analyze(
            """
            fun fib(n:Int) {
                if n < 2 {
                    return n
                }
                return fib(n - 1) + fib(n - 2)
            }
            fun main() {
                print fib(5)
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `null literal without expected type fails`() {
        val r = SemaTestSupport.analyze("fun main() {\n  x = null\n}")
        assertTrue(r.hasCode(ErrorCodes.INFERENCE_FAILURE), r.diagnostics.toString())
    }

    @Test
    fun `null literal with nullable expected ok`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                name:String?
                name = null
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `lambda without expected type fails`() {
        val r = SemaTestSupport.analyze("fun main() {\n  f = (x -> x + 1)\n}")
        assertTrue(r.hasCode(ErrorCodes.INFERENCE_FAILURE), r.diagnostics.toString())
    }

    @Test
    fun `lambda with expected type ok`() {
        val r = SemaTestSupport.analyze(
            """
            fun transform(f:(Int) -> Int, x:Int) = f(x)
            fun main() {
                y = transform({ it + 1 }, 5)
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `conflicting return types fail`() {
        val r = SemaTestSupport.analyze(
            """
            fun foo(flag:Boolean) {
                if flag {
                    return 1
                }
                return "str"
            }
            """.trimIndent(),
        )
        assertTrue(r.hasErrors, r.diagnostics.toString())
    }

    @Test
    fun `top level property inference`() {
        val r = SemaTestSupport.analyze("maxPlayers = 100")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `null body function inference fails without crash`() {
        val r = SemaTestSupport.analyze("fun f() = null")
        assertTrue(r.hasCode(ErrorCodes.INFERENCE_FAILURE), r.diagnostics.toString())
    }

    @Test
    fun `null return statement inference fails without crash`() {
        val r = SemaTestSupport.analyze("fun f() {\n  return null\n}")
        assertTrue(r.hasCode(ErrorCodes.INFERENCE_FAILURE), r.diagnostics.toString())
    }

    @Test
    fun `nothing function with null rejected`() {
        val r = SemaTestSupport.analyze("fun f():Nothing = null")
        assertTrue(r.hasCode(ErrorCodes.TYPE_MISMATCH), r.diagnostics.toString())
    }

    @Test
    fun `nullable arithmetic rejected`() {
        val r = SemaTestSupport.analyze("fun main() {\n  a:Int? = 1\n  b:Int? = 2\n  c = a + b\n}")
        assertTrue(r.hasCode(ErrorCodes.INVALID_OPERATOR_OPERAND), r.diagnostics.toString())
    }

    @Test
    fun `byte overflow literal rejected`() {
        val r = SemaTestSupport.analyze("fun main() {\n  x:Byte = 200\n}")
        assertTrue(r.hasCode(ErrorCodes.TYPE_MISMATCH), r.diagnostics.toString())
    }
}
