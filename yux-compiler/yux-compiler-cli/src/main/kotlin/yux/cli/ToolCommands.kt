package yux.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.optionalValue
import yux.compiler.diag.DiagnosticSink
import yux.compiler.fmt.YuxFormatter
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 工具类命令（M16）：new 脚手架 / fmt 格式化 / version / doc。
 */

// ── yuxc new ─────────────────────────────────────────────────────────────────

/** 模板清单：模板名 → (资源相对路径 → 目标相对路径)。
 * 注意：资源名避免 `.` 开头（Gradle 默认排除点文件，如 `.gitignore`）。 */
private val TEMPLATES: Map<String, Map<String, String>> =
    mapOf(
        "hello" to mapOf("main.yux" to "main.yux"),
        "project" to
            mapOf(
                "build.yml" to "build.yml",
                "src/main.yux" to "src/main.yux",
                "gitignore" to ".gitignore",
            ),
        "plugin" to
            mapOf(
                "build.yml" to "build.yml",
                "src/main.yux" to "src/main.yux",
                "gitignore" to ".gitignore",
            ),
    )

internal class NewCommand :
    CliktCommand(
        name = "new",
        help = "从模板创建新项目（hello/project/plugin）",
    ) {
    private val name by argument("name", help = "项目名（同时作为目录名与 build.yml name）")
    private val template by option("-t", "--template", help = "模板：hello/project/plugin").default("project")
    private val packageName by option("--package", help = "包名（写入 main.yux 的 package 声明）").default("")
    private val force by option("--force", help = "目标目录已存在时覆盖").flag()

    override fun run() {
        val files =
            TEMPLATES[template]
                ?: throw UsageError("未知模板: $template（可选：${TEMPLATES.keys.joinToString("/")}）", statusCode = 1)
        if (!name.matches(Regex("[A-Za-z0-9_\\-]+"))) {
            throw UsageError("非法项目名: $name（仅允许字母/数字/下划线/连字符）", statusCode = 1)
        }
        val target = Path.of(name)
        if (Files.exists(target) && !force) {
            throw UsageError("目录已存在: $target（使用 --force 覆盖）", statusCode = 1)
        }
        if (Files.exists(target)) {
            target.toFile().deleteRecursively()
        }
        for ((resourcePath, targetPath) in files) {
            val content =
                loadTemplate(template, resourcePath) ?: run {
                    throw UsageError("模板资源缺失: templates/$template/$resourcePath", statusCode = 1)
                }
            val rendered = render(content)
            val out = target.resolve(targetPath)
            Files.createDirectories(out.parent)
            Files.writeString(out, rendered)
        }
        println("已创建 $template 项目: $target")
        if (packageName.orEmpty().isNotEmpty()) {
            println("包名: $packageName")
        }
    }

    private fun loadTemplate(
        template: String,
        relative: String,
    ): String? {
        val stream =
            NewCommand::class.java.getResourceAsStream("/templates/$template/$relative")
                ?: return null
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    /** 占位符替换：`<name>` 项目名、`<Name>` 首字母大写、`<package>` 包名（空则去掉 package 行）。 */
    private fun render(content: String): String {
        val withName =
            content
                .replace("<name>", name)
                .replace("<Name>", name.replaceFirstChar { it.uppercaseChar() })
        return if (packageName.orEmpty().isEmpty()) {
            withName.lines().filterNot { it.contains("<package>") }.joinToString("\n")
        } else {
            withName.replace("<package>", packageName.orEmpty())
        }
    }
}

// ── yuxc fmt ─────────────────────────────────────────────────────────────────

internal class FmtCommand :
    CliktCommand(
        name = "fmt",
        help = "格式化 Yux 源码（yux.compiler.fmt）",
    ) {
    private val color by colorOption()
    private val plugins by pluginOption()
    private val files by argument("files", help = "源文件 .yux（缺省读取 stdin；无参数且非 tty 时读取 stdin）").multiple()
    private val inPlace by option("-i", "--in-place", help = "原地改写文件").flag()
    private val check by option("--check", help = "只校验不改写：有未格式化文件时退出码 1").flag()

    override fun run() {
        val colors = Ansi.colors(color)
        val pluginManager = loadPlugins(plugins) ?: throw CliFailure()
        val formatter = YuxFormatter(pluginManager = pluginManager)
        if (files.isEmpty()) {
            val input = System.`in`.readBytes().toString(StandardCharsets.UTF_8)
            val formatted = format(formatter, input, colors) ?: throw CliFailure()
            if (check) {
                if (formatted != input) {
                    System.err.println("需要格式化: <stdin>")
                    throw CliFailure()
                }
            } else {
                print(formatted)
            }
            return
        }
        var failed = false
        for (file in files) {
            val path = Path.of(file)
            if (!Files.isRegularFile(path)) {
                System.err.println("错误: 文件不存在: $file")
                failed = true
                continue
            }
            val input = Files.readString(path)
            val formatted =
                format(formatter, input, colors, file) ?: run {
                    failed = true
                    continue
                }
            when {
                check -> {
                    if (formatted != input) {
                        System.err.println("需要格式化: $file")
                        failed = true
                    }
                }

                inPlace -> {
                    if (formatted != input) Files.writeString(path, formatted)
                }

                else -> {
                    print(formatted)
                }
            }
        }
        if (failed) throw CliFailure()
        if (check) println("全部已格式化")
    }

    private fun format(
        formatter: YuxFormatter,
        input: String,
        colors: Colors,
        label: String = "<stdin>",
    ): String? {
        val diagnostics = DiagnosticSink()
        val out = formatter.format(input, diagnostics)
        if (out == null) {
            System.err.println("错误: 语法错误（无法格式化）: $label")
            DiagPrinter.print(diagnostics, input, colors)
            return null
        }
        return out
    }
}

// ── yuxc version ─────────────────────────────────────────────────────────────

internal class VersionCommand : CliktCommand(name = "version", help = "显示 yuxc 版本") {
    override fun run() {
        println("yuxc ${YuxcVersion.version}")
    }
}

// ── yuxc doc ─────────────────────────────────────────────────────────────────

internal class DocCommand : CliktCommand(name = "doc", help = "打开在线文档（headless 环境仅打印 URL）") {
    private val topic by argument("topic", help = "文档主题：01-入门 … 05-附录 / tutorial / 具体路径").optional()

    override fun run() {
        val base = "https://github.com/FallingSkyQwQ/yux/blob/main/docs"
        val path =
            when (topic) {
                null, "", "tutorial", "00" -> "tutorial/00-总览.md"
                "01", "入门" -> "tutorial/01-入门.md"
                "02", "进阶" -> "tutorial/02-进阶.md"
                "03", "精通" -> "tutorial/03-精通.md"
                "04", "生态" -> "tutorial/04-生态门户.md"
                "05", "附录" -> "tutorial/05-附录.md"
                else -> topic!!.removePrefix("/")
            }
        val url = "$base/$path"
        println(url)
        try {
            java.awt.Desktop
                .getDesktop()
                .browse(URI(url))
        } catch (_: Exception) {
            // headless / 无桌面：仅打印 URL
        }
    }
}
