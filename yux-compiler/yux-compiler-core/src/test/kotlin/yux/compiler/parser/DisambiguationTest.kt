package yux.compiler.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M2-5 消歧（01-§7.7 / 02-§6）：两遍式声明预收集 + `Type(args)` 构造 vs
 * `func(args)` 调用 vs `func arg` 无括号调用 vs `Type TypeArg(args)` 泛型构造。
 */
class DisambiguationTest {

    private fun dump(text: String) = ParseTestSupport.dump(text)

    private fun inFun(body: String): String = "fun main() {\n$body\n}"

    @Test
    fun `type followed by parens is constructor`() {
        val ast = dump(inFun("player = Player(\"Steve\", 20)"))
        assertTrue(ast.contains("New(Player, [Str(\"\\\"Steve\\\"\"), Int(20)])"), ast)
    }

    @Test
    fun `generic type followed by parens is constructor with type args`() {
        val ast = dump("data Result T { }\nfun main() {\n  r = Result Int(true, 5)\n}")
        assertTrue(ast.contains("New(Result<Int>, [Bool(true), Int(5)])"), ast)
    }

    @Test
    fun `bare generic constructor`() {
        val ast = dump(inFun("list = List String()"))
        assertTrue(ast.contains("New(List<String>, [])"), ast)
    }

    @Test
    fun `function call with parens`() {
        val ast = dump("fun send(msg:String) {\n}\nfun main() {\n  send(\"Hi\")\n}")
        assertTrue(ast.contains("Call(Id(send), [Str(\"\\\"Hi\\\"\")])"), ast)
    }

    @Test
    fun `function no-paren call`() {
        val ast = dump("fun send(msg:String) {\n}\nfun main() {\n  send \"Hi\"\n}")
        assertTrue(ast.contains("CallNP(Id(send), [Str(\"\\\"Hi\\\"\")])"), ast)
    }

    @Test
    fun `member no-paren call on variable`() {
        val ast = dump(inFun("player.send \"Welcome\""))
        assertTrue(ast.contains("CallNP(Get(Id(player), send), [Str(\"\\\"Welcome\\\"\")])"), ast)
    }

    @Test
    fun `type name with member access is static access`() {
        val ast = dump(inFun("t = System.currentTimeMillis()"))
        assertTrue(ast.contains("Call(Get(TypeRef(System), currentTimeMillis), [])"), ast)
    }

    @Test
    fun `builtin type member property`() {
        val ast = dump(inFun("x = String.length"))
        assertTrue(ast.contains("Get(TypeRef(String), length)"), ast)
    }

    @Test
    fun `collector records declared type arity`() {
        // Result 声明为单类型参数；Result Int(x) 应为 Result<Int>(x)
        val ast = dump("data Result T { }\nfun main() {\n  r = Result Int(x)\n}")
        assertTrue(ast.contains("New(Result<Int>, [Id(x)])"), ast)
    }

    @Test
    fun `map with two type args groups correctly`() {
        val ast = dump(inFun("m:Map String Int = newMap()"))
        assertTrue(ast.contains("var m: Map<String, Int> = Call(Id(newMap), [])"), ast)
    }

    @Test
    fun `list with nested generic type arg`() {
        val ast = dump(inFun("m:Map String List Int = newMap()"))
        assertTrue(ast.contains("Map<String, List<Int>>"), ast)
    }

    @Test
    fun `lowercase unknown name treated as callable`() {
        val ast = dump(inFun("print player.name"))
        assertTrue(ast.contains("CallNP(Id(print), [Get(Id(player), name)])"), ast)
    }

    @Test
    fun `imported type last segment usable in constructor`() {
        val ast = dump("import java.util.Date\nfun main() {\n  d = Date()\n}")
        assertTrue(ast.contains("Call(Id(Date), [])") || ast.contains("New(Date, [])"), ast)
    }

    @Test
    fun `member function disambiguates inside class`() {
        val ast = dump("Player {\n  fun send(msg:String) {\n  }\n  fun check() {\n    x = send(\"hi\")\n  }\n}")
        assertTrue(ast.contains("Call(Id(send), [Str(\"\\\"hi\\\"\")])"), ast)
    }

    @Test
    fun `no errors in disambiguation samples`() {
        val text = """
            data Result T { }
            fun send(msg:String) { }
            fun main() {
                r = Result Int(true, 5)
                send "Hi"
                player = Player("Steve", 20)
                list = List String()
                t = System.currentTimeMillis()
            }
        """.trimIndent()
        assertFalse(ParseTestSupport.parse(text).hasErrors)
    }
}
