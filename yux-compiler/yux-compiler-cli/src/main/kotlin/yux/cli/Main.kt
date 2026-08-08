package yux.cli

import yux.compiler.Compiler
import yux.compiler.diag.DiagnosticSink
import yux.compiler.ir.IrPrinter
import yux.compiler.lexer.Lexer
import yux.compiler.lexer.TokenPrinter
import yux.compiler.parser.AstPrinter
import yux.compiler.plugin.PluginLoader
import yux.compiler.plugin.PluginManager
import yux.compiler.sema.AnalysisPrinter
import yux.compiler.source.SourceFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * yuxc 命令行入口（02-§14 / 06-§9.2）。
 *
 * v0.1 里程碑命令：
 * - `yuxc lex  <file>`  M1 词法：输出 token 流
 * - `yuxc ast  <file>`  M2 语法：输出 AST dump
 * - `yuxc check <file>` M3 语义：输出类型推导与诊断
 * - `yuxc ir   <file>`  M4 IR：输出 IrModule（含最小优化）
 * - `yuxc run  <file>`  M5 运行：编译 → 自定义类加载器 → main 执行（T-M5-10）
 * - `--plugin <jar>`     M6 插件：加载编译器插件（T-M6-3）
 */
fun main(args: Array<String>) {
    // System.exit 会终止 JVM，测试通过 [runCli] 直接取退出码，避免杀测试进程。
    kotlin.system.exitProcess(runCli(args))
}

/** 执行命令并返回进程退出码（0=成功，1=失败）；供 main 与测试复用。 */
internal fun runCli(args: Array<String>): Int {
    val (pluginJars, positional) = parseArgs(args)
    val pluginManager = loadPlugins(pluginJars) ?: return 1
    return when (val cmd = positional.getOrNull(0)) {
        "lex" -> runLex(positional.getOrNull(1))
        "ast" -> runAst(positional.getOrNull(1), pluginManager)
        "check" -> runCheck(positional.getOrNull(1), pluginManager)
        "ir" -> runIr(positional.getOrNull(1), pluginManager)
        "run" -> runRun(positional.getOrNull(1), pluginManager)
        else -> {
            System.err.println(
                """
                yuxc — Yux 编译器命令行
                用法: yuxc [--plugin <jar>]... <command> <file.yux>
                  lex   <file>   输出词法 token（M1）
                  ast   <file>   输出语法 AST（M2）
                  check <file>   输出语义分析（M3）
                  ir    <file>   输出 IR（M4）
                  run   <file>   编译并运行 main（M5）
                  --plugin <jar> 加载编译器插件（M6）
                """.trimIndent(),
            )
            if (cmd == "lex" || cmd == "ast" || cmd == "check" || cmd == "ir" || cmd == "run") {
                System.err.println("错误: 缺少源文件参数")
            }
            1
        }
    }
}

/** 拆分 `--plugin <jar>` 选项与位置参数（command/file）。 */
private fun parseArgs(args: Array<String>): Pair<List<Path>, List<String>> {
    val jars = mutableListOf<Path>()
    val positional = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--plugin" -> {
                if (i + 1 < args.size) {
                    jars.add(Path.of(args[i + 1]))
                    i += 2
                } else {
                    System.err.println("错误: --plugin 缺少 jar 路径")
                    i++
                }
            }
            else -> {
                positional += args[i]
                i++
            }
        }
    }
    return jars to positional
}

/** 加载插件并注册；失败返回 null（诊断已输出）。 */
private fun loadPlugins(jars: List<Path>): PluginManager? {
    val manager = PluginManager()
    if (jars.isEmpty()) return manager
    val plugins = try {
        PluginLoader.loadFromJars(jars)
    } catch (e: Exception) {
        System.err.println("错误: 插件加载失败: ${e.message}")
        return null
    }
    for (plugin in plugins) {
        manager.register(plugin)
    }
    if (manager.diagnostics.hasErrors) {
        printDiagnostics(manager.diagnostics)
        return null
    }
    return manager
}

private fun loadSource(path: String?): SourceFile? {
    if (path == null) {
        System.err.println("错误: 缺少源文件参数")
        return null
    }
    try {
        val p = Path.of(path)
        if (!Files.isRegularFile(p)) {
            System.err.println("错误: 文件不存在: $path")
            return null
        }
        val normalized = p.toRealPath()
        return SourceFile(normalized.toString(), Files.readString(p))
    } catch (e: Exception) {
        System.err.println("错误: 无法读取文件: $path")
        return null
    }
}

private fun runLex(path: String?): Int {
    val source = loadSource(path) ?: return 1
    val diagnostics = DiagnosticSink()
    val tokens = Lexer(source, diagnostics).tokenize()
    print(TokenPrinter.print(tokens))
    printDiagnostics(diagnostics)
    return if (diagnostics.hasErrors) 1 else 0
}

private fun runAst(path: String?, pluginManager: PluginManager): Int {
    val source = loadSource(path) ?: return 1
    val compiler = Compiler(DiagnosticSink(), pluginManager)
    val decls = compiler.parseToDecls(source)
    print(AstPrinter.dump(decls))
    printDiagnostics(compiler.diagnostics)
    return if (compiler.diagnostics.hasErrors) 1 else 0
}

private fun runCheck(path: String?, pluginManager: PluginManager): Int {
    val source = loadSource(path) ?: return 1
    val compiler = Compiler(DiagnosticSink(), pluginManager)
    val decls = compiler.parseToDecls(source)
    if (compiler.diagnostics.hasErrors) {
        printDiagnostics(compiler.diagnostics)
        return 1
    }
    val result = compiler.analyze(mapOf(source.path to decls))
    print(AnalysisPrinter.dumpTypes(result.exprTypes))
    printDiagnostics(compiler.diagnostics)
    return if (compiler.diagnostics.hasErrors) 1 else 0
}

private fun runIr(path: String?, pluginManager: PluginManager): Int {
    val source = loadSource(path) ?: return 1
    val compiler = Compiler(DiagnosticSink(), pluginManager)
    val decls = compiler.parseToDecls(source)
    if (compiler.diagnostics.hasErrors) {
        printDiagnostics(compiler.diagnostics)
        return 1
    }
    val analysis = compiler.analyze(mapOf(source.path to decls))
    if (compiler.diagnostics.hasErrors) {
        printDiagnostics(compiler.diagnostics)
        return 1
    }
    try {
        val module = compiler.generate(mapOf(source.path to decls), analysis)
        print(IrPrinter.dump(module))
        return 0
    } catch (e: IllegalStateException) {
        // IRGen 对语义合法但不支持的输入以 error() 抛异常：转为诊断而非堆栈
        System.err.println("IRGen 错误: ${e.message}")
        return 1
    }
}

private fun runRun(path: String?, pluginManager: PluginManager): Int {
    val source = loadSource(path) ?: return 1
    val compiled = Runner.compile(path!!, source, pluginManager)
    printDiagnostics(compiled.diagnostics)
    if (compiled.diagnostics.hasErrors) return 1
    return Runner().run(compiled)
}

private fun printDiagnostics(diagnostics: DiagnosticSink) {
    if (diagnostics.diagnostics.isNotEmpty()) {
        System.err.println("\n-- 诊断 --")
        diagnostics.diagnostics.forEach { d ->
            val code = d.code?.let { "[$it]" } ?: ""
            System.err.println("${d.severity}$code${d.position?.let { " $it" } ?: ""}: ${d.message}")
        }
    }
}
