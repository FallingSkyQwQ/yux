plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "YuxPlugin SDK 运行时层（bootstrap/event/command/config/NBT）"

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
