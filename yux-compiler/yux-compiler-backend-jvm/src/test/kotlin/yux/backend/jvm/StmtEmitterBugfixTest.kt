package yux.backend.jvm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T-M11 字节码正确性缺陷回归测试（缺陷清单 10 项）：
 * 每项先复现（VerifyError / 错误输出 / 生成期异常），修复后转绿。
 */
class StmtEmitterBugfixTest {

    // ── 缺陷 1：finally 异常临时槽从 0 起分配，覆盖 this/参数/局部 ──────────────

    @Test
    fun `finally temp slot does not overlap this`() {
        val out = BackendTestSupport.compileAndRun("""
            Bomb {
                name:String
                fun f(): Int {
                    try {
                        x = 1 / 0
                    } finally {
                        print name
                    }
                    return 0
                }
            }
            fun main() {
                b = Bomb("bomb")
                try {
                    b.f()
                } catch e {
                    print "caught"
                }
            }
        """.trimIndent())
        // finally 异常路径先读 this（print name），再重抛；外层捕获
        assertEquals("bombcaught", out)
    }

    // ── 缺陷 2：混合可空/非可空数值 == / != 装箱后引用比较 ───────────────────────

    @Test
    fun `mixed nullable numeric equality compares correctly`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                age:Int? = 18
                print (age == 18)
                print (age != 18)
                n:Int? = null
                print (n == 18)
                print (n != 18)
                print (18 == age)
            }
        """.trimIndent())
        assertEquals("truefalsefalsetruetrue", out)
    }

    // ── 缺陷 3：数值加宽缺失（return / 局部 store 的 I→J/D、I→F 等）──────────────

    @Test
    fun `numeric widening on return and local store`() {
        val out = BackendTestSupport.compileAndRun("""
            fun f(): Long { return 1 }
            fun g(): Double { return 1 }
            fun main() {
                l:Long = 1
                fl:Float = 1
                print f()
                print l
                print g()
                print fl
            }
        """.trimIndent())
        assertEquals("111.01.0", out)
    }

    // ── 缺陷 4：原语接收者实例调用先装箱（n.intValue()）─────────────────────────

    @Test
    fun `primitive receiver instance call boxes before invoke`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                n = 42
                print n.intValue()
                d:Double = 3.5
                print d.doubleValue()
            }
        """.trimIndent())
        assertEquals("423.5", out)
    }

    // ── 缺陷 5：Char 字符串拼接/插值走 append(char) ─────────────────────────────

    @Test
    fun `char concat and interpolation use append char`() {
        val out = BackendTestSupport.compileAndRun("""
            data P {
                c:Char
            }
            fun main() {
                print ("a" + 'b')
                print ("c${'$'}{'d'}")
                p = P('x')
                print p.toString
            }
        """.trimIndent())
        assertEquals("abcdP(c=x)", out)
    }

    // ── 缺陷 6：Float/Double data 属性 equals 与 hashCode 契约 ──────────────────

    @Test
    fun `float data class equals hashCode contract`() {
        val classes = BackendTestSupport.compile("""
            data F {
                v:Float
            }
            data D {
                w:Double
            }
        """.trimIndent())
        val loader = BackendTestSupport.load(classes)
        val fCls = Class.forName("F", false, loader)
        val fCtor = fCls.constructors.single()
        // -0.0f == 0.0f 为 true 但 hashCode 不同 → equals 必须区分（Float.compare 语义）
        val pos = fCtor.newInstance(0.0f)
        val neg = fCtor.newInstance(-0.0f)
        assertNotEquals(pos, neg, "-0.0f 与 0.0f 必须不等（equals/hashCode 契约）")
        // NaN 相等且 hashCode 相同
        val nan1 = fCtor.newInstance(Float.NaN)
        val nan2 = fCtor.newInstance(Float.NaN)
        assertEquals(nan1, nan2)
        assertEquals(nan1.hashCode(), nan2.hashCode())
        // 单属性 data 类：hash = 31 * 1 + Float.hashCode(v)
        assertEquals(31 + java.lang.Float.hashCode(Float.NaN), nan1.hashCode())
        // 普通值相等且 hashCode 相同
        val a = fCtor.newInstance(1.5f)
        val b = fCtor.newInstance(1.5f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val dCls = Class.forName("D", false, loader)
        val dCtor = dCls.constructors.single()
        val dPos = dCtor.newInstance(0.0)
        val dNeg = dCtor.newInstance(-0.0)
        assertNotEquals(dPos, dNeg, "-0.0 与 0.0 必须不等（equals/hashCode 契约）")
        val dNan1 = dCtor.newInstance(Double.NaN)
        val dNan2 = dCtor.newInstance(Double.NaN)
        assertEquals(dNan1, dNan2)
        assertEquals(dNan1.hashCode(), dNan2.hashCode())
        assertEquals(31 + java.lang.Double.hashCode(Double.NaN), dNan1.hashCode())
    }

    // ── 缺陷 7：调用返回 Unit 的函数类型值残留 Object 在栈上 ────────────────────

    @Test
    fun `unit function type invocation pops result`() {
        // 语句位置 FnInvoke 残留 Object：非折叠分支合并处栈帧不一致 → VerifyError
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                f:() -> Unit = { print "x" }
                i = 1
                if i > 0 {
                    f()
                }
                g:(Int) -> Unit = { print "u" }
                g(1)
                print "y"
            }
        """.trimIndent())
        assertEquals("xuy", out)
    }

    // ── 缺陷 8：catch 参数递归查找 + 不复用方法参数槽 ───────────────────────────

    @Test
    fun `catch param found in nested expression`() {
        // 嵌套在赋值值表达式中的 catch 参数读取：递归查找槽位
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                try {
                    x = 1 / 0
                } catch e {
                    s = "err: " + e
                    print s
                }
            }
        """.trimIndent())
        assertEquals("err: java.lang.ArithmeticException: / by zero", out)
    }

    @Test
    fun `catch param does not reuse method param slot`() {
        val out = BackendTestSupport.compileAndRun("""
            fun f(e:Int): Int {
                try {
                    x = 1 / 0
                } catch e {
                    print ("e=" + e)
                }
                return e
            }
            fun main() {
                print f(0)
            }
        """.trimIndent())
        // catch 参数独立槽位：方法参数 e 不得被 ASTORE 覆盖
        assertEquals("e=java.lang.ArithmeticException: / by zero0", out)
    }

    // ── 缺陷 9：Function4 接口 + arity 0..4 映射 ────────────────────────────────

    @Test
    fun `function type with four params`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                f:(Int, Int, Int, Int) -> Int = (a, b, c, d -> a + b + c + d)
                print f(1, 2, 3, 4)
            }
        """.trimIndent())
        assertEquals("10", out)
    }
}
