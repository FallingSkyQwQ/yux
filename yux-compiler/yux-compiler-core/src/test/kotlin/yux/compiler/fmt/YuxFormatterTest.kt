package yux.compiler.fmt

import org.junit.jupiter.api.Test
import yux.compiler.diag.DiagnosticSink
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * YuxFormatter 格式化测试（M16，T-M16-3）：
 * 规范排版 + 幂等性（format(format(x)) == format(x)）+ 注释保留 + 解析失败路径。
 */
class YuxFormatterTest {
    private val fmt = YuxFormatter()

    /** 格式化；解析失败则直接 fail。 */
    private fun fmtOk(source: String): String {
        val diag = DiagnosticSink()
        val out = fmt.format(source, diag)
        assertNotNull(out, "应格式化成功: $source\n诊断: ${diag.diagnostics}")
        return out
    }

    private fun assertFormat(
        input: String,
        expected: String,
    ) {
        val actual = fmtOk(input)
        // 格式化输出以单个 '\n' 结尾
        assertEquals(expected.trimIndent() + "\n", actual, "格式化输出不匹配")
        // 幂等性：二次格式化不变
        assertEquals(actual, fmtOk(actual), "格式化不幂等")
    }

    @Test
    fun `hello program`() {
        assertFormat(
            "fun main(){print \"Hello Yux\"}",
            """
            fun main() {
                print "Hello Yux"
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `indent and blank lines between top-level decls`() {
        assertFormat(
            "fun a() {\n    print 1\n}\nfun b() { print 2 }",
            """
            fun a() {
                print 1
            }

            fun b() {
                print 2
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `spacing rules`() {
        assertFormat(
            "fun main() {\n    x=a+b*c\n    v = f(a,b)\n    s = x[0]\n    r = 0..5\n    n = -x\n    b = !flag\n    t = m.k as String?\n}",
            """
            fun main() {
                x = a + b * c
                v = f(a, b)
                s = x[0]
                r = 0..5
                n = -x
                b = !flag
                t = m.k as String?
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `if else chain`() {
        assertFormat(
            "fun main() {\n    if health <= 0 { print \"died\" } else if health < 10 { print \"low\" } else { print \"ok\" }\n}",
            """
            fun main() {
                if health <= 0 {
                    print "died"
                } else if health < 10 {
                    print "low"
                } else {
                    print "ok"
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `for while when`() {
        assertFormat(
            "fun main() {\n    for i in 0..5 { total += i }\n    while x > 0 { x -= 1 }\n    when x { 0 -> print \"zero\" else -> print \"other\" }\n}",
            """
            fun main() {
                for i in 0..5 {
                    total += i
                }
                while x > 0 {
                    x -= 1
                }
                when x {
                    0 -> print "zero"
                    else -> print "other"
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `data class`() {
        assertFormat(
            "data User { id:Int name:String }",
            """
            data User {
                id: Int
                name: String
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `async fun and try catch`() {
        assertFormat(
            "async fun compute():Int {\n    v = doubleAsync(21)\n    return v + 1\n}\nfun main() {\n    try { return risky() } catch e { return 0 } finally { print \"done\" }\n}",
            """
            async fun compute(): Int {
                v = doubleAsync(21)
                return v + 1
            }

            fun main() {
                try {
                    return risky()
                } catch e {
                    return 0
                } finally {
                    print "done"
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `block lambda inline and multiline`() {
        assertFormat(
            "fun main() {\n    t = Tasks.launch { }\n    xs = list.map { it * 2 }\n    ys = list.map { x -> x + 1 }\n    zs = list.map {\n        a = it * 2\n        a + 1\n    }\n}",
            """
            fun main() {
                t = Tasks.launch { }
                xs = list.map { it * 2 }
                ys = list.map { x -> x + 1 }
                zs = list.map {
                    a = it * 2
                    a + 1
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `string interpolation`() {
        assertFormat(
            "fun main() {\n    print \"\${player.name} is \${age} years\"\n    print \"\$name\"\n}",
            """
            fun main() {
                print "${'$'}{player.name} is ${'$'}{age} years"
                print "${'$'}name"
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `comments preserved inline and own line`() {
        assertFormat(
            "// 顶部注释\nfun main() {\n    x = 1 // 行内注释\n    // 独占行注释\n    y = 2\n    /* 块注释 */ z = 3\n}",
            """
            // 顶部注释
            fun main() {
                x = 1 // 行内注释
                // 独占行注释
                y = 2
                /* 块注释 */ z = 3
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `blank lines in block preserved`() {
        assertFormat(
            "fun main() {\n    a = 1\n\n    b = 2\n}",
            """
            fun main() {
                a = 1

                b = 2
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `import package annotation`() {
        assertFormat(
            "package com.example\nimport a.b.C\nimport d.e.*\n@Ann(x = 1)\nfun main() { }",
            """
            package com.example

            import a.b.C
            import d.e.*

            @Ann(x = 1)
            fun main() { }
            """.trimIndent(),
        )
    }

    @Test
    fun `if expression and when expression`() {
        assertFormat(
            "fun main() {\n    name = if args.isEmpty() then \"default\" else (args.get(0) as String)\n    v = when x { 1 -> \"a\" else -> \"b\" }\n}",
            """
            fun main() {
                name = if args.isEmpty() then "default" else (args.get(0) as String)
                v = when x {
                    1 -> "a"
                    else -> "b"
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `type annotations and generics`() {
        assertFormat(
            "fun f(n: Int): String {\n    m:Map String Int = Map()\n    return m.get(n)\n}",
            """
            fun f(n: Int): String {
                m: Map String Int = Map()
                return m.get(n)
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `parse error returns null`() {
        val diag = DiagnosticSink()
        val out = fmt.format("fun main() { x = }", diag)
        assertNull(out, "语法错误应返回 null")
        assertEquals(true, diag.hasErrors)
    }

    @Test
    fun `empty block is brace space brace`() {
        assertFormat(
            "fun f() { }\nA { }",
            """
            fun f() { }

            A { }
            """.trimIndent(),
        )
    }

    @Test
    fun `extension function dot`() {
        assertFormat(
            "fun Int.double(): Int { return this * 2 }\nfun main() {\n    print 21.double()\n}",
            """
            fun Int.double(): Int {
                return this * 2
            }

            fun main() {
                print 21.double()
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `await and async block`() {
        assertFormat(
            "async fun f(): Int { t = Tasks.launch { }\nawait t\nreturn 1 }\nfun main() {\n    async {\n        t = Tasks.launch { Tasks.sleep(50) }\n        await t\n        print \"block\"\n    }\n}",
            """
            async fun f(): Int {
                t = Tasks.launch { }
                await t
                return 1
            }

            fun main() {
                async {
                    t = Tasks.launch { Tasks.sleep(50) }
                    await t
                    print "block"
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `property with accessors`() {
        assertFormat(
            "service Counter {\n    count:Int {\n        get { return 1 }\n    }\n}",
            """
            service Counter {
                count: Int {
                    get {
                        return 1
                    }
                }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `is and as expressions`() {
        assertFormat(
            "fun main() {\n    if x is String { print \"s\" }\n    y = x as String\n}",
            """
            fun main() {
                if x is String {
                    print "s"
                }
                y = x as String
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `idempotent on samples`() {
        val sources =
            listOf(
                "fun main() {\n    x:Int = 1\n    print x\n}",
                "data User {\n    id: Int\n    name: String\n}\nfun main() {\n    u = User(1, \"Steve\")\n    print u.toString\n}",
                "fun main() {\n    health = 10\n    if health <= 0 {\n        print \"died\"\n    } else if health < 10 {\n        print \"low\"\n    } else {\n        print \"ok\"\n    }\n    total = 0\n    for i in 0..5 {\n        total += i\n    }\n}",
            )
        for (s in sources) {
            val once = fmtOk(s)
            assertEquals(once, fmtOk(once), "非幂等: $s")
        }
    }
}
