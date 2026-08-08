package yux.compiler.irgen

import yux.compiler.ast.YxAs
import yux.compiler.ast.YxBinary
import yux.compiler.ast.YxBlockLambda
import yux.compiler.ast.YxBoolLiteral
import yux.compiler.ast.YxCall
import yux.compiler.ast.YxCharLiteral
import yux.compiler.ast.YxExpr
import yux.compiler.ast.YxFloatLiteral
import yux.compiler.ast.YxIdentifier
import yux.compiler.ast.YxIntLiteral
import yux.compiler.ast.YxIs
import yux.compiler.ast.YxLambda
import yux.compiler.ast.YxMemberAccess
import yux.compiler.ast.YxNullLiteral
import yux.compiler.ast.YxNullable
import yux.compiler.ast.YxParen
import yux.compiler.ast.YxRange
import yux.compiler.ast.YxRawStringLiteral
import yux.compiler.ast.YxStringLiteral
import yux.compiler.ast.YxStringTemplate
import yux.compiler.ast.YxStringText
import yux.compiler.ast.YxSuper
import yux.compiler.ast.YxThis
import yux.compiler.ast.YxThrow
import yux.compiler.ast.YxTypeCall
import yux.compiler.ast.YxTypeReference
import yux.compiler.ast.YxUnary
import yux.compiler.ir.ArithOp
import yux.compiler.ir.CompareOp
import yux.compiler.ir.IrCallable
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrField
import yux.compiler.ir.IrJvmCall
import yux.compiler.ir.IrMethodRef
import yux.compiler.ir.IrParam
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType
import yux.compiler.sema.FileScope
import yux.compiler.sema.FunctionSymbol
import yux.compiler.sema.ParameterSymbol
import yux.compiler.sema.PropertySymbol
import yux.compiler.sema.SemaType
import yux.compiler.sema.VariableSymbol
import yux.compiler.sema.YxClassSymbol

/**
 * 表达式下沉（T-M4-4，02-§8.2）：字面量解码（含转义）、调用/成员访问解析
 * （Yux 方法引用 + JVM 互操作 + 空守卫包裹）、`&&`/`||` 短路、Lambda 合成方法、
 * 字符串模板。
 */
class ExprGen(
    private val irGen: IRGen,
    private val resolver: JvmCallResolver,
) {

    fun gen(expr: YxExpr, g: MethodGen, fileScope: FileScope): IrExpr = when (expr) {
        is YxIntLiteral -> IrExpr.Const(decodeInt(expr.text))
        is YxFloatLiteral -> IrExpr.Const(decodeFloat(expr.text))
        is YxCharLiteral -> IrExpr.Const(decodeChar(expr.text))
        is YxStringLiteral -> IrExpr.Const(decodeString(expr.text))
        is YxRawStringLiteral -> IrExpr.Const(expr.text.removePrefix("\"\"\"").removeSuffix("\"\"\""))
        is YxStringText -> IrExpr.Const(expr.text)
        is YxBoolLiteral -> IrExpr.Const(expr.value)
        is YxNullLiteral -> IrExpr.Const(null)
        is YxIdentifier -> genIdentifier(expr, g)
        is YxThis -> IrExpr.This
        is YxSuper -> IrExpr.This // M4 简化：super 视为 this（S-8.7 继承语义后置）
        is YxParen -> gen(expr.expr, g, fileScope)
        is YxNullable -> gen(expr.expr, g, fileScope)
        is YxTypeReference -> error("IRGen: 类型引用只能作为静态成员接收者: ${expr.type}")
        is YxMemberAccess -> genMemberAccess(expr, g, fileScope)
        is YxCall -> genCall(expr, g, fileScope)
        is YxTypeCall -> IrExpr.New(
            TypeBridge.toIr(irGen.exprTypes[expr] ?: SemaType.ErrorT),
            expr.args.map { gen(it, g, fileScope) },
        )
        is YxBinary -> genBinary(expr, g, fileScope)
        is YxUnary -> genUnary(expr, g, fileScope)
        is YxLambda -> genLambda(expr, g, fileScope)
        is YxBlockLambda -> genBlockLambda(expr, g, fileScope)
        is YxStringTemplate -> IrExpr.StringTemplate(
            expr.parts.map { if (it is YxStringText) IrExpr.Const(it.text) else gen(it, g, fileScope) },
        )
        is YxIs -> IrExpr.IsType(gen(expr.expr, g, fileScope), irGen.resolveIrType(expr.type, fileScope))
        is YxAs -> IrExpr.Convert(gen(expr.expr, g, fileScope), irGen.resolveIrType(expr.type, fileScope))
        is YxThrow -> error("IRGen: throw 仅支持语句位置（M4）")
        is YxRange -> IrExpr.New(
            IrType.RANGE,
            listOf(gen(expr.from, g, fileScope), gen(expr.to, g, fileScope)),
        )
        is yux.compiler.ast.YxAssign -> genAssignValue(expr, g, fileScope)
    }

    private fun genIdentifier(expr: YxIdentifier, g: MethodGen): IrExpr {
        val sym = irGen.resolvedRefs[expr]
        return when (sym) {
            is VariableSymbol, is ParameterSymbol -> {
                val local = g.lookupLocal(sym.name)
                    ?: error("IRGen: 局部变量未登记: ${sym.name}")
                IrExpr.LocalRead(local)
            }
            is PropertySymbol -> {
                // 隐式 this 属性读取走 getter（S-5.2/02-§9.1：自定义访问器语义）
                val getter = irGen.propertyAccessors[sym]?.getter
                    ?: error("IRGen: 属性 getter 未登记: ${sym.name}")
                IrExpr.Invoke(IrMethodRef(getter), IrExpr.This, emptyList())
            }
            is FunctionSymbol -> {
                val method = irGen.functionMethods[sym]
                    ?: error("IRGen: 函数未登记: ${sym.name}")
                IrExpr.Lambda(IrMethodRef(method))
            }
            else -> error("IRGen: 未解析的标识符: ${expr.name}")
        }
    }

    private fun genMemberAccess(expr: YxMemberAccess, g: MethodGen, fileScope: FileScope): IrExpr {
        val receiverType = SemaType.resolveVar(irGen.exprTypes[expr.receiver] ?: SemaType.ErrorT)
        val result: IrExpr
        if (expr.receiver is YxTypeReference) {
            // 静态成员：System.currentTimeMillis → Invoke(静态)
            val static = resolver.resolveStaticMethod(receiverType, expr.name)
            if (static != null) {
                result = IrExpr.Invoke(static, null, emptyList())
            } else {
                val field = resolver.resolveStaticField(receiverType, expr.name)
                result = field?.let { IrExpr.FieldRead(null, it) }
                    ?: error("IRGen: 静态成员不存在: ${expr.name}")
            }
        } else {
            val receiver = gen(expr.receiver, g, fileScope)
            result = resolveMemberExpr(receiver, receiverType, expr.name, emptyList())
                ?: error("IRGen: 成员不存在: ${expr.name} on ${receiverType.render()}")
        }
        return if (expr in irGen.guardSet) IrExpr.NullGuard(result) else result
    }

    /** 成员解析：Yux 属性→getter 调用；Yux 函数/JVM 方法→调用。 */
    private fun resolveMemberExpr(receiver: IrExpr, rt: SemaType, name: String, args: List<IrExpr>): IrExpr? = when {
        rt is SemaType.Declared && rt.symbol is YxClassSymbol -> {
            val sym = rt.symbol as YxClassSymbol
            sym.property(name)?.let { prop ->
                irGen.propertyAccessors[prop]?.getter?.let { IrExpr.Invoke(IrMethodRef(it), receiver, emptyList()) }
            } ?: sym.functionsNamed(name).firstOrNull()?.let { fn ->
                irGen.functionMethods[fn]?.let { IrExpr.Invoke(IrMethodRef(it), receiver, args) }
            }
        }
        else -> resolver.resolveInstanceMethod(rt, name)?.let { IrExpr.Invoke(it, receiver, args) }
    }

    private fun genCall(call: YxCall, g: MethodGen, fileScope: FileScope): IrExpr {
        val args = call.args.map { gen(it, g, fileScope) }
        return when (val callee = call.callee) {
            is YxIdentifier -> {
                val sym = irGen.resolvedRefs[callee]
                when (sym) {
                    is FunctionSymbol -> {
                        val target: IrCallable = irGen.functionMethods[sym]?.let { IrMethodRef(it) }
                            ?: builtinCall(sym)
                        IrExpr.Invoke(target, null, args)
                    }
                    else -> error("IRGen: 未解析的调用: ${callee.name}")
                }
            }
            is YxMemberAccess -> genMemberCall(callee, args, g, fileScope)
            else -> error("IRGen: 不支持的调用目标: ${callee::class.simpleName}")
        }
    }

    private fun genMemberCall(
        callee: YxMemberAccess,
        args: List<IrExpr>,
        g: MethodGen,
        fileScope: FileScope,
    ): IrExpr {
        val receiverType = SemaType.resolveVar(irGen.exprTypes[callee.receiver] ?: SemaType.ErrorT)
        val result: IrExpr
        if (callee.receiver is YxTypeReference) {
            val static = resolver.resolveStaticMethod(receiverType, callee.name)
                ?: error("IRGen: 静态方法不存在: ${callee.name}")
            result = IrExpr.Invoke(static, null, args)
        } else {
            val receiver = gen(callee.receiver, g, fileScope)
            result = resolveMemberExpr(receiver, receiverType, callee.name, args)
                ?: error("IRGen: 成员方法不存在: ${callee.name} on ${receiverType.render()}")
        }
        return if (callee in irGen.guardSet) IrExpr.NullGuard(result) else result
    }

    /** 内置函数（print/println/serialize，M3 注册的 decl==null 函数）→ yux.core.CoreLib。 */
    private fun builtinCall(sym: FunctionSymbol): IrCallable = IrJvmCall(
        name = sym.name,
        owner = "yux.core.CoreLib",
        static = true,
        params = sym.params.map { TypeBridge.toIr(it.type) },
        retType = TypeBridge.toIr(sym.returnType ?: SemaType.UnitT),
    )

    private fun genBinary(expr: YxBinary, g: MethodGen, fileScope: FileScope): IrExpr = when (expr.op) {
        "&&", "||" -> genShortCircuit(expr, g, fileScope)
        "+", "-", "*", "/", "%" -> {
            val op = when (expr.op) {
                "+" -> ArithOp.ADD
                "-" -> ArithOp.SUB
                "*" -> ArithOp.MUL
                "/" -> ArithOp.DIV
                else -> ArithOp.MOD
            }
            IrExpr.Arith(op, gen(expr.left, g, fileScope), gen(expr.right, g, fileScope))
        }
        "<", "<=", ">", ">=", "==", "!=" -> {
            val op = when (expr.op) {
                "<" -> CompareOp.LT
                "<=" -> CompareOp.LE
                ">" -> CompareOp.GT
                ">=" -> CompareOp.GE
                "==" -> CompareOp.EQ
                else -> CompareOp.NE
            }
            IrExpr.Compare(op, gen(expr.left, g, fileScope), gen(expr.right, g, fileScope))
        }
        ".." -> IrExpr.New(IrType.RANGE, listOf(gen(expr.left, g, fileScope), gen(expr.right, g, fileScope)))
        else -> error("IRGen: 未知运算符: ${expr.op}")
    }

    /** `&&`/`||` 短路：临时变量 + 条件跳转（结果落在同一临时变量）。 */
    private fun genShortCircuit(expr: YxBinary, g: MethodGen, fileScope: FileScope): IrExpr {
        val tmp = g.newLocal("tmp", IrType.BOOLEAN)
        val rhsL = g.newLabel()
        val endL = g.newLabel()
        g.emit(IrStmt.LocalAssign(tmp, gen(expr.left, g, fileScope)))
        val then = gen(expr.right, g, fileScope)
        if (expr.op == "&&") {
            g.emit(IrStmt.Branch(IrExpr.LocalRead(tmp), rhsL, endL))
        } else {
            g.emit(IrStmt.Branch(IrExpr.LocalRead(tmp), endL, rhsL))
        }
        g.emit(IrStmt.Label(rhsL))
        g.emit(IrStmt.LocalAssign(tmp, then))
        g.emit(IrStmt.Label(endL))
        return IrExpr.LocalRead(tmp)
    }

    private fun genUnary(expr: YxUnary, g: MethodGen, fileScope: FileScope): IrExpr = when (expr.op) {
        "!" -> IrExpr.Not(gen(expr.operand, g, fileScope))
        "-" -> IrExpr.Neg(gen(expr.operand, g, fileScope))
        else -> error("IRGen: 未知一元运算符: ${expr.op}")
    }

    /** 箭头 Lambda：合成方法 `lambda$n`，体为 `Return(gen(body))`。 */
    private fun genLambda(expr: YxLambda, g: MethodGen, fileScope: FileScope): IrExpr {
        val fnType = irGen.exprTypes[expr] as? SemaType.Function
            ?: error("IRGen: Lambda 缺少函数类型上下文: $expr")
        val method = newLambdaMethod(expr, fnType, g)
        val lambdaG = MethodGen(method, g.owner)
        lambdaG.enterScope()
        lambdaG.emit(IrStmt.Return(gen(expr.body, lambdaG, fileScope)))
        lambdaG.exitScope()
        return IrExpr.Lambda(IrMethodRef(method))
    }

    /** 块 Lambda（隐式 `it`）：合成方法体为块语句序列。 */
    private fun genBlockLambda(expr: YxBlockLambda, g: MethodGen, fileScope: FileScope): IrExpr {
        val fnType = irGen.exprTypes[expr] as? SemaType.Function
            ?: error("IRGen: 块 Lambda 缺少函数类型上下文: $expr")
        val method = newLambdaMethod(expr, fnType, g)
        val lambdaG = MethodGen(method, g.owner)
        lambdaG.enterScope()
        expr.body.statements.forEach { irGen.genStmt(it, lambdaG, fileScope) }
        lambdaG.exitScope()
        return IrExpr.Lambda(IrMethodRef(method))
    }

    private fun newLambdaMethod(expr: YxExpr, fnType: SemaType.Function, g: MethodGen): yux.compiler.ir.IrMethod {
        val names = when (expr) {
            is YxLambda -> expr.params
            is YxBlockLambda -> listOf("it")
            else -> emptyList()
        }
        val params = names.mapIndexed { i, name ->
            IrParam(name, TypeBridge.toIr(fnType.params.getOrElse(i) { SemaType.ErrorT }))
        }
        val method = irGen.newLambdaMethod(
            name = irGen.nextLambdaName(),
            params = params,
            returnType = TypeBridge.toIr(fnType.ret),
            isStatic = g.method.isStatic,
            owner = g.owner,
        )
        return method
    }

    /** 表达式位置赋值：发射写入语句后返回写入值。 */
    private fun genAssignValue(expr: yux.compiler.ast.YxAssign, g: MethodGen, fileScope: FileScope): IrExpr {
        val value = gen(expr.value, g, fileScope)
        when (val t = expr.target) {
            is YxIdentifier -> {
                val sym = irGen.resolvedRefs[t]
                if (sym is VariableSymbol) {
                    val local = g.lookupLocal(sym.name)
                    if (local != null) {
                        g.emit(IrStmt.LocalAssign(local, value))
                        return IrExpr.LocalRead(local)
                    }
                }
            }
            is YxMemberAccess -> {
                val receiverType = SemaType.resolveVar(irGen.exprTypes[t.receiver] ?: SemaType.ErrorT)
                val receiver = if (t.receiver is YxTypeReference) null else gen(t.receiver, g, fileScope)
                if (receiverType is SemaType.Declared && receiverType.symbol is YxClassSymbol) {
                    val prop = (receiverType.symbol as YxClassSymbol).property(t.name)
                    val setter = prop?.let { irGen.propertyAccessors[it]?.setter }
                    if (setter != null) {
                        g.emit(IrStmt.Call(IrMethodRef(setter), receiver, listOf(value), IrType.Void))
                        return IrExpr.Invoke(IrMethodRef(setter), receiver, emptyList())
                    }
                }
            }
            else -> Unit
        }
        return value
    }

    // ── 字面量解码（01-§2.3/§2.5；词法器已验证合法转义）────────────────────────

    private fun decodeInt(text: String): Any {
        val clean = text.replace("_", "")
        val long = clean.endsWith("L") || clean.endsWith("l")
        val digits = if (long) clean.dropLast(1) else clean
        val value = when {
            digits.startsWith("0x") || digits.startsWith("0X") -> digits.substring(2).toLong(16)
            digits.startsWith("0b") || digits.startsWith("0B") -> digits.substring(2).toLong(2)
            else -> digits.toLong()
        }
        return if (long) value else value.toInt()
    }

    private fun decodeFloat(text: String): Any {
        val clean = text.replace("_", "")
        return when (clean.lastOrNull()) {
            'f', 'F' -> clean.dropLast(1).toFloat()
            'd', 'D' -> clean.dropLast(1).toDouble()
            else -> clean.toDouble()
        }
    }

    private fun decodeChar(text: String): Char {
        val inner = text.substring(1, text.length - 1)
        return decodeEscapes(inner).single()
    }

    private fun decodeString(text: String): String =
        decodeEscapes(text.substring(1, text.length - 1))

    private fun decodeEscapes(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                append(c)
                i++
                continue
            }
            val next = s[i + 1]
            when (next) {
                'n' -> { append('\n'); i += 2 }
                't' -> { append('\t'); i += 2 }
                'r' -> { append('\r'); i += 2 }
                'b' -> { append('\b'); i += 2 }
                'f' -> { append('\u000C'); i += 2 }
                '\\' -> { append('\\'); i += 2 }
                '\'' -> { append('\''); i += 2 }
                '"' -> { append('"'); i += 2 }
                '$' -> { append('$'); i += 2 }
                '0' -> { append('\u0000'); i += 2 }
                'u' -> {
                    if (i + 5 < s.length) {
                        append(s.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 6
                    } else {
                        append('u')
                        i += 1
                    }
                }
                else -> { append('\\'); i++ }
            }
        }
    }
}
