import java.util.Properties

plugins {
    kotlin("multiplatform")
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
    }

    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation(project(":engine"))
                implementation("androidx.core:core-ktx:1.18.0")
                // 1.13.0 is the first release whose ComponentActivity publishes a
                // NavigationEventDispatcherOwner through the view tree; the shell's
                // PredictiveBackHandler rides the back stream that owner receives. 1.8.2 had
                // nothing to resolve against and no gesture progress to offer.
                implementation("androidx.activity:activity-compose:1.13.0")
                // Miuix 0.9.0 routes `WindowDialog`'s back handling through
                // `androidx.navigationevent`, but declares it as `implementation`, so the
                // types are runtime-only until named here. MainActivity publishes the
                // `NavigationEventDispatcherOwner` those handlers resolve against.
                implementation("androidx.navigationevent:navigationevent-compose:1.0.2")
                implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
                implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

                implementation("org.jetbrains.compose.runtime:runtime:1.10.3")
                implementation("org.jetbrains.compose.foundation:foundation:1.10.3")
                implementation("org.jetbrains.compose.ui:ui:1.10.3")

                implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.0")
                implementation("top.yukonga.miuix.kmp:miuix-core-android:0.9.0")
                implementation("top.yukonga.miuix.kmp:miuix-shapes-android:0.9.0")
                implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.0")
                implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.0")
                // `OverlayDropdownPreference`: the settings dropdown whose list popup is
                // anchored to the row it belongs to. Separate artifact from `miuix-ui`.
                implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.0")

                // Optional iOS 27 liquid-glass surface (lens/highlight/shadow backdrop effects).
                // Kyant0/AndroidLiquidGlass — 2.0.0-alpha03 is the newest release still built
                // against Compose 1.10.x, which is what this project pins; 2.0.0-rc01 and later
                // are compiled against Compose 1.11+/Kotlin 2.4 and will not link here.
                implementation("io.github.kyant0:backdrop-android:2.0.0-alpha03")

                // AOSP smali toolchain, run on-device so a jar never has to leave the phone.
                implementation("com.android.tools.smali:smali-baksmali:3.0.10")
                implementation("com.android.tools.smali:smali:3.0.10")
                implementation("com.android.tools.smali:smali-dexlib2:3.0.10")
                // Declared alongside them so the signer `:engine` calls is on the app's own
                // runtime classpath too; nothing in `:app` references apksig directly.
                implementation("com.android.tools.build:apksig:8.13.0")
            }
        }
    }
}

android {
    namespace = "dev.kaorios.patcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.kaorios.patcher"
        // Android 13+ (API 33): required by miuix-blur runtime shaders. Matches the
        // HyperOS 4 / Android 17 deployment target.
        minSdk = 33
        targetSdk = 37
        versionCode = providers.gradleProperty("appVersionCode").get().toInt()
        versionName = providers.gradleProperty("appVersionName").get()
    }

    signingConfigs {
        // Signed only when signing info is provided: Gradle properties
        // (-Pks.storeFile=… -Pks.storePassword=… -Pks.keyAlias=… -Pks.keyPassword=…,
        // what CI passes) or a local keystore.properties (gitignored, keys without the
        // `ks.` prefix, see README "Release signing"). Without either, unsigned.
        fun ksProp(name: String): String? {
            providers.gradleProperty(name).orNull?.let { return it }
            val propsFile = rootProject.file("keystore.properties")
            if (!propsFile.exists()) return null
            return Properties().apply { propsFile.inputStream().use { load(it) } }
                .getProperty(name.removePrefix("ks."))
        }

        ksProp("ks.storeFile")?.let { storePath ->
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = ksProp("ks.storePassword")
                keyAlias = ksProp("ks.keyAlias")
                keyPassword = ksProp("ks.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        // Same as release but skips R8/resource shrinking for fast local verification.
        create("releaseFast") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}