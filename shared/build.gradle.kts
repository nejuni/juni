import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Android and desktop are both JVM, but the default hierarchy gives them no source set in
    // common.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
// Local metadata parsers and analysis use serialization; keep
                // the version aligned with commonMain instead of overriding it
                // with a Kotlin 2.3-era runtime.
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            // Models in this module intentionally have no Android or Compose dependency.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
        }
    }
}

android {
    namespace = "com.nejuni.juni.shared"
    compileSdk = 36
}
