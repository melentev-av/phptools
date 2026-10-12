import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.intellijPlatform) apply false
    alias(libs.plugins.intellijPlatformModule) apply false
}

val javaVersion = providers.gradleProperty("javaVersion").map { it.toInt() }

subprojects {
    group = providers.gradleProperty("pluginGroup").get()
    // У core своей версии нет: он вшивается в каждый плагин.
    providers.gradleProperty("pluginVersion.$name").orNull?.let { version = it }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(javaVersion.get())
        }
    }
}
