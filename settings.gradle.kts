pluginManagement {
    repositories {
        // 国内镜像优先，原站兜底（Maven Central 直连在国内常不稳定）。
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/Sakura-Fubuki76/yume-lib")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .getOrElse("")
                password = providers.gradleProperty("gpr.token")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .getOrElse("")
            }
        }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Yume"
include(":app")
include(":core:common")
include(":core:data")
include(":core:database")
include(":core:datastore")
include(":core:domain")
include(":core:media")
include(":core:model")
include(":core:ui")
include(":core:cache")
include(":feature:player")
include(":feature:settings")
include(":feature:videopicker")
include(":feature:imagebrowser")
