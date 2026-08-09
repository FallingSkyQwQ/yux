package yux.compiler.sema

/**
 * JVM 扩展函数注册表（T-M11-3）：Yux 成员调用 `list.map {}` / `s.uppercase()` 降级为 stdlib 静态调用。
 *
 * Yux 的集合/字符串操作（S-10.1/S-10.2）以「成员调用」语法书写，但运行时由
 * yux-stdlib 的静态方法提供（`yux.collection.Colls` / `yux.core.Text`）。
 * 本表登记 (接收者 JVM 限定名, 成员名) → (宿主 object 类名, 静态方法名) 的映射，
 * 供语义层 [TypeChecker.checkJvmExtension] 与 IRGen [JvmCallResolver.resolveJvmExtension]
 * 将成员调用降级为静态调用（receiver 前置为实参 0）。
 *
 * 接收者按「接口/超类链」匹配（如 java.util.ArrayList → java.util.List 的 map），
 * 使具体实现类上调用接口级操作也能命中注册表。
 */
object JvmExtensions {
    // (接收者 JVM 限定名, 成员名) → (宿主 object 类名, 静态方法名)
    private val TABLE: Map<String, Map<String, Pair<String, String>>> = mapOf(
        "java.util.List" to mapOf(
            "map" to ("yux.collection.Colls" to "map"),
            "filter" to ("yux.collection.Colls" to "filter"),
            "forEach" to ("yux.collection.Colls" to "forEach"),
            "sort" to ("yux.collection.Colls" to "sort"),
            "sum" to ("yux.collection.Colls" to "sum"),
            "max" to ("yux.collection.Colls" to "max"),
            "first" to ("yux.collection.Colls" to "first"),
            "reduce" to ("yux.collection.Colls" to "reduce"),
        ),
        "java.lang.String" to mapOf(
            "uppercase" to ("yux.core.Text" to "uppercase"),
            "lowercase" to ("yux.core.Text" to "lowercase"),
            "split" to ("yux.core.Text" to "split"),
            "format" to ("yux.core.Text" to "format"),
        ),
    )

    /**
     * 查找接收者类型上的扩展条目。
     *
     * 先精确查 [TABLE]；未命中则用反射沿 superclass + interfaces 做 BFS（去重，
     * Object 终止），对每个祖先类名查 [TABLE]。同时接受接口本身的精确匹配
     * （如接收者即 `java.util.List`）。
     *
     * 返回 (宿主 object 类名, 静态方法名)；未知成员 / 类不可加载（ClassNotFound 等）
     * 一律安全返回 null。
     */
    fun find(receiverQualifiedName: String, memberName: String): Pair<String, String>? {
        TABLE[receiverQualifiedName]?.get(memberName)?.let { return it }
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(receiverQualifiedName)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            // 反射遍历：类不可加载（不存在/无类路径）时安全跳过该节点
            val cls = try {
                Class.forName(current, false, JvmClassSymbol::class.java.classLoader)
            } catch (_: ClassNotFoundException) {
                null
            } catch (_: NoClassDefFoundError) {
                null
            } catch (_: LinkageError) {
                null
            }
            if (cls == null) continue
            TABLE[current]?.get(memberName)?.let { return it }
            // Object 终止：其 superclass 为 null、无接口，不再入队
            cls.superclass?.let { if (it != Any::class.java) queue.addLast(it.name) }
            cls.interfaces.forEach { queue.addLast(it.name) }
        }
        return null
    }
}
