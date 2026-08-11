package yux.cli

import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import yux.compiler.plugin.PluginLoader
import yux.compiler.plugin.PluginManager
import yux.compiler.source.SourceFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * yuxc 命令行入口（02-§14 / 06-§9.2；M16 起基于 Clikt 4.4）。
 *
 * 命令一览：
 * - `yuxc lex <file>`        M1 词法：输出 token 流
 * - `yuxc ast <file>`        M2 语法：输出 AST dump
 * - `yuxc check <file>`      M3 语义：输出类型推导与诊断
 * - `yuxc ir <file>`         M4 IR：输出 IrModule（含优化）
 * - `yuxc run [file|-p dir]` M5/M7 运行：编译并执行 main（单文件或项目）
 * - `yuxc build [-p dir]`    M7 构建：build.yml → 编译 → Gradle 打包
 * - `yuxc test [-p dir]`     M7 测试：编译 + @Test 反射执行
 * - `yuxc new <name>`        M16 脚手架：hello/project/plugin 模板
 * - `yuxc fmt [files...]`    M16 格式化：stdout / -i 原地 / --check 校验
 * - `yuxc version`           M16 版本：构建生成的 version 属性
 * - `yuxc doc [topic]`       M16 文档：打开在线文档（headless 仅打印 URL）
 *
 * 全局风格：`--color auto|always|never`、`--plugin <jar>`（可重复）、`--completion` 补全。
 */
fun main(args: Array<String>) {
    // System.exit 会终止 JVM，测试通过 [runCli] 直接取退出码，避免杀测试进程。
    kotlin.system.exitProcess(runCli(args))
}

/**
 * 执行命令并返回进程退出码（0=成功，1=失败）；供 main 与测试复用。
 * 镜像 Clikt `main()` 的错误处理：help → stdout（0），usage 错误 → stderr（statusCode）。
 */
internal fun runCli(args: Array<String>): Int {
    val root = YuxcRoot()
    return try {
        root.parse(args.toList())
        0
    } catch (e: CliktError) {
        root.echoFormattedHelp(e)
        e.statusCode
    } catch (e: CliFailure) {
        1
    }
}

/** 根命令：yuxc 本身无动作，仅挂子命令；裸调用输出 help。 */
internal class YuxcRoot : CliktCommand(
    name = "yuxc",
    help = "Yux 语言工具链：编译 / 运行 / 构建 / 格式化 / 脚手架",
    invokeWithoutSubcommand = true,
) {
    init {
        completionOption(names = arrayOf("--completion"), help = "生成 shell 补全脚本（bash/zsh/fish）")
        subcommands(*COMMANDS.toTypedArray())
    }

    override fun run() {
        // Clikt 先执行父命令 run() 再分发子命令：有子命令时静默，无子命令时输出 help
        if (currentContext.invokedSubcommand == null) {
            throw PrintHelpMessage(currentContext)
        }
    }

    companion object {
        /** 子命令清单（HelpCommand 复用同一注册表）。 */
        val COMMANDS: List<CliktCommand> = listOf(
            LexCommand(), AstCommand(), CheckCommand(), IrCommand(), RunCommand(),
            BuildCommand(), TestCommand(),
            NewCommand(), FmtCommand(), VersionCommand(), DocCommand(), HelpCommand(),
        )
    }
}

/** `yuxc help [command]`：显式查看帮助（复用 `--help` 的解析路径）。 */
internal class HelpCommand : CliktCommand(name = "help", help = "显示命令帮助") {
    private val targetCommand by argument("command", help = "子命令名").optional()

    override fun run() {
        // 先校验子命令存在（否则 `--help` 会抢先触发 PrintHelpMessage）
        if (targetCommand != null && YuxcRoot.COMMANDS.none { it.commandName == targetCommand }) {
            throw UsageError("未知命令: $targetCommand", statusCode = 1)
        }
        val args = if (targetCommand == null) listOf("--help") else listOf(targetCommand!!, "--help")
        try {
            YuxcRoot().parse(args)
        } catch (e: PrintHelpMessage) {
            throw e // 由 runCli 统一输出（help → stdout，exit 0）
        }
    }
}

// ── 共享选项助手 ────────────────────────────────────────────────────────────

/** `--color auto|always|never`（诊断/进度输出的 ANSI 开关）。 */
internal fun CliktCommand.colorOption() =
    option("--color", help = "输出颜色：auto/always/never").enum<Ansi.ColorMode>(ignoreCase = true)

/** `--plugin <jar>`（可重复）：加载编译器插件。 */
internal fun CliktCommand.pluginOption() =
    option("--plugin", help = "加载编译器插件 jar（可重复）").multiple()// ── 共享助手 ────────────────────────────────────────────────────────────────

/** 加载源文件；失败输出诊断并返回 null。 */
internal fun loadSource(path: String?): SourceFile? {
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

/** 加载插件并注册；失败返回 null（诊断已输出）。 */
internal fun loadPlugins(jars: List<String>): PluginManager? {
    val manager = PluginManager()
    if (jars.isEmpty()) return manager
    val plugins = try {
        PluginLoader.loadFromJars(jars.map { Path.of(it) })
    } catch (e: Exception) {
        System.err.println("错误: 插件加载失败: ${e.message}")
        return null
    }
    for (plugin in plugins) {
        manager.register(plugin)
    }
    if (manager.diagnostics.hasErrors) {
        DiagPrinter.print(manager.diagnostics)
        return null
    }
    return manager
}
