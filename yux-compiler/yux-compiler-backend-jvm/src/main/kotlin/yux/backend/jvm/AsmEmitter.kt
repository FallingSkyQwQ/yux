package yux.backend.jvm

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import yux.compiler.ir.ArithOp
import yux.compiler.ir.CompareOp
import yux.compiler.ir.IrAnnotation
import yux.compiler.ir.IrCallable
import yux.compiler.ir.IrCatch
import yux.compiler.ir.IrClass
import yux.compiler.ir.IrExpr
import yux.compiler.ir.IrField
import yux.compiler.ir.IrJvmCall
import yux.compiler.ir.IrLabel
import yux.compiler.ir.IrLocal
import yux.compiler.ir.IrMethod
import yux.compiler.ir.IrMethodRef
import yux.compiler.ir.IrModule
import yux.compiler.ir.IrStmt
import yux.compiler.ir.IrType

/**
 * 类级发射器（T-M5-1/02-§9.2）：一个 [IrClass] → `.class` 字节。
 *
 * 覆盖：类/文件类命名、私有字段 + 访问器（T-M5-2）、方法体（T-M5-3）、
 * 构造/调用/Lambda（T-M5-4）、注解（T-M5-6）、空守卫/字符串模板（T-M5-8）、
 * async 同步降级（T-M5-9）。data 类合成方法见 [AsmDataGen]。
 */
class AsmEmitter(
    private val module: IrModule,
    private val resolver: JvmDescResolver = JvmDescResolver(),
) {
    private val ownerName: String get() = cls.name
    private lateinit var cls: IrClass

    /** 当前类内部名（StmtEmitter 字段访问/捕获用）。 */
    val ownerInternalName: String get() = cls.name

    fun emitClass(irClass: IrClass): ByteArray {
        cls = irClass
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        val superInternal = irClass.superType?.let { JvmTypeMapper.internalName(it) } ?: "java/lang/Object"
        val interfaces = irClass.interfaces.map { JvmTypeMapper.internalName(it) }.toTypedArray()
        cw.visit(
            Opcodes.V21,
            Opcodes.ACC_PUBLIC,
            ownerName,
            null,
            superInternal,
            interfaces,
        )
        emitAnnotations(cw::visitAnnotation, irClass.annotations)

        // 字段：私有（T-M5-2）；文件类静态字段 + 静态访问器由 IRGen 生成
        for (field in irClass.fields) {
            val access = if (field.isStatic) Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC else Opcodes.ACC_PRIVATE
            cw.visitField(access, field.name, JvmTypeMapper.descriptor(field.type), null, null)
        }

        // 方法（含构造器、$clinit、访问器、Lambda 合成方法）
        for (method in irClass.methods) {
            emitMethod(cw, method)
        }
        // data 类自动方法（T-M5-5 / S-5.3.1）
        if (irClass.isData) {
            AsmDataGen(irClass).emit(cw)
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    // ── 方法 ──────────────────────────────────────────────────────────────────

    private fun emitMethod(cw: ClassWriter, method: IrMethod) {
        try {
            emitMethodInner(cw, method)
        } catch (e: RuntimeException) {
            throw RuntimeException("ASM 方法生成失败: ${method.owner?.name}.${method.name}(${methodDescriptor(method)}): ${e.message}", e)
        }
    }

    private fun emitMethodInner(cw: ClassWriter, method: IrMethod) {
        val jvmName = when {
            method.isConstructor -> "<init>"
            method.name == "\$clinit" -> "<clinit>"
            else -> method.name
        }
        val access = buildAccess(method)
        val desc = methodDescriptor(method)
        val mv = cw.visitMethod(access, jvmName, desc, null, null)
        emitAnnotations(mv::visitAnnotation, method.annotations)
        mv.visitCode()
        if (method.isConstructor) {
            // 先调用父构造器（Yux 构造实参 = 属性；superType 支持后置，M4 起 Object）
            val superInternal = cls.superType?.let { JvmTypeMapper.internalName(it) } ?: "java/lang/Object"
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "()V", false)
        }
        // 局部槽位分配：this(0) + 参数 + 方法体局部
        val slots = LocalSlotAllocator(method, cls.isFileClass).allocate()
        val emitter = StmtEmitter(mv, this, slots, method)
        for (stmt in method.body) {
            emitter.emitStmt(stmt)
        }
        // 隐式返回（void 方法体末尾；非 void 由 Sema 保证 return）
        if (method.returnType is IrType.Void) {
            mv.visitInsn(Opcodes.RETURN)
        }
        mv.visitMaxs(0, 0) // COMPUTE_FRAMES 自动计算
        mv.visitEnd()
    }
    private fun buildAccess(method: IrMethod): Int {
        var access = Opcodes.ACC_PUBLIC
        if (method.isStatic || method.name == "\$clinit") access = access or Opcodes.ACC_STATIC
        return access
    }

    private fun methodDescriptor(method: IrMethod): String {
        val params = method.params.joinToString("") { JvmTypeMapper.descriptor(it.type) }
        val ret = if (method.isConstructor) "V" else JvmTypeMapper.descriptor(method.returnType)
        return "($params)$ret"
    }

    /** 调用解析：Yux 方法引用 → 描述符/所有者；JVM 调用 → 反射解析。 */
    fun resolveCallable(callable: IrCallable): JvmDescResolver.ResolvedMethod = when (callable) {
        is IrMethodRef -> {
            val m = callable.method
            JvmDescResolver.ResolvedMethod(
                ownerInternal = m.owner?.name ?: ownerName,
                name = m.name,
                desc = methodDescriptor(m),
                isStatic = m.isStatic,
                isInterface = false,
                realParamDescs = m.params.map { JvmTypeMapper.descriptor(it.type) },
                realRetDesc = if (m.isConstructor) "V" else JvmTypeMapper.descriptor(m.returnType),
            )
        }
        is IrJvmCall -> resolver.resolve(callable)
    }

    /** 构造器解析：用户类用 IR 构造器；JVM 类用反射。返回描述符 + 真实参数描述符。 */
    fun resolveConstructor(type: IrType, args: List<IrExpr>): ResolvedConstructor {
        val t = type.nonNull()
        val ownerDotted = when (t) {
            is IrType.Basic -> when (t.name) {
                "Range" -> "yux.core.Range"
                else -> error("M5 不支持构造基础类型: ${t.name}")
            }
            is IrType.Declared -> when (val s = t.symbol) {
                is yux.compiler.sema.YxClassSymbol -> {
                    val irClass = module.classNamed(s.name)
                        ?: error("M5 构造器解析失败: 类未生成 ${s.name}")
                    val ctor = irClass.constructor
                        ?: error("M5 构造器解析失败: 无构造器 ${s.name}")
                    return ResolvedConstructor(methodDescriptor(ctor), ctor.params.map { JvmTypeMapper.descriptor(it.type) })
                }
                is yux.compiler.sema.JvmClassSymbol -> s.qualifiedName
                else -> error("M5 不支持构造类型: ${t.render()}")
            }
            else -> error("M5 不支持构造类型: ${t.render()}")
        }
        val argTypes = args.map { it.inferType() ?: IrType.ANY }
        val desc = resolver.constructorDesc(ownerDotted, argTypes)
        return ResolvedConstructor(desc, argTypes.map { JvmTypeMapper.descriptor(it) })
    }

    /** 构造器解析结果。 */
    data class ResolvedConstructor(
        val desc: String,
        /** 真实参数描述符（装箱适配用；JVM 类反射优先）。 */
        val paramDescs: List<String>,
    )

    /** 注解发射（T-M5-6）：RuntimeVisible + 常量实参。 */
    private fun emitAnnotations(visit: (String, Boolean) -> org.objectweb.asm.AnnotationVisitor, annotations: List<IrAnnotation>) {
        for (a in annotations) {
            val av = visit("L${a.name.replace('.', '/')};", true)
            for (arg in a.args) {
                av.visit(arg.name, arg.value)
            }
            av.visitEnd()
        }
    }
}

/** 局部槽位分配：IrLocal.index（编译期序号）→ JVM 槽位。 */
internal class LocalSlotAllocator(
    private val method: IrMethod,
    private val isFileClass: Boolean,
) {
    fun allocate(): Map<IrLocal, Int> {
        val all = LinkedHashMap<Int, IrLocal>()
        method.paramLocals.forEach { all[it.index] = it }
        collectLocals(method.body, all)
        val sorted = all.entries.sortedBy { it.key }
        val slots = HashMap<IrLocal, Int>()
        var slot = if (method.isStatic) 0 else 1 // 实例方法槽 0 = this
        for ((_, local) in sorted) {
            slots[local] = slot
            slot += if (JvmTypeMapper.isWide(local.type)) 2 else 1
        }
        return slots
    }

    private fun collectLocals(stmts: List<IrStmt>, out: MutableMap<Int, IrLocal>) {
        for (stmt in stmts) {
            when (stmt) {
                is IrStmt.LocalAssign -> out[stmt.local.index] = stmt.local
                is IrStmt.Call -> {
                    collectExpr(stmt.receiver, out)
                    stmt.args.forEach { collectExpr(it, out) }
                }
                is IrStmt.New -> stmt.args.forEach { collectExpr(it, out) }
                is IrStmt.Eval -> collectExpr(stmt.expr, out)
                is IrStmt.FieldAccess -> {
                    collectExpr(stmt.receiver, out)
                    collectExpr(stmt.value, out)
                }
                is IrStmt.Branch -> collectExpr(stmt.cond, out)
                is IrStmt.Return -> collectExpr(stmt.value, out)
                is IrStmt.Throw -> collectExpr(stmt.value, out)
                is IrStmt.Monitor -> collectExpr(stmt.expr, out)
                is IrStmt.Try -> {
                    collectLocals(stmt.body, out)
                    stmt.catches.forEach { c ->
                        collectLocals(c.body, out)
                        collectCatchParam(c, out)
                    }
                    stmt.finallyBody?.let { collectLocals(it, out) }
                }
                else -> Unit
            }
        }
    }

    private fun collectCatchParam(c: IrCatch, out: MutableMap<Int, IrLocal>) {
        // catch 参数局部已由 IRGen 登记；按名字在 catch 体中回找
        findLocalByName(c.body, c.paramName)?.let { out[it.index] = it }
    }

    private fun findLocalByName(stmts: List<IrStmt>, name: String): IrLocal? {
        for (stmt in stmts) {
            if (stmt is IrStmt.LocalAssign && stmt.local.name == name) return stmt.local
        }
        // catch 参数以 LocalRead 出现
        var found: IrLocal? = null
        fun walk(e: IrExpr?) {
            if (found != null || e == null) return
            if (e is IrExpr.LocalRead && e.local.name == name) found = e.local
        }
        for (stmt in stmts) {
            when (stmt) {
                is IrStmt.LocalAssign -> walk(stmt.value)
                is IrStmt.Return -> walk(stmt.value)
                is IrStmt.Eval -> walk(stmt.expr)
                is IrStmt.Branch -> walk(stmt.cond)
                is IrStmt.Call -> { walk(stmt.receiver); stmt.args.forEach { walk(it) } }
                else -> Unit
            }
        }
        return found
    }

    private fun collectExpr(e: IrExpr?, out: MutableMap<Int, IrLocal>) {
        if (e == null) return
        when (e) {
            is IrExpr.LocalRead -> out[e.local.index] = e.local
            is IrExpr.FieldRead -> collectExpr(e.receiver, out)
            is IrExpr.New -> e.args.forEach { collectExpr(it, out) }
            is IrExpr.Arith -> { collectExpr(e.l, out); collectExpr(e.r, out) }
            is IrExpr.Compare -> { collectExpr(e.l, out); collectExpr(e.r, out) }
            is IrExpr.Not -> collectExpr(e.operand, out)
            is IrExpr.Neg -> collectExpr(e.operand, out)
            is IrExpr.Convert -> collectExpr(e.expr, out)
            is IrExpr.IsType -> collectExpr(e.expr, out)
            is IrExpr.Invoke -> {
                collectExpr(e.receiver, out)
                e.args.forEach { collectExpr(it, out) }
            }
            is IrExpr.FnInvoke -> {
                collectExpr(e.fn, out)
                e.args.forEach { collectExpr(it, out) }
            }
            is IrExpr.StringTemplate -> e.parts.forEach { collectExpr(it, out) }
            is IrExpr.NullGuard -> collectExpr(e.expr, out)
            is IrExpr.Lambda -> e.captures.forEach { collectExpr(it, out) }
            else -> Unit
        }
    }
}
