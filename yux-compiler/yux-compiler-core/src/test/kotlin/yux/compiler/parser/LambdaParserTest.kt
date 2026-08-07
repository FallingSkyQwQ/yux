package yux.compiler.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M2-6 Lambda（01-§7.4）：箭头 Lambda、多参括号形式、块 Lambda（隐式 `it`）、
 * Lambda 作为无括号调用实参。
 */
class LambdaParserTest {

    private fun dump(text: String) = ParseTestSupport.dump(text)

    private fun inFun(body: String): String = "fun main() {\n$body\n}"

    @Test
    fun `single param arrow lambda`() {
        val ast = dump(inFun("f = x -> x + 1"))
        assertTrue(ast.contains("Lambda([x], Binary(+, Id(x), Int(1)))"), ast)
    }

    @Test
    fun `multi param lambda with parens`() {
        val ast = dump(inFun("f = (a, b) -> a + b"))
        assertTrue(ast.contains("Lambda([a, b], Binary(+, Id(a), Id(b)))"), ast)
    }

    @Test
    fun `multi param lambda with arrow inside parens`() {
        val ast = dump(inFun("f = (a, b -> a + b)"))
        assertTrue(ast.contains("Lambda([a, b], Binary(+, Id(a), Id(b)))"), ast)
    }

    @Test
    fun `block lambda as no-paren call argument`() {
        val ast = dump(inFun("names = players.map { it.name }"))
        assertTrue(ast.contains("CallNP(Get(Id(players), map), [BlockLambda{Get(Id(it), name)}])"), ast)
    }

    @Test
    fun `block lambda as paren call argument`() {
        val ast = dump(inFun("names = players.map({ it.name })"))
        assertTrue(ast.contains("Call(Get(Id(players), map), [BlockLambda{Get(Id(it), name)}])"), ast)
    }

    @Test
    fun `lambda as parenthesized no-paren argument`() {
        // players.sort (a, b -> a.health > b.health)
        val ast = dump(inFun("sorted = players.sort (a, b -> a.health > b.health)"))
        assertTrue(ast.contains("CallNP(Get(Id(players), sort), [Lambda([a, b], Binary(>, Get(Id(a), health), Get(Id(b), health)))])"), ast)
    }

    @Test
    fun `multi arg call with paren lambda`() {
        // numbers.reduce (0, (acc, n -> acc + n))
        val ast = dump(inFun("count = numbers.reduce (0, (acc, n -> acc + n))"))
        assertTrue(ast.contains("Call(Get(Id(numbers), reduce), [Int(0), Lambda([acc, n], Binary(+, Id(acc), Id(n)))])"), ast)
    }

    @Test
    fun `lambda assigned via as cast to function type`() {
        val ast = dump(inFun("increment = (x -> x + 1) as (Int) -> Int"))
        assertTrue(ast.contains("As(Lambda([x], Binary(+, Id(x), Int(1))), (Int) -> Int)"), ast)
    }

    @Test
    fun `lambda with multiple statements block`() {
        val ast = dump(inFun("xs = list.map {\n  print it\n  it.name\n}"))
        assertTrue(ast.contains("BlockLambda{CallNP(Id(print), [Id(it)]); Get(Id(it), name)}"), ast)
    }

    @Test
    fun `it identifier inside block lambda resolves`() {
        val ast = dump(inFun("xs = list.map { it.health }"))
        assertTrue(ast.contains("Get(Id(it), health)"), ast)
    }

    @Test
    fun `no errors for lambda samples`() {
        val text = """
            fun main() {
                names = players.map { it.name }
                sorted = players.sort (a, b -> a.health > b.health)
                count = numbers.reduce (0, (acc, n -> acc + n))
                increment = (x -> x + 1) as (Int) -> Int
            }
        """.trimIndent()
        assertFalse(ParseTestSupport.parse(text).hasErrors)
    }
}
