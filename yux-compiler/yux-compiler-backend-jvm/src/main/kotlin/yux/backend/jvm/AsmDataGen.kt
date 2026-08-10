package yux.backend.jvm

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import yux.compiler.ir.IrClass
import yux.compiler.ir.IrType

/**
 * data 类自动方法生成（T-M5-5 / 01-§5.3 S-5.3.1）：`equals`/`hashCode`/`toString`。
 *
 * 全参构造器已由 IRGen 生成；本类补充三个 Object 方法：
 * - `toString`：`User(id=1, name=Steve)`（属性名 + 值，字符串不引号）；
 * - `equals`：引用相等短路 → instanceof → 逐属性相等（基本类型 `==`，引用 `Objects.equals`）；
 * - `hashCode`：标准 31 进制组合（基本类型包装 hashCode，引用 `Objects.hashCode`）。
 */
internal class AsmDataGen(
    private val irClass: IrClass,
) {
    private val props = irClass.properties

    fun emit(cw: ClassWriter) {
        emitToString(cw)
        emitEquals(cw)
        emitHashCode(cw)
    }

    private fun emitToString(cw: ClassWriter) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null)
        mv.visitCode()
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        appendConst(mv, "${irClass.name}(")
        props.forEachIndexed { i, prop ->
            if (i > 0) appendConst(mv, ", ")
            appendConst(mv, "${prop.name}=")
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(
                Opcodes.GETFIELD,
                irClass.name,
                prop.backingField!!.name,
                JvmTypeMapper.descriptor(prop.type),
            )
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                appendDesc(prop.type),
                false,
            )
        }
        appendConst(mv, ")")
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitEquals(cw: ClassWriter) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null)
        mv.visitCode()
        // this == o → true
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        val notSame = Label()
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, notSame)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitLabel(notSame)
        // !(o instanceof User) → false
        val isType = Label()
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, irClass.name)
        mv.visitJumpInsn(Opcodes.IFNE, isType)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitLabel(isType)
        // other = (User) o；逐属性比较
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitTypeInsn(Opcodes.CHECKCAST, irClass.name)
        mv.visitVarInsn(Opcodes.ASTORE, 2)
        for (prop in props) {
            val field = prop.backingField!!
            val desc = JvmTypeMapper.descriptor(prop.type)
            val mismatch = Label()
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(Opcodes.GETFIELD, irClass.name, field.name, desc)
            mv.visitVarInsn(Opcodes.ALOAD, 2)
            mv.visitFieldInsn(Opcodes.GETFIELD, irClass.name, field.name, desc)
            when {
                desc == "J" -> {
                    mv.visitInsn(Opcodes.LCMP)
                    mv.visitJumpInsn(Opcodes.IFNE, mismatch)
                }
                desc == "F" -> {
                    // Float.compare 语义：NaN 相等、-0.0f ≠ 0.0f，与 Float.hashCode 契约一致
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false)
                    mv.visitJumpInsn(Opcodes.IFNE, mismatch)
                }
                desc == "D" -> {
                    // Double.compare 语义：NaN 相等、-0.0 ≠ 0.0，与 Double.hashCode 契约一致
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false)
                    mv.visitJumpInsn(Opcodes.IFNE, mismatch)
                }
                desc == "I" || desc == "Z" || desc == "C" || desc == "B" -> {
                    mv.visitJumpInsn(Opcodes.IF_ICMPNE, mismatch)
                }
                else -> {
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "java/util/Objects",
                        "equals",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                        false,
                    )
                    mv.visitJumpInsn(Opcodes.IFEQ, mismatch)
                }
            }
            // 属性相等 → 继续；不相等 → return false
            val next = Label()
            mv.visitJumpInsn(Opcodes.GOTO, next)
            mv.visitLabel(mismatch)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitInsn(Opcodes.IRETURN)
            mv.visitLabel(next)
        }
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitHashCode(cw: ClassWriter) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitVarInsn(Opcodes.ISTORE, 1)
        for (prop in props) {
            val field = prop.backingField!!
            val desc = JvmTypeMapper.descriptor(prop.type)
            // hash = 31 * hash + fieldHash
            mv.visitVarInsn(Opcodes.ILOAD, 1)
            mv.visitIntInsn(Opcodes.BIPUSH, 31)
            mv.visitInsn(Opcodes.IMUL)
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitFieldInsn(Opcodes.GETFIELD, irClass.name, field.name, desc)
            emitFieldHash(mv, prop.type, desc)
            mv.visitInsn(Opcodes.IADD)
            mv.visitVarInsn(Opcodes.ISTORE, 1)
        }
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** 字段哈希值入栈（栈上已有字段值）。 */
    private fun emitFieldHash(mv: MethodVisitor, type: IrType, desc: String) {
        when (desc) {
            "I", "Z", "C", "B" -> Unit // 值本身即哈希
            "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "hashCode", "(J)I", false)
            "F" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "hashCode", "(F)I", false)
            "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "hashCode", "(D)I", false)
            else -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "hashCode", "(Ljava/lang/Object;)I", false)
        }
    }

    private fun appendConst(mv: MethodVisitor, s: String) {
        mv.visitLdcInsn(s)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
    }

    private fun appendDesc(type: IrType): String = when (val desc = JvmTypeMapper.descriptor(type)) {
        "J" -> "(J)Ljava/lang/StringBuilder;"
        "F" -> "(F)Ljava/lang/StringBuilder;"
        "D" -> "(D)Ljava/lang/StringBuilder;"
        "C" -> "(C)Ljava/lang/StringBuilder;"
        "I", "Z", "B" -> "(I)Ljava/lang/StringBuilder;"
        "Ljava/lang/String;" -> "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
        else -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"
    }
}
