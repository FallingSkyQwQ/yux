package yux.serializer

import yux.json.Json

/**
 * 序列化运行时入口（01-§8.3：`serialize(x):String` / `deserialize<T>(text, type):T` 的 JVM 载体）。
 *
 * Yux 编译器的 `serialize`/`deserialize` 内建调用降级为对本对象的静态调用
 * （与 yux.collection.Colls / yux.core.Text 的模式一致：Kotlin object + [@JvmStatic]）。
 *
 * 实现复用 yux.json.Json 的反射机制（JSON 格式；§8.3.1 承诺的 YAML/NBT 为独立关切，
 * 分别由 yux.minecraft.io HomeStore / yux.minecraft NBT 扩展另行提供）。
 */
object YuxSerializer {

    /** 序列化为 JSON 文本（顶层 `serialize(x)`，data 类/枚举/List/Map/标量均支持）。 */
    @JvmStatic
    fun serialize(value: Any?): String = Json.serialize(value)

    /**
     * 反序列化 JSON 文本为 [type] 标记的实例（顶层 `deserialize(text, Type)`）。
     *
     * [type] 为 data 类的 Class（Yux 侧 `deserialize(json, PlayerData)` 传递类字面量）；
     * 标量/枚举的 Class 亦可（枚举按常量名还原，未知常量报错）。
     * data 类字段的泛型签名（`List<PlayerData>` / `Map<String, PlayerData>` 等）会用于
     * 元素类型转换，保证 `deserialize(serialize(x), x::class.java) == x`。
     *
     * @throws IllegalArgumentException JSON 非法 / 类型不可构造 / 字段缺失或不可转换 / 循环引用。
     */
    @JvmStatic
    fun <T> deserialize(text: String, type: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return Json.deserialize(text, type) as T
    }
}
