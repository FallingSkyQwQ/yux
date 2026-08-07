package yux.compiler.sema

import org.junit.jupiter.api.Test
import yux.compiler.diag.ErrorCodes
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-M3-6 智能转型（02-§7.3 / 01-S-6.3.1、S-8.1）：`is T` 分支、空值判空分支、
 * 可变引用抑制（R0002）。
 */
class SmartCastTest {

    @Test
    fun `is type branch enables member access`() {
        val r = SemaTestSupport.analyze(
            """
            Sword {
                damage:Int
            }
            fun main() {
                item:Any? = null
                if item is Sword {
                    d = item.damage
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `when is branch enables member access`() {
        val r = SemaTestSupport.analyze(
            """
            Sword {
                damage:Int
            }
            fun main() {
                item:Any? = null
                when item {
                    is Sword -> d = item.damage
                    else -> print "none"
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `null check enables member access on nullable`() {
        val r = SemaTestSupport.analyze(
            """
            Player {
                name:String = ""
            }
            fun main() {
                p:Player? = null
                if p != null {
                    n = p.name
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `else branch of null check is non-null`() {
        val r = SemaTestSupport.analyze(
            """
            Player {
                name:String = ""
            }
            fun main() {
                p:Player? = null
                if p == null {
                    print "empty"
                } else {
                    n = p.name
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `cast not leaking out of branch`() {
        val r = SemaTestSupport.analyze(
            """
            Sword {
                damage:Int
            }
            fun main() {
                item:Any? = null
                if item is Sword {
                    d = item.damage
                }
                d2 = item.damage
            }
            """.trimIndent(),
        )
        // 分支外 item 回到 Any?，.damage 不可解析
        assertTrue(r.hasCode(ErrorCodes.UNRESOLVED_MEMBER), r.diagnostics.toString())
    }

    @Test
    fun `mutable reference smart cast suppressed with remind`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                x:Any? = null
                if x is String {
                    print "str"
                }
                x = 1
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.SMART_CAST_MUTABLE), r.diagnostics.toString())
    }

    @Test
    fun `mutable reference null check suppressed with remind`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                x:String?
                if x != null {
                    print "not null"
                }
                x = "a"
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.SMART_CAST_MUTABLE), r.diagnostics.toString())
    }

    @Test
    fun `immutable parameter smart cast works`() {
        val r = SemaTestSupport.analyze(
            """
            Sword {
                damage:Int
            }
            fun hit(item:Any?) {
                if item is Sword {
                    d = item.damage
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `negation null check`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                s:String? = "abc"
                if !(s == null) {
                    n = s.length
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `smart cast on non-null any via is`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                v:Any? = "hello"
                if v is String {
                    n = v.length
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `when null condition smart casts in else`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                s:String? = "a"
                when s {
                    null -> print "empty"
                    else -> n = s.length
                }
            }
            """.trimIndent(),
        )
        assertFalse(r.hasErrors, r.errors.toString())
    }

    @Test
    fun `loop variable reassignment disables smart cast`() {
        val r = SemaTestSupport.analyze(
            """
            fun f(items:List Any) {
                for x in items {
                    if x is String {
                        x = 5
                        n = x.length
                    }
                }
            }
            """.trimIndent(),
        )
        // x 在分支内被重赋值 → 转型失效，x.length 不可解析（S-6.3.1/02-§7.3）
        assertTrue(r.hasCode(ErrorCodes.UNRESOLVED_MEMBER), r.diagnostics.toString())
    }

    @Test
    fun `impossible is-cast rejected`() {
        val r = SemaTestSupport.analyze(
            """
            fun f(x:String) {
                if x is Int {
                    n = x + 1
                }
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.TYPE_MISMATCH), r.diagnostics.toString())
    }

    @Test
    fun `when branch condition type mismatch`() {
        val r = SemaTestSupport.analyze(
            """
            fun main() {
                x:String = "a"
                when x {
                    5 -> print "five"
                    else -> print "other"
                }
            }
            """.trimIndent(),
        )
        assertTrue(r.hasCode(ErrorCodes.TYPE_MISMATCH), r.diagnostics.toString())
    }
}
