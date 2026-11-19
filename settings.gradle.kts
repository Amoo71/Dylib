// Maven Central war für die GitHub-Server zeitweise nicht erreichbar → Googles Spiegel als Ausweichquelle
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(centralMirror)
    }
}
rootProject.name = "RoomLauncher"
include(":app")
