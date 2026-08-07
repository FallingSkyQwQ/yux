package yux.compiler.sema

import org.junit.jupiter.api.Test
import yux.compiler.diag.ErrorCodes
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M3-8/9 data 校验、service 注入图循环检测、注解目标校验。
 */
class AnnotationsTest {

    // ── data 类（S-5.3）─────────────────────────────────────────────────────

    @Test
    fun `data class with all properties`() {
        val r = SemaTestSupport.analyze(
            """
            data User {
                id:Int
                name:String
            }
            fun main() {
                u = User(1, "Steve")
                n = u.name
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `data class constructor arity mismatch`() {
        val r = SemaTestSupport.analyze(
            """
            data User {
                id:Int
                name:String
            }
            fun main() {
                u = User(1)
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.ARGUMENT_COUNT_MISMATCH), r.diagnostics.toString())
    }

    @Test
    fun `data class constructor arg type mismatch`() {
        val r = SemaTestSupport.analyze(
            """
            data User {
                id:Int
                name:String
            }
            fun main() {
                u = User("a", 1)
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.TYPE_MISMATCH), r.diagnostics.toString())
    }

    // ── service（S-5.4）─────────────────────────────────────────────────────

    @Test
    fun `service with injection ok`() {
        val r = SemaTestSupport.analyze(
            """
            service Database {
                connect() {
                }
            }
            service UserService {
                database:Database
                fun list() = database.connect()
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `service cycle detected`() {
        val r = SemaTestSupport.analyze(
            """
            service A {
                b:B
            }
            service B {
                a:A
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.SERVICE_CYCLE), r.diagnostics.toString())
    }

    @Test
    fun `service cycle with three nodes`() {
        val r = SemaTestSupport.analyze(
            """
            service A {
                b:B
            }
            service B {
                c:C
            }
            service C {
                a:A
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.SERVICE_CYCLE), r.diagnostics.toString())
    }

    @Test
    fun `service non injectable property needs initializer`() {
        val r = SemaTestSupport.analyze(
            """
            service Config {
                port:Int
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.SERVICE_PROPERTY_NO_INIT), r.diagnostics.toString())
    }

    @Test
    fun `service property with initializer ok`() {
        val r = SemaTestSupport.analyze(
            """
            service Config {
                port:Int = 25565
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `acyclic services ok`() {
        val r = SemaTestSupport.analyze(
            """
            service Database {
            }
            service UserService {
                database:Database
            }
            service Api {
                users:UserService
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 注解（S-8.2）────────────────────────────────────────────────────────

    @Test
    fun `serializable on data class ok`() {
        val r = SemaTestSupport.analyze(
            """
            @Serializable
            data User {
                name:String
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `serializable on function error`() {
        val r = SemaTestSupport.analyze("@Serializable\nfun f() {\n}")
        assertTrue(r.hasCode(ErrorCodes.INVALID_ANNOTATION_TARGET), r.diagnostics.toString())
    }

    @Test
    fun `override on data class error`() {
        val r = SemaTestSupport.analyze("@Override\ndata User {\n  name:String\n}")
        assertTrue(r.hasCode(ErrorCodes.INVALID_ANNOTATION_TARGET), r.diagnostics.toString())
    }

    @Test
    fun `unknown annotation passes through`() {
        val r = SemaTestSupport.analyze("@EventHandler\nfun f() {\n}")
        assertFalse(r.hasErrors, r.errors.toString())
    }

    // ── 访问器（S-5.2）──────────────────────────────────────────────────────

    @Test
    fun `custom accessors ok`() {
        val r = SemaTestSupport.analyze(
            """
            Player {
                health:Int {
                    get { return value }
                    set { value = set }
                }
            }
            fun main() {
                p = Player(20)
                h = p.health
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `read only property assignment error`() {
        val r = SemaTestSupport.analyze(
            """
            data User {
                id:Int
            }
            fun main() {
                u = User(1)
                u.id = 5
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.VAL_ASSIGNMENT), r.diagnostics.toString())
    }

    @Test
    fun `duplicate accessor error`() {
        val r = SemaTestSupport.analyze(
            """
            Player {
                health:Int {
                    get { return value }
                    get { return value }
                }
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.ILLEGAL_ACCESSOR), r.diagnostics.toString())
    }
}
