plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release builds are signed only when ANDROID_KEYSTORE_FILE is set, like the panel's;
// without it assembleRelease produces an unsigned APK.
val releaseKeystore: String? = System.getenv("ANDROID_KEYSTORE_FILE")

android {
    namespace = "com.craftersy.treadmill.workout"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.craftersy.treadmill.workout"
        minSdk = 31
        targetSdk = 35
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The language is switched inside the app, so app bundles must keep every language.
    bundle {
        language {
            enableSplit = false
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":lib"))
    implementation(libs.snakeyaml.engine)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(kotlin("test-junit")) // built-in Kotlin does not pick the JUnit variant of kotlin("test")
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Format tests read the example from the specification.
    systemProperty("workout.spec", rootDir.resolve("../../docs/workout.md").canonicalPath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
