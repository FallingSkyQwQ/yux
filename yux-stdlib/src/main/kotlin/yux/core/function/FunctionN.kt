package yux.core.function

/**
 * 函数类型运行时接口（01-§4.4 / 02-§9.1：`(T)->U` 映射 FunctionN）。
 *
 * 后端（T-M5-4）用 LambdaMetafactory 生成实现：SAM 方法名统一为 `invoke`，
 * 擦除签名 `(Object...)Object`（参数/返回值在调用点装箱/拆箱）。
 */

/** 0 参函数。 */
fun interface Function0<R> {
    fun invoke(): R
}

/** 1 参函数。 */
fun interface Function1<P1, R> {
    fun invoke(p1: P1): R
}

/** 2 参函数。 */
fun interface Function2<P1, P2, R> {
    fun invoke(p1: P1, p2: P2): R
}

/** 3 参函数。 */
fun interface Function3<P1, P2, P3, R> {
    fun invoke(p1: P1, p2: P2, p3: P3): R
}

/** 4 参函数。 */
fun interface Function4<P1, P2, P3, P4, R> {
    fun invoke(p1: P1, p2: P2, p3: P3, p4: P4): R
}
