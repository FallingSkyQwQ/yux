import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "dev.yux"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    // 统一测试基座：JUnit 5
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // Kotlin/JVM 通用配置（ADR-02）
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
    }

    // 编码规范：detekt（T-M0-3）
    pluginManager.withPlugin("dev.detekt") {
        tasks.named("check") {
            dependsOn("detekt")
        }
    }

    // 覆盖率（T-M0-4：门禁 ≥ 60%）
    pluginManager.withPlugin("jacoco") {
        tasks.withType<JacocoReport>().configureEach {
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
        tasks.withType<JacocoCoverageVerification>().configureEach {
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        value = "COVEREDRATIO"
                        minimum = "0.60".toBigDecimal()
                    }
                }
            }
        }
        tasks.named("check") {
            dependsOn("jacocoTestReport", "jacocoTestCoverageVerification")
        }
    }
}

// `./gradlew lint` —— 聚合全部子模块的 detekt
tasks.register("lint") {
    group = "verification"
    description = "Run detekt across all modules."
    dependsOn(
        subprojects.mapNotNull { project ->
            project.tasks.findByName("detekt")
        },
    )
}
