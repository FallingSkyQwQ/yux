plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "Yux 编译器核心：Lexer / Parser / AST / Sema / IR / diag"

dependencies {
    testImplementation(project(":yux-compiler:yux-test-support"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
