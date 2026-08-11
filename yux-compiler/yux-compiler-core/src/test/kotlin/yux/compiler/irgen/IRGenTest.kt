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
    private fun ir(
        text: String,
        path: String = "main.yux",
    ): IrModule {
        val diags = DiagnosticSink()
        val parser = Parser(SourceFile(path, text), diags)
        val decls = CstToAst().convert(parser.parse())
        val analysis = SemanticAnalyzer().analyze(mapOf(path to decls), diags)
        check(!diags.hasErrors) { "不应有错误: ${diags.diagnostics}" }
        return IRGen(analysis).generate(mapOf(path to decls))
    }

    private fun methodBody(
        module: IrModule,
        className: String,
        methodName: String,
    ): List<IrStmt> {
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
        val hasNext =
            body.filterIsInstance<IrStmt.LocalAssign>().any {
                (it.value as? IrExpr.Invoke)?.target?.displayName?.endsWith("#hasNext") ==
                    true
            }
        val next =
            body.filterIsInstance<IrStmt.LocalAssign>().any {
                (it.value as? IrExpr.Invoke)?.target?.displayName?.endsWith("#next") ==
                    true
            }
        val branch = body.filterIsInstance<IrStmt.Branch>().size
        val goto = body.filterIsInstance<IrStmt.Goto>().size
        val labels = body.filterIsInstance<IrStmt.Label>().size
        assertTrue(hasNext, "应生成 iterator.hasNext(): $body")
        assertTrue(next, "应生成 iterator.next(): $body")
        assertEquals(1, branch)
        assertEquals(1, goto)
        assertEquals(3, labels, "迭代器展开应有 3 个标签（条件/体/结束）")
        // Range 的 iterator 归属 yux.core.Range（stdlib 未实现前的确定性占位）
        val iteratorCall =
            body
                .mapNotNull { (it as? IrStmt.LocalAssign)?.value as? IrExpr.Invoke }
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
        val module =
            ir(
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
        val getterCall =
            mainBody
                .filterIsInstance<IrStmt.Call>()
                .single()
                .args
                .mapNotNull { it as? IrExpr.Invoke }
                .single()
        assertEquals("getName", (getterCall.target as yux.compiler.ir.IrMethodRef).method.name)
    }

    @Test
    fun `data class constructor assigns properties in order`() {
        val module =
            ir(
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
    fun `expression position assignment evaluates receiver once before value`() {
        // 接收者副作用只求值一次，且先于右值（getReceiver() 不得重复执行）
        val module =
            ir(
                """
                Box {
                    n:Int
                }
                fun getReceiver():Box {
                    print "r"
                    return Box(1)
                }
                fun f() { print (getReceiver().n = 5) }
                """.trimIndent(),
            )
        val body = methodBody(module, "Main", "f")
        val receiverAssigns =
            body.filterIsInstance<IrStmt.LocalAssign>().filter {
                (it.value as? IrExpr.Invoke)?.target?.displayName?.contains("getReceiver") == true
            }
        assertEquals(1, receiverAssigns.size, "接收者只应求值一次: $body")
        // 接收者求值（LocalAssign）必须先于右值写入（setter Call）
        val receiverIndex = body.indexOfFirst { it == receiverAssigns.single() }
        val setterIndex = body.indexOfFirst { it is IrStmt.Call }
        assertTrue(receiverIndex < setterIndex, "接收者应先于右值求值: $body")
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
    fun `block lambda body emits return of last expression`() {
        val module = ir("fun f() { g:(Int)->Int = { it + 1 } }")
        val main = module.classNamed("Main")!!
        val lambda = main.methods.firstOrNull { it.isSynthetic && it.name.startsWith("lambda") }
        assertTrue(lambda != null, "应生成 lambda 合成方法: ${main.methods.map { it.name }}")
        // S-7.4.3：块 Lambda 的末条表达式语句 `it + 1` 作为返回值
        val ret = lambda!!.body.single() as IrStmt.Return
        val arith = ret.value as IrExpr.Arith
        assertEquals(ArithOp.ADD, arith.op)
        assertEquals(0, (arith.l as IrExpr.LocalRead).local.index, "参数 it 应为槽位 #0")
        assertEquals(IrExpr.Const(1), arith.r)
    }

    @Test
    fun `lambda capturing outer local does not crash`() {
        val module = ir("fun f() { x = 5\n g:(Int)->Int = v -> v + x\n print x }")
        val main = module.classNamed("Main")!!
        val lambda = main.methods.firstOrNull { it.isSynthetic && it.name.startsWith("lambda") }
        assertTrue(lambda != null, "应生成 lambda 合成方法: ${main.methods.map { it.name }}")
        // S-7.4.4 闭包：捕获 x 并入参数表（v, x）
        assertEquals(listOf("x", "v"), lambda!!.params.map { it.name })
        val body = methodBody(module, "Main", "f")
        val lambdaRef = body.mapNotNull { (it as? IrStmt.LocalAssign)?.value as? IrExpr.Lambda }.single()
        assertEquals(1, lambdaRef.captures.size)
    }

    @Test
    fun `block lambda capturing outer local`() {
        val module = ir("fun f() { x = 5\n g:(Int)->Int = { it + x } }")
        val main = module.classNamed("Main")!!
        val lambda = main.methods.firstOrNull { it.isSynthetic && it.name.startsWith("lambda") }
        assertTrue(lambda != null, "应生成 lambda 合成方法: ${main.methods.map { it.name }}")
        assertEquals(2, lambda!!.params.size, "捕获 x 后应有 (x, it) 两个参数: ${lambda.params}")
        assertTrue(lambda.body.single() is IrStmt.Return)
    }

    @Test
    fun `calling function type local produces fn invoke`() {
        val module = ir("fun f() { g:(Int)->Int = { it + 1 }\n h = g(3) }")
        val body = methodBody(module, "Main", "f")
        // h 的赋值右值为 FnInvoke(函数值 LocalRead, [Const(3)])
        val assign = body.filterIsInstance<IrStmt.LocalAssign>().last()
        val fnInvoke = assign.value as IrExpr.FnInvoke
        assertTrue(fnInvoke.fn is IrExpr.LocalRead, "函数值接收者应为 LocalRead: $fnInvoke")
        assertEquals(listOf(IrExpr.Const(3)), fnInvoke.args)
    }

    @Test
    fun `fn type local called inside lambda body is captured and invoked`() {
        // B3 × B2（P1-1 回归）：Lambda 体内调用函数类型局部——callee 须被捕获并入参数表
        val module = ir("fun f() { g:(Int)->Int = { it + 1 }\n h:(Int)->Int = v -> g(v) }")
        val main = module.classNamed("Main")!!
        val inner =
            main.methods.firstOrNull { it.name == "lambda\$1" }
                ?: error("缺少内层 lambda 合成方法: ${main.methods.map { it.name }}")
        // 捕获 g 并入参数表（v, g）
        assertEquals(listOf("g", "v"), inner.params.map { it.name }, "g 应作为捕获参数: ${inner.params}")
        // 体内调用为 FnInvoke(捕获 g 的 LocalRead, [参数 v])
        val ret = inner.body.single() as IrStmt.Return
        val fnInvoke = ret.value as IrExpr.FnInvoke
        assertTrue(fnInvoke.fn is IrExpr.LocalRead, "函数值接收者应为 LocalRead: $fnInvoke")
        assertEquals(1, fnInvoke.args.size)
    }

    @Test
    fun `fn type local called as statement emits eval fn invoke`() {
        // P1-2 回归：语句位置函数类型调用 → Eval(FnInvoke)，不得报"未解析的调用"
        val module = ir("fun f() { g:(Int)->Int = { it + 1 }\n g(3) }")
        val body = methodBody(module, "Main", "f")
        val eval = body.filterIsInstance<IrStmt.Eval>().single()
        val fnInvoke = eval.expr as IrExpr.FnInvoke
        assertTrue(fnInvoke.fn is IrExpr.LocalRead, "函数值接收者应为 LocalRead: $fnInvoke")
        assertEquals(listOf(IrExpr.Const(3)), fnInvoke.args)
    }

    @Test
    fun `statement side effect in comparison is preserved as eval`() {
        // P1-3 回归：`side() == 1` 语句的调用副作用不得被 isDiscardable 门丢弃
        val module = ir("fun f() { side() == 1 }\nfun side():Int { print \"x\"\n return 1 }")
        val body = methodBody(module, "Main", "f")
        val eval = body.filterIsInstance<IrStmt.Eval>().single()
        val cmp = eval.expr as IrExpr.Compare
        assertTrue(cmp.l is IrExpr.Invoke, "比较左操作数的调用应保留: $cmp")
    }

    @Test
    fun `lambda capture is not suppressed by loop var shadowing`() {
        // P1-4 回归：for 循环变量与嵌套 Lambda 参数仅在自身作用域绑定——
        // 离开作用域后不得压制同名外层变量的捕获（bound 须随作用域恢复）
        val module = ir("fun f() { i = 5\n g:(Int)->Int = { for i in 0..3 { print i }\n it + i } }")
        val main = module.classNamed("Main")!!
        val lambda =
            main.methods.firstOrNull { it.isSynthetic && it.name.startsWith("lambda") }
                ?: error("缺少 lambda 合成方法: ${main.methods.map { it.name }}")
        // 外层 i 须被捕获（参数表含 i）；循环体内 i 为循环局部
        assertEquals(listOf("i", "it"), lambda.params.map { it.name }, "外层 i 应被捕获: ${lambda.params}")
    }

    @Test
    fun `lambda capture is not suppressed by nested lambda param shadowing`() {
        // P1-4 回归：内层 Lambda 参数 w 不得泄漏到外层遍历——
        // 内层之后的 `it + w` 引用的外层 w 须被捕获
        val module = ir("fun f() { w = 5\n g:(Int)->Int = { h:(Int)->Int = z -> z + w\n it + w } }")
        val main = module.classNamed("Main")!!
        val lambda =
            main.methods.firstOrNull { it.isSynthetic && it.name.startsWith("lambda") }
                ?: error("缺少 lambda 合成方法: ${main.methods.map { it.name }}")
        assertEquals(listOf("w", "it"), lambda.params.map { it.name }, "外层 w 应被捕获: ${lambda.params}")
    }

    @Test
    fun `top level val getter honors custom accessor`() {
        // 自定义访问器回归：顶层 val 的 getter 体应为自定义体而非字段读取
        val module =
            ir(
                """
                max:Int {
                    get { return 10 }
                }
                fun main() {
                }
                """.trimIndent(),
            )
        val main = module.classNamed("Main")!!
        val prop = main.properties.single { it.name == "max" }
        assertTrue(prop.setter == null, "顶层 val 不应生成 setter")
        val getter = prop.getter ?: error("缺少 getter")
        val ret = getter.body.single() as IrStmt.Return
        assertEquals(IrExpr.Const(10), ret.value, "自定义 getter 体应返回 10 而非字段读取: ${getter.body}")
    }

    @Test
    fun `property initializer runs before ctor params`() {
        val module =
            ir(
                """
                Player {
                    name:String
                    health:Int = 20
                }
                fun main() {
                    p = Player("A", 20)
                }
                """.trimIndent(),
            )
        val ctor = module.classNamed("Player")!!.constructor!!
        val writes = ctor.body.filterIsInstance<IrStmt.FieldAccess>()
        // S-5.2.5：初始化器写 health 必须先于任何构造参数赋值
        assertTrue(writes.size >= 3, "初始化器 + 两个参数赋值: $writes")
        assertEquals("health", writes[0].field.name)
        assertEquals(IrExpr.Const(20), writes[0].value)
    }

    @Test
    fun `top level val has no setter and final field`() {
        val module =
            ir(
                """
                max:Int {
                    get { return 10 }
                }
                fun main() {
                }
                """.trimIndent(),
            )
        val main = module.classNamed("Main")!!
        val prop = main.properties.single { it.name == "max" }
        assertTrue(prop.setter == null, "顶层 val 不应生成 setter")
        assertTrue(prop.backingField!!.isFinal, "顶层 val 的 backing 字段应为 final")
    }

    @Test
    fun `short circuit with side-effecting right operand`() {
        val module = ir("fun f(a:Boolean) { x = a && sideEffect() }\nfun sideEffect():Boolean { print \"x\"\n return true }")
        val body = methodBody(module, "Main", "f")
        // 短路分支后才有右操作数调用：sideEffect 的 Invoke 必须在 rhsL 标签之后
        val branchIndex = body.indexOfFirst { it is IrStmt.Branch }
        assertTrue(branchIndex >= 0)
        val invokeIndex =
            body.indexOfFirst { stmt ->
                (stmt as? IrStmt.LocalAssign)?.value is IrExpr.Invoke
            }
        assertTrue(invokeIndex > branchIndex, "右操作数调用应在短路分支之后: $body")
    }

    @Test
    fun `compound property assignment reads getter and writes setter`() {
        val module =
            ir(
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

    @Test
    fun `object method toString falls back to jvm call on user class`() {
        // S-8.7.1 回退（M5-11 data 验收）：`u.toString` 无成员声明时走 Object 方法
        val module = ir("data User { id:Int }\nfun f(u:User) = u.toString")
        val body = methodBody(module, "Main", "f")
        val ret = body.single() as IrStmt.Return
        val invoke = ret.value as IrExpr.Invoke
        val call = invoke.target as IrJvmCall
        assertEquals("toString", call.name)
        assertEquals("User", call.owner)
        assertTrue(!call.static, "toString 应为实例调用")
        assertEquals(IrType.STRING, call.retType)
    }

    @Test
    fun `object method fallback works for equals and hashCode`() {
        val module = ir("data User { id:Int }\nfun f(u:User) { print u.equals(u)\n print u.hashCode }")
        val body = methodBody(module, "Main", "f")
        val invokes =
            body
                .filterIsInstance<IrStmt.Call>()
                .flatMap { it.args.filterIsInstance<IrExpr.Invoke>() }
                .map { it.target as IrJvmCall }
        assertEquals("equals", invokes[0].name)
        assertEquals(IrType.BOOLEAN, invokes[0].retType)
        assertEquals("hashCode", invokes[1].name)
        assertEquals(IrType.INT, invokes[1].retType)
    }

    @Test
    fun `service class carries yux service annotation`() {
        val module = ir("service Database {\n connect() { }\n}")
        val db = module.classNamed("Database")!!
        assertTrue(db.isService)
        assertEquals(listOf("yux.di.YuxService"), db.annotations.map { it.name })
    }

    @Test
    fun `missing default arg is synthesized at call site`() {
        // S-5.5.5（T-M12 缺陷修复）：`f(1)` 缺 `b` → 调用点物化实参 a + 生成默认值 42
        val module = ir("fun f(a:Int, b:Int = 42):Int { return a + b }\nfun main() { f(1) }")
        val body = methodBody(module, "Main", "main")
        val call = body.filterIsInstance<IrStmt.Call>().single { it.callee.displayName.endsWith("f") }
        assertEquals(2, call.args.size, "缺省实参应在调用点补齐: $call")
        // 实参 1 物化为局部，默认值 42 生成到局部后以 LocalRead 传递
        assertTrue(call.args.all { it is IrExpr.LocalRead }, "补齐后的实参应为局部读取: ${call.args}")
        val assigns = body.filterIsInstance<IrStmt.LocalAssign>()
        assertTrue(assigns.any { it.value == IrExpr.Const(42) }, "应存在默认值常量 42 的赋值: $assigns")
    }

    @Test
    fun `default value may reference earlier params`() {
        // S-5.5.5：`fun f(a, b = a * 2)` —— 已提供实参物化为同名局部，默认表达式按名解析
        val module = ir("fun f(a:Int, b:Int = a * 2):Int { return a + b }\nfun main() { f(3) }")
        val body = methodBody(module, "Main", "main")
        val call = body.filterIsInstance<IrStmt.Call>().single { it.callee.displayName.endsWith("f") }
        assertEquals(2, call.args.size)
        val assigns = body.filterIsInstance<IrStmt.LocalAssign>()
        val defaultAssign = assigns.first { (it.value as? IrExpr.Arith)?.op == ArithOp.MUL }
        val mul = defaultAssign.value as IrExpr.Arith
        assertEquals(IrExpr.Const(2), mul.r, "默认值 b = a * 2 应引用更早形参: $mul")
        assertTrue(mul.l is IrExpr.LocalRead)
    }

    @Test
    fun `member method default args are synthesized`() {
        // S-5.5.5 成员方法：`c.m(1)` 缺 `y` → 补齐默认值 7
        val module = ir("C { fun m(x:Int, y:Int = 7):Int { return x + y } }\nfun main() { c = C(); c.m(1) }")
        val body = methodBody(module, "Main", "main")
        val call = body.filterIsInstance<IrStmt.Call>().single { it.callee.displayName.endsWith("m") }
        assertEquals(2, call.args.size, "成员方法缺省实参应补齐: $call")
        assertTrue(call.args.all { it is IrExpr.LocalRead })
        assertTrue(body.filterIsInstance<IrStmt.LocalAssign>().any { it.value == IrExpr.Const(7) })
    }

    @Test
    fun `expression position call synthesizes defaults in invoke args`() {
        // genCall 路径（表达式位置）：`print f(1)` → Invoke 实参同样补齐
        val module = ir("fun f(a:Int, b:Int = 42):Int { return a + b }\nfun main() { print f(1) }")
        val body = methodBody(module, "Main", "main")
        val printCall = body.filterIsInstance<IrStmt.Call>().single { it.callee.displayName.endsWith("print") }
        val invoke = printCall.args.filterIsInstance<IrExpr.Invoke>().single { it.target.displayName.endsWith("f") }
        assertEquals(2, invoke.args.size, "表达式位置调用应补齐缺省实参: $invoke")
        assertTrue(invoke.args.all { it is IrExpr.LocalRead })
    }

    @Test
    fun `full args bypass default synthesis`() {
        // 实参齐全时不物化/不生成默认值（原有调用路径不变）
        val module = ir("fun f(a:Int, b:Int = 42):Int { return a + b }\nfun main() { f(1, 2) }")
        val body = methodBody(module, "Main", "main")
        val call = body.filterIsInstance<IrStmt.Call>().single { it.callee.displayName.endsWith("f") }
        assertEquals(2, call.args.size)
        assertTrue(
            body.filterIsInstance<IrStmt.LocalAssign>().none { it.value == IrExpr.Const(42) },
            "实参齐全时不应生成默认值赋值",
        )
    }
}
