plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = "com.craftersy.treadmill"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Protocol tests decode every frame of the captured session log.
    systemProperty("treadmill.measurementLog", rootDir.resolve("../../docs/measurements/2026-10-31.log").canonicalPath)
    inputs.file("../../../docs/measurements/2026-10-31.log")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
