package yux.compiler.sema

import org.junit.jupiter.api.Test
import yux.compiler.diag.ErrorCodes
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M3-4 类型检查：S-xx 规则的正向/负向用例。
 */
class TypeCheckerTest {

    // ── 条件（S-6.2.1）──────────────────────────────────────────────────────

    @Test
    fun `boolean condition ok`() {
        val r = SemaTestSupport.analyze("fun main() {\n  if true {\n  }\n  while false {\n  }\n}")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `comparison condition ok`() {
        val r = SemaTestSupport.analyze("fun main() {\n  x = 1\n  if x < 5 {\n  }\n}")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 调用 / 实参（S-5.5.5）───────────────────────────────────────────────

    @Test
    fun `function call with default arg`() {
        val r = SemaTestSupport.analyze(
            """
            fun greet(name:String, title:String = "Mr.") {
            }
            fun main() {
                greet "Steve"
                greet("Alex", "Dr.")
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `class method call`() {
        val r = SemaTestSupport.analyze(
            """
            Counter {
                count:Int = 0
                fun add(n:Int) = count + n
            }
            fun main() {
                c = Counter(0)
                x = c.add(5)
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `chain of jvm method calls`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                s = "  hello  ".trim()
                n = s.length
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `static jvm method call`() {
        val r = SemaTestSupport.analyze("fun main() {\n  t = System.currentTimeMillis()\n}")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 运算符（S-7.5）──────────────────────────────────────────────────────

    @Test
    fun `string concat`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                s = "a" + 1
                t = 1 + "a"
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `arithmetic promotion`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                a = 1 + 2
                b = 1L + 2
                c = 1.5 + 1
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
        val types = r.analysis.exprTypes
        assertTrue(types.values.any { it is SemaType.Basic && it.name == "Long" })
        assertTrue(types.values.any { it is SemaType.Basic && it.name == "Double" })
    }

    @Test
    fun `logical operators need boolean`() {
        val r = SemaTestSupport.analyze("fun main() {\n  x = true && false\n  y = true || false\n}")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 确定性赋值（S-6.1.2）───────────────────────────────────────────────

    @Test
    fun `assigned in both branches is definite`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                x:Int
                if true {
                    x = 1
                } else {
                    x = 2
                }
                print x
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `uninitialized variable error`() {
        val r = SemaTestSupport.analyze("fun main() {\n  x:Int\n  print x\n}")
        assertTrue(r.hasCode(ErrorCodes.VARIABLE_NOT_INITIALIZED), r.diagnostics.toString())
    }

    // ── 返回类型（S-5.5.2）──────────────────────────────────────────────────

    @Test
    fun `expr body return inference`() {
        val r = SemaTestSupport.analyze(
            """
            fun add(a:Int, b:Int) = a + b
            fun main() {
                x = add(1, 2)
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `unit function with no return`() {
        val r = SemaTestSupport.analyze("fun main() {\n  print \"hi\"\n}")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `void function returning value error`() {
        val r = SemaTestSupport.analyze("fun main():Unit {\n  return 5\n}")
        assertTrue(r.hasCode(ErrorCodes.RETURN_TYPE_MISMATCH), r.diagnostics.toString())
    }

    @Test
    fun `bare return in void function ok`() {
        val r = SemaTestSupport.analyze("fun main() {\n  return\n}")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 循环（S-6.4）────────────────────────────────────────────────────────

    @Test
    fun `for over range`() {
        val r = SemaTestSupport.analyze("fun main() {\n  for i in 0..10 {\n    print i\n  }\n}")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `break and continue inside loop`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                for i in 0..10 {
                    if i == 5 {
                        break
                    }
                    continue
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 异常（S-6.6）────────────────────────────────────────────────────────

    @Test
    fun `try catch finally`() {
        val r = SemaTestSupport.analyze(
            """
            fun risky() = 1
            fun main() {
                try {
                    x = risky()
                } catch e:Exception {
                    print e
                } finally {
                    print "done"
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 继承 / 接口（S-8.7）─────────────────────────────────────────────────

    @Test
    fun `override valid method`() {
        val r = SemaTestSupport.analyze(
            """
            Base {
                fun tick() {
                }
            }
            Impl extends Base {
                override fun tick() {
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `override without super member error`() {
        val r = SemaTestSupport.analyze(
            """
            Base {
            }
            Impl extends Base {
                override fun tick() {
                }
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.OVERRIDE_NO_SUPER), r.diagnostics.toString())
    }

    // ── this / super ────────────────────────────────────────────────────────

    @Test
    fun `this inside class`() {
        val r = SemaTestSupport.analyze(
            """
            Player {
                name:String = "Steve"
                fun hello() = this.name
            }
            fun main() {
                p = Player("Steve")
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `this outside class error`() {
        val r = SemaTestSupport.analyze("fun main() {\n  x = this\n}")
        assertTrue(r.hasCode(ErrorCodes.THIS_OUTSIDE_CLASS), r.diagnostics.toString())
    }

    // ── Lambda（S-7.4）──────────────────────────────────────────────────────

    @Test
    fun `block lambda with it`() {
        val r = SemaTestSupport.analyze(
            """
            fun apply(f:(Int) -> Int, x:Int) = x
            fun main() {
                y = apply ({ it + 1 }, 5)
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }
}
