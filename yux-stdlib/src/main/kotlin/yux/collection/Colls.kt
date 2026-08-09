package yux.collection

import yux.core.function.Function1
import yux.core.function.Function2

/**
 * 集合操作库（01-§10.1：`list.map {}` 等成员调用降级为对本类的静态调用）。
 *
 * IRGen 将 Yux 的集合成员调用（map/filter/forEach/sort/sum/max/first/reduce）解析为
 * `yux.collection.Colls.<name>` 静态调用（实参类型 ANY → JVM Object），
 * 本类方法签名（方法名/参数顺序/参数类型）必须与之一致。
 *
 * 所有函数均为函数式风格：只读入参、不改动原列表，返回新 ArrayList。
 */
object Colls {

    /** 逐元素映射：对每个元素执行 f，收集结果为新列表。 */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun map(list: java.util.List<*>, f: Function1<Any?, Any?>): java.util.List<Any?> =
        list.map { f.invoke(it) } as java.util.List<Any?>

    /** 过滤：保留 f 返回 true 的元素，收集为新列表。 */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun filter(list: java.util.List<*>, f: Function1<Any?, Boolean>): java.util.List<Any?> =
        list.filter { f.invoke(it) == true } as java.util.List<Any?>

    /** 逐元素遍历：对每个元素调用 f，无返回值。 */
    @JvmStatic
    fun forEach(list: java.util.List<*>, f: Function1<Any?, Unit>) {
        for (item in list) {
            f.invoke(item)
        }
    }

    /** 排序：按比较器 f(a,b)（a 应排在 b 前时返回 true）对元素排序，返回新列表。 */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun sort(list: java.util.List<*>, f: Function2<Any?, Any?, Boolean>): java.util.List<Any?> {
        val comparator = Comparator<Any?> { a, b ->
            when {
                f.invoke(a, b) -> -1
                f.invoke(b, a) -> 1
                else -> 0
            }
        }
        return list.sortedWith(comparator) as java.util.List<Any?>
    }

    /**
     * 数值求和：Number 子类转 double 累加，非 Number 元素计 0.0；空列表返回 0.0。
     *
     * 设计偏差：01-§10.1 的求和按元素原类型返回，v0.1 擦除后统一返回 Double。
     */
    @JvmStatic
    fun sum(list: java.util.List<*>): Double {
        var total = 0.0
        for (item in list) {
            total += (item as? Number)?.toDouble() ?: 0.0
        }
        return total
    }

    /** 最大值：元素须为 Comparable（经 compareTo 比较）；空列表返回 null。 */
    @JvmStatic
    fun max(list: java.util.List<*>): Any? {
        if (list.isEmpty()) {
            return null
        }
        var max: Any? = list[0]
        for (i in 1 until list.size) {
            val candidate = list[i]
            @Suppress("UNCHECKED_CAST")
            if ((candidate as Comparable<Any?>).compareTo(max) > 0) {
                max = candidate
            }
        }
        return max
    }

    /** 首元素；空列表返回 null。 */
    @JvmStatic
    fun first(list: java.util.List<*>): Any? = list.firstOrNull()

    /**
     * 左折叠：以首个元素为初始累加值，对剩余元素依次执行 f(acc, item)；
     * 空列表返回 null。
     */
    @JvmStatic
    fun reduce(list: java.util.List<*>, f: Function2<Any?, Any?, Any?>): Any? {
        if (list.isEmpty()) {
            return null
        }
        var acc: Any? = list[0]
        for (i in 1 until list.size) {
            acc = f.invoke(acc, list[i])
        }
        return acc
    }
}
