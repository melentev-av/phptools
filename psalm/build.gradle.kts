plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.intellijPlatform)
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
        // Классы core вшиваются в jar плагина: без <content> в plugin.xml папка lib/modules не попадает в classpath.
        pluginComposedModule(implementation(project(":core")))
    }
}

intellijPlatform {
    instrumentCode = false             // форм нет; иначе нужны артефакты с серверов JetBrains
    buildSearchableOptions = false
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        vendor {
            name = providers.gradleProperty("pluginVendor")
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("platformSinceBuild")
            untilBuild = providers.gradleProperty("platformUntilBuild")
        }
    }
}

// Песочница с PHP-плагином OpenIDE для ручной проверки: ./gradlew :<модуль>:runIdeWithPhp.
// Плагин кладётся только в песочницу и в зависимости сборки не попадает.
intellijPlatformTesting {
    runIde {
        register("runIdeWithPhp") {
            plugins {
                localPlugin(providers.gradleProperty("openphpPluginPath"))
            }
        }
    }
}
