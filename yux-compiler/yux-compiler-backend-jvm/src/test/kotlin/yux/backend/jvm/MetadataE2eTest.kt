package yux.backend.jvm

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-M12：LineNumberTable 与 Signature 属性回归测试。
 *
 * - 方法体携带源码行号（javap -l / 调试器/堆栈行号定位）；
 * - 泛型类/字段/方法发射 JVM Signature 属性（`Box<T>` → `<T:Ljava/lang/Object;>...`、
 *   `List<Int>` → `Ljava/util/List<Ljava/lang/Integer;>;`），纯擦除类型无 Signature。
 */
class MetadataE2eTest {

    /** 收集指定方法的 LineNumberTable 行号集。 */
    private fun lineNumbers(classBytes: ByteArray, methodName: String): List<Int> {
        val lines = mutableListOf<Int>()
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name != methodName) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitLineNumber(line: Int, start: org.objectweb.asm.Label) {
                            lines += line
                        }
                    }
                }
            },
            ClassReader.SKIP_FRAMES,
        )
        return lines
    }

    /** 类签名 / 指定字段签名 / 指定方法签名（null = 无 Signature 属性）。 */
    private fun signaturesOf(classBytes: ByteArray, fieldName: String?, methodName: String?): Triple<String?, String?, String?> {
        var classSig: String? = null
        var fieldSig: String? = null
        var methodSig: String? = null
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String?,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    classSig = signature
                }

                override fun visitField(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    if (name == fieldName) fieldSig = signature
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name == methodName) methodSig = signature
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG,
        )
        return Triple(classSig, fieldSig, methodSig)
    }

    @Test
    fun `method bodies carry source line numbers`() {
        // 注：常量赋值会被 SSA 常量传播消除（a=1; b=2; print(a+b) → print(3)），
        // 故用依赖参数的存活语句验证行号表
        val classes = BackendTestSupport.compile(
            """
            fun calc(n: Int) {
                b = n + 1
                print (b + n)
            }
            fun main() {
                calc(1)
            }
            """.trimIndent(),
        )
        val lines = lineNumbers(classes["Main"]!!, "calc")
        assertTrue(lines.isNotEmpty(), "calc 应产出 LineNumberTable")
        assertTrue(lines.contains(2), "b = n + 1 应在第 2 行: $lines")
        assertTrue(lines.contains(3), "print 应在第 3 行: $lines")
    }

    @Test
    fun `generic classes and fields emit signature attribute`() {
        val classes = BackendTestSupport.compile(
            """
            data Box T {
                item: T
                scores: List Int
            }
            fun main() {
            }
            """.trimIndent(),
        )
        val (classSig, fieldSig, _) = signaturesOf(classes["Box"]!!, "scores", null)
        assertTrue(
            classSig!!.startsWith("<T:Ljava/lang/Object;>"),
            "Box<T> 类签名应含类型参数: $classSig",
        )
        assertEquals(
            "Ljava/util/List<Ljava/lang/Integer;>;",
            fieldSig,
            "List Int 字段应保留泛型实参",
        )
    }

    @Test
    fun `method signature keeps generic params`() {
        val classes = BackendTestSupport.compile(
            """
            fun sum(xs: List Int): Int {
                return 0
            }
            fun main() {
                print sum((List Int() as List Int))
            }
            """.trimIndent(),
        )
        val (_, _, sumSig) = signaturesOf(classes["Main"]!!, null, "sum")
        assertEquals(
            "(Ljava/util/List<Ljava/lang/Integer;>;)I",
            sumSig,
            "sum(List Int):Int 方法签名应保留泛型实参",
        )
    }

    @Test
    fun `erased types get no signature attribute`() {
        val classes = BackendTestSupport.compile(
            """
            fun main() {
                x: Int = 1
                s = "a"
            }
            """.trimIndent(),
        )
        val (classSig, _, mainSig) = signaturesOf(classes["Main"]!!, null, "main")
        assertNull(classSig, "无泛型类不应有类签名")
        assertNull(mainSig, "纯擦除方法不应有签名")
    }
}
