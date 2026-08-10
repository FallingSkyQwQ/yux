package yux.backend.jvm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * S-6.4.1 迭代端到端：`for c in "abc"` 走 String 索引循环（length/charAt）；
 * `for x in intArray` 走 JVM 数组索引循环（length 字段 + xALOAD）——
 * String 与 JVM 数组不实现 java.lang.Iterable，此前生成 iterator() 调用导致 NoSuchMethodError。
 */
class ForLoopE2eTest {

    @Test
    fun `for over string iterates chars`() {
        val out = BackendTestSupport.compileAndRun(
            """
            fun main() {
                for c in "abc" {
                    print c
                }
                print ","
                s = "hello"
                for ch in s {
                    print ch
                }
            }
            """.trimIndent(),
        )
        assertEquals("abc,hello", out)
    }

    @Test
    fun `for over string inside loop body uses loop var`() {
        val out = BackendTestSupport.compileAndRun(
            """
            fun main() {
                total = 0
                for c in "a1b2" {
                    if c == '1' {
                        total += 1
                    }
                    if c == '2' {
                        total += 2
                    }
                }
                print total
            }
            """.trimIndent(),
        )
        assertEquals("3", out)
    }

    @Test
    fun `for over jvm int array iterates elements`() {
        val out = BackendTestSupport.compileAndRun(
            """
            import yux.backend.jvm.ArraySource
            fun main() {
                arr = ArraySource.ints()
                for x in arr {
                    print x
                }
            }
            """.trimIndent(),
        )
        assertEquals("102030", out)
    }

    @Test
    fun `for over jvm char array iterates chars`() {
        val out = BackendTestSupport.compileAndRun(
            """
            import yux.backend.jvm.ArraySource
            fun main() {
                for ch in ArraySource.chars() {
                    print ch
                }
            }
            """.trimIndent(),
        )
        assertEquals("xyz", out)
    }

    @Test
    fun `array index read works in expression`() {
        val out = BackendTestSupport.compileAndRun(
            """
            import yux.backend.jvm.ArraySource
            fun main() {
                arr = ArraySource.ints()
                print arr[1]
                print (arr[0] + arr[2])
            }
            """.trimIndent(),
        )
        assertEquals("2040", out)
    }
}
