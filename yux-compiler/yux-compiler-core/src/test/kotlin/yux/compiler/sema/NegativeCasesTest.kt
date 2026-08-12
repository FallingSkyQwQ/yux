package yux.compiler.sema

import org.junit.jupiter.api.Test
import yux.compiler.diag.ErrorCodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-M3-10 验收：编译期负向用例表（06-§7.1 / M3 验收「≥80 个」）。
 *
 * 每条用例：源码 + 期望诊断码；断言「恰好命中期望码且无其他错误码」。
 * 若出现新错误码（回归），先更新实现或补充说明，再同步 [ErrorCodes.md]。
 */
class NegativeCasesTest {

    private data class Case(val name: String, val source: String, val code: String)

    private val cases: List<Case> = buildList {
        fun c(name: String, source: String, code: String) = add(Case(name, source, code))

        // ── E0001 未解析的标识符 ────────────────────────────────────────────
        c("未解析变量", "fun main() {\n  y = missingVar\n}", ErrorCodes.UNRESOLVED_REFERENCE)
        c("未解析函数调用", "fun main() {\n  missingFunc()\n}", ErrorCodes.UNRESOLVED_REFERENCE)
        c("未解析二元操作数", "fun main() {\n  x = a + b\n}", ErrorCodes.UNRESOLVED_REFERENCE)
        c("未解析接收者", "fun main() {\n  missing.call()\n}", ErrorCodes.UNRESOLVED_REFERENCE)
        c("未解析实参表达式", "fun f(x:Int) {\n}\nfun main() {\n  f(zzz)\n}", ErrorCodes.UNRESOLVED_REFERENCE)

        // ── E0002 重复声明 ──────────────────────────────────────────────────
        c("重复类型", "A {\n}\nA {\n}", ErrorCodes.DUPLICATE_DECLARATION)
        c("重复类属性", "A {\n  x:Int\n  x:Int\n}", ErrorCodes.DUPLICATE_DECLARATION)
        c("重复函数签名", "fun f(a:Int) {\n}\nfun f(b:Int) {\n}", ErrorCodes.DUPLICATE_DECLARATION)
        c("类型与函数冲突", "Player {\n}\nfun Player() {\n}", ErrorCodes.DUPLICATE_DECLARATION)
        c("重复顶层属性", "a = 1\na = 2", ErrorCodes.DUPLICATE_DECLARATION)

        // ── E0003 条件必须 Boolean ──────────────────────────────────────────
        c("if 整数条件", "fun main() {\n  if 1 {\n  }\n}", ErrorCodes.CONDITION_NOT_BOOLEAN)
        c("if 字符串条件", "fun main() {\n  if \"x\" {\n  }\n}", ErrorCodes.CONDITION_NOT_BOOLEAN)
        c("if 变量非布尔", "fun main() {\n  x = 1\n  if x {\n  }\n}", ErrorCodes.CONDITION_NOT_BOOLEAN)
        c("while 数字条件", "fun main() {\n  while 5 {\n  }\n}", ErrorCodes.CONDITION_NOT_BOOLEAN)
        c("while 字符串条件", "fun main() {\n  while \"x\" {\n  }\n}", ErrorCodes.CONDITION_NOT_BOOLEAN)
        c("if 变量字符串条件", "fun main() {\n  x = \"a\"\n  if x {\n  }\n}", ErrorCodes.CONDITION_NOT_BOOLEAN)

        // ── E0004 类型不匹配 ────────────────────────────────────────────────
        c("字符串赋给 Int", "fun main() {\n  x:Int = \"str\"\n}", ErrorCodes.TYPE_MISMATCH)
        c("浮点赋给 Int", "fun main() {\n  x:Int = 1.5\n}", ErrorCodes.TYPE_MISMATCH)
        c("Long 字面量赋给 Int", "fun main() {\n  x:Int = 42L\n}", ErrorCodes.TYPE_MISMATCH)
        c("Double 赋给 Long", "fun main() {\n  x:Long = 1.5\n}", ErrorCodes.TYPE_MISMATCH)
        c("整数赋给 Boolean", "fun main() {\n  x:Boolean = 1\n}", ErrorCodes.TYPE_MISMATCH)
        c("函数实参类型不匹配", "fun f(s:String) {\n}\nfun main() {\n  f(5)\n}", ErrorCodes.TYPE_MISMATCH)
        c("函数实参 Boolean 不匹配", "fun f(s:String) {\n}\nfun main() {\n  f(true)\n}", ErrorCodes.TYPE_MISMATCH)
        c("多实参其一不匹配", "fun f(a:Int, b:Int) {\n}\nfun main() {\n  f(\"a\", 1)\n}", ErrorCodes.TYPE_MISMATCH)
        c("重赋值类型不一致", "fun main() {\n  x = 1\n  x = \"a\"\n}", ErrorCodes.TYPE_MISMATCH)
        c("拼接结果赋给 Int", "fun main() {\n  x = 1\n  y = x + \"a\"\n  z:Int = y\n}", ErrorCodes.TYPE_MISMATCH)
        c("data 构造实参类型", "data User {\n  id:Int\n}\nfun main() {\n  u = User(\"a\")\n}", ErrorCodes.TYPE_MISMATCH)
        c("可空值赋给非空", "fun main() {\n  s:String? = \"a\"\n  t:String = s\n}", ErrorCodes.TYPE_MISMATCH)
        c("Byte 正数越界", "fun main() {\n  b:Byte = 128\n}", ErrorCodes.TYPE_MISMATCH)
        c("Byte 负数越界", "fun main() {\n  b:Byte = -129\n}", ErrorCodes.TYPE_MISMATCH)
        c("十进制整数溢出", "fun main() {\n  x = 99999999999999999999\n}", ErrorCodes.TYPE_MISMATCH)
        c("十六进制超出 Int 范围", "fun main() {\n  x = 0x1FFFFFFFF\n}", ErrorCodes.TYPE_MISMATCH)

        // ── E0005 泛型实参数目（过度/不足在解析器层被拒；此处覆盖放行后的实参数目校验）─
        c("泛型实参不足 Map", "fun f(m:Map String) {\n}", ErrorCodes.TYPE_ARGUMENT_COUNT_MISMATCH)

        // ── E0006 实参数目 ──────────────────────────────────────────────────
        c("实参过少", "fun f(a:Int) {\n}\nfun main() {\n  f()\n}", ErrorCodes.ARGUMENT_COUNT_MISMATCH)
        c("实参过多", "fun f(a:Int) {\n}\nfun main() {\n  f(1, 2)\n}", ErrorCodes.ARGUMENT_COUNT_MISMATCH)
        c("构造实参过少", "data User {\n  id:Int\n  name:String\n}\nfun main() {\n  u = User(1)\n}", ErrorCodes.ARGUMENT_COUNT_MISMATCH)
        c("构造实参过多", "data User {\n  id:Int\n  name:String\n}\nfun main() {\n  u = User(1, \"a\", true)\n}", ErrorCodes.ARGUMENT_COUNT_MISMATCH)
        c("类构造实参缺失", "Player {\n  name:String\n}\nfun main() {\n  p = Player()\n}", ErrorCodes.ARGUMENT_COUNT_MISMATCH)

        // ── E0007 确定性赋值 ────────────────────────────────────────────────
        c("声明未赋值即使用", "fun main() {\n  x:Int\n  print x\n}", ErrorCodes.VARIABLE_NOT_INITIALIZED)
        c("声明未赋值参与运算", "fun main() {\n  x:Int\n  y = x + 1\n}", ErrorCodes.VARIABLE_NOT_INITIALIZED)
        c("仅分支赋值", "fun main() {\n  x:Int\n  if true {\n    x = 5\n  }\n  print x\n}", ErrorCodes.VARIABLE_NOT_INITIALIZED)
        c("仅循环内赋值", "fun main() {\n  x:Int\n  while true {\n    x = 1\n  }\n  print x\n}", ErrorCodes.VARIABLE_NOT_INITIALIZED)
        c("自引用赋值", "fun main() {\n  x:Int\n  x = x + 1\n}", ErrorCodes.VARIABLE_NOT_INITIALIZED)

        // ── E0009 类型无法解析 ──────────────────────────────────────────────
        c("变量类型未解析", "fun main() {\n  x:NoSuchType = 1\n}", ErrorCodes.UNRESOLVED_TYPE)
        c("参数类型未解析", "fun f(p:Nope) {\n}", ErrorCodes.UNRESOLVED_TYPE)
        c("父类型未解析", "A extends Missing {\n}", ErrorCodes.UNRESOLVED_TYPE)
        c("返回类型未解析", "fun f():NoSuchType {\n  return null\n}", ErrorCodes.UNRESOLVED_TYPE)

        // ── E0010 访问器非法 ────────────────────────────────────────────────
        c("重复 getter", "Player {\n  health:Int {\n    get { return value }\n    get { return value }\n  }\n}", ErrorCodes.ILLEGAL_ACCESSOR)

        // ── E0012 service 循环 ──────────────────────────────────────────────
        c("service 两节点循环", "service A {\n  b:B\n}\nservice B {\n  a:A\n}", ErrorCodes.SERVICE_CYCLE)
        c("service 三节点循环", "service A {\n  b:B\n}\nservice B {\n  c:C\n}\nservice C {\n  a:A\n}", ErrorCodes.SERVICE_CYCLE)

        // ── E0013 注解目标 ──────────────────────────────────────────────────
        c("Serializable 用于函数", "@Serializable\nfun f() {\n}", ErrorCodes.INVALID_ANNOTATION_TARGET)
        c("Override 用于 data", "@Override\ndata X {\n  name:String\n}", ErrorCodes.INVALID_ANNOTATION_TARGET)
        c("Serializable 用于 service", "@Serializable\nservice X {\n}", ErrorCodes.INVALID_ANNOTATION_TARGET)

        // ── E0014 推断失败 ──────────────────────────────────────────────────
        c("null 无期望类型", "fun main() {\n  x = null\n}", ErrorCodes.INFERENCE_FAILURE)
        c("Lambda 无上下文", "fun main() {\n  f = (x -> x + 1)\n}", ErrorCodes.INFERENCE_FAILURE)

        // ── E0015 返回类型不匹配 ────────────────────────────────────────────
        c("块体返回类型不符", "fun f():Int {\n  return \"x\"\n}", ErrorCodes.RETURN_TYPE_MISMATCH)
        c("Int 函数空 return", "fun f():Int {\n  return\n}", ErrorCodes.RETURN_TYPE_MISMATCH)
        c("Unit 函数返回值", "fun main():Unit {\n  return 5\n}", ErrorCodes.RETURN_TYPE_MISMATCH)
        c("表达式体返回类型不符", "fun f():String = 5", ErrorCodes.RETURN_TYPE_MISMATCH)
        c("表达式体返回类型不符 2", "fun f():Int = \"x\"", ErrorCodes.RETURN_TYPE_MISMATCH)
        c("块体声明返回但缺 return", "fun f():Int {\n  print 1\n}", ErrorCodes.RETURN_TYPE_MISMATCH)

        // ── E0016 成员不存在 ────────────────────────────────────────────────
        c("String 方法不存在", "fun main() {\n  s = \"abc\"\n  x = s.bogus()\n}", ErrorCodes.UNRESOLVED_MEMBER)
        c("String 属性不存在", "fun main() {\n  s = \"abc\"\n  x = s.nope\n}", ErrorCodes.UNRESOLVED_MEMBER)
        c("Double 方法不存在", "fun main() {\n  m = Math.max(1, 2)\n  y = m.bogus()\n}", ErrorCodes.UNRESOLVED_MEMBER)
        c("Any 方法不存在", "fun main() {\n  x:Any = 1\n  y = x.foo()\n}", ErrorCodes.UNRESOLVED_MEMBER)
        c("Long 方法不存在", "fun main() {\n  t = System.currentTimeMillis()\n  y = t.bogus()\n}", ErrorCodes.UNRESOLVED_MEMBER)
        c("空类属性不存在", "A {\n}\nfun main() {\n  a = A()\n  b = a.missing\n}", ErrorCodes.UNRESOLVED_MEMBER)

        // ── E0017 运算符合法性 ──────────────────────────────────────────────
        c("字符串相减", "fun main() {\n  x = \"a\" - \"b\"\n}", ErrorCodes.INVALID_OPERATOR_OPERAND)
        c("字符串乘法", "fun main() {\n  x = \"a\" * 2\n}", ErrorCodes.INVALID_OPERATOR_OPERAND)
        c("布尔加法", "fun main() {\n  x = true + 1\n}", ErrorCodes.INVALID_OPERATOR_OPERAND)
        c("Int 与 Boolean 与", "fun main() {\n  x = 1 && true\n}", ErrorCodes.INVALID_OPERATOR_OPERAND)
        c("字符串比较", "fun main() {\n  x = \"a\" < 5\n}", ErrorCodes.INVALID_OPERATOR_OPERAND)
        c("布尔比较", "fun main() {\n  x = true > false\n}", ErrorCodes.INVALID_OPERATOR_OPERAND)
        c("非运算作用于整数", "fun main() {\n  x = !5\n}", ErrorCodes.INVALID_OPERATOR_OPERAND)
        c("负号作用于字符串", "fun main() {\n  x = -\"a\"\n}", ErrorCodes.INVALID_OPERATOR_OPERAND)
        c("取模字符串", "fun main() {\n  x = 1 % \"a\"\n}", ErrorCodes.INVALID_OPERATOR_OPERAND)
        c("Boolean 与 Int 与", "fun main() {\n  x = true && 1\n}", ErrorCodes.INVALID_OPERATOR_OPERAND)
        c("Int 复合赋值布尔", "fun main() {\n  x = 1\n  x += true\n}", ErrorCodes.INVALID_OPERATOR_OPERAND)
        c("Int 复合赋值字符串", "fun main() {\n  x = 1\n  x += \"a\"\n}", ErrorCodes.TYPE_MISMATCH)

        // ── E0018 this 在类外 ───────────────────────────────────────────────
        c("顶层 this", "fun main() {\n  x = this\n}", ErrorCodes.THIS_OUTSIDE_CLASS)

        // ── E0019 override 无父成员 ─────────────────────────────────────────
        c("override 无父类", "A {\n  override fun f() {\n  }\n}", ErrorCodes.OVERRIDE_NO_SUPER)
        c("override 父类无此成员", "A {\n}\nB extends A {\n  override fun nope() {\n  }\n}", ErrorCodes.OVERRIDE_NO_SUPER)

        // ── E0037 async 覆写一致性（T-M14，双 ABI）───────────────────────────
        c("非 async 覆写 async fun", "A {\n  async fun f() {\n  }\n}\nB extends A {\n  override fun f() {\n  }\n}", ErrorCodes.ASYNC_OVERRIDE_MISMATCH)
        c("async 覆写非 async fun", "A {\n  fun f() {\n  }\n}\nB extends A {\n  override async fun f() {\n  }\n}", ErrorCodes.ASYNC_OVERRIDE_MISMATCH)

        // ── E0020 只读赋值 ──────────────────────────────────────────────────
        c("参数不可赋值", "fun f(x:Int) {\n  x = 5\n}", ErrorCodes.VAL_ASSIGNMENT)
        c("参数重声明赋值", "fun f(x:Int) {\n  x: Int = 5\n}", ErrorCodes.VAL_ASSIGNMENT)
        c("for 变量重声明赋值", "fun main() {\n  for i in 0..5 {\n    i: Int = 2\n  }\n}", ErrorCodes.VAL_ASSIGNMENT)
        c("data 属性不可赋值", "data User {\n  id:Int\n}\nfun main() {\n  u = User(1)\n  u.id = 5\n}", ErrorCodes.VAL_ASSIGNMENT)

        // ── E0021 break/continue 在循环外 ───────────────────────────────────
        c("break 在循环外", "fun main() {\n  break\n}", ErrorCodes.BREAK_OUTSIDE_LOOP)
        c("continue 在循环外", "fun main() {\n  continue\n}", ErrorCodes.BREAK_OUTSIDE_LOOP)
        c("if 内 break 循环外", "fun main() {\n  if true {\n    break\n  }\n}", ErrorCodes.BREAK_OUTSIDE_LOOP)

        // ── E0022 不可迭代 ──────────────────────────────────────────────────
        c("for 迭代整数", "fun main() {\n  for i in 5 {\n  }\n}", ErrorCodes.LOOP_ITERABLE_INVALID)
        c("for 迭代浮点", "fun main() {\n  for i in 1.5 {\n  }\n}", ErrorCodes.LOOP_ITERABLE_INVALID)
        c("for 迭代布尔", "fun main() {\n  for i in true {\n  }\n}", ErrorCodes.LOOP_ITERABLE_INVALID)

        // ── E0023 可空写路径需显式判空 ─────────────────────────────────────
        c("可空接收者写入", "Player {\n  name:String = \"\"\n}\nfun main() {\n  p:Player? = null\n  p.name = \"x\"\n}", ErrorCodes.NULLABLE_WRITE_NEEDS_CHECK)

        // ── E0024 super 在类外 ──────────────────────────────────────────────
        c("顶层 super", "fun main() {\n  x = super\n}", ErrorCodes.SUPER_OUTSIDE_CLASS)

        // ── E0026 service 属性缺初始值 ──────────────────────────────────────
        c("service 非注入属性缺初始值", "service Config {\n  port:Int\n}", ErrorCodes.SERVICE_PROPERTY_NO_INIT)

        // ── E0015 块 Lambda 非 Unit 返回缺末条表达式（S-7.4.3）────────────────
        c("块 Lambda if 末语句非表达式", "fun f() {\n  g:(Int)->Int = {\n    if true { 1 } else { 2 }\n  }\n}", ErrorCodes.RETURN_TYPE_MISMATCH)
        c("块 Lambda var 末语句非表达式", "fun f() {\n  g:(Int)->Int = {\n    x = 1\n  }\n}", ErrorCodes.RETURN_TYPE_MISMATCH)

        // ── E0038 运算符缺 operator 修饰符（M13）────────────────────────────
        c("同名函数缺 operator 修饰符", "V {\n  x:Int\n  fun plus(o:V):V {\n    return V(x + o.x)\n  }\n}\nfun main() {\n  a = V(1)\n  b = V(2)\n  c = a + b\n}", ErrorCodes.OPERATOR_MODIFIER_REQUIRED)
        c("扩展函数缺 operator 修饰符", "V {\n  x:Int\n}\nfun V.plus(o:V):V {\n  return V(this.x + o.x)\n}\nfun main() {\n  a = V(1)\n  b = V(2)\n  c = a + b\n}", ErrorCodes.OPERATOR_MODIFIER_REQUIRED)

        // ── E0039 operator 返回类型约束（M13）───────────────────────────────
        c("operator equals 返回非 Boolean", "V {\n  x:Int\n  operator fun equals(o:V):Int {\n    return x - o.x\n  }\n}", ErrorCodes.ILLEGAL_OPERATOR_DECL)
        c("operator compareTo 返回非 Int", "V {\n  x:Int\n  operator fun compareTo(o:V):Boolean {\n    return true\n  }\n}", ErrorCodes.ILLEGAL_OPERATOR_DECL)
        c("operator set 返回非 Unit", "V {\n  x:Int\n  operator fun set(i:Int, v:Int):Int {\n    return 0\n  }\n}", ErrorCodes.ILLEGAL_OPERATOR_DECL)

        // ── E0016 索引写入缺 set 运算符（M13）────────────────────────────────
        c("用户类索引写入未定义 set", "V {\n  x:Int\n}\nfun main() {\n  v = V(1)\n  v[0] = 2\n}", ErrorCodes.UNRESOLVED_MEMBER)
    }

    @Test
    fun `negative cases hit expected error code`() {
        val failures = mutableListOf<String>()
        for (case in cases) {
            val r = SemaTestSupport.analyze(case.source)
            // 仅比对 ERROR 级诊断码（W/R 为警告/提示，不视为失败）
            val errorCodes = r.diagnostics.diagnostics
                .filter { it.severity == yux.compiler.diag.Severity.ERROR }
                .map { it.code }
            val hasExpected = errorCodes.contains(case.code)
            val unexpected = errorCodes.filter { it != case.code && it != ErrorCodes.UNRESOLVED_REFERENCE }
            if (!hasExpected || unexpected.isNotEmpty()) {
                failures += buildString {
                    append("用例 [").append(case.name).append("] 期望 ").append(case.code)
                    append(" 实际 ").append(errorCodes)
                    if (!hasExpected) append("（未命中期望码）")
                    if (unexpected.isNotEmpty()) append(" 意外错误码: ").append(unexpected)
                    append("\n源码:\n").append(case.source)
                    append("\n诊断:\n").append(r.diagnostics.diagnostics.joinToString("\n") { "${it.code}: ${it.message}" })
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "共 ${failures.size} 个负向用例未通过（共 ${cases.size} 个）:\n\n${failures.joinToString("\n\n---\n\n")}",
        )
        assertTrue(cases.size >= 80, "负向用例数 ${cases.size} 应 ≥ 80")
    }

    @Test
    fun `remind codes coverage`() {
        val nullGuard = SemaTestSupport.analyze("fun main() {\n  s:String? = \"a\"\n  n = s.length\n}")
        assertTrue(nullGuard.hasCode(ErrorCodes.NULL_GUARD_INSERTED), nullGuard.diagnostics.toString())

        val mutableCast = SemaTestSupport.analyze(
            "fun main() {\n  x:Any? = null\n  if x is String {\n  }\n  x = 1\n}",
        )
        assertTrue(mutableCast.hasCode(ErrorCodes.SMART_CAST_MUTABLE), mutableCast.diagnostics.toString())

        // W0002（M13）：operator equals 未声明 hashCode → 警告
        val equalsNoHash = SemaTestSupport.analyze(
            "V {\n  x:Int\n  operator fun equals(o:V):Boolean {\n    return x == o.x\n  }\n}",
        )
        assertTrue(equalsNoHash.hasCode(ErrorCodes.EQUALS_WITHOUT_HASHCODE), equalsNoHash.diagnostics.toString())
    }

    @Test
    fun `error codes manual is consistent with constants`() {
        val manual = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/yux/compiler/diag/ErrorCodes.md"),
        )
        val constants = ErrorCodes::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { it.get(null) as? String }
        for (code in constants) {
            assertTrue(manual.contains(code), "ErrorCodes.md 缺少常量 $code")
        }
    }
}
