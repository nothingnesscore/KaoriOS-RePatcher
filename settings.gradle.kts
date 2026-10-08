pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KaoriosPatcherApp"

include(":engine")

// :app needs an Android SDK (compileSdk 37) and Maven access for Compose Multiplatform.
// Enable it once an SDK is reachable: `sdk.dir` in local.properties (Android Studio writes
// this) or the ANDROID_HOME / ANDROID_SDK_ROOT environment variable (CI sets one of these).
val localProps = file("local.properties")
val hasLocalSdkDir = localProps.exists() && localProps.readLines().any { it.startsWith("sdk.dir=") }
val hasEnvSdk = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
    .any { !System.getenv(it).isNullOrBlank() }

if (hasLocalSdkDir || hasEnvSdk) {
    include(":app")
}