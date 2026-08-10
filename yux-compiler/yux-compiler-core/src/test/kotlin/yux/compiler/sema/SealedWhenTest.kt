package yux.compiler.sema

import org.junit.jupiter.api.Test
import yux.compiler.diag.ErrorCodes
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M12：when 表达式（产生值）+ 密封类型（sealed）+ 穷尽性检查。
 *
 * - `x = when v { 1 -> "a" else -> "b" }` 可作表达式（分支体为单个表达式）；
 * - 非密封 subject 的 when 表达式无 else → E0034；
 * - 密封类不可直接实例化 → E0035；密封类的直接子类须同文件 → E0036；
 * - 密封 subject 的 when 覆盖全部直接子类（is 分支）→ else 可省；缺分支 → E0034。
 */
class SealedWhenTest {

    // ── when 表达式 ─────────────────────────────────────────────────────────

    @Test
    fun `when expression as var initializer`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                n = 1
                x = when n { 1 -> "one" else -> "other" }
                y = when n { 1 -> "one" else -> "other" }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `when expression as return value`() {
        val r = SemaTestSupport.analyze(
            """
            fun pick(n: Int): String = when n {
                1 -> "one"
                else -> "other"
            }
            fun main() {
                s = pick(1)
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `when expression missing else on non-sealed subject is error`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                n = 1
                x = when n { 1 -> "one" }
            }
            """.trimIndent(),
        )
        assertTrue(r.hasErrors, "非密封 when 表达式缺 else 应报错")
        assertTrue(r.hasCode(ErrorCodes.WHEN_NOT_EXHAUSTIVE), "应报 E0034: ${r.errorCodes}")
    }

    @Test
    fun `when expression branch bodies unify to common type`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                n = 1
                b = true
                x = when b { true -> 1 else -> 2 }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `when expression as function argument`() {
        val r = SemaTestSupport.analyze(
            """
            fun id(x: String): String = x
            fun main() {
                n = 1
                s = id(when n { 1 -> "a" else -> "b" })
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `when expression subject type mismatch is error`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                s = "hi"
                x = when s { 1 -> "a" else -> "b" }
            }
            """.trimIndent(),
        )
        assertTrue(r.hasErrors, "when 分支条件与 subject 类型不匹配应报错")
        assertTrue(r.hasCode(ErrorCodes.TYPE_MISMATCH), "应报 E0004: ${r.errorCodes}")
    }

    // ── 密封类型声明校验 ─────────────────────────────────────────────────────

    @Test
    fun `plain subclass of sealed in same file is allowed`() {
        val r = SemaTestSupport.analyze(
            """
            sealed Shape {}
            Circle extends Shape {}
            Rect extends Shape {}
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `sealed subclass in different file is error`() {
        val r = SemaTestSupport.analyzeAll(
            "shape.yux" to "sealed Shape {}",
            "sub.yux" to "Circle extends Shape {}",
        )
        assertTrue(r.hasErrors, "密封类的直接子类必须同文件")
        assertTrue(r.hasCode(ErrorCodes.SEALED_SUBCLASS_DIFFERENT_FILE), "应报 E0036: ${r.errorCodes}")
    }

    @Test
    fun `sealed class cannot be instantiated`() {
        val r = SemaTestSupport.analyze(
            """
            sealed Shape {}
            Circle extends Shape {}
            fun main() {
                s = Shape()
            }
            """.trimIndent(),
        )
        assertTrue(r.hasErrors, "密封类不可直接实例化")
        assertTrue(r.hasCode(ErrorCodes.SEALED_INSTANTIATION), "应报 E0035: ${r.errorCodes}")
    }

    @Test
    fun `plain subclass of sealed can be instantiated`() {
        val r = SemaTestSupport.analyze(
            """
            sealed Shape {}
            Circle extends Shape {}
            fun main() {
                c = Circle()
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 密封穷尽性 ───────────────────────────────────────────────────────────

    @Test
    fun `sealed when expression exhaustive without else`() {
        val r = SemaTestSupport.analyze(
            """
            sealed Shape {}
            Circle extends Shape {}
            Rect extends Shape {}
            fun area(s: Shape): Int = when s {
                is Circle -> 1
                is Rect -> 2
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `sealed when expression missing branch is error`() {
        val r = SemaTestSupport.analyze(
            """
            sealed Shape {}
            Circle extends Shape {}
            Rect extends Shape {}
            fun area(s: Shape): Int = when s {
                is Circle -> 1
            }
            """.trimIndent(),
        )
        assertTrue(r.hasErrors, "密封 when 未覆盖全部直接子类应报错")
        assertTrue(r.hasCode(ErrorCodes.WHEN_NOT_EXHAUSTIVE), "应报 E0034: ${r.errorCodes}")
    }

    @Test
    fun `sealed when statement exhaustive without else`() {
        val r = SemaTestSupport.analyze(
            """
            sealed Shape {}
            Circle extends Shape {}
            Rect extends Shape {}
            fun area(s: Shape) {
                when s {
                    is Circle -> print 1
                    is Rect -> print 2
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `sealed when statement missing branch is error`() {
        val r = SemaTestSupport.analyze(
            """
            sealed Shape {}
            Circle extends Shape {}
            Rect extends Shape {}
            fun area(s: Shape) {
                when s {
                    is Circle -> print 1
                }
            }
            """.trimIndent(),
        )
        assertTrue(r.hasErrors, "密封 when 语句未覆盖全部直接子类应报错")
        assertTrue(r.hasCode(ErrorCodes.WHEN_NOT_EXHAUSTIVE), "应报 E0034: ${r.errorCodes}")
    }

    @Test
    fun `nullable sealed subject requires else`() {
        val r = SemaTestSupport.analyze(
            """
            sealed Shape {}
            Circle extends Shape {}
            Rect extends Shape {}
            fun area(s: Shape?) = when s {
                is Circle -> 1
                is Rect -> 2
            }
            """.trimIndent(),
        )
        assertTrue(r.hasErrors, "可空密封 subject 无 else 无法穷尽")
        assertTrue(r.hasCode(ErrorCodes.WHEN_NOT_EXHAUSTIVE), "应报 E0034: ${r.errorCodes}")
    }

    @Test
    fun `sealed when with else always fine`() {
        val r = SemaTestSupport.analyze(
            """
            sealed Shape {}
            Circle extends Shape {}
            fun area(s: Shape): Int = when s {
                is Circle -> 1
                else -> 2
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `non-sealed when statement without else still allowed`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                n = 1
                when n {
                    1 -> print "one"
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `is sealed base branch counts as exhaustive`() {
        val r = SemaTestSupport.analyze(
            """
            sealed Shape {}
            Circle extends Shape {}
            fun area(s: Shape): Int = when s {
                is Shape -> 1
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── `sealed` 保持普通标识符语义 ───────────────────────────────────────────

    @Test
    fun `sealed remains usable as identifier`() {
        val r = SemaTestSupport.analyze(
            """
            sealed = 5
            fun main() {
                sealed = 10
                print sealed
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `sealed typed property remains a property`() {
        val r = SemaTestSupport.analyze(
            """
            sealed: Int = 5
            fun main() {
                print sealed
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `sealed data class is sealed`() {
        val r = SemaTestSupport.analyze(
            """
            sealed data Shape {}
            data Circle extends Shape {}
            fun area(s: Shape): Int = when s {
                is Circle -> 1
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }
}
