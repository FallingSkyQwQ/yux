package yux.cli

import yux.backend.jvm.AsmBackend
import yux.compiler.diag.DiagnosticSink
import yux.compiler.irgen.IRGen
import yux.compiler.optimizer.BasicOpt
import yux.compiler.parser.CstToAst
import yux.compiler.parser.Parser
import yux.compiler.sema.SemanticAnalyzer
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
        /** 编译单个 .yux 文件为类字节。 */
        fun compile(path: String, source: SourceFile): Compiled {
            val diagnostics = DiagnosticSink()
            val program = Parser(source, diagnostics).parse()
            val decls = CstToAst().convert(program)
            if (diagnostics.hasErrors) return Compiled(emptyMap(), mainName(path), diagnostics)
            val analysis = SemanticAnalyzer().analyze(mapOf(path to decls), diagnostics)
            if (diagnostics.hasErrors) return Compiled(emptyMap(), mainName(path), diagnostics)
            val module = IRGen(analysis).generate(mapOf(path to decls))
            BasicOpt.optimize(module)
            val artifacts = AsmBackend().generate(module, diagnostics)
            return Compiled(
                artifacts.associate { it.className to it.bytes },
                mainName(path),
                diagnostics,
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
