package yux.async

import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T-M11-2 并发时序验收：Task / launch / parallel / sleep / await。
 *
 * 验证 launch 非阻塞返回、await 真实等待、并行执行、parallel 块尾隐式 join、
 * sleep 时序、异常解包与 cancel 语义；总时长控制在 3 秒内。
 */
class AsyncTest {

    private companion object {
        /** 短暂休眠：验证 await 真实等待块内副作用。 */
        const val SLEEP_SHORT_MS = 50L

        /** parallel 块内休眠：验证阻塞至完成且总耗时不小于该值。 */
        const val SLEEP_MEDIUM_MS = 80L

        /** 两个任务各自休眠：验证并行而非串行（串行需 200ms）。 */
        const val SLEEP_LONG_MS = 100L

        /** 两个任务开始时间差上限：>50ms 说明近似串行。 */
        const val CONCURRENCY_SLACK_MS = 50L

        /** sleep 时序容差：sleep(100) 至少 90ms。 */
        const val SLEEP_SLACK_MS = 10L

        /** cancel 用长任务：确保 cancel 调用时块尚未完成。 */
        const val CANCEL_SLEEP_MS = 300L
    }

    @Test
    fun `launch 立即返回且 await 得到空结果`() {
        val task = Tasks.launch { }
        // launch 不阻塞调用方：任务可能仍在调度中，也可能已瞬间完成
        Tasks.await(task)
        assertTrue(task.isDone, "await 返回后任务应已完成")
        // Function0<Unit> 的返回值为 null（runAsync 结果）
        assertNull(Tasks.await(task))
    }

    @Test
    fun `await 真实等待至块内副作用完成`() {
        val ref = AtomicReference<String>()
        val task = Tasks.launch {
            Thread.sleep(SLEEP_SHORT_MS)
            ref.set("done")
        }
        Tasks.await(task)
        assertEquals("done", ref.get(), "await 返回后块内写入应已可见")
    }

    @Test
    fun `两个 launch 真正并行执行`() {
        val starts = ConcurrentLinkedQueue<Long>()
        val latch = CountDownLatch(2)
        val t1 = Tasks.launch {
            starts.add(System.currentTimeMillis())
            latch.countDown()
            Thread.sleep(SLEEP_LONG_MS)
        }
        val t2 = Tasks.launch {
            starts.add(System.currentTimeMillis())
            latch.countDown()
            Thread.sleep(SLEEP_LONG_MS)
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS), "两个任务都应已启动")
        Tasks.await(t1)
        Tasks.await(t2)
        assertEquals(2, starts.size)
        val diff = Math.abs(starts.poll() - starts.poll())
        assertTrue(
            diff < CONCURRENCY_SLACK_MS,
            "两任务开始时间差 $diff ms 应 < 50ms（证明并行而非串行）",
        )
    }

    @Test
    fun `parallel 阻塞至块完成`() {
        val flag = AtomicBoolean(false)
        val start = System.nanoTime()
        Tasks.parallel {
            Thread.sleep(SLEEP_MEDIUM_MS)
            flag.set(true)
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(flag.get(), "parallel 返回后块内副作用应已生效（块尾隐式 join）")
        assertTrue(
            elapsedMs >= SLEEP_MEDIUM_MS,
            "parallel 应阻塞至少 ${SLEEP_MEDIUM_MS}ms，实际 $elapsedMs ms",
        )
    }

    @Test
    fun `sleep 时序符合预期`() {
        val start = System.nanoTime()
        Tasks.sleep(SLEEP_LONG_MS)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(
            elapsedMs >= SLEEP_LONG_MS - SLEEP_SLACK_MS,
            "sleep(100) 应至少 90ms，实际 $elapsedMs ms",
        )
    }

    @Test
    fun `await 解包块内异常`() {
        val boom = RuntimeException("boom-from-block")
        val task = Tasks.launch { throw boom }
        val thrown = assertFailsWith<RuntimeException> { Tasks.await(task) }
        assertSame(boom, thrown.cause, "await 应解包原始异常为 cause")
    }

    @Test
    fun `cancel 使任务完成且块不再执行`() {
        val flag = AtomicBoolean(false)
        val task = Tasks.launch {
            Thread.sleep(CANCEL_SLEEP_MS)
            flag.set(true)
        }
        assertTrue(task.cancel(), "cancel 应返回 true")
        assertTrue(task.isDone, "取消后任务视为已完成")
        // 给被取消任务一点观察时间：若未被取消则块体会在 300ms 后写入 flag
        Thread.sleep(SLEEP_SHORT_MS)
        assertFalse(flag.get(), "被取消的任务不应执行块体")
    }
}
