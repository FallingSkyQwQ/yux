package yux.compiler.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M2-9 CstToAst：CST → AST 规约正确性（节点种类与结构）。
 */
class CstToAstTest {

    private fun dump(text: String) = ParseTestSupport.dump(text)

    private fun inFun(body: String): String = "fun main() {\n$body\n}"

    @Test
    fun `parens are preserved as nodes in AST`() {
        val ast = dump(inFun("x = (a + b)"))
        assertTrue(ast.contains("Paren(Binary(+, Id(a), Id(b)))"), ast)
    }

    @Test
    fun `string template parts alternate text and expressions`() {
        val ast = dump(inFun("m = \"pre \$a post\""))
        assertTrue(ast.contains("Template[Text(\"pre \"), Id(a), Text(\" post\")]"), ast)
    }

    @Test
    fun `nested interpolation expression`() {
        val ast = dump(inFun("m = \"\${greet(\"x\")}\""))
        assertTrue(ast.contains("Call(Id(greet), [Str(\"\\\"x\\\"\")])"), ast)
    }

    @Test
    fun `raw string literal`() {
        val ast = dump(inFun("r = \"\"\"raw \$\$ text\"\"\""))
        assertTrue(ast.contains("Raw("), ast)
    }

    @Test
    fun `nullable type in declaration`() {
        val ast = dump(inFun("name:String? = null"))
        assertTrue(ast.contains("var name: String?"), ast)
        assertTrue(ast.contains("Null"), ast)
    }

    @Test
    fun `function type annotation`() {
        val ast = dump("fun apply(f:(Int) -> Int) {\n}")
        assertTrue(ast.contains("f: (Int) -> Int"), ast)
    }

    @Test
    fun `nullable postfix expression`() {
        val ast = dump(inFun("x = a.b?"))
        assertTrue(ast.contains("Nullable(Get(Id(a), b))"), ast)
    }

    @Test
    fun `assignment expression in value position`() {
        val ast = dump(inFun("a = 1\nx = (a = 2)"))
        assertTrue(ast.contains("Paren(Assign(Id(a), =, Int(2)))"), ast)
    }

    @Test
    fun `boolean and char literals`() {
        val ast = dump(inFun("a = true\nb = false\nc = 'x'"))
        assertTrue(ast.contains("Bool(true)"), ast)
        assertTrue(ast.contains("Bool(false)"), ast)
        assertTrue(ast.contains("Char('x')"), ast)
    }

    @Test
    fun `float and int literals preserve text`() {
        val ast = dump(inFun("a = 1_000L\nb = 3.14F\nc = 0x1F"))
        assertTrue(ast.contains("Int(1_000L)"), ast)
        assertTrue(ast.contains("Float(3.14F)"), ast)
        assertTrue(ast.contains("Int(0x1F)"), ast)
    }

    @Test
    fun `type call preserves generic structure`() {
        val ast = dump("data Result T { }\nfun main() {\n  r = Result Int(true)\n}")
        assertTrue(ast.contains("New(Result<Int>, [Bool(true)])"), ast)
    }
}
