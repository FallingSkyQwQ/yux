package yux.backend.jvm

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T-M14-*：async fun 完整 CPS 状态机协程的端到端验收。
 *
 * 覆盖：同步门面（返回 Task）+ Tasks.await、async 上下文内挂起调用（续延传递）、
 * 急切内联至首挂起点（非阻塞主线程）、挂起恢复跨线程、异常经门面交付。
 */
class AsyncFunE2eTest {

    private fun classFiles(source: String): Map<String, ByteArray> = BackendTestSupport.compile(source)

    @Test
    fun `async fun 同步门面返回 Task 且 await 得到结果`() {
        val out = BackendTestSupport.run(
            classFiles(
                """

                async fun doubleAsync(n: Int): Int {
                    t = Tasks.launch { }
                    await t
                    return n * 2
                }

                fun main() {
                    t = doubleAsync(21)
                    v = Tasks.await(t)
                    print (v as Int)
                }
                """.trimIndent(),
            ),
            "Main",
        )
        assertEquals("42", out)
    }

    @Test
    fun `async 上下文内挂起调用返回声明类型`() {
        val out = BackendTestSupport.run(
            classFiles(
                """

                async fun fetch(): Int {
                    t = Tasks.launch { Tasks.sleep(20) }
                    await t
                    return 7
                }

                async fun compute(): Int {
                    v = fetch()
                    return v + 1
                }

                fun main() {
                    print (Tasks.await(compute()) as Int)
                }
                """.trimIndent(),
            ),
            "Main",
        )
        assertEquals("8", out)
    }

    @Test
    fun `async fun 调用不阻塞主线程`() {
        val classes = classFiles(
            """

            async fun work(): Int {
                t = Tasks.launch { Tasks.sleep(300) }
                await t
                return 1
            }

            fun main() {
                // 只触发门面创建：work 应立即返回（在池线程上等待），不阻塞 main
                t = work()
                print t.isDone
            }
            """.trimIndent(),
        )
        val out = BackendTestSupport.run(classes, "Main")
        assertEquals("false", out, "门面创建后 work 应已挂起（isDone=false），证明未阻塞主线程")
    }

    @Test
    fun `await 挂起可取消`() {
        val classes = classFiles(
            """

            async fun blocked(): Int {
                t = Tasks.launch { Tasks.sleep(5000) }
                await t
                return 1
            }

            fun main() {
                t = blocked()
                cancelled = t.cancel()
                print cancelled
            }
            """.trimIndent(),
        )
        // cancel 门面仅阻止交付（不中断运行中的协程）
        val out = BackendTestSupport.run(classes, "Main")
        assertEquals("true", out)
    }

    @Test
    fun `await 的目标 Task 被取消以 CancellationException 传播`() {
        val classes = classFiles(
            """

            async fun guarded2(x: Task?): Int {
                await x
                return 1
            }

            fun main() {
                inner = Tasks.launch { Tasks.sleep(5000) }
                outer = guarded2(inner)
                inner.cancel()
                try {
                    Tasks.await(outer)
                    print "no-throw"
                } catch e {
                    print "caught"
                }
            }
            """.trimIndent(),
        )
        val out = BackendTestSupport.run(classes, "Main")
        assertEquals("caught", out)
    }

    @Test
    fun `async fun 异常经门面 Task 交付`() {
        val classes = classFiles(
            """

            async fun boom() {
                throw RuntimeException()
            }

            fun main() {
                t = boom()
                try {
                    Tasks.await(t)
                    print "no-throw"
                } catch e {
                    print "caught"
                }
            }
            """.trimIndent(),
        )
        val out = BackendTestSupport.run(classes, "Main")
        assertEquals("caught", out)
    }

    @Test
    fun `async fun 链深层嵌套正确执行`() {
        val out = BackendTestSupport.run(
            classFiles(
                """

                async fun level(n: Int): Int {
                    t = Tasks.launch { }
                    await t
                    if n <= 0 {
                        return 0
                    }
                    return level(n - 1)
                }

                fun main() {
                    print (Tasks.await(level(20)) as Int)
                }
                """.trimIndent(),
            ),
            "Main",
        )
        assertEquals("0", out)
    }

    @Test
    fun `循环内挂起`() {
        val out = BackendTestSupport.run(
            classFiles(
                """

                async fun sumLoop(n: Int): Int {
                    total = 0
                    i = 0
                    while i < n {
                        t = Tasks.launch { }
                        await t
                        total = total + i
                        i = i + 1
                    }
                    return total
                }

                fun main() {
                    print (Tasks.await(sumLoop(5)) as Int)
                }
                """.trimIndent(),
            ),
            "Main",
        )
        assertEquals("10", out)
    }

    @Test
    fun `try catch 跨挂起点捕获异常`() {
        val out = BackendTestSupport.run(
            classFiles(
                """

                async fun risky(): Int {
                    t = Tasks.launch { throw RuntimeException() }
                    await t
                    return 1
                }

                async fun guarded(): Int {
                    try {
                        return risky()
                    } catch e {
                        return 42
                    }
                }

                fun main() {
                    print (Tasks.await(guarded()) as Int)
                }
                """.trimIndent(),
            ),
            "Main",
        )
        assertEquals("42", out)
    }

    @Test
    fun `finally 跨挂起点执行`() {
        val out = BackendTestSupport.run(
            classFiles(
                """

                async fun withFinally(flag: Int): Int {
                    r = 0
                    try {
                        t = Tasks.launch { }
                        await t
                        if flag == 1 {
                            throw RuntimeException()
                        }
                        r = 10
                    } finally {
                        t2 = Tasks.launch { }
                        await t2
                        r = r + 1
                    }
                    return r
                }

                fun main() {
                    print (Tasks.await(withFinally(0)) as Int)
                    try {
                        Tasks.await(withFinally(1))
                        print "no-throw"
                    } catch e {
                        print "throw"
                    }
                }
                """.trimIndent(),
            ),
            "Main",
        )
        // withFinally(0) → finally 在正常路径执行后返回 11；withFinally(1) → 异常经 finally 后传播
        assertEquals("11throw", out)
    }

    @Test
    fun `CancellationException 自动重抛不被 catch 吞掉`() {
        val classes = classFiles(
            """

            async fun swallowTry(x: Task?): Int {
                try {
                    await x
                    return 1
                } catch e {
                    // Kotlin 式自动重抛：CancellationException 不应被吞掉
                    return 2
                }
            }

            fun main() {
                inner = Tasks.launch { Tasks.sleep(5000) }
                t = swallowTry(inner)
                Tasks.sleep(100)
                inner.cancel()
                try {
                    Tasks.await(t)
                    print "no-throw"
                } catch e {
                    print "caught"
                }
            }
            """.trimIndent(),
        )
        val out = BackendTestSupport.run(classes, "Main")
        assertEquals("caught", out)
    }

    @Test
    fun `async block 体内可 await 且不阻塞主线程`() {
        val out = BackendTestSupport.run(
            classFiles(
                """
                fun main() {
                    async {
                        t = Tasks.launch { Tasks.sleep(50) }
                        await t
                        print "block-done"
                    }
                    Tasks.sleep(200)
                    print "main-done"
                }
                """.trimIndent(),
            ),
            "Main",
        )
        // async{} 立即返回（不阻塞）；块体在池上执行，await 后完成
        assertEquals("block-donemain-done", out)
    }

    @Test
    fun `深循环挂起栈安全（单续延迭代驱动）`() {
        val out = BackendTestSupport.run(
            classFiles(
                """
                async fun spin(n: Int): Int {
                    i = 0
                    total = 0
                    while i < n {
                        t = Tasks.launch { }
                        await t
                        total = total + 1
                        i = i + 1
                    }
                    return total
                }

                fun main() {
                    print (Tasks.await(spin(20000)) as Int)
                }
                """.trimIndent(),
            ),
            "Main",
        )
        assertEquals("20000", out)
    }

    @Test
    fun `async 递归链（挂起 + resume 链）不爆栈`() {
        val out = BackendTestSupport.run(
            classFiles(
                """
                async fun level(n: Int): Int {
                    t = Tasks.launch { }
                    await t
                    if n <= 0 {
                        return 0
                    }
                    return level(n - 1)
                }

                fun main() {
                    print (Tasks.await(level(1000)) as Int)
                }
                """.trimIndent(),
            ),
            "Main",
        )
        assertEquals("0", out)
    }

    @Test
    fun `async fun 可被 override 且经父类型引用虚拟分派`() {
        val out = BackendTestSupport.run(
            classFiles(
                """
                Base {
                    async fun greet(): Int {
                        t = Tasks.launch { }
                        await t
                        return 1
                    }
                }

                Child extends Base {
                    override async fun greet(): Int {
                        t = Tasks.launch { }
                        await t
                        return 2
                    }
                }

                fun main() {
                    c = Child()
                    print (Tasks.await(c.greet()) as Int)
                    b:Base = c
                    print (Tasks.await(b.greet()) as Int)
                }
                """.trimIndent(),
            ),
            "Main",
        )
        // 子类 override 与父类型引用都应分派到 Child 的实现
        assertEquals("22", out)
    }

    @Test
    fun `接口可声明 async fun 默认方法`() {
        val out = BackendTestSupport.run(
            classFiles(
                """
                Greeter {
                    async fun greet(): Int {
                        t = Tasks.launch { }
                        await t
                        return 7
                    }
                }

                GreeterImpl implements Greeter {
                }

                fun main() {
                    g = GreeterImpl()
                    print (Tasks.await(g.greet()) as Int)
                }
                """.trimIndent(),
            ),
            "Main",
        )
        assertEquals("7", out)
    }
}
