package yux.backend.jvm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 完整 SSA 优化器端到端正确性回归：
 * 覆盖常量传播/φ 合并/循环/结构化 Try（flush 拷贝 + 原寄存器回退）、
 * break/continue 跨 try（SSA 保守回退 BasicOpt，仍须正确）。
 */
class SsaE2eTest {

    @Test
    fun `try defining local read after is preserved`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                u = "hi"
                try {
                    u = u + "!"
                } catch e {
                    u = "err"
                }
                print u
            }
        """.trimIndent())
        assertEquals("hi!", out)
    }

    @Test
    fun `try conditional definition via catch is preserved`() {
        val out = BackendTestSupport.compileAndRun("""
            fun bump(v: Int): Int {
                v2:Int = 0
                try {
                    v2 = v + 1
                } catch e {
                    v2 = 0
                }
                return v2
            }
            fun main() {
                print bump(1)
                print bump(0)
            }
        """.trimIndent())
        assertEquals("21", out)
    }

    @Test
    fun `loop containing try accumulates correctly`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                total = 0
                i = 0
                while i < 5 {
                    try {
                        total = total + i
                    } catch e {
                        total = 0
                    }
                    i = i + 1
                }
                print total
            }
        """.trimIndent())
        assertEquals("10", out)
    }

    @Test
    fun `break across try falls back to basic opt and stays correct`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                i = 0
                while i < 10 {
                    try {
                        i = i + 1
                        if i >= 5 {
                            break
                        }
                    } catch e {
                        i = i + 1
                    }
                }
                print i
            }
        """.trimIndent())
        assertEquals("5", out)
    }

    @Test
    fun `constant propagation across branches with types preserved`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                n = 5
                r:Double = 0.0
                if n > 0 {
                    r = n * 1.5
                } else {
                    r = n * -1.0
                }
                print r
                s = "x"
                if n == 5 {
                    s = s + "y"
                }
                print s
            }
        """.trimIndent())
        assertEquals("7.5xy", out)
    }

    @Test
    fun `when expression on runtime value keeps all paths`() {
        val out = BackendTestSupport.compileAndRun("""
            fun pick(n: Int): String {
                return when (n) {
                    1 -> "one"
                    2 -> "two"
                    else -> "other"
                }
            }
            fun main() {
                print pick(1)
                print pick(2)
                print pick(7)
            }
        """.trimIndent())
        assertEquals("onetwoother", out)
    }
}
