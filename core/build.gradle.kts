plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.intellijPlatformModule)
}

repositories {
    mavenCentral()
    intellijPlatform {
        localPlatformArtifacts()
        // defaultRepositories() — включать, только если доступны серверы JetBrains
    }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("openidePath"))
    }

    // Чистая логика тестируется на JUnit 5 без платформенного тест-фреймворка:
    // тот тянет артефакты с серверов JetBrains.
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

intellijPlatform {
    instrumentCode = false
}

tasks.test {
    useJUnitPlatform()
}
