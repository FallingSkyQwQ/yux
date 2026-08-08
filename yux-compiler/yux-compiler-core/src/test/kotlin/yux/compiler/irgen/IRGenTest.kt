package yux.compiler.irgen

import org.junit.jupiter.api.Test
import yux.compiler.diag.DiagnosticSink
import yux.compiler.ir.ArithOp
import yux.compiler.ir.IrClass
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrJvmCall
import yux.compiler.ir.IrModule
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType
import yux.compiler.parser.CstToAst
import yux.compiler.parser.Parser
import yux.compiler.sema.SemanticAnalyzer
import yux.compiler.source.SourceFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-M4-4 验收：AST → IR（if→Branch、for→迭代器展开、短路、模板、空守卫、属性映射）。
 */
class IRGenTest {

    /** 源码文本 → IrModule（解析 + 语义 + IRGen，不含优化器）。 */
    private fun ir(text: String, path: String = "main.yux"): IrModule {
        val diags = DiagnosticSink()
        val parser = Parser(SourceFile(path, text), diags)
        val decls = CstToAst().convert(parser.parse())
        val analysis = SemanticAnalyzer().analyze(mapOf(path to decls), diags)
        check(!diags.hasErrors) { "不应有错误: ${diags.diagnostics}" }
        return IRGen(analysis).generate(mapOf(path to decls))
    }

    private fun methodBody(module: IrModule, className: String, methodName: String): List<IrStmt> {
        val cls = module.classNamed(className) ?: error("缺少类 $className: ${module.allClasses.map { it.name }}")
        val method = cls.methodNamed(methodName) ?: error("缺少方法 $methodName: ${cls.methods.map { it.name }}")
        return method.body
    }

    @Test
    fun `if else lowers to branch and goto`() {
        val module = ir("fun f(x:Int) { if x > 0 { print \"p\" } else { print \"n\" } }")
        val body = methodBody(module, "Main", "f")
        val kinds = body.map { it::class.simpleName }
        assertEquals(
            listOf("Branch", "Label", "Call", "Goto", "Label", "Call", "Label"),
            kinds,
            "if/else 应下沉为 Branch+Label+Call+Goto 结构: $body",
        )
        assertTrue((body[0] as IrStmt.Branch).cond is IrExpr.Compare)
    }

    @Test
    fun `for loop lowers to iterator expansion`() {
        val module = ir("fun f() { for i in 0..10 { print i } }")
        val body = methodBody(module, "Main", "f")
        val hasNext = body.filterIsInstance<IrStmt.LocalAssign>().any { (it.value as? IrExpr.Invoke)?.target?.displayName?.endsWith("#hasNext") == true }
        val next = body.filterIsInstance<IrStmt.LocalAssign>().any { (it.value as? IrExpr.Invoke)?.target?.displayName?.endsWith("#next") == true }
        val branch = body.filterIsInstance<IrStmt.Branch>().size
        val goto = body.filterIsInstance<IrStmt.Goto>().size
        val labels = body.filterIsInstance<IrStmt.Label>().size
        assertTrue(hasNext, "应生成 iterator.hasNext(): $body")
        assertTrue(next, "应生成 iterator.next(): $body")
        assertEquals(1, branch)
        assertEquals(1, goto)
        assertEquals(3, labels, "迭代器展开应有 3 个标签（条件/体/结束）")
        // Range 的 iterator 归属 yux.core.Range（stdlib 未实现前的确定性占位）
        val iteratorCall = body.mapNotNull { (it as? IrStmt.LocalAssign)?.value as? IrExpr.Invoke }
            .firstOrNull { it.target.displayName.endsWith("#iterator") }
        assertTrue(iteratorCall != null, "应生成 iterable.iterator(): $body")
        assertEquals("yux.core.Range", (iteratorCall!!.target as IrJvmCall).owner)
    }

    @Test
    fun `while loop lowers to branch structure`() {
        val module = ir("fun f() { x = 0\n while x < 3 { x += 1 } }")
        val body = methodBody(module, "Main", "f")
        val labels = body.filterIsInstance<IrStmt.Label>().size
        val branch = body.filterIsInstance<IrStmt.Branch>().size
        val goto = body.filterIsInstance<IrStmt.Goto>().size
        assertEquals(3, labels)
        assertEquals(1, branch)
        assertEquals(1, goto)
    }

    @Test
    fun `logical and lowers to short circuit branches`() {
        val module = ir("fun f(a:Boolean, b:Boolean) = a && b")
        val body = methodBody(module, "Main", "f")
        val branch = body.filterIsInstance<IrStmt.Branch>().single()
        val assigns = body.filterIsInstance<IrStmt.LocalAssign>()
        assertEquals(2, assigns.size, "短路应产生 2 次临时赋值: $body")
        assertTrue(assigns[0].local == assigns[1].local, "两次赋值应写入同一临时变量")
        assertTrue((branch.cond as IrExpr.LocalRead).local == assigns[0].local)
    }

    @Test
    fun `string template builds parts`() {
        val module = ir("fun f(n:String) = \"Hi \$n\"")
        val body = methodBody(module, "Main", "f")
        val ret = body.single() as IrStmt.Return
        val template = ret.value as IrExpr.StringTemplate
        assertEquals(2, template.parts.size)
        assertEquals(IrExpr.Const("Hi "), template.parts[0])
        assertTrue(template.parts[1] is IrExpr.LocalRead)
    }

    @Test
    fun `nullable member access is wrapped in null guard`() {
        val module = ir("fun f(s:String?) { print s.length }")
        val body = methodBody(module, "Main", "f")
        val call = body.filterIsInstance<IrStmt.Call>().single()
        val arg = call.args.single()
        assertTrue(arg is IrExpr.NullGuard, "可空接收者读取应被空守卫包裹: $arg")
        val inner = (arg as IrExpr.NullGuard).expr
        assertTrue(inner is IrExpr.Invoke, "守卫内应为 JVM 属性调用: $inner")
    }

    @Test
    fun `builtin print maps to corelib static call`() {
        val module = ir("fun main() { print \"Hi\" }")
        val body = methodBody(module, "Main", "main")
        val call = body.filterIsInstance<IrStmt.Call>().single()
        assertEquals("yux.core.CoreLib.print", call.callee.displayName)
    }

    @Test
    fun `top level function is static in file class`() {
        val module = ir("fun main() { }")
        val main = module.classNamed("Main")!!
        assertTrue(main.isFileClass)
        val fn = main.methodNamed("main")!!
        assertTrue(fn.isStatic)
        assertEquals(IrType.Void, fn.returnType)
    }

    @Test
    fun `class property maps to backing field getter and setter`() {
        val module = ir(
            """
            Player {
                name:String
            }
            fun main() {
                p = Player("Alex")
                print p.name
            }
            """.trimIndent(),
        )
        val player = module.classNamed("Player")!!
        val prop = player.properties.single()
        assertEquals("name", prop.name)
        assertEquals("getName", prop.getter!!.name)
        assertEquals("setName", prop.setter!!.name)
        val getterBody = prop.getter!!.body
        val ret = getterBody.single() as IrStmt.Return
        val read = ret.value as IrExpr.FieldRead
        assertTrue(read.receiver is IrExpr.This)
        assertEquals(prop.backingField, read.field)
        // 属性读取走 getter 调用（S-5.2/02-§9.1：自定义访问器语义）
        val main = module.classNamed("Main")!!
        val mainBody = main.methodNamed("main")!!.body
        val getterCall = mainBody.filterIsInstance<IrStmt.Call>().single().args
            .mapNotNull { it as? IrExpr.Invoke }.single()
        assertEquals("getName", (getterCall.target as yux.compiler.ir.IrMethodRef).method.name)
    }

    @Test
    fun `data class constructor assigns properties in order`() {
        val module = ir(
            """
            data User {
                id:Int
                name:String
            }
            fun main() {
                u = User(1, "Steve")
                print u.id
            }
            """.trimIndent(),
        )
        val user = module.classNamed("User")!!
        assertTrue(user.isData)
        val ctor = user.constructor!!
        assertTrue(ctor.isConstructor)
        assertEquals(listOf("id", "name"), ctor.params.map { it.name })
        val writes = ctor.body.filterIsInstance<IrStmt.FieldAccess>()
        assertEquals(2, writes.size)
        assertTrue(writes.all { it.write })
        assertEquals(listOf("id", "name"), writes.map { it.field.name })
        // 调用处为 New 表达式
        val mainBody = methodBody(module, "Main", "main")
        val newExpr = mainBody.mapNotNull { (it as? IrStmt.LocalAssign)?.value as? IrExpr.New }.single()
        assertEquals("User", newExpr.type.render())
        assertEquals(2, newExpr.args.size)
    }

    @Test
    fun `jvm interop call keeps owner and name`() {
        val module = ir("fun f() { t = System.currentTimeMillis() }")
        val body = methodBody(module, "Main", "f")
        val invoke = body.mapNotNull { (it as? IrStmt.LocalAssign)?.value as? IrExpr.Invoke }.single()
        val target = invoke.target as IrJvmCall
        assertTrue(target.static)
        assertEquals("java.lang.System", target.owner)
        assertEquals("currentTimeMillis", target.name)
    }

    @Test
    fun `arithmetic maps to arith with promotion`() {
        val module = ir("fun f(a:Int, b:Int) = a + b")
        val body = methodBody(module, "Main", "f")
        val ret = body.single() as IrStmt.Return
        val arith = ret.value as IrExpr.Arith
        assertEquals(ArithOp.ADD, arith.op)
        assertTrue(arith.l is IrExpr.LocalRead && arith.r is IrExpr.LocalRead)
    }

    @Test
    fun `short circuit inside try body stays inside try`() {
        // 回归：短路/表达式级发射必须落在 try 子块内，不得被提升到方法体顶层
        val module = ir("fun f(a:Boolean) { try { if a && a { print \"x\" } } catch e {} }")
        val body = methodBody(module, "Main", "f")
        val tryStmt = body.filterIsInstance<IrStmt.Try>().single()
        // try 子块内应有短路产生的 LocalAssign 与 Branch
        val tryBody = tryStmt.body
        assertTrue(tryBody.filterIsInstance<IrStmt.LocalAssign>().size >= 2, "短路赋值应在 try 内: $tryBody")
        assertTrue(tryBody.filterIsInstance<IrStmt.Branch>().isNotEmpty(), "短路分支应在 try 内: $tryBody")
        // 方法体顶层不应有提升出去的短路语句
        assertTrue(body.none { it is IrStmt.Branch }, "短路分支不得提升到方法体顶层: $body")
    }

    @Test
    fun `literal decoding produces typed constants`() {
        val module = ir("fun f() { a = 0x1F\n b = 1_000L\n c = 3.14F\n d = '\\n'\n e = \"x\\ty\" }")
        val body = methodBody(module, "Main", "f")
        val values = body.filterIsInstance<IrStmt.LocalAssign>().map { (it.value as IrExpr.Const).value }
        assertEquals(listOf(31, 1000L, 3.14f, '\n', "x\ty"), values)
    }

    @Test
    fun `lambda lowers to synthetic method`() {
        val module = ir("fun f() { g:(Int)->Int = { it + 1 } }")
        // 合成方法 lambda$0 存在于 owner 类且含参数 it
        val main = module.classNamed("Main")!!
        val lambda = main.methods.firstOrNull { it.isSynthetic && it.name.startsWith("lambda") }
        assertTrue(lambda != null, "应生成 lambda 合成方法: ${main.methods.map { it.name }}")
        assertEquals("it", lambda!!.params.single().name)
        assertEquals(IrType.INT, lambda.returnType)
        // 调用处为 Lambda 引用
        val body = methodBody(module, "Main", "f")
        val lambdaRef = body.mapNotNull { (it as? IrStmt.LocalAssign)?.value as? IrExpr.Lambda }.single()
        assertEquals(lambda, lambdaRef.target.method)
    }

    @Test
    fun `short circuit with side-effecting right operand`() {
        val module = ir("fun f(a:Boolean) { x = a && sideEffect() }\nfun sideEffect():Boolean { print \"x\"\n return true }")
        val body = methodBody(module, "Main", "f")
        // 短路分支后才有右操作数调用：sideEffect 的 Invoke 必须在 rhsL 标签之后
        val branchIndex = body.indexOfFirst { it is IrStmt.Branch }
        assertTrue(branchIndex >= 0)
        val invokeIndex = body.indexOfFirst { stmt ->
            (stmt as? IrStmt.LocalAssign)?.value is IrExpr.Invoke
        }
        assertTrue(invokeIndex > branchIndex, "右操作数调用应在短路分支之后: $body")
    }

    @Test
    fun `compound property assignment reads getter and writes setter`() {
        val module = ir(
            """
            Counter {
                n:Int
            }
            fun main() {
                c = Counter(0)
                c.n += 5
            }
            """.trimIndent(),
        )
        val mainBody = methodBody(module, "Main", "main")
        val calls = mainBody.filterIsInstance<IrStmt.Call>()
        // 复合赋值：getter 读取（含 op）+ setter 写入
        val setterCall = calls.last()
        assertEquals("setN", (setterCall.callee as yux.compiler.ir.IrMethodRef).method.name)
        val writeValue = setterCall.args.single() as IrExpr.Arith
        assertEquals(ArithOp.ADD, writeValue.op)
        val readSide = writeValue.l as IrExpr.Invoke
        assertEquals("getN", (readSide.target as yux.compiler.ir.IrMethodRef).method.name)
    }

    @Test
    fun `expression position assignment returns assigned value`() {
        val module = ir("fun f() { y = 0\n print (y = 5) }")
        val body = methodBody(module, "Main", "f")
        val call = body.filterIsInstance<IrStmt.Call>().single()
        // print 实参为赋值表达式的值：写入 5 并返回该变量读取
        val arg = call.args.single()
        assertTrue(arg is IrExpr.LocalRead, "赋值表达式应返回变量读取: $arg")
        val assign = body.filterIsInstance<IrStmt.LocalAssign>().last()
        assertEquals(IrExpr.Const(5), assign.value)
        assertEquals(assign.local, (arg as IrExpr.LocalRead).local)
    }
}
