package yux.compiler.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M2-3 语句解析（01-§6）：if/when/for/while/return/try/async/parallel/unsafe、
 * StmtEnd 换行/`;`、块内最后语句省略换行。
 */
class StatementParserTest {

    private fun dump(text: String) = ParseTestSupport.dump(text)

    private fun inFun(body: String): String = "fun main() {\n$body\n}"

    @Test
    fun `var decl with explicit type and init`() {
        val ast = dump(inFun("age:Int = 18"))
        assertTrue(ast.contains("var age: Int = Int(18)"), ast)
    }

    @Test
    fun `var decl without init`() {
        val ast = dump(inFun("age:Int"))
        assertTrue(ast.contains("var age: Int"), ast)
        assertFalse(ast.contains("="), ast)
    }

    @Test
    fun `var decl with inferred type`() {
        val ast = dump(inFun("name = \"Steve\""))
        assertTrue(ast.contains("var name = Str(\"\\\"Steve\\\"\")"), ast)
    }

    @Test
    fun `redeclared identifier is assignment`() {
        val ast = dump(inFun("x = 1\nx = 2"))
        assertTrue(ast.contains("var x = Int(1)"), ast)
        assertTrue(ast.contains("assign Id(x) = Int(2)"), ast)
    }

    @Test
    fun `if with block body`() {
        val ast = dump(inFun("if health <= 0 {\n  player.send \"你死了\"\n}"))
        assertTrue(ast.contains("if Binary(<="), ast)
        assertTrue(ast.contains("CallNP(Get(Id(player), send)"), ast)
    }

    @Test
    fun `if else if else chain`() {
        val ast = dump(inFun("if a {\n  x = 1\n} else if b {\n  x = 2\n} else {\n  x = 3\n}"))
        assertTrue(ast.contains("else"), ast)
        assertTrue(ast.contains("if Binary(<=") || ast.contains("if Id(a)"), ast)
    }

    @Test
    fun `single line if body`() {
        val ast = dump(inFun("if x return 1"))
        assertTrue(ast.contains("if Id(x)"), ast)
        assertTrue(ast.contains("return Int(1)"), ast)
    }

    @Test
    fun `when with is expr and else branches`() {
        val ast = dump(inFun("when item {\n  is Sword -> print \"weapon\"\n  1 -> print \"one\"\n  else -> print \"other\"\n}"))
        assertTrue(ast.contains("when Id(item)"), ast)
        assertTrue(ast.contains("branch is Sword ->"), ast)
        assertTrue(ast.contains("branch Int(1) ->"), ast)
        assertTrue(ast.contains("branch else ->"), ast)
    }

    @Test
    fun `for loop with range`() {
        val ast = dump(inFun("for i in 0..10 {\n  print i\n}"))
        assertTrue(ast.contains("for i in Range(Int(0), Int(10))"), ast)
    }

    @Test
    fun `for loop over collection`() {
        val ast = dump(inFun("for player in onlinePlayers() {\n  player.send \"在线\"\n}"))
        assertTrue(ast.contains("for player in Call(Id(onlinePlayers), [])"), ast)
    }

    @Test
    fun `while loop`() {
        val ast = dump(inFun("while running {\n  tick()\n}"))
        assertTrue(ast.contains("while Id(running)"), ast)
    }

    @Test
    fun `return with and without value`() {
        val ast = dump(inFun("return 42\nif x {\n  return\n}"))
        assertTrue(ast.contains("return Int(42)"), ast)
        assertTrue(ast.contains("return"), ast)
    }

    @Test
    fun `break and continue`() {
        val ast = dump(inFun("for i in 0..5 {\n  if i == 3 {\n    break\n  }\n  continue\n}"))
        assertTrue(ast.contains("break"), ast)
        assertTrue(ast.contains("continue"), ast)
    }

    @Test
    fun `try expression shorthand with catch`() {
        val ast = dump(inFun("try loadConfig()\ncatch e {\n  print \"fail\"\n}"))
        assertTrue(ast.contains("try"), ast)
        assertTrue(ast.contains("catch e"), ast)
        assertTrue(ast.contains("Call(Id(loadConfig), [])"), ast)
    }

    @Test
    fun `try block with typed catch and finally`() {
        val ast = dump(inFun("try {\n  risky()\n} catch e:IOException {\n  print \"IO\"\n} finally {\n  close()\n}"))
        assertTrue(ast.contains("catch e: IOException"), ast)
        assertTrue(ast.contains("finally"), ast)
    }

    @Test
    fun `async parallel unsafe blocks`() {
        val ast = dump(inFun("async {\n  data = await fetch()\n}\nparallel {\n  download()\n  calculate()\n}\nunsafe {\n  poke()\n}"))
        assertTrue(ast.contains("async"), ast)
        assertTrue(ast.contains("parallel"), ast)
        assertTrue(ast.contains("unsafe"), ast)
    }

    @Test
    fun `nested blocks`() {
        val ast = dump(inFun("if a {\n  if b {\n    x = 1\n  }\n}"))
        assertTrue(ast.contains("block"), ast)
        assertTrue(ast.contains("var x = Int(1)"), ast)
    }

    @Test
    fun `semicolon terminates statements on same line`() {
        val ast = dump(inFun("a = 1; b = 2"))
        assertTrue(ast.contains("var a = Int(1)"), ast)
        assertTrue(ast.contains("var b = Int(2)"), ast)
    }

    @Test
    fun `last statement in block may omit newline`() {
        val ast = dump("fun main() {\n  print \"hello\"\n}")
        assertFalse(ParseTestSupport.parse("fun main() {\n  print \"hello\"\n}").hasErrors)
        assertTrue(ast.contains("print"), ast)
    }
}
