package yux.backend.jvm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * T-M11-3 端到端：Yux 成员调用语法 `xs.map {}` / `s.uppercase()` 降级为
 * yux-stdlib 静态调用（Colls/Text），编译运行并精确比对 stdout。
 *
 * 覆盖：map（含 `it * 10` 元素类型 Int 生效）/filter/sum/max/first/reduce 的
 * 组合输出、lambda 捕获、uppercase/lowercase/split/format、静态 Text 调用。
 */
class JvmExtensionsE2eTest {

    @Test
    fun `collection and string extension calls run`() {
        val out = BackendTestSupport.compileAndRun(
            """
            fun main() {
                xs = List Int()
                xs.add(3); xs.add(1); xs.add(2)
                ys = xs.map { it * 10 }
                print ys.sum()
                print (ys.max() as Int)
                print (ys.first() as Int)
                print (ys.reduce((a, b -> (a as Int) + (b as Int))) as Int)
                print ys.filter({ (it as Int) > 20 }).sum()
                k = 2
                print xs.map({ it * k }).sum()
                print ("AbC".uppercase() as String)
                print ("AbC".lowercase() as String)
                print "a,b,c".split(",").size
                print (Text.uppercase("hello") as String)
                args:List Any = List Any() as List Any
                args.add("x"); args.add(42)
                print (Text.format("%s=%d", args) as String)
            }
            """.trimIndent(),
        )
        assertEquals("60.030306030.012.0ABCabc3HELLOx=42", out)
    }

    @Test
    fun `sum and first on empty list`() {
        val out = BackendTestSupport.compileAndRun(
            """
            fun main() {
                e = List Int()
                print e.sum()
                print (e.first() as Any)
            }
            """.trimIndent(),
        )
        assertEquals("0.0null", out)
    }

    @Test
    fun `lowercase and uppercase round trip`() {
        val out = BackendTestSupport.compileAndRun(
            """
            fun main() {
                print ("Hello World".lowercase() as String)
                print ("Hello World".uppercase() as String)
            }
            """.trimIndent(),
        )
        assertEquals("hello worldHELLO WORLD", out)
    }
}
