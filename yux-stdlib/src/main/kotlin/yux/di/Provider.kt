package yux.di

/**
 * 惰性服务提供者（01-§10.7：`type Provider<T> // get():T`）。
 *
 * 服务构造函数参数声明为 `Provider<X>` 时，容器注入一个惰性 [Provider]：
 * [get] 首次调用才解析并缓存 X 的单例，避免构建服务时立即构造重型依赖
 *（也允许在 X 尚未注册时就先注入 Provider）。
 */
fun interface Provider<out T> {
    fun get(): T
}
