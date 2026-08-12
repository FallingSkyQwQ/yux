package yux.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.optionalValue
import yux.buildtool.CompiledProject
import yux.buildtool.YuxBuild
import yux.compiler.Compiler
import yux.compiler.diag.DiagnosticSink
import yux.compiler.ir.IrPrinter
import yux.compiler.lexer.Lexer
import yux.compiler.lexer.TokenPrinter
import yux.compiler.parser.AstPrinter
import yux.compiler.sema.AnalysisPrinter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * 单文件编译命令（M1~M5 验收命令）与项目命令（M7，T-M7-5）。
 * 逻辑自旧版手写 CLI 移植（Main.kt v0.1），行为与错误文案保持不变。
 */

// ── 单文件命令 ──────────────────────────────────────────────────────────────

internal class LexCommand : CliktCommand(name = "lex", help = "输出词法 token 流（M1）") {
    private val color by colorOption()
    private val plugins by pluginOption()
    private val file by argument("file", help = "源文件 .yux")

    override fun run() {
        val colors = Ansi.colors(color)
        val pluginManager = loadPlugins(plugins) ?: throw CliFailure()
        val source = loadSource(file) ?: throw CliFailure()
        val diagnostics = DiagnosticSink()
        val tokens = Lexer(source, diagnostics).tokenize()
        print(TokenPrinter.print(tokens))
        DiagPrinter.print(diagnostics, source.text, colors)
        if (diagnostics.hasErrors) throw CliFailure()
    }
}

internal class AstCommand : CliktCommand(name = "ast", help = "输出语法 AST（M2）") {
    private val color by colorOption()
    private val plugins by pluginOption()
    private val file by argument("file", help = "源文件 .yux")

    override fun run() {
        val colors = Ansi.colors(color)
        val pluginManager = loadPlugins(plugins) ?: throw CliFailure()
        val source = loadSource(file) ?: throw CliFailure()
        val compiler = Compiler(DiagnosticSink(), pluginManager)
        val decls = compiler.parseToDecls(source)
        print(AstPrinter.dump(decls))
        DiagPrinter.print(compiler.diagnostics, source.text, colors)
        if (compiler.diagnostics.hasErrors) throw CliFailure()
    }
}

internal class CheckCommand : CliktCommand(name = "check", help = "输出语义分析（M3）") {
    private val color by colorOption()
    private val plugins by pluginOption()
    private val file by argument("file", help = "源文件 .yux")

    override fun run() {
        val colors = Ansi.colors(color)
        val pluginManager = loadPlugins(plugins) ?: throw CliFailure()
        val source = loadSource(file) ?: throw CliFailure()
        val compiler = Compiler(DiagnosticSink(), pluginManager)
        val decls = compiler.parseToDecls(source)
        if (compiler.diagnostics.hasErrors) {
            DiagPrinter.print(compiler.diagnostics, source.text, colors)
            throw CliFailure()
        }
        val result = compiler.analyze(mapOf(source.path to decls))
        print(AnalysisPrinter.dumpTypes(result.exprTypes))
        DiagPrinter.print(compiler.diagnostics, source.text, colors)
        if (compiler.diagnostics.hasErrors) throw CliFailure()
    }
}

internal class IrCommand : CliktCommand(name = "ir", help = "输出 IR（M4）") {
    private val color by colorOption()
    private val plugins by pluginOption()
    private val file by argument("file", help = "源文件 .yux")

    override fun run() {
        val colors = Ansi.colors(color)
        val pluginManager = loadPlugins(plugins) ?: throw CliFailure()
        val source = loadSource(file) ?: throw CliFailure()
        val compiler = Compiler(DiagnosticSink(), pluginManager)
        val decls = compiler.parseToDecls(source)
        if (compiler.diagnostics.hasErrors) {
            DiagPrinter.print(compiler.diagnostics, source.text, colors)
            throw CliFailure()
        }
        val analysis = compiler.analyze(mapOf(source.path to decls))
        if (compiler.diagnostics.hasErrors) {
            DiagPrinter.print(compiler.diagnostics, source.text, colors)
            throw CliFailure()
        }
        try {
            val module = compiler.generate(mapOf(source.path to decls), analysis)
            print(IrPrinter.dump(module))
        } catch (e: RuntimeException) {
            // IRGen/后端对语义合法但不支持的输入抛异常：转为诊断输出而非堆栈
            System.err.println("IRGen 错误: ${e.message}")
            throw CliFailure()
        }
    }
}

internal class RunCommand : CliktCommand(name = "run", help = "编译并运行 main（单文件或 -p 项目）") {
    private val color by colorOption()
    private val plugins by pluginOption()
    private val project by option("-p", "--project", help = "项目目录（项目模式：进程内编译整个项目后执行主类）").default("")
    private val file by argument("file", help = "源文件 .yux").optional()

    override fun run() {
        val colors = Ansi.colors(color)
        val pluginManager = loadPlugins(plugins) ?: throw CliFailure()
        if (project.orEmpty().isNotEmpty()) {
            runProject(Path.of(project.orEmpty()), pluginManager, colors)
            return
        }
        val source = loadSource(file) ?: throw CliFailure()
        val compiled = Runner.compile(file!!, source, pluginManager)
        DiagPrinter.print(compiled.diagnostics, source.text, colors)
        if (compiled.diagnostics.hasErrors) throw CliFailure()
        val code = Runner().run(compiled)
        if (code != 0) throw CliFailure()
    }
}

// ── 项目命令（M7，T-M7-5）───────────────────────────────────────────────────

internal class BuildCommand : CliktCommand(name = "build", help = "读取 build.yml 编译并打包（M7）") {
    private val color by colorOption()
    private val plugins by pluginOption()
    private val project by option("-p", "--project", help = "项目目录").default(".")
    private val clean by option("--clean", help = "全量重建").flag()
    private val offline by option("--offline", help = "Gradle 离线模式").flag()

    override fun run() {
        val colors = Ansi.colors(color)
        val pluginManager = loadPlugins(plugins) ?: throw CliFailure()
        val projectDir = Path.of(project)
        if (!Files.isDirectory(projectDir)) {
            System.err.println("错误: 项目目录不存在: $projectDir")
            throw CliFailure()
        }
        try {
            val result =
                YuxBuild(projectDir, pluginManager, gradleBinary = System.getenv("YUX_GRADLE"))
                    .build(clean = clean, offline = offline)
            if (result.success) {
                val artifact = result.artifactJar?.toString().orEmpty()
                val message =
                    if (result.compiled) {
                        colors.green("构建成功: $artifact")
                    } else {
                        colors.green("构建成功（增量命中，跳过编译）: $artifact")
                    }
                println(message)
                DiagPrinter.print(result.diagnostics, null, colors)
            } else {
                DiagPrinter.print(result.diagnostics, null, colors)
                System.err.println("错误: ${result.message ?: "构建失败"}")
                throw CliFailure()
            }
        } catch (e: CliFailure) {
            throw e
        } catch (e: Exception) {
            System.err.println("错误: 构建异常: ${e.message}")
            throw CliFailure()
        }
    }
}

internal class TestCommand : CliktCommand(name = "test", help = "构建 + 执行 @Test 测试（M7）") {
    private val color by colorOption()
    private val plugins by pluginOption()
    private val project by option("-p", "--project", help = "项目目录").default(".")
    private val clean by option("--clean", help = "全量重建").flag()

    override fun run() {
        val colors = Ansi.colors(color)
        val pluginManager = loadPlugins(plugins) ?: throw CliFailure()
        val projectDir = Path.of(project)
        if (!Files.isDirectory(projectDir)) {
            System.err.println("错误: 项目目录不存在: $projectDir")
            throw CliFailure()
        }
        try {
            val result = YuxBuild(projectDir, pluginManager).compile(clean = clean)
            DiagPrinter.print(result.diagnostics, null, colors)
            if (!result.success) {
                System.err.println("错误: Yux 编译失败")
                throw CliFailure()
            }
            runDiscoveredTests(result)
        } catch (e: CliFailure) {
            throw e
        } catch (e: Exception) {
            System.err.println("错误: 测试执行异常: ${e.message}")
            throw CliFailure()
        }
    }
}

/**
 * `yuxc run -p <dir>`（T-M7-5）：项目模式，进程内编译整个项目后执行主类。
 * 编译不经过 Gradle，产物经 [MemoryClassLoader] 直接加载运行。
 */
private fun runProject(
    projectDir: Path,
    pluginManager: yux.compiler.plugin.PluginManager,
    colors: Colors,
) {
    if (!Files.isDirectory(projectDir)) {
        System.err.println("错误: 项目目录不存在: $projectDir")
        throw CliFailure()
    }
    try {
        val result = YuxBuild(projectDir, pluginManager).compile()
        DiagPrinter.print(result.diagnostics, null, colors)
        if (!result.success) {
            System.err.println("错误: Yux 编译失败")
            throw CliFailure()
        }
        if (result.classes.isEmpty()) {
            System.err.println("错误: 未生成任何类")
            throw CliFailure()
        }
        executeMain(result.classes, result.mainClassName, result.projectClasspath)
    } catch (e: CliFailure) {
        throw e
    } catch (e: Exception) {
        System.err.println("错误: 构建异常: ${e.message}")
        throw CliFailure()
    }
}

/** 测试发现 + 执行：编译产物类 → @Test 方法 → 逐项执行 → 摘要与退出码。 */
private fun runDiscoveredTests(result: CompiledProject) {
    val loader = executeMainLoader(result)
    val tests =
        result.classes.keys
            .flatMap { discoverTests(it, loader) }
            .sortedBy { it.first + "." + it.second.name }
    if (tests.isEmpty()) {
        println("测试通过（未找到 @Test 测试方法）")
        return
    }
    var passed = 0
    var failed = 0
    for ((owner, method) in tests) {
        try {
            val instance =
                if (java.lang.reflect.Modifier
                        .isStatic(method.modifiers)
                ) {
                    null
                } else {
                    newTestInstance(owner, loader)
                }
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
        throw CliFailure()
    }
    println("测试通过: $passed passed, 0 failed")
}

/** 反射扫描类的 @Test 方法（含继承的公开方法）；带参数的方法报错并记为失败。 */
private fun discoverTests(
    className: String,
    loader: ClassLoader,
): List<Pair<String, Method>> {
    val cls =
        try {
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
private fun newTestInstance(
    className: String,
    loader: ClassLoader,
): Any {
    val ctor = Class.forName(className, true, loader).getDeclaredConstructor()
    ctor.isAccessible = true
    return ctor.newInstance()
}

/**
 * 进程内执行编译产物主类（镜像 Runner.run 的反射异常处理，T-M5-10）。
 * M10：混合项目经 [projectClasspath] 构造单一 URLClassLoader，使三方类互相引用可解析。
 */
private fun executeMain(
    classes: Map<String, ByteArray>,
    mainClassName: String,
    projectClasspath: List<Path> = emptyList(),
) {
    val loader =
        executeMainLoader(
            CompiledProject(
                classes,
                mainClassName,
                DiagnosticSink(),
                success = true,
                upToDate = false,
                projectClasspath = projectClasspath,
            ),
        )
    try {
        val mainClass = Class.forName(mainClassName, true, loader)
        val main = mainClass.getMethod("main")
        main.invoke(null)
    } catch (e: InvocationTargetException) {
        System.err.println("运行时异常: ${e.targetException}")
        e.targetException.stackTrace.forEach { System.err.println("  at $it") }
        throw CliFailure()
    } catch (e: ReflectiveOperationException) {
        System.err.println("运行时错误: ${e.message}")
        throw CliFailure()
    }
}

/** 编译产物类加载器：纯 Yux 用 [MemoryClassLoader]，混合项目用产物目录 URLClassLoader。 */
private fun executeMainLoader(result: CompiledProject): ClassLoader {
    val loader: ClassLoader =
        if (result.projectClasspath.isEmpty()) {
            MemoryClassLoader(result.classes, Runner::class.java.classLoader)
        } else {
            val urls = result.projectClasspath.map { it.toUri().toURL() }.toTypedArray()
            URLClassLoader(urls, Runner::class.java.classLoader)
        }
    return loader
}

/** 命令失败标记：非零退出（避免各处重复 return Int 样板）。 */
internal class CliFailure : RuntimeException()
