package yux.cli

import yux.compiler.diag.DiagnosticSink
import yux.compiler.lexer.Lexer
import yux.compiler.lexer.TokenPrinter
import yux.compiler.parser.AstPrinter
import yux.compiler.parser.CstToAst
import yux.compiler.parser.Parser
import yux.compiler.source.SourceFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * yuxc 命令行入口（02-§14 / 06-§9.2）。
 *
 * v0.1 里程碑命令：
 * - `yuxc lex  <file>`  M1 词法：输出 token 流
 * - `yuxc ast  <file>`  M2 语法：输出 AST dump
 * - `yuxc ir   <file>`  M4 IR（待 M4 实现）
 */
fun main(args: Array<String>) {
    when (val cmd = args.getOrNull(0)) {
        "lex" -> runLex(args.getOrNull(1))
        "ast" -> runAst(args.getOrNull(1))
        else -> {
            System.err.println(
                """
                yuxc — Yux 编译器命令行
                用法: yuxc <command> <file.yux>
                  lex <file>   输出词法 token（M1）
                  ast <file>   输出语法 AST（M2）
                """.trimIndent(),
            )
            if (cmd == "lex" || cmd == "ast") {
                System.err.println("错误: 缺少源文件参数")
            }
            kotlin.system.exitProcess(if (cmd == null) 1 else 0)
        }
    }
}

private fun loadSource(path: String?): SourceFile? {
    if (path == null) {
        System.err.println("错误: 缺少源文件参数")
        return null
    }
    val p = Path.of(path)
    if (!Files.isRegularFile(p)) {
        System.err.println("错误: 文件不存在: $path")
        return null
    }
    return SourceFile(p.fileName.toString(), Files.readString(p))
}

private fun runLex(path: String?) {
    val source = loadSource(path) ?: return
    val diagnostics = DiagnosticSink()
    val tokens = Lexer(source, diagnostics).tokenize()
    print(TokenPrinter.print(tokens))
    printDiagnostics(diagnostics)
}

private fun runAst(path: String?) {
    val source = loadSource(path) ?: return
    val diagnostics = DiagnosticSink()
    val parser = Parser(source, diagnostics)
    val program = parser.parse()
    val decls = CstToAst().convert(program)
    print(AstPrinter.dump(decls))
    printDiagnostics(diagnostics)
}

private fun printDiagnostics(diagnostics: DiagnosticSink) {
    if (diagnostics.diagnostics.isNotEmpty()) {
        System.err.println("\n-- 诊断 --")
        diagnostics.diagnostics.forEach { d ->
            System.err.println("${d.severity}${d.position?.let { " $it" } ?: ""}: ${d.message}")
        }
    }
}
