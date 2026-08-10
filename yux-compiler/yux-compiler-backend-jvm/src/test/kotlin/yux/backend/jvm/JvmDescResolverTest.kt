package yux.backend.jvm

import org.junit.jupiter.api.Test
import yux.compiler.ir.IrJvmCall
import yux.compiler.ir.IrType
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M11：JvmDescResolver 项目类加载器注入回归测试。
 *
 * 混合项目（M10）中 Java/Kotlin 类位于项目 classpath（URLClassLoader），默认加载器不可见。
 * 注入加载器后 [JvmDescResolver] 反射命中真实 JVM 方法 → 返回真实描述符；
 * 否则回退为按 IR 类型合成描述符（`log(Object)` 被 Yux 以 String 实参调用时两者不同）。
 */
class JvmDescResolverTest {

    /** 运行期 javac 编译 Java 测试辅助类到 [targetDir]（JDK ToolProvider；独立于仓库构建）。 */
    private fun compileJava(targetDir: Path, sources: Map<String, String>) {
        val files = sources.map { (rel, src) ->
            val f = targetDir.resolve(rel)
            Files.createDirectories(f.parent)
            Files.writeString(f, src)
            f.toString()
        }
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JvmDescResolverTest 需要 JDK javac（ToolProvider.getSystemJavaCompiler）")
        val rc = compiler.run(null, null, null, "-d", targetDir.toString(), *files.toTypedArray())
        require(rc == 0) { "javac 编译测试辅助类失败 rc=$rc" }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `url classloader resolves real method descriptor invisible to default loader`() {
        val dir = Files.createTempDirectory("yux-descresolver")
        var loader: URLClassLoader? = null
        try {
            compileJava(
                dir,
                mapOf(
                    "com/example/Logger.java" to """
                        package com.example;
                        public class Logger {
                            public void log(Object msg) { }
                        }
                    """.trimIndent(),
                    "com/example/Handler.java" to """
                        package com.example;
                        public interface Handler {
                            void handle(String msg);
                        }
                    """.trimIndent(),
                    "com/example/Box.java" to """
                        package com.example;
                        public class Box {
                            public Box(Handler handler) { }
                        }
                    """.trimIndent(),
                ),
            )
            loader = URLClassLoader(
                arrayOf(dir.toUri().toURL()),
                JvmDescResolver::class.java.classLoader,
            )
            val projectLoader = checkNotNull(loader)
            // Yux 以 String 实参调用 log(Object)：IR 参数为 String，真实描述符为 Object
            val call = IrJvmCall(
                name = "log",
                owner = "com.example.Logger",
                static = false,
                params = listOf(IrType.STRING),
                retType = IrType.Void,
            )

            // 默认加载器看不到项目类 → 合成描述符（旧行为，类不可见时的正确回退）
            val synthetic = JvmDescResolver().resolve(call)
            assertEquals("(Ljava/lang/String;)V", synthetic.desc)

            // 注入项目加载器 → 反射命中真实 JVM 方法 → 真实描述符（修复后行为）
            val real = JvmDescResolver(projectLoader).resolve(call)
            assertEquals("(Ljava/lang/Object;)V", real.desc)
            assertEquals(listOf("Ljava/lang/Object;"), real.realParamDescs)
            assertEquals("V", real.realRetDesc)
            assertFalse(real.isInterface)

            // 构造器：项目类参数经注入加载器 toClass → 反射命中真实构造器（Box(Handler)）
            val handlerSymbol = yux.compiler.sema.ClassPathSymbolProvider(projectLoader)
                .resolve("com.example.Handler")!!
            val handlerIrType = IrType.Declared(handlerSymbol, emptyList())
            assertEquals(
                "(Lcom/example/Handler;)V",
                JvmDescResolver(projectLoader).constructorDesc("com.example.Box", listOf(handlerIrType)),
            )
        } finally {
            loader?.close()
            deleteRecursively(dir)
        }
    }

    @Test
    fun `url classloader resolves interface flag of project class`() {
        val dir = Files.createTempDirectory("yux-descresolver-iface")
        var loader: URLClassLoader? = null
        try {
            compileJava(
                dir,
                mapOf(
                    "com/example/Handler.java" to """
                        package com.example;
                        public interface Handler {
                            void handle(String msg);
                        }
                    """.trimIndent(),
                ),
            )
            loader = URLClassLoader(
                arrayOf(dir.toUri().toURL()),
                JvmDescResolver::class.java.classLoader,
            )
            val projectLoader = checkNotNull(loader)
            val call = IrJvmCall(
                name = "handle",
                owner = "com.example.Handler",
                static = false,
                params = listOf(IrType.STRING),
                retType = IrType.Void,
            )
            val resolved = JvmDescResolver(projectLoader).resolve(call)
            assertEquals("(Ljava/lang/String;)V", resolved.desc)
            assertTrue(resolved.isInterface, "项目接口类应反射判定 isInterface（INVOKEINTERFACE 依赖）")
        } finally {
            loader?.close()
            deleteRecursively(dir)
        }
    }
}
