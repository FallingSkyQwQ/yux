package yux.di

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ---- 测试夹具：顶层类模拟编译产物（service 声明 → 普通类 + @YuxService）----

@YuxService
class Db

@YuxService
class UserService(val db: Db)

@YuxService
class DeepLeaf

@YuxService
class DeepMid(val leaf: DeepLeaf)

@YuxService
class DeepRoot(val mid: DeepMid)

@YuxService
class Ping(val pong: Pong)

@YuxService
class Pong(val ping: Ping)

@YuxService
class SelfLoop(val self: SelfLoop)

@YuxService
class WithDefaultService(val db: Db = Db())

@YuxService
class Expensive {
    init {
        expensiveCreated++
    }
}

var expensiveCreated = 0

@YuxService
class LazyConsumer(val expensive: Provider<Expensive>)

class ManualOnly

@YuxService
class ProviderOfManual(val manual: Provider<ManualOnly>)

class NotAService

@YuxService
interface FakeService

interface Greeter {
    fun greet(): String
}

class ChineseGreeter : Greeter {
    override fun greet(): String = "你好"
}

@YuxService
class ConcurrentRoot(val db: Db)

/**
 * T-M11 yux.di 容器验收（01-§5.4 / §10.7）：手动注册、构造依赖图、循环检测、
 * 单例缓存、Provider 惰性、classpath 扫描与并发安全。
 */
class DiTest {

    @BeforeEach
    fun reset() {
        YuxDi.clear()
    }

    @Test
    fun `手动注册后 inject 返回同一实例`() {
        val manual = ManualOnly()
        YuxDi.register(manual)
        assertSame(manual, YuxDi.inject(ManualOnly::class.java))
    }

    @Test
    fun `可按接口键注册并注入`() {
        val impl = ChineseGreeter()
        YuxDi.register(impl, Greeter::class.java)
        val greeter = YuxDi.inject(Greeter::class.java)
        assertSame(impl, greeter)
        assertEquals("你好", (greeter as Greeter).greet())
    }

    @Test
    fun `手动注册可替换自动构建的服务`() {
        val fake = Db()
        YuxDi.register(fake)
        val us = YuxDi.inject(UserService::class.java) as UserService
        assertSame(fake, us.db, "构造参数应从已注册实例解析")
    }

    @Test
    fun `构造依赖图自动解析（A 依赖 B）`() {
        val us = YuxDi.inject(UserService::class.java) as UserService
        assertNotNull(us.db, "依赖的 Db 应被自动构建并注入")
    }

    @Test
    fun `多层依赖链自动构建`() {
        val root = YuxDi.inject(DeepRoot::class.java) as DeepRoot
        assertNotNull(root.mid.leaf)
    }

    @Test
    fun `单例：重复注入返回同一实例且依赖共享`() {
        val a1 = YuxDi.inject(UserService::class.java)
        val a2 = YuxDi.inject(UserService::class.java)
        assertSame(a1, a2, "重复注入应命中缓存单例")
        val us = a1 as UserService
        assertSame(us.db, YuxDi.inject(Db::class.java), "依赖单例也应共享")
    }

    @Test
    fun `循环依赖 A-B-A 报错并给出完整构造链`() {
        val e = assertFailsWith<DiCycleException> { YuxDi.inject(Ping::class.java) }
        assertTrue(e.message!!.contains("Ping -> Pong -> Ping"), "错误信息应包含构造链，实际: ${e.message}")
    }

    @Test
    fun `自循环依赖报错`() {
        val e = assertFailsWith<DiCycleException> { YuxDi.inject(SelfLoop::class.java) }
        assertTrue(e.message!!.contains("SelfLoop -> SelfLoop"))
    }

    @Test
    fun `排除 Kotlin 默认参数合成构造函数`() {
        val svc = YuxDi.inject(WithDefaultService::class.java) as WithDefaultService
        assertNotNull(svc.db, "应选择真实主构造函数而非 DefaultConstructorMarker 合成构造")
    }

    @Test
    fun `Provider 注入惰性：get 前不构造服务`() {
        expensiveCreated = 0
        val consumer = YuxDi.inject(LazyConsumer::class.java) as LazyConsumer
        assertEquals(0, expensiveCreated, "注入时不应构造 Expensive（惰性）")
        val first = consumer.expensive.get()
        assertEquals(1, expensiveCreated)
        assertSame(first, consumer.expensive.get(), "Provider 应复用容器单例")
        assertEquals(1, expensiveCreated)
    }

    @Test
    fun `Provider 可解析手动注册的实例`() {
        val manual = ManualOnly()
        YuxDi.register(manual)
        val consumer = YuxDi.inject(ProviderOfManual::class.java) as ProviderOfManual
        assertSame(manual, consumer.manual.get())
    }

    @Test
    fun `未注册且未标注的服务注入报错`() {
        val e = assertFailsWith<DiException> { YuxDi.inject(NotAService::class.java) }
        assertTrue(e.message!!.contains("NotAService"), "错误信息应指明未注册的类型，实际: ${e.message}")
    }

    @Test
    fun `接口注入报错（无公开构造函数）`() {
        val e = assertFailsWith<DiException> { YuxDi.inject(FakeService::class.java) }
        assertTrue(e.message!!.contains("没有公开构造函数"), "实际: ${e.message}")
    }

    @Test
    fun `scanAndRegister 扫描注解类并支持跨类注入`() {
        val count = YuxDi.scanAndRegister("yux.di.testscan")
        assertEquals(2, count, "应只登记带 @YuxService 的类")
        val user = YuxDi.inject(yux.di.testscan.ScanUser::class.java) as yux.di.testscan.ScanUser
        assertNotNull(user.db, "扫描登记的服务应能按依赖图构建")
        assertFailsWith<DiException> { YuxDi.inject(yux.di.testscan.NotAnnotated::class.java) }
    }

    @Test
    fun `scanAndRegister 对不存在的包报错`() {
        val e = assertFailsWith<DiException> { YuxDi.scanAndRegister("yux.di.nonexistent.pkg") }
        assertTrue(e.message!!.contains("找不到包"))
    }

    @Test
    fun `并发注入线程安全且收敛为单例`() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<Any>()
        val futures = (1..8).map {
            pool.submit {
                start.await()
                results.add(YuxDi.inject(ConcurrentRoot::class.java))
            }
        }
        start.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(8, results.size)
        results.forEach { assertTrue(it is ConcurrentRoot) }
        val subsequent = YuxDi.inject(ConcurrentRoot::class.java)
        assertTrue(results.any { it === subsequent }, "后续注入应命中缓存中的某个已构建实例")
    }
}
