package yux.backend.jvm

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-M5 后端验收测试：
 * - 端到端（T-M5-3/11）：samples 编译运行，stdout 逐字比对；
 * - 反射验证（T-M5-2/5/6/7）：getter/setter、data 方法、注解、互操作；
 * - 类结构（T-M5-1）：javap 等价的反射/字节码核对。
 */
class AsmBackendTest {

    // ── T-M5-11 端到端（samples 全跑）────────────────────────────────────────

    @Test
    fun `hello sample prints`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                print "Hello Yux"
            }
        """.trimIndent())
        assertEquals("Hello Yux", out)
    }

    @Test
    fun `data class toString sample`() {
        val out = BackendTestSupport.compileAndRun("""
            data User {
                id:Int
                name:String
            }
            fun main() {
                u = User(1, "Steve")
                print u.toString
            }
        """.trimIndent())
        assertEquals("User(id=1, name=Steve)", out)
    }

    @Test
    fun `control flow sample runs all branches`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                health = 10
                if health <= 0 {
                    print "died"
                } else if health < 10 {
                    print "low"
                } else {
                    print "ok"
                }
                total = 0
                for i in 0..5 {
                    total += i
                }
                print total
            }
        """.trimIndent())
        assertEquals("ok15", out)
    }

    @Test
    fun `while loop and when`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                x = 3
                while x > 0 {
                    x -= 1
                }
                when x {
                    0 -> print "zero"
                    else -> print "other"
                }
            }
        """.trimIndent())
        assertEquals("zero", out)
    }

    @Test
    fun `nullable smart cast and null guard`() {
        val out = BackendTestSupport.compileAndRun("""
            Sword {
                damage:Int
            }
            fun main() {
                item:Any? = null
                if item is Sword {
                    print "sword"
                }
                name:String? = "Steve"
                if name != null {
                    print name.length
                }
                len = name.length
                print len
            }
        """.trimIndent())
        assertEquals("55", out) // name.length=5；name 非空时 len=5
    }

    @Test
    fun `interop sample uses jdk classes`() {
        val out = BackendTestSupport.compileAndRun("""
            import java.util.UUID
            fun main() {
                id:UUID? = UUID.randomUUID()
                t = System.currentTimeMillis()
                m = Math.max(1, 2)
                s = "  abc  ".trim()
                n = s.length
                print (id != null)
                print (t > 0)
                print m
                print (s == "abc")
                print n
            }
        """.trimIndent())
        assertEquals("true" + "true" + "2" + "true" + "3", out)
    }

    @Test
    fun `class with custom accessor and method`() {
        val out = BackendTestSupport.compileAndRun("""
            Player {
                name:String
                health:Int = 20
                fun greet() = "Hi " + name
            }
            fun main() {
                p = Player("Alex", 20)
                print p.greet()
            }
        """.trimIndent())
        assertEquals("Hi Alex", out)
    }

    @Test
    fun `super call invokes parent method not override`() {
        val out = BackendTestSupport.compileAndRun("""
            Base {
                fun greet() = "base"
            }
            Child extends Base {
                override fun greet() = "child"
                fun parentGreet() = super.greet()
            }
            fun main() {
                c = Child()
                print c.parentGreet()
            }
        """.trimIndent())
        // super.greet() 必须调用父类版本（INVOKESPECIAL），避免递归到子类覆写
        assertEquals("base", out)
    }

    @Test
    fun `private and protected method access flags`() {
        val classes = BackendTestSupport.compile("""
            A {
                private fun secret() = 1
                protected fun helper() = 2
                fun pub() = 3
            }
        """.trimIndent())
        val loader = BackendTestSupport.load(classes)
        val cls = Class.forName("A", false, loader)
        assertTrue(java.lang.reflect.Modifier.isPrivate(cls.getDeclaredMethod("secret").modifiers), "secret 应为 private")
        assertTrue(java.lang.reflect.Modifier.isProtected(cls.getDeclaredMethod("helper").modifiers), "helper 应为 protected")
        assertTrue(java.lang.reflect.Modifier.isPublic(cls.getDeclaredMethod("pub").modifiers), "pub 应为 public")
    }

    @Test
    fun `lambda with closure capture and invoke`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                x = 5
                g:(Int)->Int = v -> v + x
                print g(3)
            }
        """.trimIndent())
        assertEquals("8", out)
    }

    @Test
    fun `block lambda with implicit it`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                g:(Int)->Int = { it * 2 }
                print g(21)
            }
        """.trimIndent())
        assertEquals("42", out)
    }

    @Test
    fun `string template and concat`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                n = "Yux"
                print "Hi ${'$'}n!"
                print ("a" + "b")
            }
        """.trimIndent())
        assertEquals("Hi Yux!ab", out)
    }

    @Test
    fun `try catch finally`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                try {
                    x = 1 / 0
                } catch e {
                    print "caught"
                }
                print "after"
            }
        """.trimIndent())
        assertEquals("caughtafter", out)
    }

    // ── T-M5-1 类结构 ─────────────────────────────────────────────────────────

    @Test
    fun `class and method access flags via reflection`() {
        val classes = BackendTestSupport.compile("""
            fun main() {
            }
        """.trimIndent())
        val loader = BackendTestSupport.load(classes)
        val cls = Class.forName("Main", false, loader)
        assertTrue(java.lang.reflect.Modifier.isPublic(cls.modifiers), "文件类应为 public")
        val main = cls.getMethod("main")
        assertTrue(
            java.lang.reflect.Modifier.isPublic(main.modifiers) &&
                java.lang.reflect.Modifier.isStatic(main.modifiers),
            "main 应为 public static",
        )
        assertEquals(Void.TYPE, main.returnType)
    }

    @Test
    fun `data class structure`() {
        val classes = BackendTestSupport.compile("""
            data User {
                id:Int
                name:String
            }
        """.trimIndent())
        val loader = BackendTestSupport.load(classes)
        val cls = Class.forName("User", false, loader)
        // 私有字段
        val fields = cls.declaredFields.associateBy { it.name }
        assertEquals(2, fields.size)
        assertTrue(
            java.lang.reflect.Modifier.isPrivate(fields.getValue("id").modifiers) &&
                java.lang.reflect.Modifier.isPrivate(fields.getValue("name").modifiers),
        )
        // 构造器全参
        val ctor = cls.constructors.single()
        assertEquals(listOf(Int::class.javaPrimitiveType, String::class.java), ctor.parameterTypes.toList())
        // getter
        val get = cls.getMethod("getName")
        assertEquals("Steve", get.invoke(ctor.newInstance(1, "Steve")))
    }

    // ── T-M5-2 属性 getter/setter（Boolean → isX）─────────────────────────────

    @Test
    fun `property accessors round trip`() {
        val classes = BackendTestSupport.compile("""
            Flag {
                on:Boolean
                count:Int
            }
        """.trimIndent())
        val loader = BackendTestSupport.load(classes)
        val cls = Class.forName("Flag", false, loader)
        val ctor = cls.constructors.single()
        val obj = ctor.newInstance(true, 7)
        assertEquals("isOn", cls.methods.first { it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType }.name)
        assertTrue(cls.getMethod("isOn").invoke(obj) as Boolean)
        cls.getMethod("setOn", Boolean::class.javaPrimitiveType).invoke(obj, false)
        assertTrue(!(cls.getMethod("isOn").invoke(obj) as Boolean))
        cls.getMethod("setCount", Int::class.javaPrimitiveType).invoke(obj, 42)
        assertEquals(42, cls.getMethod("getCount").invoke(obj))
    }

    // ── T-M5-5 data 类方法 ────────────────────────────────────────────────────

    @Test
    fun `data class equals hashCode`() {
        val classes = BackendTestSupport.compile("""
            data User {
                id:Int
                name:String
            }
        """.trimIndent())
        val loader = BackendTestSupport.load(classes)
        val cls = Class.forName("User", false, loader)
        val ctor = cls.constructors.single()
        val a = ctor.newInstance(1, "Steve")
        val b = ctor.newInstance(1, "Steve")
        val c = ctor.newInstance(2, "Steve")
        assertTrue(a == b)
        assertTrue(a != c)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals("User(id=1, name=Steve)", a.toString())
    }

    // ── T-M5-6 注解 ───────────────────────────────────────────────────────────

    @Test
    fun `service class carries YuxService annotation`() {
        val classes = BackendTestSupport.compile("""
            service Database {
                connect() {
                }
            }
        """.trimIndent())
        val loader = BackendTestSupport.load(classes)
        val cls = Class.forName("Database", false, loader)
        val ann = cls.getAnnotation(yux.di.YuxService::class.java)
        assertNotNull(ann, "service 类应带 @YuxService（RUNTIME 可反射）")
    }

    // ── T-M5-7 互操作 ─────────────────────────────────────────────────────────

    @Test
    fun `static member calls`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                print (System.currentTimeMillis() > 0)
                print Math.max(3, 9)
            }
        """.trimIndent())
        assertEquals("true9", out)
    }

    @Test
    fun `instance member java bean mapping`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                s = "Hello"
                print (s.length)
                print (s.toUpperCase() == "HELLO")
            }
        """.trimIndent())
        assertEquals("5true", out)
    }

    // ── T-M5-8 空守卫 / 字符串模板 ────────────────────────────────────────────

    @Test
    fun `null guard defaults on nullable receiver`() {
        val out = BackendTestSupport.compileAndRun("""
            fun main() {
                s:String? = null
                len = s.length
                print len
                print ("len=" + len)
            }
        """.trimIndent())
        assertEquals("0len=0", out)
    }

    // ── T-M5-9 async 同步降级 ─────────────────────────────────────────────────

    @Test
    fun `async fun runs synchronously with remind`() {
        val diags = yux.compiler.diag.DiagnosticSink()
        val sf = yux.compiler.source.SourceFile("main.yux", "async fun f() { print \"async\" }\nfun main() { f() }")
        val program = yux.compiler.parser.Parser(sf, diags).parse()
        val decls = yux.compiler.parser.CstToAst().convert(program)
        val analysis = yux.compiler.sema.SemanticAnalyzer().analyze(mapOf("main.yux" to decls), diags)
        val module = yux.compiler.irgen.IRGen(analysis).generate(mapOf("main.yux" to decls))
        val artifacts = AsmBackend().generate(module, diags)
        assertTrue(diags.diagnostics.any { it.severity == yux.compiler.diag.Severity.REMIND }, "async 应产 REMIND 降级提示")
        val out = BackendTestSupport.run(artifacts.associate { it.className to it.bytes }, "Main")
        assertEquals("async", out)
    }

    // ── T-M5-10 运行时加载（自定义类加载器）───────────────────────────────────

    @Test
    fun `compiled classes load via custom classloader`() {
        val classes = BackendTestSupport.compile("fun main() { print \"loaded\" }")
        val loader = BackendTestSupport.load(classes)
        val cls = Class.forName("Main", true, loader)
        assertNotNull(cls)
        assertTrue(loader != BackendTestSupport::class.java.classLoader)
    }

    @Test
    fun `samples directory e2e`() {
        // 仓库 samples/ 端到端（T-M5-11）：逐一编译运行比对 stdout
        val samplesDir = Path.of("../../samples").toAbsolutePath().normalize()
        if (!Files.isDirectory(samplesDir)) return
        Files.list(samplesDir).use { stream ->
            stream.filter { it.toString().endsWith(".yux") }.sorted().forEach { file ->
                val source = Files.readString(file)
                val mainClass = file.fileName.toString().substringBeforeLast('.').replaceFirstChar { it.uppercaseChar() }
                val expected = file.resolveSibling(file.fileName.toString().substringBeforeLast('.') + ".stdout")
                    .takeIf { Files.exists(it) }?.let { Files.readString(it) }
                val out = BackendTestSupport.run(BackendTestSupport.compile(source, file.toString()), mainClass)
                if (expected != null) {
                    assertEquals(expected, out, "samples/${file.fileName} stdout 不匹配")
                }
            }
        }
    }
}
