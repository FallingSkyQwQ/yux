plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "hello-extension 示例编译器插件（T-M6-6）：greet 扩展关键字贯穿解析→下沉→字节码"

dependencies {
    implementation(project(":yux-compiler:yux-compiler-plugin-api"))
    implementation(project(":yux-compiler:yux-compiler-core"))

    testImplementation(project(":yux-compiler:yux-test-support"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
