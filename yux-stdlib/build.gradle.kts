plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "Yux 标准库：yux.core / collection / io / net / async / json / di / test"

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
