package yux.compiler.sema

import org.junit.jupiter.api.Test
import yux.compiler.diag.ErrorCodes
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 正确性缺陷批次回归测试（M11 后审查确认的 10 项缺陷）。
 * 每项：负向用例断言诊断码 / 正向用例断言行为，遵循既有 sema 测试风格。
 */
class CorrectnessFixesTest {
    // ── 缺陷 1：智能转型被嵌套作用域同名变量遮蔽 ──────────────────────────────

    @Test
    fun `loop variable shadows outer smart cast`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun main() {
                    x:Any? = null
                    if x is String {
                        for x in 0..2 {
                            n = x.length
                        }
                    }
                }
                """.trimIndent(),
            )
        // 循环变量 x 是 Int，遮蔽外层 String 转型 → x.length 不可解析
        assertTrue(r.hasCode(ErrorCodes.UNRESOLVED_MEMBER), r.diagnostics.toString())
    }

    @Test
    fun `catch parameter shadows outer smart cast`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun main() {
                    x:Any? = null
                    if x is String {
                        try {
                            print "a"
                        } catch x:Exception {
                            n = x.length
                        }
                    }
                }
                """.trimIndent(),
            )
        // catch 参数 x 是 Exception，遮蔽外层 String 转型 → x.length 不可解析
        assertTrue(r.hasCode(ErrorCodes.UNRESOLVED_MEMBER), r.diagnostics.toString())
    }

    @Test
    fun `lambda parameter shadows outer smart cast`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun apply(f:(Int) -> Int, v:Int) = v
                fun main() {
                    x:Any? = null
                    if x is String {
                        y = apply((x -> x.length), 1)
                    }
                }
                """.trimIndent(),
            )
        // lambda 参数 x 是 Int，遮蔽外层 String 转型 → x.length 不可解析
        assertTrue(r.hasCode(ErrorCodes.UNRESOLVED_MEMBER), r.diagnostics.toString())
    }

    @Test
    fun `outer smart cast restored after shadowing scope`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun main() {
                    x:Any? = null
                    if x is String {
                        for x in 0..2 {
                            print x
                        }
                        n = x.length
                    }
                }
                """.trimIndent(),
            )
        // 循环结束后外层 String 转型恢复 → x.length 可解析
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 缺陷 2：智能转型支持 &&/|| 短路链与 else 分支反向转型 ──────────────────

    @Test
    fun `and chain left null check enables right side`() {
        val r =
            SemaTestSupport.analyze(
                """
                Player {
                    name:String = ""
                }
                fun f(x: Player?) {
                    if x != null && x.name == "" {
                        x.name = "new"
                    }
                }
                """.trimIndent(),
            )
        // && 左侧判空后 x 非空 → 写入不触发 E0023
        assertFalse(r.hasCode(ErrorCodes.NULLABLE_WRITE_NEEDS_CHECK), r.diagnostics.toString())
    }

    @Test
    fun `or chain else branch casts non-null`() {
        val r =
            SemaTestSupport.analyze(
                """
                Player {
                    name:String = ""
                }
                fun f(x: Player?) {
                    if x == null || x.name == "" {
                        print "ok"
                    } else {
                        x.name = "new"
                    }
                }
                """.trimIndent(),
            )
        // || 的 else 分支：x != null → 写入不触发 E0023
        assertFalse(r.hasCode(ErrorCodes.NULLABLE_WRITE_NEEDS_CHECK), r.diagnostics.toString())
    }

    @Test
    fun `is type else branch does not carry the cast`() {
        val r =
            SemaTestSupport.analyze(
                """
                Player {
                    name:String = ""
                }
                fun f(x: Player?) {
                    if x is Player {
                        print "ok"
                    } else {
                        x.name = "new"
                    }
                }
                """.trimIndent(),
            )
        // else 分支 x 为 null（非 Player）→ 写入触发 E0023
        assertTrue(r.hasCode(ErrorCodes.NULLABLE_WRITE_NEEDS_CHECK), r.diagnostics.toString())
    }

    // ── 缺陷 3：JVM 重载匹配失败时静默选第一个候选 ─────────────────────────────

    @Test
    fun `jvm overload type mismatch reports clear diagnostic`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun main() {
                    x = Math.max("a", "b")
                }
                """.trimIndent(),
            )
        // 无匹配重载 → 明确诊断（列出签名），而非静默选第一个候选
        assertTrue(r.diagnostics.diagnostics.any { it.message.contains("没有匹配的重载") }, r.diagnostics.toString())
    }

    @Test
    fun `jvm overload arity mismatch reports clear diagnostic`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun main() {
                    x = Math.max(1, 2, 3)
                }
                """.trimIndent(),
            )
        // 元数不符 → 明确诊断（列出签名）
        assertTrue(r.diagnostics.diagnostics.any { it.message.contains("没有匹配的重载") }, r.diagnostics.toString())
    }

    // ── 缺陷 4：首字母大写即类型的消歧 hack ───────────────────────────────────

    @Test
    fun `uppercase function name is callable not type construction`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun Foo() {
                }
                fun main() {
                    Foo()
                }
                """.trimIndent(),
            )
        // 大写函数名按符号表消歧为函数调用，而非类型构造
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `uppercase variable is referenceable as value`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun main() {
                    Foo = 5
                    print Foo
                }
                """.trimIndent(),
            )
        // 大写变量按符号表消歧为变量引用，而非类型引用
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `imported jvm type still usable as static receiver`() {
        val r =
            SemaTestSupport.analyze(
                """
                import java.util.UUID
                fun main() {
                    id = UUID.randomUUID()
                }
                """.trimIndent(),
            )
        // 导入的 JVM 类型仍走类型通道（静态成员访问）
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 缺陷 5：JVM 泛型实参恒为空 ────────────────────────────────────────────

    @Test
    fun `jvm generic return type carries type args`() {
        val r =
            SemaTestSupport.analyze(
                """
                import java.util.Properties
                fun main() {
                    p = Properties()
                    names = p.stringPropertyNames()
                    for n in names {
                        s = n.length
                    }
                }
                """.trimIndent(),
            )
        // stringPropertyNames() 返回 Set<String> → 元素类型 String，n.length 可解析
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 缺陷 6：data 类中的函数/初始化块被静默丢弃 ─────────────────────────────

    @Test
    fun `data class with method reports E0011`() {
        val r =
            SemaTestSupport.analyze(
                """
                data User {
                    id:Int
                    fun greet() {
                    }
                }
                """.trimIndent(),
            )
        // data 类含方法 → E0011 ILLEGAL_DATA_MEMBER（S-5.3.1）
        assertTrue(r.hasCode(ErrorCodes.ILLEGAL_DATA_MEMBER), r.diagnostics.toString())
    }

    @Test
    fun `data class with init block reports E0011`() {
        val r =
            SemaTestSupport.analyze(
                """
                data User {
                    id:Int
                    { print "init" }
                }
                """.trimIndent(),
            )
        // data 类含初始化块 → E0011 ILLEGAL_DATA_MEMBER（S-5.3.1）
        assertTrue(r.hasCode(ErrorCodes.ILLEGAL_DATA_MEMBER), r.diagnostics.toString())
    }

    // ── 缺陷 7：`..=` 复合赋值仅解析，IRGen 才 error ──────────────────────────

    @Test
    fun `range compound assignment reports error in sema`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun main() {
                    x = 1
                    x ..= 5
                }
                """.trimIndent(),
            )
        // `..=` 未实现 → sema 直接报 E0017，不放行到 IRGen
        assertTrue(r.hasCode(ErrorCodes.INVALID_OPERATOR_OPERAND), r.diagnostics.toString())
    }

    // ── 缺陷 9：实例成员访问不沿 superclass 链解析 ────────────────────────────

    @Test
    fun `child instance calls parent method`() {
        val r =
            SemaTestSupport.analyze(
                """
                Base {
                    fun greet() = "base"
                }
                Child extends Base {
                }
                fun main() {
                    c = Child()
                    s = c.greet()
                }
                """.trimIndent(),
            )
        // 子类实例调用父类方法 → 沿父类链解析
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `child instance reads parent property`() {
        val r =
            SemaTestSupport.analyze(
                """
                Base {
                    name:String = "base"
                }
                Child extends Base {
                }
                fun main() {
                    c = Child()
                    s = c.name
                }
                """.trimIndent(),
            )
        // 子类实例读取父类属性 → 沿父类链解析
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `grandchild instance calls grandparent method`() {
        val r =
            SemaTestSupport.analyze(
                """
                GrandBase {
                    fun greet() = "grand"
                }
                Base extends GrandBase {
                }
                Child extends Base {
                }
                fun main() {
                    c = Child()
                    s = c.greet()
                }
                """.trimIndent(),
            )
        // 多级继承：子类实例调用祖父类方法 → 沿父类链解析
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 缺陷 8：super 被降级为 this ───────────────────────────────────────────

    @Test
    fun `super call resolves parent method`() {
        val r =
            SemaTestSupport.analyze(
                """
                Base {
                    fun greet() = "base"
                }
                Child extends Base {
                    override fun greet() = "child"
                    fun parentGreet() = super.greet()
                }
                """.trimIndent(),
            )
        // super.greet() 解析父类方法（sema 层）
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `super property access resolves parent property`() {
        val r =
            SemaTestSupport.analyze(
                """
                Base {
                    name:String = "base"
                }
                Child extends Base {
                    fun parentName() = super.name
                }
                """.trimIndent(),
            )
        // super.name 解析父类属性（sema 层）
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 缺陷 10：private/protected 访问控制 ───────────────────────────────────

    @Test
    fun `private member accessible within declaring class`() {
        val r =
            SemaTestSupport.analyze(
                """
                A {
                    private secret:Int = 1
                    fun read() = secret
                }
                """.trimIndent(),
            )
        // 声明类内访问 private → 合法
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `private member inaccessible from other class`() {
        val r =
            SemaTestSupport.analyze(
                """
                A {
                    private secret:Int = 1
                }
                B {
                    fun read(a:A) = a.secret
                }
                """.trimIndent(),
            )
        // 跨类访问 private 属性 → E0033
        assertTrue(r.hasCode(ErrorCodes.ILLEGAL_ACCESS), r.diagnostics.toString())
    }

    @Test
    fun `private member inaccessible from top level function`() {
        val r =
            SemaTestSupport.analyze(
                """
                A {
                    private fun secret() = 1
                }
                fun read(a:A) = a.secret()
                """.trimIndent(),
            )
        // 顶层函数跨类访问 private 方法 → E0033
        assertTrue(r.hasCode(ErrorCodes.ILLEGAL_ACCESS), r.diagnostics.toString())
    }

    @Test
    fun `protected member accessible within subclass`() {
        val r =
            SemaTestSupport.analyze(
                """
                Base {
                    protected fun helper() = 1
                }
                Child extends Base {
                    fun use() = this.helper()
                }
                """.trimIndent(),
            )
        // 子类内访问 protected → 合法
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `protected member inaccessible from unrelated class`() {
        val r =
            SemaTestSupport.analyze(
                """
                Base {
                    protected fun helper() = 1
                }
                Other {
                    fun use(b:Base) = b.helper()
                }
                """.trimIndent(),
            )
        // 非子类访问 protected → E0033
        assertTrue(r.hasCode(ErrorCodes.ILLEGAL_ACCESS), r.diagnostics.toString())
    }

    // ── 缺陷 11：`void() + "str"` 静默放行，IRGen 产出非法字节码（VerifyError） ──

    @Test
    fun `unit call plus string reports error`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun send(msg: String) {
                    print msg
                }
                fun main() {
                    sb = StringBuilder()
                    send "你的家：" + sb.toString()
                }
                """.trimIndent(),
            )
        // S-7.2.2：`send "a" + x` 解析为 `(send "a") + x`；Unit + String 按 S-7.6.1 拒绝，
        // 否则 IRGen 会把 void 调用当作拼接操作数产出栈下溢字节码（Home 插件 VerifyError）
        assertTrue(r.hasCode(ErrorCodes.INVALID_OPERATOR_OPERAND), r.diagnostics.toString())
    }

    @Test
    fun `string plus unit call reports error`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun send(msg: String) {
                    print msg
                }
                fun main() {
                    x = "a" + send("b")
                }
                """.trimIndent(),
            )
        // 括号调用同样返回 Unit，String + Unit 亦拒绝
        assertTrue(r.hasCode(ErrorCodes.INVALID_OPERATOR_OPERAND), r.diagnostics.toString())
    }

    @Test
    fun `boolean and string still absorb`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun main() {
                    x = true + "a"
                    s = "b"
                    s += false
                }
                """.trimIndent(),
            )
        // String 侧吸收任意可求值操作数（S-7.6.1）：Boolean 拼接/复合赋值仍合法
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `string concat with numeric still allowed`() {
        val r =
            SemaTestSupport.analyze(
                """
                fun main() {
                    s = "a" + 1
                    t = 1 + "a"
                    u = "b" + s
                }
                """.trimIndent(),
            )
        // String±String / String±数字 合法（S-7.6.1），不误伤
        assertFalse(r.hasErrors, r.errors.toString())
    }
}
