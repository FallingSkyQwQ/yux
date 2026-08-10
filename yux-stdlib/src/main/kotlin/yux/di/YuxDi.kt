package yux.di

import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.net.JarURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.internal.DefaultConstructorMarker

/**
 * yux.di 依赖注入容器（01-§5.4 / §10.7，S-5.4.1）。
 *
 * `service X {}` 声明编译为普通类 + `@YuxService`（IRGen 自动附加，后端发射
 * RUNTIME 可见注解），本容器负责登记与自动注入：
 *
 * - [register]：手动注册已构建实例（按具体类或显式接口/父类键，后注册覆盖先注册）。
 * - [inject]：返回服务单例；首次调用经反射选择主构造函数，递归解析每个构造参数
 *   （已注册实例 / 其他服务 / [Provider] 惰性提供者），构建成功后缓存。
 * - [scanAndRegister]：按包前缀扫描 classpath 中的 `@YuxService` 类并登记。
 *
 * 线程安全：单例缓存为 [ConcurrentHashMap]；循环依赖通过线程本地构建栈检测
 * （同一线程递归注入同一服务即视为环）。首次并发注入同一服务时可能各自构建一次，
 * 后写入缓存者胜出（幂等构建，不破坏缓存可见性）。
 */
object YuxDi {

    /** 已构建/已手动注册的单例（key = 服务类）。 */
    private val instances = ConcurrentHashMap<Class<*>, Any>()

    /** [scanAndRegister] 登记的 `@YuxService` 类型。 */
    private val serviceTypes = ConcurrentHashMap.newKeySet<Class<*>>()

    /** 线程本地构建栈：检测同线程递归注入（循环依赖）。 */
    private val buildStack = ThreadLocal.withInitial { ArrayDeque<Class<*>>() }

    /**
     * 手动注册实例（按具体类为键）。
     *
     * @param instance 已构建实例（后注册覆盖先注册，可替换自动构建的单例）
     */
    @JvmStatic
    fun register(instance: Any) {
        register(instance, instance.javaClass)
    }

    /**
     * 手动注册实例并指定注入键（接口/父类，供按抽象类型注入）。
     *
     * @param instance 已构建实例
     * @param key 注入键，必须是 [instance] 的类型或其超类型
     */
    @JvmStatic
    fun register(instance: Any, key: Class<*>) {
        require(key.isInstance(instance)) {
            "register 失败：$key 不是 ${instance.javaClass.name} 的超类型"
        }
        instances[key] = instance
    }

    /**
     * 注入服务单例（01-§10.7 `fun inject<T>():T` 的 JVM 形态）。
     *
     * 首次调用按「公开构造函数中参数最多者」选定主构造函数（排除 Kotlin 默认参数
     * 生成的 `DefaultConstructorMarker` 合成构造函数），逐个解析参数后构建并缓存；
     * 之后直接返回缓存单例。支持解析的构造参数：
     *
     * (a) 已注册实例 / 已登记或 `@YuxService` 标注的服务类型 → 递归注入单例；
     * (b) `Provider<X>` → 惰性提供者，[Provider.get] 首次调用时才解析 X。
     *
     * @param serviceClass 服务类
     * @return 服务单例实例
     * @throws DiCycleException 检测到构造环（给出完整链：A -> B -> A）
     * @throws DiException 未注册、无可构建构造函数或构建失败
     */
    @JvmStatic
    fun inject(serviceClass: Class<*>): Any {
        instances[serviceClass]?.let { return it }
        requireService(serviceClass)
        val instance = build(serviceClass)
        instances[serviceClass] = instance
        return instance
    }

    /**
     * 扫描 classpath 中 [packagePrefix] 包前缀下的 `@YuxService` 类并登记
     *（镜像 minecraft `AnnotationScanner` 模式；jar 与 classes 目录均支持）。
     *
     * 仅登记类型，构建仍由 [inject] 按需惰性进行，因此登记顺序无关紧要。
     *
     * @param packagePrefix 包前缀（如 `"com.example.services"`）
     * @param classLoader 类加载器，缺省用当前线程 contextClassLoader
     * @return 登记的服务类数量
     * @throws DiException classpath 中不存在该包前缀
     */
    @JvmStatic
    fun scanAndRegister(packagePrefix: String, classLoader: ClassLoader? = null): Int {
        require(packagePrefix.isNotBlank()) { "scanAndRegister 失败：包前缀不能为空" }
        val loader = classLoader ?: Thread.currentThread().contextClassLoader
            ?: YuxDi::class.java.classLoader
        val packagePath = packagePrefix.replace('.', '/')
        val resources = loader.getResources(packagePath).toList()
        if (resources.isEmpty()) {
            throw DiException("scanAndRegister 失败：classpath 中找不到包 $packagePrefix")
        }
        val classNames = resources.flatMap { enumerateClassNames(it, packagePath, packagePrefix) }.distinct()
        var registered = 0
        for (name in classNames) {
            val clazz = try {
                Class.forName(name, false, loader)
            } catch (e: Throwable) {
                continue
            }
            if (clazz.isAnnotationPresent(YuxService::class.java)) {
                serviceTypes.add(clazz)
                registered++
            }
        }
        return registered
    }

    /** 清空容器（测试隔离 / 插件重载时重置全部单例与登记）。 */
    @JvmStatic
    fun clear() {
        instances.clear()
        serviceTypes.clear()
    }

    // ---- 内部实现 ----

    /** 注入门禁：仅接受 `@YuxService` 标注、scan 登记或已注册实例的类型。 */
    private fun requireService(clazz: Class<*>) {
        if (clazz.isAnnotationPresent(YuxService::class.java) || clazz in serviceTypes) return
        throw DiException(
            "服务未注册：${clazz.name}（类型既未标注 @YuxService，也未由 " +
                "scanAndRegister() 登记或 register() 注册实例）",
        )
    }

    /** 构建并缓存服务单例；入口处检测循环依赖。 */
    private fun build(clazz: Class<*>): Any {
        val stack = buildStack.get()
        if (clazz in stack) {
            throw DiCycleException(
                "检测到服务循环依赖（S-5.4.1）: " +
                    "${stack.joinToString(" -> ") { it.simpleName }} -> ${clazz.simpleName}",
            )
        }
        stack.addLast(clazz)
        try {
            val ctor = chooseConstructor(clazz)
            val args = ctor.parameters.map { resolveArgument(clazz, it) }.toTypedArray()
            return try {
                ctor.newInstance(*args)
            } catch (e: ReflectiveOperationException) {
                throw DiException(
                    "构建服务 ${clazz.name} 失败：构造函数抛出异常（${e.cause?.message ?: e.message}）",
                    e,
                )
            }
        } finally {
            stack.removeLast()
        }
    }

    /**
     * 解析单个构造参数：(a) 服务类型 → 递归注入；(b) `Provider<X>` → 惰性提供者，
     * X 延迟到 [Provider.get] 首次调用时注入。
     */
    private fun resolveArgument(owner: Class<*>, parameter: java.lang.reflect.Parameter): Any {
        val type = parameter.type
        if (type == Provider::class.java) {
            val target = (parameter.parameterizedType as? ParameterizedType)
                ?.actualTypeArguments
                ?.singleOrNull() as? Class<*>
                ?: throw DiException(
                    "构建服务 ${owner.name} 失败：Provider 参数缺少具体泛型类型（应为 Provider<X>）",
                )
            return Provider { inject(target) }
        }
        return inject(type)
    }

    /** 主构造函数选择：公开且排除默认参数合成构造函数，取参数最多者。 */
    private fun chooseConstructor(clazz: Class<*>): Constructor<*> {
        val name = clazz.name
        val candidates = clazz.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) && !isDefaultCtorMarker(it) }
        if (candidates.isEmpty()) {
            throw DiException(
                "无法构建服务 $name：没有公开构造函数（接口/抽象类或私有构造器，" +
                    "请改用 YuxDi.register() 手动注册实例）",
            )
        }
        val maxParams = candidates.maxOf { it.parameterCount }
        val longest = candidates.filter { it.parameterCount == maxParams }
        if (longest.size > 1) {
            throw DiException(
                "无法构建服务 $name：存在 ${longest.size} 个参数最多的构造函数，" +
                    "无法确定主构造函数（请改用 YuxDi.register() 手动注册实例）",
            )
        }
        return longest[0]
    }

    /** Kotlin 默认参数会追加 DefaultConstructorMarker 的合成构造函数，须排除。 */
    private fun isDefaultCtorMarker(ctor: Constructor<*>): Boolean =
        ctor.parameterCount > 0 && ctor.parameterTypes.last() == DefaultConstructorMarker::class.java

    /** 枚举包路径下的类名（目录或 jar 入口），返回完整限定名。 */
    private fun enumerateClassNames(url: URL, packagePath: String, packagePrefix: String): List<String> =
        when (url.protocol) {
            "file" -> {
                val dir = File(url.toURI())
                if (!dir.isDirectory) return emptyList()
                dir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .map { it.relativeTo(dir).path.removeSuffix(".class") }
                    .map { (packagePrefix + "." + it).replace(File.separatorChar, '.') }
                    .toList()
            }
            "jar" -> {
                (url.openConnection() as JarURLConnection).jarFile.use { jar ->
                    jar.entries().asSequence()
                        .filter { e ->
                            !e.isDirectory && e.name.startsWith("$packagePath/") &&
                                e.name.endsWith(".class") && "module-info" !in e.name
                        }
                        .map { it.name.removeSuffix(".class").replace('/', '.') }
                        .toList()
                }
            }
            else -> emptyList()
        }
}

/**
 * 泛型注入便捷入口（01-§10.7 `fun inject<T>():T`）。
 *
 * 顶层函数（而非 [YuxDi] 成员）：reified 内联函数会在 JVM 上生成带 Class 参数的
 * 静态方法，与 [YuxDi.inject] 的同签名成员互斥；此处与 `YuxDi.inject(Class)` 并存。
 *
 * @throws DiCycleException 检测到构造环
 * @throws DiException 未注册、无可构建构造函数或构建失败
 */
inline fun <reified T : Any> inject(): T = YuxDi.inject(T::class.java) as T

/** yux.di 容器异常基类。 */
open class DiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 服务构造循环依赖（S-5.4.1：容器侧运行时检测，编译期由 sema 先行拦截）。 */
class DiCycleException(message: String) : DiException(message)
