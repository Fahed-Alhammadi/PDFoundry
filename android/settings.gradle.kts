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
        // Tesseract4Android (OCR, Apache 2.0) is published on JitPack only. Exclusive: that group comes
        // only from JitPack, and nothing else is ever taken from there. Its files are also pinned by
        // SHA-256 in gradle/verification-metadata.xml.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}
rootProject.name = "PDFoundry"
include(":app")
