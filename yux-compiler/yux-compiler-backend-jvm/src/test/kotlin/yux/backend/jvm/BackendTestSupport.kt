package yux.backend.jvm

import yux.compiler.diag.DiagnosticSink
import yux.compiler.irgen.AsyncCpsLowering
import yux.compiler.irgen.IRGen
import yux.compiler.optimizer.BasicOpt
import yux.compiler.optimizer.SsaOpt
import yux.compiler.parser.CstToAst
import yux.compiler.parser.Parser
import yux.compiler.sema.SemanticAnalyzer
import yux.compiler.source.SourceFile
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * 后端测试基座（T-M5-3/10/11 验收）：源码 → 编译 → 自定义类加载器 → 执行 `main`。
 *
 * 反射验证（T-M5-2/5/6/7）直接经 [load] 取类；端到端（T-M5-11）经 [run] 捕获 stdout。
 */
object BackendTestSupport {

    /** 完整管线：lex → parse → sema → IRGen → async CPS 降级 → SSA 优化 → 最小优化 → JVM 后端。 */
    fun compile(source: String, path: String = "main.yux"): Map<String, ByteArray> {
        val diags = DiagnosticSink()
        val sf = SourceFile(path, source)
        val program = Parser(sf, diags).parse()
        val decls = CstToAst().convert(program)
        require(!diags.hasErrors) { "解析错误: ${diags.diagnostics}" }
        val analysis = SemanticAnalyzer().analyze(mapOf(path to decls), diags)
        require(!diags.hasErrors) { "语义错误: ${diags.diagnostics}" }
        val module = IRGen(analysis).generate(mapOf(path to decls))
        AsyncCpsLowering(module).transform()
        SsaOpt.optimize(module)
        BasicOpt.optimize(module)
        val artifacts = AsmBackend().generate(module)
        return artifacts.associate { it.className to it.bytes }
    }

    /** 多文件完整管线（跨文件同包/跨包引用用）：路径 → 源码。 */
    fun compileMany(sources: Map<String, String>): Map<String, ByteArray> {
        val diags = DiagnosticSink()
        val declsByFile = linkedMapOf<String, List<yux.compiler.ast.YxDecl>>()
        for ((path, source) in sources) {
            val sf = SourceFile(path, source)
            declsByFile[path] = CstToAst().convert(Parser(sf, diags).parse())
        }
        require(!diags.hasErrors) { "解析错误: ${diags.diagnostics}" }
        val analysis = SemanticAnalyzer().analyze(declsByFile, diags)
        require(!diags.hasErrors) { "语义错误: ${diags.diagnostics}" }
        val module = IRGen(analysis).generate(declsByFile)
        AsyncCpsLowering(module).transform()
        SsaOpt.optimize(module)
        BasicOpt.optimize(module)
        val artifacts = AsmBackend().generate(module)
        return artifacts.associate { it.className to it.bytes }
    }

    /** 自定义类加载器：定义编译产物（父加载器含 yux-stdlib 运行时）。 */
    fun load(classes: Map<String, ByteArray>): ClassLoader =
        object : ClassLoader(BackendTestSupport::class.java.classLoader) {
            override fun findClass(name: String): Class<*> {
                val bytes = classes[name] ?: throw ClassNotFoundException(name)
                return defineClass(name, bytes, 0, bytes.size)
            }
        }

    /** 加载主类（文件名 → 文件类）并反射调用静态 `main()`。 */
    fun run(classes: Map<String, ByteArray>, mainClass: String): String {
        val loader = load(classes)
        val cls = Class.forName(mainClass, true, loader)
        val out = ByteArrayOutputStream()
        val prev = System.out
        System.setOut(PrintStream(out, true, StandardCharsets.UTF_8))
        try {
            cls.getMethod("main").invoke(null)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw AssertionError("main 抛出异常", e.targetException)
        } finally {
            System.setOut(prev)
        }
        return out.toString(StandardCharsets.UTF_8)
    }

    /** 编译并运行，返回 stdout（M5-11 端到端验收）。 */
    fun compileAndRun(source: String, mainClass: String = "Main"): String =
        run(compile(source), mainClass)
}
