package yux.backend.jvm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * T-M12 端到端：when 表达式 + 密封类型（sealed）运行时。
 *
 * - `when` 表达式：subject 求值一次 → 临时局部 + 分支链 → 结果落同一临时（镜像 if 表达式），
 *   `x = when n { 1 -> "one" else -> "other" }` 编译并运行；
 * - `sealed Shape` + sealed 子类：`is Sub` 分支编译为 INSTANCEOF 链，
 *   `fun area(s: Shape): Int = when s { is Circle -> 1 is Rect -> 2 }` 无需 else 且运行正确。
 */
class WhenExprSealedE2eTest {

    @Test
    fun `when expression assigns and returns`() {
        val out = BackendTestSupport.compileAndRun(
            """
            fun pick(n: Int): String = when n {
                1 -> "one"
                2 -> "two"
                else -> "other"
            }
            fun main() {
                n = 1
                x = when n {
                    1 -> "a"
                    2 -> "b"
                    else -> "c"
                }
                print x
                print pick(2)
                print pick(9)
            }
            """.trimIndent(),
        )
        assertEquals("atwoother", out)
    }

    @Test
    fun `sealed when with is branches runs`() {
        val out = BackendTestSupport.compileAndRun(
            """
            sealed Shape {}
            Circle extends Shape {}
            Rect extends Shape {}
            fun area(s: Shape): Int = when s {
                is Circle -> 1
                is Rect -> 2
            }
            fun main() {
                c = Circle()
                r = Rect()
                print area(c)
                print area(r)
                print area(Circle())
                print area(Rect())
            }
            """.trimIndent(),
        )
        assertEquals("1212", out)
    }

    @Test
    fun `sealed when expression value in var and else fallback`() {
        val out = BackendTestSupport.compileAndRun(
            """
            sealed Shape {}
            Circle extends Shape {}
            Rect extends Shape {}
            fun name(s: Shape): String = when s {
                is Circle -> "circle"
                is Rect -> "rect"
            }
            fun main() {
                c = Circle()
                r = Rect()
                print name(c)
                print name(r)
                n = 5
                msg = when n {
                    1 -> "low"
                    else -> "high"
                }
                print msg
            }
            """.trimIndent(),
        )
        assertEquals("circlerecthigh", out)
    }

    @Test
    fun `when expression with is branches smart casts subject`() {
        val out = BackendTestSupport.compileAndRun(
            """
            sealed Shape {}
            Circle extends Shape {
                radius: Int
            }
            Rect extends Shape {
                width: Int
            }
            fun describe(s: Shape): String = when s {
                is Circle -> "circle radius ${'$'}{s.radius}"
                is Rect -> "rect width ${'$'}{s.width}"
            }
            fun main() {
                c = Circle(5)
                r = Rect(3)
                print describe(c)
                print describe(r)
            }
            """.trimIndent(),
        )
        assertEquals("circle radius 5rect width 3", out)
    }
}
