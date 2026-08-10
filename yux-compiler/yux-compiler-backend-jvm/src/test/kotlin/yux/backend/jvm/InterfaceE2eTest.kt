package yux.backend.jvm

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-M11-5：Yux 接口运行时（01-§8.7 无 interface 关键字 —— 类被 implements 引用即接口）。
 *
 * 验证：
 * - 被 implements 引用的 Yux 类发射为 JVM 接口（ACC_INTERFACE|ACC_ABSTRACT，父类 Object），
 *   其方法（Yux 恒有体）发射为默认方法（ACC_DEFAULT，可被实现类继承）；
 * - 调用已解析到接口成员的成员 → INVOKEINTERFACE <接口名>.<成员>；
 * - 实现类 override 接口成员 → INVOKEVIRTUAL <实现类>.<成员>；
 * - 接口继承接口 + 菱形多接口传递解析；
 * - 实例化接口类在编译期拒绝。
 */
class InterfaceE2eTest {

    /** JVM 接口默认方法标志（JVMS 4.6 的 ACC_DEFAULT=0x1000；ASM 未导出常量）。 */
    private val ACC_DEFAULT = 0x1000

    /** 类访问标志 + 父类名（接口类应为 ACC_INTERFACE|ACC_ABSTRACT 且父类 Object）。 */
    private fun classMeta(classBytes: ByteArray): Pair<Int, String> {
        var access = -1
        var superName = ""
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    accessFlags: Int,
                    name: String?,
                    signature: String?,
                    superClass: String?,
                    interfaces: Array<out String>?,
                ) {
                    access = accessFlags
                    superName = superClass ?: ""
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG,
        )
        return access to superName
    }

    /** 指定方法的访问标志（如 ACC_DEFAULT / ACC_ABSTRACT）；按方法名匹配（返回类型推断不影响）。 */
    private fun methodAccess(classBytes: ByteArray, name: String): Int {
        var found = -1
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    accessFlags: Int,
                    methodName: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (methodName == name) found = accessFlags
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG,
        )
        return found
    }

    /** 字节码中是否存在 owner.name 的指定 opcode 调用（INVOKEINTERFACE/INVOKEVIRTUAL）。 */
    private fun hasInvoke(classBytes: ByteArray, opcode: Int, owner: String, name: String): Boolean {
        var found = false
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    accessFlags: Int,
                    methodName: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        insnOpcode: Int,
                        insnOwner: String?,
                        insnName: String?,
                        insnDesc: String?,
                        isInterface: Boolean,
                    ) {
                        if (insnOpcode == opcode && insnOwner == owner && insnName == name) found = true
                    }
                }
            },
            ClassReader.SKIP_DEBUG,
        )
        return found
    }

    @Test
    fun `interface class emitted as JVM interface and default method runs`() {
        val classes = BackendTestSupport.compile(
            """
            Greeter {
                fun greet() = "hi"
            }
            Foo implements Greeter {
            }
            fun main() {
                f = Foo()
                print f.greet()
            }
            """.trimIndent(),
        )
        assertTrue(classes.containsKey("Greeter"), "接口类应发射: ${classes.keys}")
        assertTrue(classes.containsKey("Foo"), "实现类应发射: ${classes.keys}")

        val (greeterAccess, greeterSuper) = classMeta(classes["Greeter"]!!)
        assertTrue(greeterAccess and Opcodes.ACC_INTERFACE != 0, "Greeter 应为 JVM 接口")
        assertTrue(greeterAccess and Opcodes.ACC_ABSTRACT != 0, "Greeter 应含 ACC_ABSTRACT")
        assertEquals("java/lang/Object", greeterSuper, "接口父类恒为 Object")

        // 接口方法有体 → 默认方法（ACC_DEFAULT，无 ACC_ABSTRACT），实现类无需 override
        val greetAccess = methodAccess(classes["Greeter"]!!, "greet")
        assertTrue(greetAccess and ACC_DEFAULT != 0, "接口具体方法应为默认方法")
        assertTrue(greetAccess and Opcodes.ACC_ABSTRACT == 0, "默认方法不得含 ACC_ABSTRACT")

        // 调用解析到接口成员 → INVOKEINTERFACE Greeter.greet
        assertTrue(hasInvoke(classes["Main"]!!, Opcodes.INVOKEINTERFACE, "Greeter", "greet"))
        assertEquals("hi", BackendTestSupport.run(classes, "Main"))
    }

    @Test
    fun `overridden interface member dispatches to implementing class`() {
        val classes = BackendTestSupport.compile(
            """
            Greeter {
                fun greet() = "hi"
            }
            Foo implements Greeter {
                override fun greet() = "yo"
            }
            fun main() {
                f = Foo()
                print f.greet()
            }
            """.trimIndent(),
        )
        // override 使解析到 Foo.greet → INVOKEVIRTUAL Foo.greet
        assertTrue(hasInvoke(classes["Main"]!!, Opcodes.INVOKEVIRTUAL, "Foo", "greet"))
        assertTrue(!hasInvoke(classes["Main"]!!, Opcodes.INVOKEINTERFACE, "Greeter", "greet"))
        assertEquals("yo", BackendTestSupport.run(classes, "Main"))
    }

    @Test
    fun `interface extending interface and diamond multiple interfaces resolve at runtime`() {
        val classes = BackendTestSupport.compile(
            """
            A {
                fun f() = "a"
            }
            B implements A {
                fun b() = "b"
            }
            C implements A {
                fun c() = "c"
            }
            D implements B, C {
            }
            fun main() {
                d = D()
                print d.f()
                print d.b()
                print d.c()
            }
            """.trimIndent(),
        )
        // 菱形：A 被 B、C 引用 → 接口；B/C 被 D 引用 → 接口（B/C 继承 A）
        val (bAccess, bSuper) = classMeta(classes["B"]!!)
        assertTrue(bAccess and Opcodes.ACC_INTERFACE != 0)
        assertEquals("java/lang/Object", bSuper, "接口父类为 Object（接口继承经 interfaces 数组）")
        // 传递解析：d.f() → A.f、d.b() → B.b、d.c() → C.c 均 INVOKEINTERFACE
        assertTrue(hasInvoke(classes["Main"]!!, Opcodes.INVOKEINTERFACE, "A", "f"))
        assertTrue(hasInvoke(classes["Main"]!!, Opcodes.INVOKEINTERFACE, "B", "b"))
        assertTrue(hasInvoke(classes["Main"]!!, Opcodes.INVOKEINTERFACE, "C", "c"))
        assertEquals("abc", BackendTestSupport.run(classes, "Main"))
    }

    @Test
    fun `diamond with override dispatches to implementing class`() {
        val classes = BackendTestSupport.compile(
            """
            A {
                fun f() = "a"
            }
            B implements A {
            }
            C implements A {
            }
            D implements B, C {
                override fun f() = "d"
            }
            fun main() {
                d = D()
                print d.f()
            }
            """.trimIndent(),
        )
        assertTrue(hasInvoke(classes["Main"]!!, Opcodes.INVOKEVIRTUAL, "D", "f"))
        assertEquals("d", BackendTestSupport.run(classes, "Main"))
    }

    @Test
    fun `instantiating an interface class is rejected at compile time`() {
        val e = assertFailsWith<RuntimeException> {
            BackendTestSupport.compile(
                """
                Greeter {
                    fun greet() = "hi"
                }
                Foo implements Greeter {
                }
                fun main() {
                    g = Greeter()
                    print g.greet()
                }
                """.trimIndent(),
            )
        }
        assertContains(e.message ?: "", "接口类不可实例化")
    }
}
