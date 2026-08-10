package yux.backend.jvm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * S-8.4 并发语义端到端：`async { }` 经 yux.async.Tasks.launch 提交（不阻塞主线程）；
 * `parallel { }` 顶层独立语句拆分为任务并发执行 + 块尾 join（依赖块级局部时退化为单任务）；
 * 非法结构（return/break/continue/写外部局部）编译期拒绝（不再静默内联）。
 */
class ConcurrencyE2eTest {

    @Test
    fun `async block runs without blocking main thread`() {
        val out = BackendTestSupport.compileAndRun(
            """
            fun main() {
                print "A"
                async {
                    Tasks.sleep(100)
                    print "X"
                }
                print "B"
                Tasks.sleep(300)
                print "C"
            }
            """.trimIndent(),
        )
        // 主线程不等待 async 块：A、B 先于 X 输出（X 在异步任务 100ms 后打印）
        assertEquals("ABXC", out)
    }

    @Test
    fun `parallel block executes statements concurrently`() {
        val started = System.nanoTime()
        val out = BackendTestSupport.compileAndRun(
            """
            fun main() {
                parallel {
                    Tasks.sleep(200)
                    Tasks.sleep(200)
                }
                print "done"
            }
            """.trimIndent(),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("done", out)
        // 两条 200ms 休眠并发执行：~200ms；顺序执行需 ~400ms（余量区分，避免 CI 抖动）
        assertTrue(elapsedMs < 350, "并行耗时 $elapsedMs ms 应显著小于顺序 400ms")
    }

    @Test
    fun `parallel block statements run on pool threads`() {
        val out = BackendTestSupport.compileAndRun(
            """
            fun main() {
                parallel {
                    print Thread.currentThread().getName().contains("ForkJoinPool")
                }
                print "done"
            }
            """.trimIndent(),
        )
        assertEquals("truedone", out)
    }

    @Test
    fun `parallel block with shared block locals falls back to single task`() {
        val out = BackendTestSupport.compileAndRun(
            """
            fun main() {
                parallel {
                    a = 1
                    print (a + 1)
                }
                print "done"
            }
            """.trimIndent(),
        )
        // 语句 2 引用语句 1 的块级局部 → 单任务模式（整块一个任务，块尾 join 后主线程继续）
        assertEquals("2done", out)
    }

    @Test
    fun `parallel block joins before main continues`() {
        val out = BackendTestSupport.compileAndRun(
            """
            fun main() {
                parallel {
                    Tasks.sleep(100)
                    print "P"
                }
                print "end"
            }
            """.trimIndent(),
        )
        assertEquals("Pend", out)
    }

    @Test
    fun `async block rejects return`() {
        val e = assertFailsWith<IllegalStateException> {
            BackendTestSupport.compile(
                """
                fun main() {
                    async {
                        return
                    }
                }
                """.trimIndent(),
            )
        }
        assertTrue(e.message!!.contains("不支持 return"), e.message)
    }

    @Test
    fun `async block rejects writing outer local`() {
        val e = assertFailsWith<IllegalStateException> {
            BackendTestSupport.compile(
                """
                fun main() {
                    x = 1
                    async {
                        x = 2
                    }
                }
                """.trimIndent(),
            )
        }
        assertTrue(e.message!!.contains("不能修改外部局部变量"), e.message)
    }

    @Test
    fun `parallel block rejects break targeting outer loop`() {
        val e = assertFailsWith<IllegalStateException> {
            BackendTestSupport.compile(
                """
                fun main() {
                    while true {
                        parallel {
                            break
                        }
                    }
                }
                """.trimIndent(),
            )
        }
        assertTrue(e.message!!.contains("作用于外层循环"), e.message)
    }

    @Test
    fun `unsafe block passes through with normal semantics`() {
        val out = BackendTestSupport.compileAndRun(
            """
            fun main() {
                unsafe {
                    print "raw"
                }
            }
            """.trimIndent(),
        )
        assertEquals("raw", out)
    }
}
