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
import yux.buildtool.CompiledProject
import yux.buildtool.YuxBuild
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * yuxc 命令行入口（02-§14 / 06-§9.2）。
 *
 * v0.1 里程碑命令：
 * - `yuxc lex  <file>`      M1 词法：输出 token 流
 * - `yuxc ast  <file>`      M2 语法：输出 AST dump
 * - `yuxc check <file>`     M3 语义：输出类型推导与诊断
 * - `yuxc ir   <file>`      M4 IR：输出 IrModule（含最小优化）
 * - `yuxc run  <file>`      M5 运行：编译 → 自定义类加载器 → main 执行（T-M5-10）
 * - `--plugin <jar>`        M6 插件：加载编译器插件（T-M6-3）
 * - `yuxc build [-p <dir>] [--clean] [--offline]`  M7 构建：build.yml → 编译 → Gradle 打包（T-M7-5）
 * - `yuxc test  [-p <dir>]`  M7 测试：v0.1 以构建成功为准（T-M7-5）
 * - `yuxc run  -p <dir>`     M7 项目运行：进程内编译整个项目后执行主类（T-M7-5）
 */
fun main(args: Array<String>) {
    // System.exit 会终止 JVM，测试通过 [runCli] 直接取退出码，避免杀测试进程。
    kotlin.system.exitProcess(runCli(args))
}

/** 执行命令并返回进程退出码（0=成功，1=失败）；供 main 与测试复用。 */
internal fun runCli(args: Array<String>): Int {
    val cli = parseArgs(args)
    val pluginManager = loadPlugins(cli.pluginJars) ?: return 1
    return when (val cmd = cli.positional.getOrNull(0)) {
        "lex" -> runLex(cli.positional.getOrNull(1))
        "ast" -> runAst(cli.positional.getOrNull(1), pluginManager)
        "check" -> runCheck(cli.positional.getOrNull(1), pluginManager)
        "ir" -> runIr(cli.positional.getOrNull(1), pluginManager)
        "run" -> runRun(cli.positional.getOrNull(1), cli, pluginManager)
        "build" -> runBuild(cli, pluginManager)
        "test" -> runTest(cli, pluginManager)
        else -> {
            System.err.println(
                """
                yuxc — Yux 编译器命令行
                用法: yuxc [--plugin <jar>]... [--project <dir>] <command> <file.yux>
                  lex   <file>           输出词法 token（M1）
                  ast   <file>           输出语法 AST（M2）
                  check <file>           输出语义分析（M3）
                  ir    <file>           输出 IR（M4）
                  run   <file>           编译并运行 main（M5）
                  build [-p <dir>]       读取 build.yml 编译并打包（M7）
                  test  [-p <dir>]       构建 + 执行测试（M7，v0.1 以构建成功为准）
                  --plugin <jar>         加载编译器插件（M6）
                  -p/--project <dir>     项目目录（build/test 缺省 "."；run 项目模式）
                  --clean                全量重建（M7）
                  --offline              Gradle 离线模式（M7）
                """.trimIndent(),
            )
            if (cmd == "lex" || cmd == "ast" || cmd == "check" || cmd == "ir" || cmd == "run") {
                System.err.println("错误: 缺少源文件参数")
            }
            1
        }
    }
}

/** 命令行参数模型：选项与位置参数（command/file）的拆分结果。 */
data class CliArgs(
    val pluginJars: List<Path>,
    val projectDir: Path?,
    val clean: Boolean,
    val offline: Boolean,
    val positional: List<String>,
)

/** 拆分 `--plugin`/`-p`/`--project`/`--clean`/`--offline` 选项与位置参数（command/file）。 */
private fun parseArgs(args: Array<String>): CliArgs {
    val jars = mutableListOf<Path>()
    val positional = mutableListOf<String>()
    var projectDir: Path? = null
    var clean = false
    var offline = false
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
            "-p", "--project" -> {
                if (i + 1 < args.size) {
                    projectDir = Path.of(args[i + 1])
                    i += 2
                } else {
                    System.err.println("错误: ${args[i]} 缺少项目目录")
                    i++
                }
            }
            "--clean" -> {
                clean = true
                i++
            }
            "--offline" -> {
                offline = true
                i++
            }
            else -> {
                positional += args[i]
                i++
            }
        }
    }
    return CliArgs(jars, projectDir, clean, offline, positional)
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
    } catch (e: RuntimeException) {
        // IRGen/后端对语义合法但不支持的输入抛异常（async 内 return、'' 字面量、`..=`、
        // ASM 生成失败等）：转为诊断输出而非堆栈。
        System.err.println("IRGen 错误: ${e.message}")
        return 1
    }
}

private fun runRun(path: String?, cli: CliArgs, pluginManager: PluginManager): Int {
    // T-M7-5：`-p <dir>` 触发项目模式（进程内编译整个项目），否则保持 M5 单文件行为。
    val projectDir = cli.projectDir
    if (projectDir != null) {
        return runProject(projectDir, pluginManager)
    }
    val source = loadSource(path) ?: return 1
    val compiled = Runner.compile(path!!, source, pluginManager)
    printDiagnostics(compiled.diagnostics)
    if (compiled.diagnostics.hasErrors) return 1
    return Runner().run(compiled)
}

/**
 * `yuxc build [-p <dir>] [--clean] [--offline]`（T-M7-5，02-§14）：
 * 读取 build.yml → 进程内 Yux 编译 → 托管 Gradle 打包。
 */
private fun runBuild(cli: CliArgs, pluginManager: PluginManager): Int {
    val projectDir = cli.projectDir ?: Path.of(".")
    if (!Files.isDirectory(projectDir)) {
        System.err.println("错误: 项目目录不存在: $projectDir")
        return 1
    }
    return try {
        val result = YuxBuild(projectDir, pluginManager, gradleBinary = System.getenv("YUX_GRADLE"))
            .build(clean = cli.clean, offline = cli.offline)
        if (result.success) {
            val artifact = result.artifactJar?.toString().orEmpty()
            val message = if (result.compiled) {
                "构建成功: $artifact"
            } else {
                "构建成功（增量命中，跳过编译）: $artifact"
            }
            println(message)
            printDiagnostics(result.diagnostics)
            0
        } else {
            printDiagnostics(result.diagnostics)
            System.err.println("错误: ${result.message ?: "构建失败"}")
            1
        }
    } catch (e: Exception) {
        System.err.println("错误: 构建异常: ${e.message}")
        1
    }
}

/**
 * `yuxc test [-p <dir>]`（01-§10.8 / T-M7-5 真实化）：
 * 进程内编译项目 → 类加载器加载产物 → 反射扫描 `@Test` 方法（文件类静态方法 + 类成员）
 * → 逐个执行 → 输出每项结果与摘要 → 有失败返回非零退出码。
 */
private fun runTest(cli: CliArgs, pluginManager: PluginManager): Int {
    val projectDir = cli.projectDir ?: Path.of(".")
    if (!Files.isDirectory(projectDir)) {
        System.err.println("错误: 项目目录不存在: $projectDir")
        return 1
    }
    return try {
        val result = YuxBuild(projectDir, pluginManager).compile(clean = cli.clean)
        printDiagnostics(result.diagnostics)
        if (!result.success) {
            System.err.println("错误: Yux 编译失败")
            return 1
        }
        runDiscoveredTests(result)
    } catch (e: Exception) {
        System.err.println("错误: 测试执行异常: ${e.message}")
        1
    }
}

/** 测试发现 + 执行：编译产物类 → @Test 方法 → 逐项执行 → 摘要与退出码。 */
private fun runDiscoveredTests(result: CompiledProject): Int {
    val loader = executeMainLoader(result)
    val tests = result.classes.keys.flatMap { discoverTests(it, loader) }.sortedBy { it.first + "." + it.second.name }
    if (tests.isEmpty()) {
        println("测试通过（未找到 @Test 测试方法）")
        return 0
    }
    var passed = 0
    var failed = 0
    for ((owner, method) in tests) {
        try {
            val instance = if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else newTestInstance(owner, loader)
            method.invoke(instance)
            println("PASS: $owner.${method.name}")
            passed++
        } catch (e: InvocationTargetException) {
            failed++
            println("FAIL: $owner.${method.name}: ${e.targetException}")
        } catch (e: ReflectiveOperationException) {
            failed++
            println("ERROR: $owner.${method.name}: ${e.message}")
        }
    }
    println("测试完成: $passed passed, $failed failed")
    if (failed > 0) {
        println("测试失败（$failed 个用例未通过）")
        return 1
    }
    println("测试通过: $passed passed, 0 failed")
    return 0
}

/** 反射扫描类的 @Test 方法（含继承的公开方法）；带参数的方法报错并记为失败。 */
private fun discoverTests(className: String, loader: ClassLoader): List<Pair<String, Method>> {
    val cls = try {
        Class.forName(className, true, loader)
    } catch (_: LinkageError) {
        return emptyList()
    } catch (_: ClassNotFoundException) {
        return emptyList()
    }
    val testAnnotation = yux.test.Test::class.java
    return cls.methods
        .filter { it.isAnnotationPresent(testAnnotation) }
        .flatMap { method ->
            if (method.parameterCount != 0) {
                System.err.println("ERROR: $className.${method.name}: @Test 方法不接受参数（v0.1）")
                emptyList()
            } else {
                listOf(className to method)
            }
        }
}

/** 无参构造器实例化测试类（文件类静态方法为 null）；缺失时报错返回失败。 */
private fun newTestInstance(className: String, loader: ClassLoader): Any {
    val ctor = Class.forName(className, true, loader).getDeclaredConstructor()
    ctor.isAccessible = true
    return ctor.newInstance()
}

/**
 * `yuxc run -p <dir>`（T-M7-5）：项目模式，进程内编译整个项目后执行主类。
 * 编译不经过 Gradle，产物经 [MemoryClassLoader] 直接加载运行。
 */
private fun runProject(projectDir: Path, pluginManager: PluginManager): Int {
    if (!Files.isDirectory(projectDir)) {
        System.err.println("错误: 项目目录不存在: $projectDir")
        return 1
    }
    return try {
        val result = YuxBuild(projectDir, pluginManager).compile()
        printDiagnostics(result.diagnostics)
        if (!result.success) {
            System.err.println("错误: Yux 编译失败")
            return 1
        }
        if (result.classes.isEmpty()) {
            System.err.println("错误: 未生成任何类")
            return 1
        }
        executeMain(result.classes, result.mainClassName, result.projectClasspath)
    } catch (e: Exception) {
        System.err.println("错误: 构建异常: ${e.message}")
        1
    }
}

/**
 * 进程内执行编译产物主类（镜像 Runner.run 的反射异常处理，T-M5-10）。
 * M10：混合项目经 [projectClasspath]（Yux/Java/Kotlin 三方产物目录）构造单一 URLClassLoader，
 * 使三方类互相引用可解析（05-§5 三方互调）；纯 Yux 项目沿用 [MemoryClassLoader]。
 */
private fun executeMain(classes: Map<String, ByteArray>, mainClassName: String, projectClasspath: List<Path> = emptyList()): Int {
    val loader = executeMainLoader(CompiledProject(classes, mainClassName, DiagnosticSink(), success = true, upToDate = false, projectClasspath = projectClasspath))
    return try {
        val mainClass = Class.forName(mainClassName, true, loader)
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

/** 编译产物类加载器：纯 Yux 用 [MemoryClassLoader]，混合项目用产物目录 URLClassLoader。 */
private fun executeMainLoader(result: CompiledProject): ClassLoader {
    val loader: ClassLoader = if (result.projectClasspath.isEmpty()) {
        MemoryClassLoader(result.classes, Runner::class.java.classLoader)
    } else {
        val urls = result.projectClasspath.map { it.toUri().toURL() }.toTypedArray()
        URLClassLoader(urls, Runner::class.java.classLoader)
    }
    return loader
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
