plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "Yux 测试基座：golden 快照 + 内存源文件工具（T-M0-5）"

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
