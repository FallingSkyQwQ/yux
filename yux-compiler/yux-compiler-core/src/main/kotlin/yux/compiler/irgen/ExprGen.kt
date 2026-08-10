package yux.compiler.irgen

import yux.compiler.ast.YxAs
import yux.compiler.ast.YxAsyncBlock
import yux.compiler.ast.YxBinary
import yux.compiler.ast.YxBlock
import yux.compiler.ast.YxBlockLambda
import yux.compiler.ast.YxBoolLiteral
import yux.compiler.ast.YxBreak
import yux.compiler.ast.YxCall
import yux.compiler.ast.YxCharLiteral
import yux.compiler.ast.YxContinue
import yux.compiler.ast.YxElseCondition
import yux.compiler.ast.YxExprCondition
import yux.compiler.ast.YxExpr
import yux.compiler.ast.YxExprStmt
import yux.compiler.ast.YxFloatLiteral
import yux.compiler.ast.YxFor
import yux.compiler.ast.YxIdentifier
import yux.compiler.ast.YxIf
import yux.compiler.ast.YxIfExpr
import yux.compiler.ast.YxIndexExpr
import yux.compiler.ast.YxIntLiteral
import yux.compiler.ast.YxIs
import yux.compiler.ast.YxIsCondition
import yux.compiler.ast.YxLambda
import yux.compiler.ast.YxMemberAccess
import yux.compiler.ast.YxNode
import yux.compiler.ast.YxNullLiteral
import yux.compiler.ast.YxNullable
import yux.compiler.ast.YxParallelBlock
import yux.compiler.ast.YxParen
import yux.compiler.ast.YxRange
import yux.compiler.ast.YxRawStringLiteral
import yux.compiler.ast.YxReturn
import yux.compiler.ast.YxStringLiteral
import yux.compiler.ast.YxStringTemplate
import yux.compiler.ast.YxStringText
import yux.compiler.ast.YxSuper
import yux.compiler.ast.YxThis
import yux.compiler.ast.YxThrow
import yux.compiler.ast.YxTry
import yux.compiler.ast.YxTypeCall
import yux.compiler.ast.YxTypeReference
import yux.compiler.ast.YxUnary
import yux.compiler.ast.YxUnsafeBlock
import yux.compiler.ast.YxVarDecl
import yux.compiler.ast.YxWhen
import yux.compiler.ast.YxWhenExpr
import yux.compiler.ast.YxWhenExprBranch
import yux.compiler.ast.YxWhile
import yux.compiler.ir.ArithOp
import yux.compiler.ir.CompareOp
import yux.compiler.ir.IrCallable
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrField
import yux.compiler.ir.IrJvmCall
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrMethodRef
import yux.compiler.ir.IrParam
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType
import yux.compiler.sema.BuiltinFunctions
import yux.compiler.sema.FileScope
import yux.compiler.sema.FunctionSymbol
import yux.compiler.sema.JvmClassSymbol
import yux.compiler.sema.ParameterSymbol
import yux.compiler.sema.PropertySymbol
import yux.compiler.sema.SemaType
import yux.compiler.sema.Symbol
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
        is YxThis -> {
            // 扩展函数（M9）：`this` 为 receiver 局部；普通上下文为 JVM this
            val recvName = irGen.extensionReceiverName
            if (recvName != null) {
                val local = g.lookupLocal(recvName) ?: error("IRGen: 扩展函数 receiver 局部未登记: $recvName")
                IrExpr.LocalRead(local)
            } else {
                IrExpr.This
            }
        }
        is YxSuper -> IrExpr.This // M4 简化：super 视为 this（S-8.7 继承语义后置）
        is YxParen -> gen(expr.expr, g, fileScope)
        is YxNullable -> gen(expr.expr, g, fileScope)
        // 表达式位置的类型引用 → 类字面量（S-8.3：`deserialize(json, PlayerData)` 传 Class 标记）；
        // 静态成员接收者（`System.currentTimeMillis`）在 genMemberAccess 单独处理，不经过此处
        is YxTypeReference -> IrExpr.ClassLiteral(irGen.resolveIrType(expr.type, fileScope))
        is YxMemberAccess -> genMemberAccess(expr, g, fileScope)
        is YxCall -> genCall(expr, g, fileScope)
        is YxTypeCall -> IrExpr.New(
            TypeBridge.toIr(irGen.exprTypes[expr] ?: SemaType.ErrorT),
            expr.args.map { genLiteralAdapted(it, g, fileScope) },
        )
        is YxBinary -> genBinary(expr, g, fileScope)
        is YxUnary -> genUnary(expr, g, fileScope)
        is YxIfExpr -> genIfExpr(expr, g, fileScope)
        is YxWhenExpr -> genWhenExpr(expr, g, fileScope)
        is YxIndexExpr -> genIndexExpr(expr, g, fileScope)
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
                // 顶层属性（M9）走静态 getter；实例属性走 this getter
                val irProp = irGen.propertyAccessors[sym]
                    ?: error("IRGen: 属性 getter 未登记: ${sym.name}")
                val getter = irProp.getter
                    ?: error("IRGen: 属性 getter 未登记: ${sym.name}")
                if (irProp.isStatic) {
                    IrExpr.Invoke(IrMethodRef(getter), null, emptyList())
                } else {
                    IrExpr.Invoke(IrMethodRef(getter), IrExpr.This, emptyList())
                }
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
        val isSuper = expr.receiver is YxSuper
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
            result = resolveMemberExpr(castSmartReceiver(receiver, receiverType), receiverType, expr.name, emptyList(), isSuper = isSuper)
                ?: error("IRGen: 成员不存在: ${expr.name} on ${receiverType.render()}")
        }
        return if (expr in irGen.guardSet) IrExpr.NullGuard(result) else result
    }

    /** 智能转型接收者适配（T-M12）：sema 已按 is 分支收窄 receiverType（Shape→Circle），
     *  但局部仍为声明类型；成员调用需 CHECKCAST 到收窄类型（emitConvert 对同描述符 no-op）。 */
    private fun castSmartReceiver(receiver: IrExpr, receiverType: SemaType): IrExpr {
        val from = receiver.inferType() ?: return receiver
        val to = TypeBridge.toIr(receiverType)
        return if (from.equivalent(to)) receiver else IrExpr.Convert(receiver, to)
    }

    /** 成员解析：Yux 属性→getter 调用；Yux 函数/JVM 方法→调用；Object 方法→JVM 调用回退。 */
    private fun resolveMemberExpr(
        receiver: IrExpr,
        rt: SemaType,
        name: String,
        args: List<IrExpr>,
        argTypes: List<SemaType> = emptyList(),
        isSuper: Boolean = false,
    ): IrExpr? = when {
        rt is SemaType.Declared && rt.symbol is YxClassSymbol -> {
            val sym = rt.symbol as YxClassSymbol
            // 沿父类链解析（S-8.7.1，缺陷 9）
            sym.propertyIncludingSuper(name)?.let { prop ->
                irGen.propertyAccessors[prop]?.getter?.let { IrExpr.Invoke(IrMethodRef(it), receiver, emptyList(), isSuper) }
            } ?: sym.functionsIncludingSuper(name).firstOrNull()?.let { fn ->
                irGen.functionMethods[fn]?.let { IrExpr.Invoke(IrMethodRef(it), receiver, args, isSuper) }
            } ?: objectMethodCall(sym.qualifiedName, name, args)?.let { IrExpr.Invoke(it, receiver, args, isSuper) }
        }
        else -> resolveMemberExprJvm(receiver, rt, name, args, argTypes, isSuper)
    }

    /**
     * JVM 接收者成员解析：注册表成员（T-M11-3）优先降级为 stdlib 静态调用
     * （与 sema checkInstanceCall 优先级一致，receiver 前置为实参 0）；
     * 未注册成员走实例方法 / JavaBean getter 解析。
     */
    private fun resolveMemberExprJvm(
        receiver: IrExpr,
        rt: SemaType,
        name: String,
        args: List<IrExpr>,
        argTypes: List<SemaType>,
        isSuper: Boolean = false,
    ): IrExpr? {
        val ext = resolver.resolveJvmExtension(rt, name)
        if (ext != null) {
            return IrExpr.Invoke(ext, null, listOf(receiver) + args)
        }
        return resolver.resolveInstanceMethod(rt, name, argTypes)
            ?.let { IrExpr.Invoke(it, receiver, args, isSuper) }
    }

    /** Object 方法回退（S-8.7.1）：data 类生成 toString/equals/hashCode，普通类继承——统一虚拟调用。 */
    private fun objectMethodCall(owner: String, name: String, args: List<IrExpr>): IrJvmCall? = when (name) {
        "toString" -> IrJvmCall("toString", owner, static = false, params = emptyList(), retType = IrType.STRING)
        "equals" -> IrJvmCall("equals", owner, static = false, params = listOf(IrType.ANY), retType = IrType.BOOLEAN)
        "hashCode" -> IrJvmCall("hashCode", owner, static = false, params = emptyList(), retType = IrType.INT)
        else -> null
    }

    private fun genCall(call: YxCall, g: MethodGen, fileScope: FileScope): IrExpr {
        val args = call.args.map { genLiteralAdapted(it, g, fileScope) }
        val argTypes = call.args.mapNotNull { irGen.exprTypes[it] }
        if (call.callee is YxMemberAccess) {
        }
        return when (val callee = call.callee) {
            is YxIdentifier -> {
                val sym = irGen.resolvedRefs[callee]
                when (sym) {
                    is FunctionSymbol -> {
                        val target: IrCallable = irGen.functionMethods[sym]?.let { IrMethodRef(it) }
                            ?: builtinCall(sym)
                        IrExpr.Invoke(target, null, args)
                    }
                    is VariableSymbol, is ParameterSymbol -> {
                        // 函数类型变量/参数调用（B3）：FnInvoke(函数值, 实参)
                        // 用符号自身类型判定（callee 标识符不经过 typeOf，exprTypes 无登记）
                        val fnType = SemaType.resolveVar(symbolType(sym) ?: SemaType.ErrorT)
                        if (fnType is SemaType.Function) {
                            IrExpr.FnInvoke(genIdentifier(callee, g), args)
                        } else {
                            error("IRGen: 未解析的调用: ${callee.name}")
                        }
                    }
                    else -> {
                        // sema 的 typeOfCall 对函数类型局部/参数的调用未登记 resolvedRefs[callee]，
                        // 退化为本地变量（函数类型值）直查（B3）
                        val local = g.lookupLocal(callee.name)
                            ?: error("IRGen: 未解析的调用: ${callee.name}")
                        IrExpr.FnInvoke(IrExpr.LocalRead(local), args)
                    }
                }
            }
            is YxMemberAccess -> genMemberCall(callee, args, argTypes, g, fileScope)
            else -> error("IRGen: 不支持的调用目标: ${callee::class.simpleName}")
        }
    }

    private fun genMemberCall(
        callee: YxMemberAccess,
        args: List<IrExpr>,
        argTypes: List<SemaType>,
        g: MethodGen,
        fileScope: FileScope,
    ): IrExpr {
        val receiverType = SemaType.resolveVar(irGen.exprTypes[callee.receiver] ?: SemaType.ErrorT)
        if (callee.name == "sendMessage") {
        }
        val result: IrExpr
        if (callee.receiver is YxTypeReference) {
            val static = resolver.resolveStaticMethod(receiverType, callee.name, argTypes)
                ?: error("IRGen: 静态方法不存在: ${callee.name}")
            result = IrExpr.Invoke(static, null, args)
        } else {
            // 扩展函数（M9）：sema 已在 resolvedRefs[callee] 登记扩展函数符号 → 静态调用（receiver 前置）
            val extSym = irGen.resolvedRefs[callee]
            if (extSym is FunctionSymbol && extSym.receiverType != null) {
                val method = irGen.functionMethods[extSym]
                    ?: error("IRGen: 扩展函数未登记: ${extSym.name}")
                val receiver = castSmartReceiver(gen(callee.receiver, g, fileScope), receiverType)
                result = IrExpr.Invoke(IrMethodRef(method), null, listOf(receiver) + args)
            } else {
                val receiver = castSmartReceiver(gen(callee.receiver, g, fileScope), receiverType)
                result = resolveMemberExpr(receiver, receiverType, callee.name, args, argTypes, isSuper = callee.receiver is YxSuper)
                    ?: error("IRGen: 成员方法不存在: ${callee.name} on ${receiverType.render()}")
            }
        }
        return if (callee in irGen.guardSet) IrExpr.NullGuard(result) else result
    }

    /**
     * 内置函数（M3 注册的 decl==null 函数）→ 运行时宿主：
     * `print`/`println` → yux.core.CoreLib；`serialize`/`deserialize`（S-8.3）→ yux.serializer.YuxSerializer。
     */
    private fun builtinCall(sym: FunctionSymbol): IrCallable = IrJvmCall(
        name = sym.name,
        owner = BuiltinFunctions.ownerOf(sym.name) ?: "yux.core.CoreLib",
        static = true,
        params = sym.params.map { TypeBridge.toIr(it.type) },
        retType = TypeBridge.toIr(sym.returnType ?: SemaType.UnitT),
    )

    private fun genBinary(expr: YxBinary, g: MethodGen, fileScope: FileScope): IrExpr = when (expr.op) {
        "&&", "||" -> genShortCircuit(expr, g, fileScope)
        "+" -> {
            // 字符串拼接（M9）：任一侧为 String 时降级为 StringTemplate（后端 StringBuilder）
            val left = gen(expr.left, g, fileScope)
            val right = gen(expr.right, g, fileScope)
            val lt = SemaType.resolveVar(irGen.exprTypes[expr.left] ?: SemaType.ErrorT)
            val rt = SemaType.resolveVar(irGen.exprTypes[expr.right] ?: SemaType.ErrorT)
            val lStr = lt is SemaType.Basic && lt.name == "String"
            val rStr = rt is SemaType.Basic && rt.name == "String"
            if (lStr || rStr) {
                IrExpr.StringTemplate(listOf(left, right))
            } else {
                IrExpr.Arith(ArithOp.ADD, left, right)
            }
        }
        "-", "*", "/", "%" -> {
            val op = when (expr.op) {
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
        if (expr.op == "&&") {
            g.emit(IrStmt.Branch(IrExpr.LocalRead(tmp), rhsL, endL))
        } else {
            g.emit(IrStmt.Branch(IrExpr.LocalRead(tmp), endL, rhsL))
        }
        g.emit(IrStmt.Label(rhsL))
        // 右操作数在 rhsL 标签之后下沉：其发射的语句（嵌套短路/表达式赋值）
        // 必须落在被守卫的块内，否则短路语义被破坏
        val then = gen(expr.right, g, fileScope)
        g.emit(IrStmt.LocalAssign(tmp, then))
        g.emit(IrStmt.Label(endL))
        return IrExpr.LocalRead(tmp)
    }
    private fun genUnary(expr: YxUnary, g: MethodGen, fileScope: FileScope): IrExpr = when (expr.op) {
        "!" -> IrExpr.Not(gen(expr.operand, g, fileScope))
        "-" -> IrExpr.Neg(gen(expr.operand, g, fileScope))
        else -> error("IRGen: 未知一元运算符: ${expr.op}")
    }

    /** 构造/调用实参字面量适配（M9）：sema 已按参数类型收窄（Double→Float），IRGen 按收窄类型生成常量。 */
    fun genLiteralAdapted(arg: YxExpr, g: MethodGen, fileScope: FileScope): IrExpr {
        val expr = gen(arg, g, fileScope)
        val semaType = SemaType.resolveVar(irGen.exprTypes[arg] ?: return expr)
        if (expr is IrExpr.Const && semaType is SemaType.Basic) {
            val v = expr.value
            return when (semaType.name) {
                "Float" -> if (v is Double) IrExpr.Const(v.toFloat()) else expr
                "Int" -> if (v is Long) IrExpr.Const(v.toInt()) else expr
                "Long" -> if (v is Int) IrExpr.Const(v.toLong()) else expr
                else -> expr
            }
        }
        return expr
    }

    /** if 表达式（M9）：临时变量 + 双分支标签，结果落同一临时变量（镜像短路模式）。 */
    private fun genIfExpr(expr: YxIfExpr, g: MethodGen, fileScope: FileScope): IrExpr {
        val type = TypeBridge.toIr(irGen.exprTypes[expr] ?: SemaType.ErrorT)
        val tmp = g.newLocal("ifTmp", type)
        val thenL = g.newLabel()
        val elseL = g.newLabel()
        val endL = g.newLabel()
        g.emit(IrStmt.Branch(gen(expr.condition, g, fileScope), thenL, elseL))
        g.emit(IrStmt.Label(thenL))
        g.emit(IrStmt.LocalAssign(tmp, gen(expr.thenExpr, g, fileScope)))
        g.emit(IrStmt.Goto(endL))
        g.emit(IrStmt.Label(elseL))
        g.emit(IrStmt.LocalAssign(tmp, gen(expr.elseExpr, g, fileScope)))
        g.emit(IrStmt.Label(endL))
        return IrExpr.LocalRead(tmp)
    }

    /** when 表达式（T-M12）：subject 求值一次 → if 链分支 → 各分支体落同一临时变量（镜像 genIfExpr）。 */
    private fun genWhenExpr(expr: YxWhenExpr, g: MethodGen, fileScope: FileScope): IrExpr {
        val type = TypeBridge.toIr(irGen.exprTypes[expr] ?: SemaType.ErrorT)
        val tmp = g.newLocal("whenTmp", type)
        val subjectLocal = g.newLocal("whenSubject", TypeBridge.toIr(irGen.exprTypes[expr.subject] ?: SemaType.ErrorT))
        g.emit(IrStmt.LocalAssign(subjectLocal, gen(expr.subject, g, fileScope)))
        val hasElse = expr.branches.any { it.condition is YxElseCondition }
        val endL = g.newLabel()
        for (branch in expr.branches) {
            val bodyL = g.newLabel()
            when (val cond = branch.condition) {
                is YxElseCondition -> {
                    g.emit(IrStmt.Label(bodyL))
                    g.emit(IrStmt.LocalAssign(tmp, gen(branch.body, g, fileScope)))
                    g.emit(IrStmt.Goto(endL))
                }
                is YxIsCondition -> {
                    val nextL = g.newLabel()
                    g.emit(
                        IrStmt.Branch(
                            IrExpr.IsType(IrExpr.LocalRead(subjectLocal), irGen.resolveIrType(cond.type, fileScope)),
                            bodyL,
                            nextL,
                        ),
                    )
                    g.emit(IrStmt.Label(bodyL))
                    g.emit(IrStmt.LocalAssign(tmp, gen(branch.body, g, fileScope)))
                    g.emit(IrStmt.Goto(endL))
                    g.emit(IrStmt.Label(nextL))
                }
                is YxExprCondition -> {
                    val nextL = g.newLabel()
                    g.emit(
                        IrStmt.Branch(
                            IrExpr.Compare(CompareOp.EQ, IrExpr.LocalRead(subjectLocal), gen(cond.expr, g, fileScope)),
                            bodyL,
                            nextL,
                        ),
                    )
                    g.emit(IrStmt.Label(bodyL))
                    g.emit(IrStmt.LocalAssign(tmp, gen(branch.body, g, fileScope)))
                    g.emit(IrStmt.Goto(endL))
                    g.emit(IrStmt.Label(nextL))
                }
            }
        }
        // T-M12：无 else 时（密封穷尽），最后的 nextL 直落 endL——whenTmp 需在 merge 点前初始化
        //（否则 COMPUTE_FRAMES 在 endL 报 local=top；Kotlin 以 NoWhenBranchMatchedException 兜底）。
        // 该路径运行时不可达（sema 穷尽性保证），初始化成类型默认值即可。
        if (!hasElse) {
            g.emit(IrStmt.LocalAssign(tmp, defaultConst(type)))
        }
        g.emit(IrStmt.Label(endL))
        return IrExpr.LocalRead(tmp)
    }

    /** 类型默认值（T-M12）：无 else 的 when 表达式 merge 点兜底初始化；运行时不可达。 */
    private fun defaultConst(type: IrType): IrExpr = when (val t = type.nonNull()) {
        is IrType.Basic -> when (t.name) {
            "Int" -> IrExpr.Const(0)
            "Long" -> IrExpr.Const(0L)
            "Float" -> IrExpr.Const(0f)
            "Double" -> IrExpr.Const(0.0)
            "Boolean" -> IrExpr.Const(false)
            "Char" -> IrExpr.Const('\u0000')
            "Byte" -> IrExpr.Const(0.toByte())
            else -> IrExpr.Const(null)
        }
        else -> IrExpr.Const(null)
    }

    /** 索引读取（M9）：Map→`get(k)`、List/Set→`get(i)`、数组→arrayload。 */
    private fun genIndexExpr(expr: YxIndexExpr, g: MethodGen, fileScope: FileScope): IrExpr {
        val baseType = SemaType.resolveVar(irGen.exprTypes[expr.base] ?: SemaType.ErrorT)
        val base = gen(expr.base, g, fileScope)
        val index = gen(expr.index, g, fileScope)
        val owner = when (val t = baseType) {
            is SemaType.Declared -> (t.symbol as? JvmClassSymbol)?.qualifiedName
            else -> null
        }
        if (owner != null && owner.startsWith("[")) {
            // JVM 数组索引读取（S-6.4.1/8.5）：a[i] → xALOAD
            val elemType = irGen.resolver.elementType(baseType) ?: SemaType.ANY
            return IrExpr.ArrayLoad(base, index, TypeBridge.toIr(elemType))
        }
        val method = when (owner) {
            "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap" -> "get"
            "java.util.List", "java.util.ArrayList", "java.util.LinkedList", "java.util.Vector" -> "get"
            else -> null
        }
        if (method != null && owner != null) {
            // JVM 泛型擦除：Map.get/List.get 运行时返回 Object，调用处按需 Convert
            val call = IrJvmCall(
                name = method,
                owner = owner,
                static = false,
                params = listOf(TypeBridge.toIr(irGen.exprTypes[expr.index] ?: SemaType.INT)),
                retType = IrType.ANY,
            )
            val invoke = IrExpr.Invoke(call, base, listOf(index))
            val target = TypeBridge.toIr(irGen.exprTypes[expr] ?: SemaType.ANY)
            return if (target != IrType.ANY && target != IrType.Void) {
                IrExpr.Convert(invoke, target)
            } else {
                invoke
            }
        }
        return error("IRGen: 索引访问不支持类型 ${baseType.render()}")
    }

    /** 箭头 Lambda：合成方法 `lambda$n`，体为 `Return(gen(body))`（捕获参数并入参数表，B2）。 */
    private fun genLambda(expr: YxLambda, g: MethodGen, fileScope: FileScope): IrExpr {
        val fnType = irGen.exprTypes[expr] as? SemaType.Function
            ?: error("IRGen: Lambda 缺少函数类型上下文: $expr")
        // B2：捕获分析须在方法创建前完成——IrMethod.params/paramLocals 为构造期固化
        val bound = expr.params.toMutableSet()
        val captures = mutableListOf<Pair<String, Symbol>>()
        collectCaptures(expr.body, bound, captures)
        val method = newLambdaMethod(expr, fnType, g, captures)
        val lambdaG = MethodGen(method, g.owner)
        lambdaG.enterScope()
        emitLambdaTail(gen(expr.body, lambdaG, fileScope), lambdaG)
        lambdaG.exitScope()
        return IrExpr.Lambda(
            IrMethodRef(method),
            captures.map { (name, _) -> IrExpr.LocalRead(captureLocal(g, name)) },
        )
    }

    /** 块 Lambda（隐式 `it`）：体为块语句序列；末条表达式语句作为返回值（B1），捕获同 B2。 */
    private fun genBlockLambda(expr: YxBlockLambda, g: MethodGen, fileScope: FileScope): IrExpr {
        val fnType = irGen.exprTypes[expr] as? SemaType.Function
            ?: error("IRGen: 块 Lambda 缺少函数类型上下文: $expr")
        // B2：捕获分析（含块作用域预扫）须在体发射前完成，使体内引用可解析
        val bound = mutableSetOf("it")
        prescanBlockVars(expr.body, bound)
        val captures = mutableListOf<Pair<String, Symbol>>()
        collectCaptures(expr.body, bound, captures)
        val method = newLambdaMethod(expr, fnType, g, captures)
        val lambdaG = MethodGen(method, g.owner)
        lambdaG.enterScope()
        val statements = expr.body.statements
        val nonUnit = SemaType.resolveVar(fnType.ret) != SemaType.UnitT
        statements.forEachIndexed { i, stmt ->
            // S-7.4.3：返回类型非 Unit 时，末条表达式语句的值即 Lambda 返回值
            val isLast = i == statements.lastIndex
            if (isLast && nonUnit) {
                when (stmt) {
                    is YxExprStmt -> emitLambdaTail(gen(stmt.expr, lambdaG, fileScope), lambdaG)
                    // 末条非表达式：sema 的 S-7.4.3 校验已拒绝；防御性报错防降级
                    else -> error("IRGen: 块 Lambda 返回类型非 Unit 但末条语句非表达式（S-7.4.3）: $stmt")
                }
            } else {
                irGen.genStmt(stmt, lambdaG, fileScope)
            }
        }
        // T-M11-3：Unit 返回的 Lambda 合成方法返回 Object（SAM 擦除），需显式 `Return(null)` 收尾
        if (!nonUnit) {
            lambdaG.emit(IrStmt.Return(IrExpr.Const(null)))
        }
        lambdaG.exitScope()
        return IrExpr.Lambda(
            IrMethodRef(method),
            captures.map { (name, _) -> IrExpr.LocalRead(captureLocal(g, name)) },
        )
    }

    /** 尾表达式作为返回值：体为 Unit/Void 值时（如 `print` 调用）作为语句发射并返回 null（T-M11-3）。 */
    private fun emitLambdaTail(tail: IrExpr, lambdaG: MethodGen) {
        if (tail.inferType() == IrType.Void) {
            lambdaG.emit(IrStmt.Eval(tail))
            lambdaG.emit(IrStmt.Return(IrExpr.Const(null)))
        } else {
            lambdaG.emit(IrStmt.Return(tail))
        }
    }

    /** 捕获槽位解析：外层局部必须已登记；缺失即 IRGen 状态不一致，报错而非 NPE。 */
    private fun captureLocal(g: MethodGen, name: String): IrLocal =
        g.lookupLocal(name) ?: error("IRGen: 捕获变量未登记: $name")

    private fun newLambdaMethod(
        expr: YxExpr,
        fnType: SemaType.Function,
        g: MethodGen,
        captures: List<Pair<String, Symbol>> = emptyList(),
    ): yux.compiler.ir.IrMethod {
        val names = when (expr) {
            is YxLambda -> expr.params
            // 块 Lambda 仅支持隐式 `it`；零参（Function0，如 `launch { }`）不生成参数（T-M11-3）
            is YxBlockLambda -> if (fnType.params.isEmpty()) emptyList() else listOf("it")
            else -> emptyList()
        }
        val params = buildList {
            // 捕获参数在前（M5：LambdaMetafactory implMethod 要求 (captures..., invoked...) -> R）
            captures.forEach { (name, sym) ->
                add(IrParam(name, captureIrType(sym)))
            }
            names.forEachIndexed { i, name ->
                add(IrParam(name, TypeBridge.toIr(fnType.params.getOrElse(i) { SemaType.ErrorT })))
            }
        }
        val method = irGen.newLambdaMethod(
            name = irGen.nextLambdaName(),
            params = params,
            // T-M11-3：Unit 返回的 Lambda 目标为 FunctionN SAM（擦除 `invoke()Ljava/lang/Object;`），
            // 合成方法必须返回 Object 而非 void（体以 `Return(Const(null))` 收尾）
            returnType = if (TypeBridge.toIr(fnType.ret) == IrType.Void) IrType.ANY else TypeBridge.toIr(fnType.ret),
            isStatic = g.method.isStatic,
            owner = g.owner,
        )
        return method
    }

    /** 捕获符号的 SemaType（变量/参数符号携带类型；其他符号视为错误占位）。 */
    private fun captureIrType(sym: Symbol): IrType = TypeBridge.toIr(symbolType(sym) ?: SemaType.ErrorT)

    /** 变量/参数符号携带的 SemaType（其他符号无类型）。 */
    private fun symbolType(sym: Symbol): SemaType? = when (sym) {
        is VariableSymbol -> sym.type
        is ParameterSymbol -> sym.type
        else -> null
    }

    // ── 捕获分析（B2：闭包 S-7.4.4）────────────────────────────────────────

    /** 块 Lambda 预扫：块体顶层声明的变量视为已绑定（块作用域遮蔽）。 */
    internal fun prescanBlockVars(block: YxBlock, bound: MutableSet<String>) {
        block.statements.forEach { stmt ->
            if (stmt is YxVarDecl) bound += stmt.name
        }
    }

    /**
     * 自由变量收集：遍历表达式/语句树，收集未绑定（非本 Lambda 参数、非体内声明）且
     * 解析到 VariableSymbol/ParameterSymbol 的标识符——即需捕获的外层局部。
     * [bound] 为已绑定名字集合（Lambda 参数 + 当前作用域内已声明的变量）；
     * [captures] 按首现顺序去重。
     *
     * 作用域纪律：进入块/Lambda/循环/捕获子句时快照 [bound]，离开时恢复——
     * 否则内层同名声明会永久压制外层变量捕获（遮蔽泄漏，P1-4）。
     */
    internal fun collectCaptures(
        node: YxNode,
        bound: MutableSet<String>,
        captures: MutableList<Pair<String, Symbol>>,
    ) {
        when (node) {
            is YxIdentifier -> {
                val sym = irGen.resolvedRefs[node]
                if (sym is VariableSymbol || sym is ParameterSymbol) {
                    if (sym.name !in bound && captures.none { it.first == sym.name }) {
                        captures.add(sym.name to sym)
                    }
                }
            }
            is YxMemberAccess -> collectCaptures(node.receiver, bound, captures) // name 为字符串，不遍历
            is YxCall -> {
                collectCaptures(node.callee, bound, captures)
                node.args.forEach { collectCaptures(it, bound, captures) }
            }
            is YxTypeCall -> node.args.forEach { collectCaptures(it, bound, captures) }
            is YxBinary -> {
                collectCaptures(node.left, bound, captures)
                collectCaptures(node.right, bound, captures)
            }
            is YxUnary -> collectCaptures(node.operand, bound, captures)
            is YxParen -> collectCaptures(node.expr, bound, captures)
            is YxNullable -> collectCaptures(node.expr, bound, captures)
            is YxIs -> collectCaptures(node.expr, bound, captures)
            is YxAs -> collectCaptures(node.expr, bound, captures)
            is YxThrow -> collectCaptures(node.expr, bound, captures)
            is YxRange -> {
                collectCaptures(node.from, bound, captures)
                collectCaptures(node.to, bound, captures)
            }
            is YxStringTemplate -> node.parts.forEach { collectCaptures(it, bound, captures) }
            is YxLambda -> inScope(bound) {
                bound += node.params
                collectCaptures(node.body, bound, captures)
            }
            is YxBlockLambda -> inScope(bound) {
                bound += "it" // 内层块 Lambda 的隐式参数
                collectCaptures(node.body, bound, captures)
            }
            is yux.compiler.ast.YxAssign -> {
                val t = node.target
                if (t is YxIdentifier || t is YxMemberAccess) collectCaptures(t, bound, captures)
                collectCaptures(node.value, bound, captures)
            }
            is YxBlock -> inScope(bound) {
                node.statements.forEach { collectCaptures(it, bound, captures) }
            }
            is YxVarDecl -> {
                bound += node.name
                node.initializer?.let { collectCaptures(it, bound, captures) }
            }
            is YxExprStmt -> collectCaptures(node.expr, bound, captures)
            is YxIf -> {
                collectCaptures(node.condition, bound, captures)
                collectCaptures(node.thenBranch, bound, captures)
                node.elseBranch?.let { collectCaptures(it, bound, captures) }
            }
            is YxWhile -> {
                collectCaptures(node.condition, bound, captures)
                collectCaptures(node.body, bound, captures)
            }
            is YxFor -> {
                // 循环变量仅体内绑定（S-6.4.1 作用域）：迭代式在外层求值
                collectCaptures(node.iterable, bound, captures)
                inScope(bound) {
                    bound += node.varName
                    collectCaptures(node.body, bound, captures)
                }
            }
            is YxReturn -> node.value?.let { collectCaptures(it, bound, captures) }
            is YxWhen -> {
                collectCaptures(node.subject, bound, captures)
                node.branches.forEach { b ->
                    (b.condition as? yux.compiler.ast.YxExprCondition)
                        ?.let { collectCaptures(it.expr, bound, captures) }
                    collectCaptures(b.body, bound, captures)
                }
            }
            is YxWhenExpr -> {
                collectCaptures(node.subject, bound, captures)
                node.branches.forEach { b ->
                    (b.condition as? yux.compiler.ast.YxExprCondition)
                        ?.let { collectCaptures(it.expr, bound, captures) }
                    collectCaptures(b.body, bound, captures)
                }
            }
            is YxTry -> {
                collectCaptures(node.body, bound, captures)
                node.catches.forEach { c ->
                    inScope(bound) {
                        bound += c.paramName // catch 参数仅捕获子句体内绑定
                        collectCaptures(c.body, bound, captures)
                    }
                }
                node.finallyBody?.let { collectCaptures(it, bound, captures) }
            }
            is YxAsyncBlock -> collectCaptures(node.body, bound, captures)
            is YxParallelBlock -> collectCaptures(node.body, bound, captures)
            is YxUnsafeBlock -> collectCaptures(node.body, bound, captures)
            // 叶子节点（无子表达式）与声明/类型节点（分析不涉及）
            is YxIntLiteral, is YxFloatLiteral, is YxCharLiteral, is YxBoolLiteral,
            is YxNullLiteral, is YxRawStringLiteral, is YxStringLiteral, is YxStringText,
            is YxThis, is YxSuper, is YxTypeReference, is YxBreak, is YxContinue -> Unit
            else -> Unit
        }
    }

    /** 作用域执行：快照 [bound]，执行 [block]，恢复快照（块内声明不泄漏到外层）。 */
    private inline fun inScope(bound: MutableSet<String>, block: () -> Unit) {
        val saved = HashSet(bound)
        block()
        bound.clear()
        bound.addAll(saved)
    }

    /** 表达式位置赋值：发射写入语句后返回写入值（S-6.1 赋值即表达式）。 */
    private fun genAssignValue(expr: yux.compiler.ast.YxAssign, g: MethodGen, fileScope: FileScope): IrExpr =
        when (val t = expr.target) {
            is YxIdentifier -> when (val sym = irGen.resolvedRefs[t]) {
                is VariableSymbol -> {
                    val local = g.lookupLocal(sym.name)
                        ?: error("IRGen: 赋值目标未登记: ${sym.name}")
                    val value = gen(expr.value, g, fileScope)
                    g.emit(IrStmt.LocalAssign(local, value))
                    IrExpr.LocalRead(local)
                }
                is PropertySymbol -> {
                    val accessor = irGen.propertyAccessors[sym]
                        ?: error("IRGen: 属性访问器未登记: ${sym.name}")
                    val setter = accessor.setter ?: error("IRGen: 属性 setter 未登记: ${sym.name}")
                    val getter = accessor.getter ?: error("IRGen: 属性 getter 未登记: ${sym.name}")
                    val value = gen(expr.value, g, fileScope)
                    if (accessor.isStatic) {
                        g.emit(IrStmt.Call(IrMethodRef(setter), null, listOf(value), IrType.Void))
                        IrExpr.Invoke(IrMethodRef(getter), null, emptyList())
                    } else {
                        g.emit(IrStmt.Call(IrMethodRef(setter), IrExpr.This, listOf(value), IrType.Void))
                        IrExpr.Invoke(IrMethodRef(getter), IrExpr.This, emptyList())
                    }
                }
                else -> error("IRGen: 不支持的赋值目标: ${t.name}")
            }
            is YxMemberAccess -> {
                val receiverType = SemaType.resolveVar(irGen.exprTypes[t.receiver] ?: SemaType.ErrorT)
                if (receiverType !is SemaType.Declared || receiverType.symbol !is YxClassSymbol) {
                    error("IRGen: 不支持的表达式位置赋值目标: ${t.name}")
                }
                val prop = (receiverType.symbol as YxClassSymbol).propertyIncludingSuper(t.name)
                    ?: error("IRGen: 赋值目标属性不存在: ${t.name}")
                val accessor = irGen.propertyAccessors[prop]
                    ?: error("IRGen: 属性访问器不存在: ${t.name}")
                val setter = accessor.setter ?: error("IRGen: 属性 setter 不存在: ${t.name}")
                val getter = accessor.getter ?: error("IRGen: 属性 getter 不存在: ${t.name}")
                // 接收者先求值一次存入临时（副作用安全），再生成右值；setter/getter 复用该临时
                val receiverLocal = if (t.receiver is YxTypeReference) {
                    null
                } else {
                    val local = g.newLocal("receiver", TypeBridge.toIr(receiverType))
                    g.emit(IrStmt.LocalAssign(local, castSmartReceiver(gen(t.receiver, g, fileScope), receiverType)))
                    local
                }
                val value = gen(expr.value, g, fileScope)
                g.emit(IrStmt.Call(IrMethodRef(setter), receiverLocal?.let { IrExpr.LocalRead(it) }, listOf(value), IrType.Void))
                IrExpr.Invoke(IrMethodRef(getter), receiverLocal?.let { IrExpr.LocalRead(it) }, emptyList())
            }
            is YxIndexExpr -> genIndexAssign(t, expr.value, g, fileScope)
            else -> error("IRGen: 不支持的赋值目标: ${t::class.simpleName}")
        }

    /** 索引赋值（M9）：Map→`put(k,v)`、List/Set→`set(i,v)`，返回被写值。 */
    private fun genIndexAssign(target: YxIndexExpr, valueExpr: YxExpr, g: MethodGen, fileScope: FileScope): IrExpr {
        val baseType = SemaType.resolveVar(irGen.exprTypes[target.base] ?: SemaType.ErrorT)
        val owner = when (val t = baseType) {
            is SemaType.Declared -> (t.symbol as? JvmClassSymbol)?.qualifiedName
            else -> null
        }
        val method = when (owner) {
            "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap" -> "put"
            "java.util.List", "java.util.ArrayList", "java.util.LinkedList", "java.util.Vector" -> "set"
            else -> null
        }
        if (method == null || owner == null) {
            error("IRGen: 索引赋值不支持类型 ${baseType.render()}")
        }
        val base = gen(target.base, g, fileScope)
        val index = gen(target.index, g, fileScope)
        val value = gen(valueExpr, g, fileScope)
        val idxType = TypeBridge.toIr(irGen.exprTypes[target.index] ?: SemaType.INT)
        val valType = TypeBridge.toIr(irGen.exprTypes[valueExpr] ?: SemaType.ANY)
        val call = IrJvmCall(
            name = method,
            owner = owner,
            static = false,
            params = listOf(idxType, valType),
            retType = IrType.ANY,
        )
        // 索引赋值结果按目标元素类型返回（List.set 返回旧值、Map.put 返回旧值），
        // 但表达式位置通常丢弃；此处直接返回调用（后端按表达式类型适配）
        val invoke = IrExpr.Invoke(call, base, listOf(index, value))
        val targetType = TypeBridge.toIr(irGen.exprTypes[target] ?: SemaType.ANY)
        return if (targetType != IrType.ANY && targetType != IrType.Void) {
            IrExpr.Convert(invoke, targetType)
        } else {
            invoke
        }
    }

    // ── 字面量解码（01-§2.3/§2.5；复用 Literals，注解实参求值共用）────────────

    private fun decodeInt(text: String): Any = Literals.decodeInt(text)

    private fun decodeFloat(text: String): Any = Literals.decodeFloat(text)

    private fun decodeChar(text: String): Char = Literals.decodeChar(text)

    private fun decodeString(text: String): String = Literals.decodeString(text)
}
