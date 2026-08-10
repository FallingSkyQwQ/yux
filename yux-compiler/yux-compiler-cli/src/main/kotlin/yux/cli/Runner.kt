package yux.cli

import yux.backend.jvm.AsmBackend
import yux.compiler.Compiler
import yux.compiler.diag.DiagnosticSink
import yux.compiler.plugin.PluginManager
import yux.compiler.source.SourceFile
import java.lang.reflect.InvocationTargetException

/**
 * 运行时加载（T-M5-10）：编译 → 自定义类加载器 → 静态 `main()` 执行。
 *
 * 编译产物经 [MemoryClassLoader] 定义；父加载器为 CLI 自身（含 yux-stdlib 运行时），
 * 使 `yux.core.CoreLib`/`yux.core.Range`/FunctionN 可解析。主类为文件类
 * （`main.yux` → `Main`，与 IRGen 文件类命名一致）。
 */
class Runner {

    /** 编译结果：类名 → 字节码。 */
    data class Compiled(
        val classes: Map<String, ByteArray>,
        val mainClassName: String,
        val diagnostics: DiagnosticSink,
    )

    /** 执行编译产物（不经过 System.exit，测试可复用）。返回进程退出码。 */
    fun run(compiled: Compiled): Int {
        val loader = MemoryClassLoader(compiled.classes, Runner::class.java.classLoader)
        return try {
            val mainClass = Class.forName(compiled.mainClassName, true, loader)
            val main = mainClass.getMethod("main")
            main.invoke(null)
            0
        } catch (e: InvocationTargetException) {
            System.err.println("运行时异常: ${e.targetException}")
            e.targetException.stackTrace.forEach { System.err.println("  at $it") }
            1
        } catch (e: ReflectiveOperationException) {
            System.err.println("运行时错误: ${e.message}")
            1
        }
    }

    companion object {
        /** 编译单个 .yux 文件为类字节（含插件管线，T-M6-5/6）。 */
        fun compile(
            path: String,
            source: SourceFile,
            pluginManager: PluginManager = PluginManager(),
        ): Compiled {
            val compiler = Compiler(DiagnosticSink(), pluginManager)
            val decls = compiler.parseToDecls(source)
            if (compiler.diagnostics.hasErrors) {
                return Compiled(emptyMap(), mainName(path), compiler.diagnostics)
            }
            val analysis = compiler.analyze(mapOf(path to decls))
            if (compiler.diagnostics.hasErrors) {
                return Compiled(emptyMap(), mainName(path), compiler.diagnostics)
            }
            val artifacts = try {
                val module = compiler.generate(mapOf(path to decls), analysis)
                val generated = AsmBackend(classLoader = compiler.classLoader).generate(module, compiler.diagnostics).toMutableList()
                // T-M6-5：插件 CodegenHook 附加产物（与后端产物同容器，供 MemoryClassLoader 定义）
                for (hook in pluginManager.hooks) {
                    for (artifact in hook.generate(module, compiler.diagnostics)) {
                        generated += AsmBackend.OutputArtifact(artifact.name, artifact.bytes)
                    }
                }
                generated
            } catch (e: RuntimeException) {
                // IRGen/后端对语义合法但不支持的输入抛异常（async 内 return、'' 字面量、`..=`、
                // ASM 生成失败等，均为 IllegalStateException/IllegalArgumentException/NumberFormatException
                // 的 RuntimeException 子类）：转为 ERROR 诊断走正常错误路径，避免 CLI 崩溃堆栈。
                compiler.diagnostics.error("编译错误: ${e.message}")
                return Compiled(emptyMap(), mainName(path), compiler.diagnostics)
            }
            return Compiled(
                artifacts.associate { it.className to it.bytes },
                mainName(path),
                compiler.diagnostics,
            )
        }

        /** 文件类名：`main.yux` → `Main`（与 IRGen.fileClassName 一致）。 */
        private fun mainName(path: String): String {
            val stem = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            return stem.replaceFirstChar { it.uppercaseChar() }
        }
    }
}

/** 内存类加载器：定义编译产物，父加载器解析 yux-stdlib/JDK。 */
class MemoryClassLoader(
    private val classes: Map<String, ByteArray>,
    parent: ClassLoader,
) : ClassLoader(parent) {

    override fun findClass(name: String): Class<*> {
        val bytes = classes[name] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }
}
