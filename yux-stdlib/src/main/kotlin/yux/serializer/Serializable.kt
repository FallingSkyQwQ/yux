package yux.serializer

/**
 * 序列化标记注解（01-§8.2/§8.3：`@Serializable` 的 JVM 载体；完整序列化器 M11）。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Serializable
