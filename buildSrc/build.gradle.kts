import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.compile.JavaCompile

object KtsBuildVersions {
    const val gradle = "8.2.0"
    const val kotlin = "1.9.10"
}

plugins {
    `kotlin-dsl`
    `kotlin-dsl-precompiled-script-plugins`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.android.tools.build:gradle:${KtsBuildVersions.gradle}")
    implementation(kotlin("gradle-plugin", version = KtsBuildVersions.kotlin))
    implementation(kotlin("allopen", version = KtsBuildVersions.kotlin))
}

// Никаких toolchain здесь не задаём — только целевой байткод для Kotlin
tasks.withType<KotlinCompile>().configureEach {
    @Suppress("DEPRECATION")
    kotlinOptions.jvmTarget = "17"
}

// Выравниваем Java с Kotlin: таргет 17
tasks.withType<JavaCompile>().configureEach {
    // --release гарантирует корректную компиляцию под 17 даже на JDK 21
    options.release.set(17)
    sourceCompatibility = "17"
    targetCompatibility = "17"
}