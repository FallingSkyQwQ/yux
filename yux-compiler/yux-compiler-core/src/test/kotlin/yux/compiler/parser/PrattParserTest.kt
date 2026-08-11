package yux.compiler.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M2-2 Pratt 表达式 + T-M2-7 无括号调用（01-§7.1 / S-7.2.2）。
 */
class PrattParserTest {

    private fun dump(text: String) = ParseTestSupport.dump(text)

    private fun inFun(body: String): String = "fun main() {\n$body\n}"

    @Test
    fun `operator precedence multiplication over addition`() {
        val ast = dump(inFun("x = 1 + 2 * 3"))
        assertTrue(ast.contains("Binary(*, Int(2), Int(3))"), ast)
        assertTrue(ast.contains("Binary(+, Int(1), Binary(*, Int(2), Int(3)))"), ast)
    }

    @Test
    fun `operator precedence comparison and logical`() {
        val ast = dump(inFun("x = a && b == c"))
        assertTrue(ast.contains("Binary(==, Id(b), Id(c))"), ast)
        assertTrue(ast.contains("Binary(&&, Id(a), Binary(==, Id(b), Id(c)))"), ast)
    }

    @Test
    fun `unary binds tighter than binary`() {
        val ast = dump(inFun("x = -a + b"))
        assertTrue(ast.contains("Unary(-, Id(a))"), ast)
        assertTrue(ast.contains("Binary(+, Unary(-, Id(a)), Id(b))"), ast)
    }

    @Test
    fun `unary minus applies to postfix member`() {
        val ast = dump(inFun("x = -a.b"))
        assertTrue(ast.contains("Unary(-, Get(Id(a), b))"), ast)
        assertFalse(ast.contains("Get(Unary"), ast)
    }

    @Test
    fun `postfix member access chains`() {
        val ast = dump(inFun("x = a.b.c"))
        assertTrue(ast.contains("Get(Get(Id(a), b), c)"), ast)
    }

    @Test
    fun `member call with parentheses`() {
        val ast = dump(inFun("x = list.size()"))
        assertTrue(ast.contains("Call(Get(Id(list), size), [])"), ast)
    }

    @Test
    fun `member access without parens is property`() {
        val ast = dump(inFun("x = list.size"))
        assertTrue(ast.contains("Get(Id(list), size)"), ast)
    }

    @Test
    fun `parenthesized expression groups`() {
        val ast = dump(inFun("x = (1 + 2) * 3"))
        assertTrue(ast.contains("Binary(*, Paren(Binary(+, Int(1), Int(2))), Int(3))"), ast)
    }

    @Test
    fun `no-paren call binds tighter than binary`() {
        // f a + b == f(a) + b（S-7.2.2）
        val ast = dump(inFun("x = f a + b"))
        assertTrue(ast.contains("Binary(+, CallNP(Id(f), [Id(a)]), Id(b))"), ast)
        assertFalse(ast.contains("CallNP(Id(f), [Binary"), ast)
    }

    @Test
    fun `no-paren call with string argument`() {
        val ast = dump(inFun("player.send \"Welcome\""))
        assertTrue(ast.contains("CallNP(Get(Id(player), send), [Str(\"\\\"Welcome\\\"\")])"), ast)
    }

    @Test
    fun `chained member no-paren call`() {
        // player.inventory.add item → add(item)
        val ast = dump(inFun("player.inventory.add item"))
        assertTrue(ast.contains("CallNP(Get(Get(Id(player), inventory), add), [Id(item)])"), ast)
    }

    @Test
    fun `no-paren call argument extends over postfix chain`() {
        // print player.name → print(player.name)
        val ast = dump(inFun("print player.name"))
        assertTrue(ast.contains("CallNP(Id(print), [Get(Id(player), name)])"), ast)
    }

    @Test
    fun `block lambda arg then chained member call applies to call result`() {
        // `xs.map { }.first()`：尾随 `.first()` 应作用于 map 的结果，而非块 lambda 本身
        val ast = dump(inFun("n = xs.map { it * 2 }.first()"))
        assertTrue(ast.contains("Call(Get(CallNP(Get(Id(xs), map), [BlockLambda"), ast)
        assertTrue(!ast.contains("BlockLambda{.*}\\.first"), "`.first()` 不应挂到块 lambda 上: $ast")
    }

    @Test
    fun `block lambda arg then chained block lambda call`() {
        // `xs.map { }.map { }`：连续块 lambda 实参
        val ast = dump(inFun("ys = xs.map { it * 2 }.map { it + 1 }"))
        assertTrue(ast.contains("CallNP(Get(CallNP(Get(Id(xs), map), [BlockLambda"), ast)
        assertTrue(ast.contains("CallNP(Get(CallNP(Get(Id(xs), map), [BlockLambda{Binary(*, Id(it), Int(2))}]), map), [BlockLambda"), ast)
    }

    @Test
    fun `range expression in for loop`() {
        val ast = dump(inFun("for i in 0..10 { print i }"))
        assertTrue(ast.contains("Range(Int(0), Int(10))"), ast)
    }

    @Test
    fun `is and as expressions`() {
        val ast = dump(inFun("x = item is Sword"))
        assertTrue(ast.contains("Is(Id(item), Sword)"), ast)
        val ast2 = dump(inFun("y = x as String"))
        assertTrue(ast2.contains("As(Id(x), String)"), ast2)
    }

    @Test
    fun `assignment is right associative`() {
        val ast = dump(inFun("a = 1\nb = 2\nc = 3\na = b = c"))
        assertTrue(ast.contains("assign Id(a) = Assign(Id(b), =, Id(c))"), ast)
    }

    @Test
    fun `compound assignment`() {
        val ast = dump(inFun("total = 0\ntotal += 1"))
        assertTrue(ast.contains("assign Id(total) += Int(1)"), ast)
    }

    @Test
    fun `throw is an expression`() {
        val ast = dump("fun f() = throw e")
        assertTrue(ast.contains("Throw(Id(e))"), ast)
    }

    @Test
    fun `string interpolation template`() {
        val ast = dump(inFun("msg = \"Hello \$name, next=\${age + 1}\""))
        assertTrue(
            ast.contains("Template[Text(\"Hello \"), Id(name), Text(\", next=\"), Binary(+, Id(age), Int(1))]"),
            ast,
        )
    }

    @Test
    fun `block lambda is not a no-paren argument in if condition`() {
        val ast = dump(inFun("if x { y = 1 }"))
        assertFalse(ast.contains("CallNP"), ast)
        assertTrue(ast.contains("if Id(x)"), ast)
    }

    @Test
    fun `for iterable block is body not argument`() {
        val ast = dump(inFun("for p in list { print p }"))
        assertFalse(ast.contains("CallNP(Id(list), [BlockLambda"), ast)
        assertTrue(ast.contains("for p in Id(list)"), ast)
    }

    @Test
    fun `no errors on valid expressions`() {
        assertFalse(
            ParseTestSupport.parse(inFun("x = 1 + 2 * 3\nf a + b\na.b.c()\n-1 + 2\n!(a && b)")).hasErrors,
        )
    }

    @Test
    fun `member access on call result`() {
        val ast = dump(inFun("x = f(a).b"))
        assertTrue(ast.contains("Get(Call(Id(f), [Id(a)]), b)"), ast)
    }
}
