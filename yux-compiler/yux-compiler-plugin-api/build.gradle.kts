plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "Yux 编译器插件 SPI（yux-plugin-api）"

dependencies {
    api(project(":yux-compiler:yux-compiler-core"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
