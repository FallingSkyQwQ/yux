plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
}

description = "Yux 编译器核心：Lexer / Parser / AST / Sema / IR / diag"

// T-M11-6：核心模块行覆盖门禁 ≥ 70%（06-§7.2「M11 起」；全局默认 0.60 之外的核心加严规则）
tasks.withType<JacocoCoverageVerification>().configureEach {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

dependencies {
    testImplementation(project(":yux-compiler:yux-test-support"))
    testImplementation(project(":yux-stdlib"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
