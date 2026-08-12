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
    // (接收者 JVM 限定名, 成员名) → [(宿主 object 类名, 静态方法名), ...]
    // 同一成员名可登记多个重载（M13：List `+` 的 concat/append），按声明顺序选择（具体优先）
    private val TABLE: Map<String, Map<String, List<Pair<String, String>>>> = mapOf(
        "java.util.List" to mapOf(
            "map" to listOf("yux.collection.Colls" to "map"),
            "filter" to listOf("yux.collection.Colls" to "filter"),
            "forEach" to listOf("yux.collection.Colls" to "forEach"),
            "sort" to listOf("yux.collection.Colls" to "sort"),
            "sum" to listOf("yux.collection.Colls" to "sum"),
            "max" to listOf("yux.collection.Colls" to "max"),
            "first" to listOf("yux.collection.Colls" to "first"),
            "reduce" to listOf("yux.collection.Colls" to "reduce"),
            // M13 运算符：`a + b`（List 拼接）/ `a + elem`（追加）
            "plus" to listOf(
                "yux.collection.Colls" to "concat",
                "yux.collection.Colls" to "append",
            ),
        ),
        "java.lang.String" to mapOf(
            "uppercase" to listOf("yux.core.Text" to "uppercase"),
            "lowercase" to listOf("yux.core.Text" to "lowercase"),
            "split" to listOf("yux.core.Text" to "split"),
            "format" to listOf("yux.core.Text" to "format"),
        ),
    )

    /**
     * 查找接收者类型上的扩展条目（取第一个注册重载）。
     *
     * 先精确查 [TABLE]；未命中则用反射沿 superclass + interfaces 做 BFS（去重，
     * Object 终止），对每个祖先类名查 [TABLE]。同时接受接口本身的精确匹配
     * （如接收者即 `java.util.List`）。
     *
     * 返回 (宿主 object 类名, 静态方法名)；未知成员 / 类不可加载（ClassNotFound 等）
     * 一律安全返回 null。
     */
    fun find(receiverQualifiedName: String, memberName: String): Pair<String, String>? {
        TABLE[receiverQualifiedName]?.get(memberName)?.let { return it.firstOrNull() }
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
            TABLE[current]?.get(memberName)?.let { return it.firstOrNull() }
            // Object 终止：其 superclass 为 null、无接口，不再入队
            cls.superclass?.let { if (it != Any::class.java) queue.addLast(it.name) }
            cls.interfaces.forEach { queue.addLast(it.name) }
        }
        return null
    }

    /**
     * 同一成员名的全部注册条目（M13 运算符重载选择，如 List `+` 的 concat/append）。
     * 遍历顺序 = TABLE 声明顺序（更具体的重载优先，如 concat 先于 append）。
     */
    fun findAll(receiverQualifiedName: String, memberName: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(receiverQualifiedName)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
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
            TABLE[current]?.get(memberName)?.let { entries -> entries.forEach { if (it !in result) result += it } }
            cls.superclass?.let { if (it != Any::class.java) queue.addLast(it.name) }
            cls.interfaces.forEach { queue.addLast(it.name) }
        }
        return result
    }

    /**
     * `sum()` 按元素类型收窄（M13）：`List Int` → `Colls.sumInt`（返回 Int）等。
     * 元素类型名称映射到类型化求和变体；未知/非数值元素回退通用 `Colls.sum`（Double）。
     * 供 sema（[yux.compiler.sema.TypeChecker.checkJvmExtension]）与 IRGen（JvmCallResolver）共用，
     * 保证声明返回类型与降级目标静态方法一致。
     */
    fun sumVariant(elementTypeName: String?): Pair<String, String> = when (elementTypeName) {
        "Int" -> "yux.collection.Colls" to "sumInt"
        "Long" -> "yux.collection.Colls" to "sumLong"
        "Float" -> "yux.collection.Colls" to "sumFloat"
        // Double 与未知/非数值元素共用通用 `sum`（已返回 Double）
        else -> "yux.collection.Colls" to "sum"
    }
}
