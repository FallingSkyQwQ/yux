package yux.backend.jvm

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import yux.compiler.ir.IrType

/**
 * IR 类型 ↔ 真实 JVM 描述符适配（T-M5-7 / 02-§9.1）：调用点装箱/拆箱/转换。
 *
 * 互操作调用的实参与返回值在 IR 中按 Yux 类型标注，而 JVM 方法按真实描述符工作
 * （如 `Iterator.next()` 真实返回 `Object`，IR 按元素类型标注 `Int`）。发射器先用真实
 * 描述符发起调用，再按 IR 类型适配栈上值。
 */
internal object InvocationAdapter {

    /** 实参入栈前适配：IR 参数类型 → 真实参数描述符。 */
    fun adaptArg(mv: MethodVisitor, irType: IrType, realDesc: String) {
        val irDesc = JvmTypeMapper.descriptor(irType)
        if (irDesc == realDesc) return
        when {
            isPrimitive(irDesc) && !isPrimitive(realDesc) -> box(mv, irDesc)
            !isPrimitive(irDesc) && isPrimitive(realDesc) -> unbox(mv, realDesc)
        }
    }

    /** 返回值适配：真实返回描述符 → IR 返回类型。 */
    fun adaptResult(mv: MethodVisitor, realDesc: String, irRet: IrType) {
        val irDesc = JvmTypeMapper.descriptor(irRet)
        if (realDesc == irDesc) return
        if (realDesc == "V") return
        when {
            !isPrimitive(realDesc) && isPrimitive(irDesc) -> unbox(mv, irDesc)
            isPrimitive(realDesc) && !isPrimitive(irDesc) -> box(mv, realDesc)
            !isPrimitive(realDesc) && !isPrimitive(irDesc) -> castTo(mv, irDesc)
        }
    }

    private fun box(mv: MethodVisitor, primitiveDesc: String) {
        when (primitiveDesc) {
            "I" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
            "J" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            "F" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)
            "D" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
            "Z" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            "C" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false)
            "B" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false)
        }
    }

    private fun unbox(mv: MethodVisitor, primitiveDesc: String) {
        val (boxed, method) = when (primitiveDesc) {
            "I" -> "java/lang/Integer" to "intValue"
            "J" -> "java/lang/Long" to "longValue"
            "F" -> "java/lang/Float" to "floatValue"
            "D" -> "java/lang/Double" to "doubleValue"
            "Z" -> "java/lang/Boolean" to "booleanValue"
            "C" -> "java/lang/Character" to "charValue"
            "B" -> "java/lang/Byte" to "byteValue"
            else -> return
        }
        mv.visitTypeInsn(Opcodes.CHECKCAST, boxed)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, boxed, method, "()$primitiveDesc", false)
    }

    private fun castTo(mv: MethodVisitor, refDesc: String) {
        if (refDesc.startsWith("L")) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, refDesc.substring(1, refDesc.length - 1))
        }
    }

    private fun isPrimitive(desc: String): Boolean =
        desc.length == 1 && desc != "V"
}
