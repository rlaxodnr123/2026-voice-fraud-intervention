pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Vosk(온디바이스 한국어 STT) 배포 저장소
        maven { url = uri("https://alphacephei.com/maven/") }
    }
}
rootProject.name = "CallGuardTestApp"
include(":app")
