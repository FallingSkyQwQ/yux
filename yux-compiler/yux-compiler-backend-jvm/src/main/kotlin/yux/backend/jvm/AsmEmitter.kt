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
    /** 项目类解析加载器（M10 混合项目：Java/Kotlin 产物目录的 URLClassLoader）；默认编译管线自身加载器。 */
    private val classLoader: ClassLoader = JvmDescResolver::class.java.classLoader,
) {
    /** JVM 接口默认方法标志（JVMS 4.6 的 ACC_DEFAULT=0x1000；ASM 未导出常量）。 */
    private val defaultMethodFlag = 0x1000

    private val resolver: JvmDescResolver = JvmDescResolver(classLoader)
    /** 当前类 JVM 内部名（`cls.name` 为点分限定名，如 `com.example.Player`）。 */
    private val ownerName: String get() = JvmTypeMapper.internalClassName(cls.name)
    private lateinit var cls: IrClass

    /** 当前类内部名（StmtEmitter 字段访问/捕获用）。 */
    val ownerInternalName: String get() = JvmTypeMapper.internalClassName(cls.name)

    fun emitClass(irClass: IrClass): ByteArray {
        cls = irClass
        val cw = newClassWriter()
        // 接口类（Yux 类被 implements 引用）：JVM 接口 = ACC_INTERFACE|ACC_ABSTRACT，父类恒 Object，
        // 其 extends/implements 目标经 interfaces 数组以「接口继承接口」表达
        val superInternal = if (irClass.isInterface) {
            "java/lang/Object"
        } else {
            irClass.superType?.let { JvmTypeMapper.internalName(it) } ?: "java/lang/Object"
        }
        val interfaces = irClass.interfaces.map { JvmTypeMapper.internalName(it) }.toTypedArray()
        val access = if (irClass.isInterface) {
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
        } else {
            Opcodes.ACC_PUBLIC
        }
        cw.visit(
            Opcodes.V21,
            access,
            ownerName,
            null,
            superInternal,
            interfaces,
        )
        emitAnnotations(cw::visitAnnotation, irClass.annotations)

        // 字段：私有（T-M5-2）；文件类静态字段 + 静态访问器由 IRGen 生成。
        // 接口类跳过实例字段（JVM 接口不允许实例字段；接口类属性为不支持场景）
        for (field in irClass.fields) {
            if (irClass.isInterface) continue
            val fieldAccess = if (field.isStatic) Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC else Opcodes.ACC_PRIVATE
            cw.visitField(fieldAccess, field.name, JvmTypeMapper.descriptor(field.type), null, null)
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
        // 接口类不发射构造器（JVM 接口无 <init>；接口不可实例化，构造由实现类承担）
        if (cls.isInterface && method.isConstructor) return
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
        // 局部槽位分配：this(0) + 参数 + 方法体局部；临时槽（try/finally）从分配末尾高位申请
        val alloc = LocalSlotAllocator(method, cls.isFileClass).allocate()
        val emitter = StmtEmitter(mv, this, alloc, method)
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
        var access = if (cls.isInterface) {
            // 接口方法必须 public；Yux 方法恒有体 → 具体方法 = 默认方法（ACC_DEFAULT + Code）。
            // 静态方法不置 ACC_DEFAULT（JVM 禁止 static default 组合）
            if (method.isStatic) Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
            else Opcodes.ACC_PUBLIC or defaultMethodFlag
        } else {
            visibilityAccess(method.visibility)
        }
        if (method.isStatic || method.name == "\$clinit") access = access or Opcodes.ACC_STATIC
        return access
    }

    private fun visibilityAccess(visibility: yux.compiler.ast.YxVisibility): Int = when (visibility) {
        yux.compiler.ast.YxVisibility.PRIVATE -> Opcodes.ACC_PRIVATE
        yux.compiler.ast.YxVisibility.PROTECTED -> Opcodes.ACC_PROTECTED
        yux.compiler.ast.YxVisibility.PUBLIC -> Opcodes.ACC_PUBLIC
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
            // 接口分发（T-M11-5 接口运行时）：方法所有者是接口类（Yux 类被 implements 引用）时
            // 以 INVOKEINTERFACE 调用；非接口类维持 INVOKEVIRTUAL。
            JvmDescResolver.ResolvedMethod(
                ownerInternal = m.owner?.name?.let(JvmTypeMapper::internalClassName) ?: ownerName,
                name = m.name,
                desc = methodDescriptor(m),
                isStatic = m.isStatic,
                isInterface = m.owner?.isInterface == true,
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
                    val irClass = module.classNamed(s.qualifiedName)
                        ?: error("M5 构造器解析失败: 类未生成 ${s.qualifiedName}")
                    if (irClass.isInterface) {
                        error("接口类不可实例化（Yux 接口 = 被 implements 引用的类，JVM 接口无构造器）: ${s.name}")
                    }
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

    /** COMPUTE_FRAMES 用类写入器：getCommonSuperClass 以注入 classLoader 解析（项目类帧合并正确）。 */
    private fun newClassWriter(): ClassWriter = object : ClassWriter(ClassWriter.COMPUTE_FRAMES) {
        override fun getCommonSuperClass(type1: String, type2: String): String {
            val c1 = loadFrameClass(type1) ?: return "java/lang/Object"
            val c2 = loadFrameClass(type2) ?: return "java/lang/Object"
            if (c1.isAssignableFrom(c2)) return type1
            if (c2.isAssignableFrom(c1)) return type2
            if (c1.isInterface || c2.isInterface) return "java/lang/Object"
            var cur = c1
            while (!cur.isAssignableFrom(c2)) {
                cur = cur.superclass
            }
            return cur.name.replace('.', '/')
        }
    }

    private fun loadFrameClass(internal: String): Class<*>? = try {
        Class.forName(internal.replace('/', '.'), false, classLoader)
    } catch (_: Throwable) {
        null
    }
}

/** 局部槽位分配：IrLocal.index（编译期序号）→ JVM 槽位。 */
internal class LocalSlotAllocator(
    private val method: IrMethod,
    private val isFileClass: Boolean,
) {
    fun allocate(): SlotAllocation {
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
        // nextFreeSlot：已分配末尾后的首个空闲槽；try/finally 异常临时槽从该处高位申请，
        // 保证不与 this/参数/局部重叠（含 Long/Double 双槽局部）
        return SlotAllocation(slots, slot)
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
        // catch 参数局部已由 IRGen 登记；递归搜索 catch 体（含嵌套表达式/嵌套 Try）回找
        findLocalByNameRecursive(c.body, c.paramName)?.let { out[it.index] = it }
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

/** 槽位分配结果：[slots] 为 IrLocal → JVM 槽位；[nextFreeSlot] 为方法级临时槽起点。 */
internal data class SlotAllocation(
    val slots: Map<IrLocal, Int>,
    val nextFreeSlot: Int,
)

/** 递归查找语句/表达式树中指定名字的局部变量（catch 参数槽位定位用；须与槽位分配一致）。 */
internal fun findLocalByNameRecursive(stmts: List<IrStmt>, name: String): IrLocal? =
    stmts.firstNotNullOfOrNull { findLocalInStmt(it, name) }

private fun findLocalInStmt(stmt: IrStmt, name: String): IrLocal? = when (stmt) {
    is IrStmt.Label -> null
    is IrStmt.LocalAssign -> findLocalInExpr(stmt.value, name) ?: stmt.local.takeIf { it.name == name }
    is IrStmt.Call -> findLocalInExpr(stmt.receiver, name) ?: findLocalInExprs(stmt.args, name)
    is IrStmt.New -> findLocalInExprs(stmt.args, name)
    is IrStmt.Eval -> findLocalInExpr(stmt.expr, name)
    is IrStmt.FieldAccess -> findLocalInExpr(stmt.receiver, name) ?: findLocalInExpr(stmt.value, name)
    is IrStmt.Branch -> findLocalInExpr(stmt.cond, name)
    is IrStmt.Goto -> null
    is IrStmt.Return -> findLocalInExpr(stmt.value, name)
    is IrStmt.Throw -> findLocalInExpr(stmt.value, name)
    is IrStmt.Monitor -> findLocalInExpr(stmt.expr, name)
    is IrStmt.Try -> findLocalByNameRecursive(stmt.body, name)
        ?: stmt.catches.firstNotNullOfOrNull { findLocalByNameRecursive(it.body, name) }
        ?: stmt.finallyBody?.let { findLocalByNameRecursive(it, name) }
    is IrStmt.Nop -> null
}

private fun findLocalInExpr(e: IrExpr?, name: String): IrLocal? {
    if (e == null) return null
    return when (e) {
        is IrExpr.Const, is IrExpr.This, is IrExpr.ClassLiteral -> null
        is IrExpr.ArrayLoad -> findLocalInExpr(e.base, name) ?: findLocalInExpr(e.index, name)
        is IrExpr.LocalRead -> e.local.takeIf { it.name == name }
        is IrExpr.FieldRead -> findLocalInExpr(e.receiver, name)
        is IrExpr.New -> findLocalInExprs(e.args, name)
        is IrExpr.Arith -> findLocalInExpr(e.l, name) ?: findLocalInExpr(e.r, name)
        is IrExpr.Compare -> findLocalInExpr(e.l, name) ?: findLocalInExpr(e.r, name)
        is IrExpr.Not -> findLocalInExpr(e.operand, name)
        is IrExpr.Neg -> findLocalInExpr(e.operand, name)
        is IrExpr.Convert -> findLocalInExpr(e.expr, name)
        is IrExpr.IsType -> findLocalInExpr(e.expr, name)
        is IrExpr.Invoke -> findLocalInExpr(e.receiver, name) ?: findLocalInExprs(e.args, name)
        is IrExpr.FnInvoke -> findLocalInExpr(e.fn, name) ?: findLocalInExprs(e.args, name)
        is IrExpr.StringTemplate -> findLocalInExprs(e.parts, name)
        is IrExpr.NullGuard -> findLocalInExpr(e.expr, name)
        is IrExpr.Lambda -> findLocalInExprs(e.captures, name)
    }
}

private fun findLocalInExprs(es: List<IrExpr>, name: String): IrLocal? =
    es.firstNotNullOfOrNull { findLocalInExpr(it, name) }
