package yux.compiler.fuzz

import org.junit.jupiter.api.Test
import yux.compiler.diag.DiagnosticSink
import yux.compiler.lexer.Lexer
import yux.compiler.parser.CstToAst
import yux.compiler.parser.Parser
import yux.compiler.source.SourceFile
import kotlin.random.Random

/**
 * T-M11-4：词法/语法 fuzz。
 *
 * 用固定种子生成 5000 个随机输入（覆盖 Yux 源码可能出现的大部分字符），
 * 依次跑 Lexer → Parser →（无诊断错误时）CstToAst，断言整个过程不抛任何异常。
 * 诊断允许存在（fuzz 输入大概率非法），只要求不崩溃。
 */
class LexerParserFuzzTest {

    /** Yux 源码可能出现的全部字符：字母、数字、运算符、空白、`_`、中文。 */
    private val fuzzChars: CharArray = buildString {
        append(('a'..'z').toList())
        append(('A'..'Z').toList())
        append(('0'..'9').toList())
        append("+-*/%=!<>?.&|:;(),[]{}#@\$\"'`") // 运算符与引号（含双引号/单引号/反引号）
        append(' ') // 空格
        append('\t') // 制表
        append('\n') // 换行
        append('_')
        append("你好世界中文注释测试") // 注释内可出现的中文字符
    }.toCharArray()

    @Test
    fun `random lexer-parser inputs never crash`() {
        val rng = Random(20260810) // 固定种子，保证可复现
        for (i in 0 until 5000) {
            val length = rng.nextInt(0, 81) // 长度 0~80
            val input = buildString(length) {
                repeat(length) { append(fuzzChars[rng.nextInt(fuzzChars.size)]) }
            }
            runLexerParserAst(input, i)
        }
    }

    private fun runLexerParserAst(input: String, index: Int) {
        // 1. Lexer：不得抛异常
        try {
            Lexer(SourceFile("fuzz.yux", input)).tokenize()
        } catch (t: Throwable) {
            throw AssertionError("Lexer crashed on input #$index: ${quote(input)}", t)
        }

        // 2. Parser：诊断进 DiagnosticSink，不抛异常
        val diagnostics = DiagnosticSink()
        val program = try {
            Parser(SourceFile("fuzz.yux", input), diagnostics).parse()
        } catch (t: Throwable) {
            throw AssertionError("Parser crashed on input #$index: ${quote(input)}", t)
        }

        // 3. 仅当无诊断错误时才跑 CstToAst（有错误时 AST 可能不完整，跳过转换）
        if (!diagnostics.hasErrors) {
            try {
                CstToAst().convert(program)
            } catch (t: Throwable) {
                throw AssertionError("CstToAst crashed on input #$index: ${quote(input)}", t)
            }
        }
    }

    /** 转义控制字符，便于在断言消息中定位失败输入。 */
    private fun quote(input: String): String =
        input.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\r", "\\r")
            .replace("\"", "\\\"")
}
