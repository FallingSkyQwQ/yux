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
        val out =
            BackendTestSupport.compileAndRun(
                """
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
                """.trimIndent(),
            )
        // finally 异常路径先读 this（print name），再重抛；外层捕获
        assertEquals("bombcaught", out)
    }

    // ── 缺陷 2：混合可空/非可空数值 == / != 装箱后引用比较 ───────────────────────

    @Test
    fun `mixed nullable numeric equality compares correctly`() {
        val out =
            BackendTestSupport.compileAndRun(
                """
                fun main() {
                    age:Int? = 18
                    print (age == 18)
                    print (age != 18)
                    n:Int? = null
                    print (n == 18)
                    print (n != 18)
                    print (18 == age)
                }
                """.trimIndent(),
            )
        assertEquals("truefalsefalsetruetrue", out)
    }

    // ── 缺陷 3：数值加宽缺失（return / 局部 store 的 I→J/D、I→F 等）──────────────

    @Test
    fun `numeric widening on return and local store`() {
        val out =
            BackendTestSupport.compileAndRun(
                """
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
                """.trimIndent(),
            )
        assertEquals("111.01.0", out)
    }

    // ── 缺陷 4：原语接收者实例调用先装箱（n.intValue()）─────────────────────────

    @Test
    fun `primitive receiver instance call boxes before invoke`() {
        val out =
            BackendTestSupport.compileAndRun(
                """
                fun main() {
                    n = 42
                    print n.intValue()
                    d:Double = 3.5
                    print d.doubleValue()
                }
                """.trimIndent(),
            )
        assertEquals("423.5", out)
    }

    // ── 缺陷 5：Char 字符串拼接/插值走 append(char) ─────────────────────────────

    @Test
    fun `char concat and interpolation use append char`() {
        val out =
            BackendTestSupport.compileAndRun(
                """
                data P {
                    c:Char
                }
                fun main() {
                    print ("a" + 'b')
                    print ("c${'$'}{'d'}")
                    p = P('x')
                    print p.toString
                }
                """.trimIndent(),
            )
        assertEquals("abcdP(c=x)", out)
    }

    // ── 缺陷 6：Float/Double data 属性 equals 与 hashCode 契约 ──────────────────

    @Test
    fun `float data class equals hashCode contract`() {
        val classes =
            BackendTestSupport.compile(
                """
                data F {
                    v:Float
                }
                data D {
                    w:Double
                }
                """.trimIndent(),
            )
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
        val out =
            BackendTestSupport.compileAndRun(
                """
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
                """.trimIndent(),
            )
        assertEquals("xuy", out)
    }

    // ── 缺陷 8：catch 参数递归查找 + 不复用方法参数槽 ───────────────────────────

    @Test
    fun `catch param found in nested expression`() {
        // 嵌套在赋值值表达式中的 catch 参数读取：递归查找槽位
        val out =
            BackendTestSupport.compileAndRun(
                """
                fun main() {
                    try {
                        x = 1 / 0
                    } catch e {
                        s = "err: " + e
                        print s
                    }
                }
                """.trimIndent(),
            )
        assertEquals("err: java.lang.ArithmeticException: / by zero", out)
    }

    @Test
    fun `catch param does not reuse method param slot`() {
        val out =
            BackendTestSupport.compileAndRun(
                """
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
                """.trimIndent(),
            )
        // catch 参数独立槽位：方法参数 e 不得被 ASTORE 覆盖
        assertEquals("e=java.lang.ArithmeticException: / by zero0", out)
    }

    // ── 缺陷 9：Function4 接口 + arity 0..4 映射 ────────────────────────────────

    @Test
    fun `function type with four params`() {
        val out =
            BackendTestSupport.compileAndRun(
                """
                fun main() {
                    f:(Int, Int, Int, Int) -> Int = (a, b, c, d -> a + b + c + d)
                    print f(1, 2, 3, 4)
                }
                """.trimIndent(),
            )
        assertEquals("10", out)
    }

    // ── 缺陷修复：when 语句 + is 分支内 return（密封 subject，无 else）────────────

    @Test
    fun `when statement with return in is branches falls off end safely`() {
        // 全分支 return 的 when 语句：尾部 Label 曾是悬空 goto 目标（goto 指向代码末尾
        // → VerifyError "StackMapTable error: bad offset"）；现补发类型匹配的兜底返回。
        val out =
            BackendTestSupport.compileAndRun(
                """
                sealed Shape {
                }
                Circle extends Shape {
                    r:Int
                }
                Square extends Shape {
                    side:Int
                }
                fun describe(s:Shape):String {
                    when s {
                        is Circle -> return "circle " + s.r
                        is Square -> return "square " + s.side
                    }
                }
                fun main() {
                    print describe(Circle(2))
                    print describe(Square(3))
                }
                """.trimIndent(),
            )
        assertEquals("circle 2square 3", out)
    }

    // ── 缺陷修复：super 调用经优化器后保持 INVOKESPECIAL（S-8.7.1）──────────────

    @Test
    fun `super call dispatches to parent after optimizer`() {
        // BasicOpt.foldExpr 重建 Invoke 时曾丢失 isSuper → invokevirtual 派发到子类
        // 覆盖方法（同签名）→ 无限递归 StackOverflow。基类方法先被调用同样触发。
        val out =
            BackendTestSupport.compileAndRun(
                """
                Animal {
                    fun speak():String = "generic"
                }
                Dog extends Animal {
                    fun speak():String = super.speak() + " dog"
                }
                fun main() {
                    a = Animal()
                    d = Dog()
                    print a.speak()
                    print d.speak()
                }
                """.trimIndent(),
            )
        assertEquals("genericgeneric dog", out)
    }

    @Test
    fun `super call works when parent return type differs from child`() {
        // 父类推断 String、子类显式 String 时同签名也必须非虚拟调用父实现
        val out =
            BackendTestSupport.compileAndRun(
                """
                Animal {
                    fun name():String = "animal"
                }
                Dog extends Animal {
                    fun name():String = super.name() + "!"
                }
                fun main() {
                    d = Dog()
                    print d.name()
                }
                """.trimIndent(),
            )
        assertEquals("animal!", out)
    }

    // ── 缺陷修复：访问器 value/set 绑定（S-5.2.3）────────────────────────────────

    @Test
    fun `accessor value and set bindings work`() {
        // getter 内 value = backing field；setter 内 value 可写、set = 传入新值
        val out =
            BackendTestSupport.compileAndRun(
                """
                Thermostat {
                    temperature:Int = 20
                    display:Int {
                        get { return value }
                        set { value = set }
                    }
                    limit:Int {
                        get { return value }
                        set { value = 26 }
                    }
                }
                fun main() {
                    t = Thermostat(20, 0, 0)
                    t.display = 99
                    print t.display
                    t.limit = 99
                    print t.limit
                    print t.temperature
                }
                """.trimIndent(),
            )
        assertEquals("992620", out)
    }

    @Test
    fun `top level accessor value binding works`() {
        // 顶层属性（无初始化器，字段默认 0）：getter 读 value、setter 写 value 且用 set
        val out =
            BackendTestSupport.compileAndRun(
                """
                counter:Int {
                    get { return value }
                    set { value = set * 2 }
                }
                fun main() {
                    counter = 21
                    print counter
                }
                """.trimIndent(),
            )
        assertEquals("42", out)
    }

    // ── 缺陷修复：基础类型（String/Int）用户扩展函数 ────────────────────────────

    @Test
    fun `extension functions on basic types resolve`() {
        // checkInstanceCall 的 Basic 分支此前从不查 lookupExtensionCall
        val out =
            BackendTestSupport.compileAndRun(
                """
                fun String.shout(suffix:String = "!"):String {
                    return this + suffix
                }
                fun Int.squared():Int {
                    return this * this
                }
                fun main() {
                    print "hi".shout()
                    print 7.squared()
                }
                """.trimIndent(),
            )
        assertEquals("hi!49", out)
    }

    // ── 缺陷修复：无 subject 的 when（`when { cond -> }`，条件为布尔表达式）────────

    @Test
    fun `when without subject statement`() {
        // 解析器此前把 `when {` 当作 subject 缺失：`{` 被 parseCondition 吞掉后报
        // "expected newline or ';'" / "unexpected token '->'" 一串错
        val out =
            BackendTestSupport.compileAndRun(
                """
                fun main() {
                    x = 5
                    when {
                        x > 0 -> print "positive"
                        x < 0 -> print "negative"
                        else -> print "zero"
                    }
                    y = -2
                    when {
                        y > 0 -> print "p"
                        else -> print "n"
                    }
                }
                """.trimIndent(),
            )
        assertEquals("positiven", out)
    }

    @Test
    fun `when without subject expression`() {
        // 无 subject 的 when 表达式：条件为布尔表达式，必须 else（sema 穷尽性）
        val out =
            BackendTestSupport.compileAndRun(
                """
                fun classify(x:Int):String = when {
                    x > 0 -> "pos"
                    x < 0 -> "neg"
                    else -> "zero"
                }
                fun main() {
                    print classify(5)
                    print classify(-2)
                    print classify(0)
                }
                """.trimIndent(),
            )
        assertEquals("posnegzero", out)
    }

    // ── 缺陷修复：顶层/类属性 init + 访问器组合（`x:Int = 10 { get { } }`）───────

    @Test
    fun `top level property initializer plus accessor`() {
        // 初始化表达式 `0 { get { } }` 此前被无括号调用解析吞成 `0(块 lambda)` → "Int 不可调用"
        val out =
            BackendTestSupport.compileAndRun(
                """
                counter:Int = 10 {
                    get { return value + 1 }
                }
                fun main() {
                    print counter
                }
                """.trimIndent(),
            )
        assertEquals("11", out)
    }

    @Test
    fun `class property initializer plus accessor`() {
        // 类属性同规则：初始化值成为构造实参，getter 读 backing field
        val out =
            BackendTestSupport.compileAndRun(
                """
                A {
                    n:Int = 5 {
                        get { return value * 2 }
                    }
                }
                fun main() {
                    a = A(5)
                    print a.n
                }
                """.trimIndent(),
            )
        assertEquals("10", out)
    }

    // ── 缺陷修复：表达式体推断被调用点约束（println 的 Any 参数）顶成 Any ─────────

    @Test
    fun `inferred return type not clobbered by println arg`() {
        // `println a.speak()` 的 Any 参数约束曾覆盖 speak 已由函数体解出的 String，
        // 导致后续 `a.speak().length` 报 "成员 'length' 不存在于类型 'Any'"
        val out =
            BackendTestSupport.compileAndRun(
                """
                Animal {
                    fun speak() = "generic"
                }
                fun main() {
                    a:Animal = Animal()
                    println a.speak()
                    println (a.speak().length)
                }
                """.trimIndent(),
            )
        assertEquals("generic\n7\n", out)
    }

    @Test
    fun `override inferred return not clobbered by println arg`() {
        // 覆盖方法经父类引用调用（虚分派到 Dog.speak）：推断返回类型同样不被顶成 Any
        val out =
            BackendTestSupport.compileAndRun(
                """
                Animal {
                    fun speak() = "generic"
                }
                Dog extends Animal {
                    override fun speak() = "dog"
                }
                fun main() {
                    a:Animal = Dog()
                    println a.speak()
                    println (a.speak().length)
                }
                """.trimIndent(),
            )
        assertEquals("dog\n3\n", out)
    }

    // ── 缺陷修复：隐式 this 成员调用（同类方法以标识符调用）VerifyError ──────────

    @Test
    fun `implicit this member call emits receiver`() {
        // `g()` 以标识符调用同类方法：IRGen 曾发 receiver=null 的 Invoke，
        // JVM 后端对非静态方法发 invokevirtual 而栈上无接收者 → Operand stack underflow
        val out =
            BackendTestSupport.compileAndRun(
                """
                A {
                    fun g():String = "hello"
                    fun f() {
                        println g()
                    }
                }
                fun main() {
                    a = A()
                    a.f()
                }
                """.trimIndent(),
            )
        assertEquals("hello\n", out)
    }

    @Test
    fun `implicit this member call before callee declaration`() {
        // 前置引用（callee 在 caller 之后声明，表达式体推断）：同样以 this 作接收者
        val out =
            BackendTestSupport.compileAndRun(
                """
                A {
                    fun f():String {
                        return g()
                    }
                    fun g() = "hi"
                }
                fun main() {
                    a = A()
                    println a.f()
                }
                """.trimIndent(),
            )
        assertEquals("hi\n", out)
    }

    @Test
    fun `when expression as inferred expression body runs`() {
        // when/if 表达式体返回类型按分支公共类型求解（不再自绑定成环 → StackOverflow）
        val out =
            BackendTestSupport.compileAndRun(
                """
                fun classify(x:Int) = when {
                    x > 0 -> "pos"
                    else -> "neg"
                }
                fun abs(x:Int) = if (x > 0) then x else -x
                fun main() {
                    print classify(3)
                    print classify(-1)
                    print abs(-4)
                }
                """.trimIndent(),
            )
        assertEquals("posneg4", out)
    }
}
