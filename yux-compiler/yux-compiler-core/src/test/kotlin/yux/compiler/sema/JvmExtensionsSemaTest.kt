package yux.compiler.sema

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M11-3 Yux 源码级 sema：`xs.map {}` / `s.uppercase()` 等成员调用降级为
 * yux-stdlib 静态调用，均须无错误通过。
 */
class JvmExtensionsSemaTest {

    @Test
    fun `list map with element typed lambda`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                xs = List Int()
                xs.add(3); xs.add(1); xs.add(2)
                ys = xs.map { it * 10 }
                print ys.sum()
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
        // 块 Lambda 的 `it` 应推导为元素类型 Int
        assertTrue(r.analysis.exprTypes.values.any { it is SemaType.Function && it.params == listOf(SemaType.INT) })
    }

    @Test
    fun `string uppercase extension on basic receiver`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                up = "AbC".uppercase()
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `list forEach with implicit it`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                xs = List Int()
                xs.add(3)
                xs.forEach { print it }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `list sort with two param lambda`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                xs = List Int()
                xs.add(3); xs.add(1); xs.add(2)
                ys = xs.sort((a, b -> (a as Int) > (b as Int)))
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `sum on fresh list constructor`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                total = List Int().sum()
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `text format accepts List Any variable`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                s:List Any = List Any() as List Any
                s.add(1)
                out = Text.format("%s", s)
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `list int variable add works`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                s:List Int = List Int() as List Int
                s.add(1)
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `collection members all resolve`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                xs = List Int()
                xs.add(3); xs.add(1); xs.add(2)
                a = xs.filter { (it as Int) > 1 }
                b = xs.max()
                c = xs.first()
                d = xs.reduce((x, y -> (x as Int) + (y as Int)))
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `string lowercase split format extensions`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                lo = "AbC".lowercase()
                parts = "a,b,c".split(",")
                f = "%s!".format(List Any() as List Any)
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `static Text uppercase still works`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                s = Text.uppercase("hello")
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }
}
