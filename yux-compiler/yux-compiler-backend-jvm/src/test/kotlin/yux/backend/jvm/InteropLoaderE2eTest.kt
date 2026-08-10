package yux.backend.jvm

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import yux.compiler.Compiler
import yux.compiler.diag.DiagnosticSink
import yux.compiler.sema.ClassPathSymbolProvider
import yux.compiler.source.SourceFile
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import javax.tools.ToolProvider
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M11：项目 classLoader 贯穿后端的端到端回归测试。
 *
 * Yux `for x in <项目 Java Iterable>` 展开为 `Ints.iterator()` 调用（StmtGen 以 retType=Any 标注，
 * 合成描述符为 `()Ljava/lang/Object;`，真实为 `()Ljava/util/Iterator;`）。项目类对默认加载器不可见时
 * 发射错误描述符 → 运行期 NoSuchMethodError。注入项目 URLClassLoader 后必须发射真实描述符。
 */
class InteropLoaderE2eTest {

    private fun compileJava(targetDir: Path, sources: Map<String, String>) {
        val files = sources.map { (rel, src) ->
            val f = targetDir.resolve(rel)
            Files.createDirectories(f.parent)
            Files.writeString(f, src)
            f.toString()
        }
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("InteropLoaderE2eTest 需要 JDK javac（ToolProvider.getSystemJavaCompiler）")
        val rc = compiler.run(null, null, null, "-d", targetDir.toString(), *files.toTypedArray())
        require(rc == 0) { "javac 编译测试辅助类失败 rc=$rc" }
    }

    /** 指定 owner/name/desc 的方法调用是否出现在字节码中。 */
    private fun hasMethodCall(classBytes: ByteArray, owner: String, name: String, desc: String): Boolean {
        var found = false
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    methodName: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        insnOwner: String?,
                        insnName: String?,
                        insnDesc: String?,
                        isInterface: Boolean,
                    ) {
                        if (insnOwner == owner && insnName == name && insnDesc == desc) found = true
                    }
                }
            },
            ClassReader.SKIP_DEBUG,
        )
        return found
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `project iterable emits real iterator descriptor via injected loader`() {
        val dir = Files.createTempDirectory("yux-interop-loader")
        var loader: URLClassLoader? = null
        try {
            compileJava(
                dir,
                mapOf(
                    "com/example/Ints.java" to """
                        package com.example;
                        import java.util.Iterator;
                        public class Ints implements Iterable<Integer> {
                            public Iterator<Integer> iterator() {
                                return java.util.Collections.singletonList(1).iterator();
                            }
                        }
                    """.trimIndent(),
                ),
            )
            loader = URLClassLoader(
                arrayOf(dir.toUri().toURL()),
                ClassPathSymbolProvider::class.java.classLoader,
            )
            val projectLoader = checkNotNull(loader)
            val source = """
                import com.example.Ints
                fun main() {
                    ints = Ints() as Ints
                    for x in ints {
                        print x
                    }
                }
            """.trimIndent()
            val compiler = Compiler(DiagnosticSink(), classLoader = projectLoader)
            val sf = SourceFile("main.yux", source)
            val decls = compiler.parseToDecls(sf)
            val analysis = compiler.analyze(mapOf("main.yux" to decls))
            val module = compiler.generate(mapOf("main.yux" to decls), analysis)

            // 修复后：后端以项目加载器解析 → 真实描述符 ()Ljava/util/Iterator;
            val fixed = AsmBackend(classLoader = projectLoader).generate(module)
            val mainBytes = fixed.first { it.className == "Main" }.bytes
            assertTrue(
                hasMethodCall(mainBytes, "com/example/Ints", "iterator", "()Ljava/util/Iterator;"),
                "修复后应发射真实 iterator 描述符",
            )
            assertFalse(
                hasMethodCall(mainBytes, "com/example/Ints", "iterator", "()Ljava/lang/Object;"),
                "修复后不应出现合成描述符",
            )

            // 对照：默认加载器（旧行为）→ 项目类不可见 → 合成描述符（错误描述符复现）
            val legacy = AsmBackend().generate(module)
            val legacyBytes = legacy.first { it.className == "Main" }.bytes
            assertTrue(
                hasMethodCall(legacyBytes, "com/example/Ints", "iterator", "()Ljava/lang/Object;"),
                "默认加载器下应复现合成描述符（回归前提）",
            )
        } finally {
            loader?.close()
            deleteRecursively(dir)
        }
    }
}
