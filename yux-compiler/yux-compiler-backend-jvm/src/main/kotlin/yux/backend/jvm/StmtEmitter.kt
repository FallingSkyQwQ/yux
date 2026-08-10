package yux.backend.jvm

import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import yux.compiler.ir.ArithOp
import yux.compiler.ir.CompareOp
import yux.compiler.ir.IrCatch
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrField
import yux.compiler.ir.IrLabel
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrMethod
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType

/**
 * 方法体发射器（T-M5-3 / 02-§9.2）：遍历 IrStmt/IrExpr → ASM 指令。
 *
 * 关键映射：
 * - 局部：槽位由 [LocalSlotAllocator] 分配；this 恒为槽 0（实例方法）；
 * - 标签：IrLabel → ASM Label（单方法 map）；
 * - 调用：IrMethodRef 用 IR 描述符；IrJvmCall 用真实描述符 + 装箱适配（T-M5-7）；
 * - Lambda：invokedynamic LambdaMetafactory（T-M5-4）；
 * - NullGuard：ifnull/ifnonnull + 类型默认值（T-M5-8）；
 * - StringTemplate：StringBuilder.append 序列（02-§9.3）。
 */
internal class StmtEmitter(
    private val mv: MethodVisitor,
    private val classEmitter: AsmEmitter,
    private val allocation: SlotAllocation,
    private val method: IrMethod,
) {
    private val labels = HashMap<IrLabel, Label>()
    private var catchTmpCounter = 0

    private val slots: Map<IrLocal, Int> get() = allocation.slots

    /** 方法级临时槽起点（try/finally 异常临时槽从该处高位申请，不与 this/参数/局部重叠）。 */
    private fun tmpSlot(): Int = allocation.nextFreeSlot + catchTmpCounter++

    fun emitStmt(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.Label -> mv.visitLabel(labelOf(stmt.label))
            is IrStmt.LocalAssign -> {
                emitExpr(stmt.value)
                storeLocal(stmt.local, stmt.value.inferType())
            }
            is IrStmt.Call -> {
                emitInvoke(stmt.callee, stmt.receiver, stmt.args, stmt.isSuper)
                // 语句位置：结果出栈（void 无需）
                popIfNeeded(stmt.ret)
            }
            is IrStmt.New -> {
                val type = stmt.type
                val ctor = classEmitter.resolveConstructor(type, stmt.args)
                val owner = JvmTypeMapper.internalName(type)
                mv.visitTypeInsn(Opcodes.NEW, owner)
                mv.visitInsn(Opcodes.DUP)
                emitArgs(stmt.args, ctor.paramDescs)
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", ctor.desc, false)
                mv.visitInsn(Opcodes.POP) // 语句位置丢弃实例
            }
            is IrStmt.Eval -> {
                emitExpr(stmt.expr)
                popIfNeeded(stmt.expr.inferType() ?: IrType.Void)
            }
            is IrStmt.FieldAccess -> emitFieldAccess(stmt)
            is IrStmt.Branch -> emitBranch(stmt)
            is IrStmt.Goto -> mv.visitJumpInsn(Opcodes.GOTO, labelOf(stmt.label))
            is IrStmt.Return -> emitReturn(stmt)
            is IrStmt.Throw -> {
                emitExpr(stmt.value)
                mv.visitInsn(Opcodes.ATHROW)
            }
            is IrStmt.Monitor -> {
                emitExpr(stmt.expr)
                mv.visitInsn(if (stmt.enter) Opcodes.MONITORENTER else Opcodes.MONITOREXIT)
            }
            is IrStmt.Try -> emitTry(stmt)
            is IrStmt.Nop -> Unit
        }
    }

    // ── 语句 ──────────────────────────────────────────────────────────────────

    private fun emitFieldAccess(stmt: IrStmt.FieldAccess) {
        val field = stmt.field
        val ownerInternal = field.owner?.replace('.', '/') ?: classEmitter.ownerInternalName
        if (stmt.write) {
            if (field.isStatic) {
                emitExpr(stmt.value!!)
                mv.visitFieldInsn(Opcodes.PUTSTATIC, ownerInternal, field.name, JvmTypeMapper.descriptor(field.type))
            } else {
                emitExpr(stmt.receiver!!)
                emitExpr(stmt.value!!)
                mv.visitFieldInsn(Opcodes.PUTFIELD, ownerInternal, field.name, JvmTypeMapper.descriptor(field.type))
            }
        } else {
            if (field.isStatic) {
                mv.visitFieldInsn(Opcodes.GETSTATIC, ownerInternal, field.name, JvmTypeMapper.descriptor(field.type))
            } else {
                emitExpr(stmt.receiver!!)
                mv.visitFieldInsn(Opcodes.GETFIELD, ownerInternal, field.name, JvmTypeMapper.descriptor(field.type))
            }
        }
    }

    private fun emitBranch(stmt: IrStmt.Branch) {
        // Branch(cond, then, else)：cond 为布尔（栈上 0/1）→ IFNE then；GOTO else
        emitExpr(stmt.cond)
        mv.visitJumpInsn(Opcodes.IFNE, labelOf(stmt.then))
        mv.visitJumpInsn(Opcodes.GOTO, labelOf(stmt.elseLabel))
    }

    private fun emitReturn(stmt: IrStmt.Return) {
        val value = stmt.value
        if (value == null) {
            mv.visitInsn(Opcodes.RETURN)
            return
        }
        emitExpr(value)
        val desc = JvmTypeMapper.descriptor(method.returnType)
        // T-M11-3：lambda 合成方法擦除返回 Object（FunctionN）而体为原语时装箱适配
        //（如 `xs.map { it * 10 }` 的返回类型 Any → Object，体产生 Int）
        val valueDesc = JvmTypeMapper.descriptor(value.inferType() ?: method.returnType)
        if (valueDesc == "V" && desc != "V") {
            // Unit 值（如 `print`）返回非空方法：补 null（防御，IRGen 正常路径已生成 Const(null)）
            mv.visitInsn(Opcodes.ACONST_NULL)
        } else if (valueDesc != desc) {
            InvocationAdapter.adaptResult(mv, valueDesc, method.returnType)
        }
        mv.visitInsn(returnOpcode(desc))
    }

    private fun returnOpcode(desc: String): Int = when (desc) {
        "I", "Z", "C", "B" -> Opcodes.IRETURN
        "J" -> Opcodes.LRETURN
        "F" -> Opcodes.FRETURN
        "D" -> Opcodes.DRETURN
        "V" -> Opcodes.RETURN
        else -> Opcodes.ARETURN
    }

    private fun emitTry(stmt: IrStmt.Try) {
        val tryStart = Label()
        val tryEnd = Label()
        val end = Label()
        val finallyLabel = if (stmt.finallyBody != null) Label() else null

        mv.visitLabel(tryStart)
        stmt.body.forEach { emitStmt(it) }
        mv.visitLabel(tryEnd)

        // finally 的正常路径
        stmt.finallyBody?.forEach { emitStmt(it) }
        mv.visitJumpInsn(Opcodes.GOTO, end)

        // 每个 catch：handler → ASTORE catch 参数 → 体 → finally → GOTO end
        for (catch in stmt.catches) {
            val handler = Label()
            mv.visitLabel(handler)
            val catchLocal = resolveCatchLocal(catch)
            mv.visitVarInsn(Opcodes.ASTORE, catchLocal)
            catch.body.forEach { emitStmt(it) }
            stmt.finallyBody?.forEach { emitStmt(it) }
            mv.visitJumpInsn(Opcodes.GOTO, end)
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, catchType(catch))
        }

        // finally 的异常路径：catch-all → finally → rethrow
        if (finallyLabel != null) {
            mv.visitLabel(finallyLabel)
            // 临时槽高位申请：异常暂存不得覆盖 this/参数/局部（含宽局部双槽）
            val tmp = tmpSlot()
            mv.visitVarInsn(Opcodes.ASTORE, tmp)
            stmt.finallyBody?.forEach { emitStmt(it) }
            mv.visitVarInsn(Opcodes.ALOAD, tmp)
            mv.visitInsn(Opcodes.ATHROW)
            mv.visitTryCatchBlock(tryStart, tryEnd, finallyLabel, null)
        }
        mv.visitLabel(end)
    }

    private fun catchType(c: IrCatch): String? =
        c.type?.let { JvmTypeMapper.internalName(it) }

    private fun resolveCatchLocal(c: IrCatch): Int {
        // catch 参数局部：递归搜索 catch 体（含嵌套表达式/嵌套 Try），独立槽位——
        // 不得复用方法参数槽（同名参数场景 ASTORE 会覆盖参数）；未引用时用临时槽
        val found = findLocalByNameRecursive(c.body, c.paramName)
        if (found != null) return slots[found] ?: error("catch 参数槽位缺失: ${c.paramName}")
        return tmpSlot()
    }

    // ── 表达式 ────────────────────────────────────────────────────────────────

    fun emitExpr(e: IrExpr) {
        when (e) {
            is IrExpr.Const -> emitConst(e.value)
            is IrExpr.ClassLiteral -> mv.visitLdcInsn(Type.getObjectType(JvmTypeMapper.classLiteralInternal(e.type)))
            is IrExpr.ArrayLoad -> emitArrayLoad(e)
            is IrExpr.This -> mv.visitVarInsn(Opcodes.ALOAD, 0)
            is IrExpr.LocalRead -> loadLocal(e.local)
            is IrExpr.FieldRead -> emitFieldRead(e)
            is IrExpr.New -> emitNew(e)
            is IrExpr.Arith -> emitArith(e)
            is IrExpr.Compare -> emitCompare(e)
            is IrExpr.Not -> emitNot(e)
            is IrExpr.Neg -> emitNeg(e)
            is IrExpr.Convert -> emitConvert(e)
            is IrExpr.IsType -> emitIsType(e)
            is IrExpr.Invoke -> emitInvoke(e.target, e.receiver, e.args, e.isSuper)
            is IrExpr.FnInvoke -> emitFnInvoke(e)
            is IrExpr.StringTemplate -> emitStringTemplate(e)
            is IrExpr.NullGuard -> emitNullGuard(e)
            is IrExpr.Lambda -> emitLambda(e)
        }
    }

    /** JVM 数组元素读取：`a[i]` → 栈 base,index 后按元素描述符发 xALOAD。 */
    private fun emitArrayLoad(e: IrExpr.ArrayLoad) {
        emitExpr(e.base)
        emitExpr(e.index)
        when (JvmTypeMapper.descriptor(e.elemType)) {
            "I" -> mv.visitInsn(Opcodes.IALOAD)
            "J" -> mv.visitInsn(Opcodes.LALOAD)
            "F" -> mv.visitInsn(Opcodes.FALOAD)
            "D" -> mv.visitInsn(Opcodes.DALOAD)
            "Z", "B" -> mv.visitInsn(Opcodes.BALOAD)
            "C" -> mv.visitInsn(Opcodes.CALOAD)
            else -> mv.visitInsn(Opcodes.AALOAD)
        }
    }

    private fun emitConst(value: Any?) {        when (value) {
            null -> mv.visitInsn(Opcodes.ACONST_NULL)
            is Int -> pushInt(value)
            is Long -> {
                if (value == 0L) mv.visitInsn(Opcodes.LCONST_0)
                else if (value == 1L) mv.visitInsn(Opcodes.LCONST_1)
                else mv.visitLdcInsn(value)
            }
            is Float -> {
                if (value == 0.0f) mv.visitInsn(Opcodes.FCONST_0)
                else if (value == 1.0f) mv.visitInsn(Opcodes.FCONST_1)
                else mv.visitLdcInsn(value)
            }
            is Double -> {
                if (value == 0.0) mv.visitInsn(Opcodes.DCONST_0)
                else if (value == 1.0) mv.visitInsn(Opcodes.DCONST_1)
                else mv.visitLdcInsn(value)
            }
            is Boolean -> pushInt(if (value) 1 else 0)
            is Char -> pushInt(value.code)
            is Byte -> pushInt(value.toInt())
            is String -> mv.visitLdcInsn(value)
            else -> mv.visitLdcInsn(value)
        }
    }

    private fun pushInt(v: Int) {
        when (v) {
            -1 -> mv.visitInsn(Opcodes.ICONST_M1)
            0 -> mv.visitInsn(Opcodes.ICONST_0)
            1 -> mv.visitInsn(Opcodes.ICONST_1)
            2 -> mv.visitInsn(Opcodes.ICONST_2)
            3 -> mv.visitInsn(Opcodes.ICONST_3)
            4 -> mv.visitInsn(Opcodes.ICONST_4)
            5 -> mv.visitInsn(Opcodes.ICONST_5)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, v)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, v)
            else -> mv.visitLdcInsn(v)
        }
    }

    private fun emitFieldRead(e: IrExpr.FieldRead) {
        val field = e.field
        val ownerInternal = field.owner?.replace('.', '/') ?: classEmitter.ownerInternalName
        if (field.isStatic) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, ownerInternal, field.name, JvmTypeMapper.descriptor(field.type))
        } else {
            emitExpr(e.receiver!!)
            mv.visitFieldInsn(Opcodes.GETFIELD, ownerInternal, field.name, JvmTypeMapper.descriptor(field.type))
        }
    }

    private fun emitNew(e: IrExpr.New) {
        val owner = JvmTypeMapper.internalName(e.type)
        mv.visitTypeInsn(Opcodes.NEW, owner)
        mv.visitInsn(Opcodes.DUP)
        val ctor = classEmitter.resolveConstructor(e.type, e.args)
        emitArgs(e.args, ctor.paramDescs)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", ctor.desc, false)
    }

    /** 实参发射 + 按真实参数描述符适配（装箱/拆箱，T-M5-7）。 */
    private fun emitArgs(args: List<IrExpr>, realParamDescs: List<String>) {
        args.forEachIndexed { i, arg ->
            emitExpr(arg)
            val real = realParamDescs.getOrElse(i) { JvmTypeMapper.descriptor(arg.inferType() ?: IrType.ANY) }
            InvocationAdapter.adaptArg(mv, argType(arg), real)
        }
    }

    private fun emitArith(e: IrExpr.Arith) {
        val ret = e.inferType()
        val stringAdd = ret is IrType.Basic && ret.name == "String" && e.op == ArithOp.ADD
        if (stringAdd) {
            // 字符串拼接：StringBuilder.append 序列（02-§9.3）
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            mv.visitInsn(Opcodes.DUP)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            emitExpr(e.l)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", appendDesc(e.l), false)
            emitExpr(e.r)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", appendDesc(e.r), false)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            return
        }
        val desc = JvmTypeMapper.descriptor(ret ?: IrType.INT)
        emitExpr(e.l)
        widenOperand(e.l, desc)
        emitExpr(e.r)
        widenOperand(e.r, desc)
        mv.visitInsn(arithOpcode(e.op, desc))
    }

    /** 操作数拓宽到运算宽度（Int 参与 Long 运算时 i2l）。 */
    private fun widenOperand(operand: IrExpr, targetDesc: String) {
        val src = JvmTypeMapper.descriptor(operand.inferType() ?: IrType.INT)
        InvocationAdapter.widen(mv, src, targetDesc)
    }

    private fun arithOpcode(op: ArithOp, desc: String): Int = when (desc) {
        "J" -> when (op) {
            ArithOp.ADD -> Opcodes.LADD
            ArithOp.SUB -> Opcodes.LSUB
            ArithOp.MUL -> Opcodes.LMUL
            ArithOp.DIV -> Opcodes.LDIV
            ArithOp.MOD -> Opcodes.LREM
        }
        "F" -> when (op) {
            ArithOp.ADD -> Opcodes.FADD
            ArithOp.SUB -> Opcodes.FSUB
            ArithOp.MUL -> Opcodes.FMUL
            ArithOp.DIV -> Opcodes.FDIV
            ArithOp.MOD -> Opcodes.FREM
        }
        "D" -> when (op) {
            ArithOp.ADD -> Opcodes.DADD
            ArithOp.SUB -> Opcodes.DSUB
            ArithOp.MUL -> Opcodes.DMUL
            ArithOp.DIV -> Opcodes.DDIV
            ArithOp.MOD -> Opcodes.DREM
        }
        else -> when (op) {
            ArithOp.ADD -> Opcodes.IADD
            ArithOp.SUB -> Opcodes.ISUB
            ArithOp.MUL -> Opcodes.IMUL
            ArithOp.DIV -> Opcodes.IDIV
            ArithOp.MOD -> Opcodes.IREM
        }
    }

    private fun emitCompare(e: IrExpr.Compare) {
        val lType = e.l.inferType() ?: IrType.INT
        val rType = e.r.inferType() ?: IrType.INT
        val lIsNull = (e.l as? IrExpr.Const)?.let { it.value == null } ?: false
        val rIsNull = (e.r as? IrExpr.Const)?.let { it.value == null } ?: false
        var lDesc = JvmTypeMapper.descriptor(lType)
        var rDesc = JvmTypeMapper.descriptor(rType)
        val lRef = !isPrimitiveDesc(lDesc)
        val rRef = !isPrimitiveDesc(rDesc)
        val ordering = e.op == CompareOp.LT || e.op == CompareOp.LE || e.op == CompareOp.GT || e.op == CompareOp.GE
        // 混合可空/非可空数值：== / != 装箱原语侧走引用比较（null 感知）；
        // 排序比较拆箱引用侧（null → NPE，与 `as Int` 强制拆箱一致）走原语比较
        val unboxL = lRef && !rRef && ordering
        val unboxR = rRef && !lRef && ordering
        val boxL = !lRef && rRef && !ordering
        val boxR = !rRef && lRef && !ordering

        emitExpr(e.l)
        when {
            unboxL -> InvocationAdapter.adaptArg(mv, lType, JvmTypeMapper.descriptor(lType.nonNull()))
            boxL -> InvocationAdapter.adaptArg(mv, lType, rDesc)
        }
        emitExpr(e.r)
        when {
            unboxR -> InvocationAdapter.adaptArg(mv, rType, JvmTypeMapper.descriptor(rType.nonNull()))
            boxR -> InvocationAdapter.adaptArg(mv, rType, lDesc)
        }
        if (unboxL) lDesc = JvmTypeMapper.descriptor(lType.nonNull())
        if (unboxR) rDesc = JvmTypeMapper.descriptor(rType.nonNull())
        if (boxL) lDesc = rDesc
        if (boxR) rDesc = lDesc

        val refCompare = lIsNull || rIsNull || (!isPrimitiveDesc(lDesc) && !isPrimitiveDesc(rDesc))

        when {
            refCompare -> {
                if (lIsNull || rIsNull) {
                    // null 判断：IF_ACMPEQ/NE（S-7.5.2 引用相等）
                    when (e.op) {
                        CompareOp.EQ -> jumpPush(Opcodes.IF_ACMPEQ)
                        CompareOp.NE -> jumpPush(Opcodes.IF_ACMPNE)
                        else -> error("M5 引用比较仅支持 == / !=: ${e.op}")
                    }
                } else {
                    // 引用结构相等（S-7.5.2）：Objects.equals(l, r)
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "java/util/Objects",
                        "equals",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        false,
                    )
                    when (e.op) {
                        CompareOp.EQ -> Unit
                        CompareOp.NE -> negateBoolean()
                        else -> error("M5 引用比较仅支持 == / !=: ${e.op}")
                    }
                }
            }
            lDesc == "J" || rDesc == "J" -> {
                InvocationAdapter.widen(mv, lDesc, "J")
                InvocationAdapter.widen(mv, rDesc, "J")
                mv.visitInsn(Opcodes.LCMP)
                cmpResultJump(e.op)
            }
            lDesc == "F" || rDesc == "F" -> {
                InvocationAdapter.widen(mv, lDesc, "F")
                InvocationAdapter.widen(mv, rDesc, "F")
                mv.visitInsn(Opcodes.FCMPL)
                cmpResultJump(e.op)
            }
            lDesc == "D" || rDesc == "D" -> {
                InvocationAdapter.widen(mv, lDesc, "D")
                InvocationAdapter.widen(mv, rDesc, "D")
                mv.visitInsn(Opcodes.DCMPL)
                cmpResultJump(e.op)
            }
            else -> {
                // 整型：IF_ICMPxx
                when (e.op) {
                    CompareOp.EQ -> jumpPush(Opcodes.IF_ICMPEQ)
                    CompareOp.NE -> jumpPush(Opcodes.IF_ICMPNE)
                    CompareOp.LT -> jumpPush(Opcodes.IF_ICMPLT)
                    CompareOp.LE -> jumpPush(Opcodes.IF_ICMPLE)
                    CompareOp.GT -> jumpPush(Opcodes.IF_ICMPGT)
                    CompareOp.GE -> jumpPush(Opcodes.IF_ICMPGE)
                }
            }
        }
    }

    /** LCMP/FCMPL/DCMPL 结果（-1/0/1）按运算符跳转并 push 0/1。 */
    private fun cmpResultJump(op: CompareOp) {
        when (op) {
            CompareOp.EQ -> jumpPush(Opcodes.IFEQ)
            CompareOp.NE -> jumpPush(Opcodes.IFNE)
            CompareOp.LT -> jumpPush(Opcodes.IFLT)
            CompareOp.LE -> jumpPush(Opcodes.IFLE)
            CompareOp.GT -> jumpPush(Opcodes.IFGT)
            CompareOp.GE -> jumpPush(Opcodes.IFGE)
        }
    }

    /** 条件跳转 + push 0/1（满足 → 1，否则 → 0）。 */
    private fun jumpPush(jumpOpcode: Int) {
        val trueL = Label()
        val endL = Label()
        mv.visitJumpInsn(jumpOpcode, trueL)
        pushInt(0)
        mv.visitJumpInsn(Opcodes.GOTO, endL)
        mv.visitLabel(trueL)
        pushInt(1)
        mv.visitLabel(endL)
    }

    /** 栈顶布尔取反（0/1 → 1/0）。 */
    private fun negateBoolean() {
        val trueL = Label()
        val endL = Label()
        mv.visitJumpInsn(Opcodes.IFEQ, trueL)
        pushInt(0)
        mv.visitJumpInsn(Opcodes.GOTO, endL)
        mv.visitLabel(trueL)
        pushInt(1)
        mv.visitLabel(endL)
    }

    private fun isPrimitiveDesc(desc: String): Boolean =
        desc.length == 1 && desc != "V"

    private fun emitNot(e: IrExpr.Not) {
        emitExpr(e.operand)
        val endL = Label()
        val falseL = Label()
        mv.visitJumpInsn(Opcodes.IFEQ, falseL)
        pushInt(0)
        mv.visitJumpInsn(Opcodes.GOTO, endL)
        mv.visitLabel(falseL)
        pushInt(1)
        mv.visitLabel(endL)
    }

    private fun emitNeg(e: IrExpr.Neg) {
        emitExpr(e.operand)
        val desc = JvmTypeMapper.descriptor(e.operand.inferType() ?: IrType.INT)
        mv.visitInsn(
            when (desc) {
                "J" -> Opcodes.LNEG
                "F" -> Opcodes.FNEG
                "D" -> Opcodes.DNEG
                else -> Opcodes.INEG
            },
        )
    }

    private fun emitConvert(e: IrExpr.Convert) {
        emitExpr(e.expr)
        val from = JvmTypeMapper.descriptor(e.expr.inferType() ?: IrType.ANY)
        val to = JvmTypeMapper.descriptor(e.to)
        if (from == to) return
        when (from to to) {
            "I" to "J" -> mv.visitInsn(Opcodes.I2L)
            "I" to "F" -> mv.visitInsn(Opcodes.I2F)
            "I" to "D" -> mv.visitInsn(Opcodes.I2D)
            "J" to "I" -> mv.visitInsn(Opcodes.L2I)
            "J" to "F" -> mv.visitInsn(Opcodes.L2F)
            "J" to "D" -> mv.visitInsn(Opcodes.L2D)
            "F" to "I" -> mv.visitInsn(Opcodes.F2I)
            "F" to "J" -> mv.visitInsn(Opcodes.F2L)
            "F" to "D" -> mv.visitInsn(Opcodes.F2D)
            "D" to "I" -> mv.visitInsn(Opcodes.D2I)
            "D" to "J" -> mv.visitInsn(Opcodes.D2L)
            "D" to "F" -> mv.visitInsn(Opcodes.D2F)
            else -> {
                // 引用 ↔ 基本：委托 InvocationAdapter（CHECKCAST/box/unbox）
                if (isPrimitiveDesc(from) != isPrimitiveDesc(to)) {
                    InvocationAdapter.adaptResult(mv, from, e.to)
                } else if (to.startsWith("L") && from.startsWith("L")) {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, to.substring(1, to.length - 1))
                }
            }
        }
    }

    private fun emitIsType(e: IrExpr.IsType) {
        emitExpr(e.expr)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, JvmTypeMapper.internalName(e.type))
    }

    /** 调用（语句/表达式共用）：接收者 + 实参 + INVOKE*。 */
    private fun emitInvoke(callable: yux.compiler.ir.IrCallable, receiver: IrExpr?, args: List<IrExpr>, isSuper: Boolean = false) {
        val resolved = classEmitter.resolveCallable(callable)
        if (resolved.isStatic) {
            args.forEachIndexed { i, arg ->
                emitExpr(arg)
                InvocationAdapter.adaptArg(mv, argType(arg), resolved.realParamDescs.getOrElse(i) { JvmTypeMapper.descriptor(arg.inferType() ?: IrType.ANY) })
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, resolved.ownerInternal, resolved.name, resolved.desc, false)
        } else {
            receiver?.let { emitExpr(it) }
            boxPrimitiveReceiver(receiver, resolved)
            args.forEachIndexed { i, arg ->
                emitExpr(arg)
                InvocationAdapter.adaptArg(mv, argType(arg), resolved.realParamDescs.getOrElse(i) { JvmTypeMapper.descriptor(arg.inferType() ?: IrType.ANY) })
            }
            // super 调用：对父类非虚拟调用（INVOKESPECIAL，S-8.7.1）
            val opcode = when {
                isSuper -> Opcodes.INVOKESPECIAL
                resolved.isInterface -> Opcodes.INVOKEINTERFACE
                else -> Opcodes.INVOKEVIRTUAL
            }
            mv.visitMethodInsn(opcode, resolved.ownerInternal, resolved.name, resolved.desc, resolved.isInterface)
        }
        // 返回值适配（T-M5-7）：真实 JVM 返回类型 → IR 类型（`Iterator.next()` Object → Int 拆箱）
        if (resolved.realRetDesc != "V") {
            InvocationAdapter.adaptResult(mv, resolved.realRetDesc, callable.retType)
        }
    }

    /** 原语接收者装箱（`n.intValue()` / `n.toString()`）：Basic 原语经 jvmForBasic 解析为装箱类
     *  实例方法时，栈上是原语，需先 valueOf 装箱再 INVOKEVIRTUAL。 */
    private fun boxPrimitiveReceiver(receiver: IrExpr?, resolved: JvmDescResolver.ResolvedMethod) {
        val rt = receiver?.inferType() ?: return
        val rtDesc = JvmTypeMapper.descriptor(rt)
        if (!isPrimitiveDesc(rtDesc)) return
        if (JvmTypeMapper.boxedDescriptor(rt) == "L${resolved.ownerInternal};") {
            InvocationAdapter.boxPrimitive(mv, rtDesc)
        }
    }

    /** 函数类型值调用（T-M5-4 FnInvoke）：FunctionN.invoke + 装箱适配。 */
    private fun emitFnInvoke(e: IrExpr.FnInvoke) {
        val fnType = e.fn.inferType() as? IrType.Function
            ?: error("M5 FnInvoke 函数类型缺失: ${e.fn.inferType()}")
        val interfaceName = JvmTypeMapper.functionInternal(fnType)
            ?: error("函数类型参数过多（>4），JVM 后端不支持: ${fnType.render()}")
        emitExpr(e.fn)
        e.args.forEachIndexed { i, arg ->
            emitExpr(arg)
            // FunctionN.invoke 擦除签名 (Object...)Object：装箱
            InvocationAdapter.adaptArg(mv, argType(arg), "Ljava/lang/Object;")
        }
        val samDesc = "(" + List(e.args.size) { "Ljava/lang/Object;" }.joinToString("") + ")Ljava/lang/Object;"
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, interfaceName, "invoke", samDesc, true)
        // 结果适配到返回类型；Unit 返回时 FunctionN.invoke 残留 Object，必须弹出保持栈平衡
        val retDesc = JvmTypeMapper.descriptor(fnType.ret)
        when {
            retDesc == "V" -> mv.visitInsn(Opcodes.POP)
            retDesc != "Ljava/lang/Object;" -> InvocationAdapter.adaptResult(mv, "Ljava/lang/Object;", fnType.ret)
        }
    }

    private fun emitStringTemplate(e: IrExpr.StringTemplate) {
        // StringBuilder.append 序列（02-§9.3 / T-M5-8）
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        for (part in e.parts) {
            emitExpr(part)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", appendDesc(part), false)
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
    }

    /** StringBuilder.append 描述符：按操作数 IR 类型选 append 重载。 */
    private fun appendDesc(part: IrExpr): String {
        val desc = JvmTypeMapper.descriptor(part.inferType() ?: IrType.STRING)
        return when (desc) {
            "J" -> "(J)Ljava/lang/StringBuilder;"
            "F" -> "(F)Ljava/lang/StringBuilder;"
            "D" -> "(D)Ljava/lang/StringBuilder;"
            // Char 走 append(char)：`"a" + 'b'` 输出 "ab" 而非 append(int) 的 "a98"
            "C" -> "(C)Ljava/lang/StringBuilder;"
            "I", "Z", "B" -> "(I)Ljava/lang/StringBuilder;"
            "Ljava/lang/String;" -> "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
            else -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
        }
    }

    /** 空守卫（T-M5-8 / 02-§9.4）：接收者 null → 类型默认值。 */
    private fun emitNullGuard(e: IrExpr.NullGuard) {
        val guarded = e.expr
        val nullL = Label()
        val endL = Label()
        when (guarded) {
            is IrExpr.Invoke -> {
                // 守卫对象：实例调用为 receiver，静态调用为第 0 实参（扩展函数 receiver，M9）
                val guardExpr = guarded.receiver ?: guarded.args.firstOrNull()
                if (guardExpr == null) {
                    emitExpr(guarded)
                    return
                }
                // 接收者已在栈上（DUP 守卫）：只发实参 + 调用
                emitExpr(guardExpr)
                mv.visitInsn(Opcodes.DUP)
                mv.visitJumpInsn(Opcodes.IFNULL, nullL)
                guarded.args.forEachIndexed { i, arg ->
                    // 静态守卫：第 0 实参（receiver）已发射，跳过
                    if (guarded.receiver == null && i == 0) return@forEachIndexed
                    emitExpr(arg)
                    val real = resolvedParamDescs(guarded).getOrElse(i) { JvmTypeMapper.descriptor(arg.inferType() ?: IrType.ANY) }
                    InvocationAdapter.adaptArg(mv, argType(arg), real)
                }
                emitInvokeNoReceiver(guarded)
                mv.visitJumpInsn(Opcodes.GOTO, endL)
            }
            is IrExpr.FieldRead -> {
                emitExpr(guarded.receiver!!)
                mv.visitInsn(Opcodes.DUP)
                mv.visitJumpInsn(Opcodes.IFNULL, nullL)
                emitFieldReadNoReceiver(guarded)
                mv.visitJumpInsn(Opcodes.GOTO, endL)
            }
            else -> {
                emitExpr(guarded)
                return
            }
        }
        mv.visitLabel(nullL)
        mv.visitInsn(Opcodes.POP) // 弹掉 null 接收者
        emitDefault(guarded.inferType() ?: IrType.ANY)
        mv.visitLabel(endL)
    }

    private fun resolvedParamDescs(invoke: IrExpr.Invoke): List<String> =
        classEmitter.resolveCallable(invoke.target).realParamDescs

    /** 实例调用发射（接收者已在栈上，不重复发射）。 */
    private fun emitInvokeNoReceiver(invoke: IrExpr.Invoke) {
        val resolved = classEmitter.resolveCallable(invoke.target)
        val opcode = when {
            resolved.isStatic -> Opcodes.INVOKESTATIC
            invoke.isSuper -> Opcodes.INVOKESPECIAL
            resolved.isInterface -> Opcodes.INVOKEINTERFACE
            else -> Opcodes.INVOKEVIRTUAL
        }
        mv.visitMethodInsn(opcode, resolved.ownerInternal, resolved.name, resolved.desc, resolved.isInterface)
        InvocationAdapter.adaptResult(mv, resolved.realRetDesc, invoke.target.retType)
    }

    /** 字段读取发射（接收者已在栈上）。 */
    private fun emitFieldReadNoReceiver(fieldRead: IrExpr.FieldRead) {
        val field = fieldRead.field
        val ownerInternal = field.owner?.replace('.', '/') ?: classEmitter.ownerInternalName
        mv.visitFieldInsn(Opcodes.GETFIELD, ownerInternal, field.name, JvmTypeMapper.descriptor(field.type))
    }

    private fun emitDefault(type: IrType) {
        val desc = JvmTypeMapper.descriptor(type)
        when (desc) {
            "I", "Z", "C", "B" -> mv.visitInsn(Opcodes.ICONST_0)
            "J" -> mv.visitInsn(Opcodes.LCONST_0)
            "F" -> mv.visitInsn(Opcodes.FCONST_0)
            "D" -> mv.visitInsn(Opcodes.DCONST_0)
            else -> mv.visitInsn(Opcodes.ACONST_NULL)
        }
    }

    /** Lambda（T-M5-4 / 02-§9.1）：invokedynamic LambdaMetafactory。 */
    private fun emitLambda(e: IrExpr.Lambda) {
        val target = e.target.method
        // 捕获参数在前（M5 约定）：命名参数 = 方法参数去掉捕获
        val fnType = IrType.Function(
            params = target.params.drop(e.captures.size).map { it.type },
            ret = target.returnType,
        )
        val interfaceName = JvmTypeMapper.functionInternal(fnType)
            ?: error("函数类型参数过多（>4），JVM 后端不支持: ${fnType.render()}")
        val samName = "invoke"
        // samMethodType = 擦除的 SAM 签名（Object...）；instantiatedMethodType = 装箱实际类型
        // （LambdaMetafactory 用 asType 把 implMethod (I)I 适配到 (Integer)Integer）
        val samDesc = "(" + List(fnType.params.size) { "Ljava/lang/Object;" }.joinToString("") + ")Ljava/lang/Object;"
        val samType = Type.getMethodType(samDesc)
        val instantiatedDesc = "(" +
            fnType.params.joinToString("") { JvmTypeMapper.boxedDescriptor(it) } +
            ")" + JvmTypeMapper.boxedDescriptor(fnType.ret)
        val instantiatedType = Type.getMethodType(instantiatedDesc)

        // 捕获实参（先 this 后捕获，LambdaMetafactory 要求捕获在前）
        val captureDescs = mutableListOf<String>()
        if (!target.isStatic) {
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            captureDescs += "L${classEmitter.ownerInternalName};"
        }
        e.captures.forEach { c ->
            emitExpr(c)
            captureDescs += JvmTypeMapper.descriptor(c.inferType() ?: IrType.ANY)
        }
        val factoryDesc = "(${captureDescs.joinToString("")})L$interfaceName;"

        val implDesc = "(" +
            target.params.joinToString("") { JvmTypeMapper.descriptor(it.type) } +
            ")" + JvmTypeMapper.descriptor(target.returnType)
        val implKind = if (target.isStatic) Opcodes.H_INVOKESTATIC else Opcodes.H_INVOKEVIRTUAL
        val implHandle = Handle(
            implKind,
            target.owner?.name?.let(JvmTypeMapper::internalClassName) ?: classEmitter.ownerInternalName,
            target.name,
            implDesc,
            false,
        )
        val bsm = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                "Ljava/lang/invoke/CallSite;",
            false,
        )
        mv.visitInvokeDynamicInsn(samName, factoryDesc, bsm, samType, implHandle, instantiatedType)
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private fun argType(arg: IrExpr): IrType = arg.inferType() ?: IrType.ANY

    private fun loadLocal(local: IrLocal) {
        val slot = slots[local] ?: error("局部槽位缺失: ${local.name}")
        val desc = JvmTypeMapper.descriptor(local.type)
        mv.visitVarInsn(
            when (desc) {
                "J" -> Opcodes.LLOAD
                "F" -> Opcodes.FLOAD
                "D" -> Opcodes.DLOAD
                "I", "Z", "C", "B" -> Opcodes.ILOAD
                else -> Opcodes.ALOAD
            },
            slot,
        )
    }

    private fun storeLocal(local: IrLocal, valueType: IrType?) {
        val slot = slots[local] ?: error("局部槽位缺失: ${local.name}")
        val desc = JvmTypeMapper.descriptor(local.type)
        // 数值加宽/装箱适配（`l:Long = 1` → I2L；`age:Int? = 18` → valueOf 装箱）
        if (valueType != null) {
            InvocationAdapter.adaptArg(mv, valueType, desc)
        }
        mv.visitVarInsn(
            when (desc) {
                "J" -> Opcodes.LSTORE
                "F" -> Opcodes.FSTORE
                "D" -> Opcodes.DSTORE
                "I", "Z", "C", "B" -> Opcodes.ISTORE
                else -> Opcodes.ASTORE
            },
            slot,
        )
    }

    private fun popIfNeeded(type: IrType) {
        val desc = JvmTypeMapper.descriptor(type)
        if (desc == "V") return
        mv.visitInsn(if (desc == "J" || desc == "D") Opcodes.POP2 else Opcodes.POP)
    }

    private fun labelOf(label: IrLabel): Label = labels.getOrPut(label) { Label() }
}
