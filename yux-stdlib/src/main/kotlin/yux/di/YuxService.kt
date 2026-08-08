package yux.di

/**
 * service 标记注解（01-§5.4 / S-5.4.1：`service` 编译为普通类 + `@YuxService`，供 yux.di 容器扫描）。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class YuxService
