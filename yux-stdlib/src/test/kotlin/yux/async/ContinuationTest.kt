package yux.async

import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T-M14-*：协程续延运行时验收（Continuation / tryAwait / 取消信号 / 门面完成）。
 */
class ContinuationTest {

    private class RecordingContinuation : Continuation {
        val value = AtomicReference<Any?>()
        val error = AtomicReference<Throwable?>()

        override fun resume(value: Any?) {
            this.value.set(value)
        }

        override fun resumeWithException(t: Throwable) {
            this.error.set(t)
        }
    }

    @Test
    fun `未完成 future 注册回调并返回 SUSPENDED`() {
        val future = CompletableFuture<Any?>()
        val cont = RecordingContinuation()
        assertSame(Continuations.SUSPENDED, Continuations.tryAwait(future, cont))
        future.complete(42)
        assertEquals(42, cont.value.get(), "future 完成后回调应恢复续延")
    }

    @Test
    fun `已完成 future 内联返回结果且不触发回调`() {
        val future = CompletableFuture<Any?>()
        future.complete("done")
        val cont = RecordingContinuation()
        val result = Continuations.tryAwait(future, cont)
        assertEquals("done", result, "已完成 future 应内联返回结果")
        assertNull(cont.value.get(), "内联返回不应触发 resume 回调")
    }

    @Test
    fun `异常完成的 future 返回 PendingException 包装`() {
        val boom = RuntimeException("boom")
        val future = CompletableFuture<Any?>()
        future.completeExceptionally(boom)
        val pending = Continuations.tryAwait(future, RecordingContinuation())
        val wrapped = assertIs<Continuations.PendingException>(pending)
        assertSame(boom, wrapped.throwable, "应解包 cause 而非 CompletionException 壳")
    }

    @Test
    fun `异常完成的 future 回调解包 cause`() {
        val boom = RuntimeException("boom")
        val future = CompletableFuture<Any?>()
        val cont = RecordingContinuation()
        Continuations.tryAwait(future, cont)
        future.completeExceptionally(boom)
        assertSame(boom, cont.error.get(), "回调应收到解包后的 cause")
    }

    @Test
    fun `取消的 future 以 CancellationException 恢复`() {
        val future = CompletableFuture<Any?>()
        val cont = RecordingContinuation()
        Continuations.tryAwait(future, cont)
        future.cancel(false)
        assertIs<CancellationException>(cont.error.get(), "取消应以 CancellationException 恢复续延")
    }

    @Test
    fun `已取消 future 内联返回 PendingException(CancellationException)`() {
        val future = CompletableFuture<Any?>()
        future.cancel(false)
        val pending = Continuations.tryAwait(future, RecordingContinuation())
        val wrapped = assertIs<Continuations.PendingException>(pending)
        assertIs<CancellationException>(wrapped.throwable)
    }

    @Test
    fun `FutureCompletion 将结果写入 future`() {
        val future = CompletableFuture<Any?>()
        val completion = Continuations.FutureCompletion(future)
        completion.resume("value")
        assertEquals("value", future.getNow(null))
    }

    @Test
    fun `FutureCompletion 将异常写入 future`() {
        val boom = RuntimeException("boom")
        val future = CompletableFuture<Any?>()
        val completion = Continuations.FutureCompletion(future)
        completion.resumeWithException(boom)
        try {
            future.getNow(null)
            error("应抛异常")
        } catch (e: CompletionException) {
            assertSame(boom, e.cause, "异常应经 CompletionException 包装到达")
        }
    }

    @Test
    fun `FutureCompletion 线程安全写入`() {
        val future = CompletableFuture<Any?>()
        val completion = Continuations.FutureCompletion(future)
        val latch = CountDownLatch(1)
        val thread = Thread {
            latch.countDown()
            Thread.sleep(20)
            completion.resume(7)
        }
        thread.start()
        latch.await(2, TimeUnit.SECONDS)
        assertEquals(7, future.get(2, TimeUnit.SECONDS), "跨线程 resume 应完成 future")
    }
}
