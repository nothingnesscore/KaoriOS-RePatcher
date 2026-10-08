plugins {
    kotlin("multiplatform")
}

// Patching engine targets the JVM today. Its sources contain no Android or
// java.io dependencies, so adding androidTarget() (and other targets) later is
// a one-line change in this file.
kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                // AOSP smali toolchain. The round-trip only disassembles the handful of
                // classes the engine targets and rebuilds only the dexes it changed, so
                // multi-dex framework jars stay workable on a phone.
                implementation("com.android.tools.smali:smali-baksmali:3.0.10")
                implementation("com.android.tools.smali:smali:3.0.10")
                implementation("com.android.tools.smali:smali-dexlib2:3.0.10")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

/** Classpath of the engine's JVM main output plus its runtime dependencies. */
val engineJvmClasspath: FileCollection by lazy {
    val mainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles
}

/**
 * Runs the desktop round-trip against a directory of pulled jars.
 *
 * `.\gradlew.bat :engine:runCli -PcliArgs="<pulled-dir> <work-dir> [mode] [mount]"`
 */
tasks.register<JavaExec>("runCli") {
    group = "verification"
    description = "Disassemble, patch and package pulled Android artifacts on the desktop."
    mainClass.set("dev.kaorios.engine.cli.PatchCli")
    classpath = engineJvmClasspath
    args((project.findProperty("cliArgs") as String?)?.split(" ") ?: emptyList<String>())
}

/**
 * Dumps the raw string literals of one class.
 *
 * Used to identify literals baksmali fails to render on particular ROMs.
 *
 * `.\gradlew.bat :engine:runStringProbe -PprobeArgs="<archive> <dex> <descriptor> [method]"`
 */
tasks.register<JavaExec>("runStringProbe") {
    group = "verification"
    description = "Print escaped const-string literals for a class in an Android artifact."
    mainClass.set("dev.kaorios.engine.cli.StringProbe")
    classpath = engineJvmClasspath
    args((project.findProperty("probeArgs") as String?)?.split(" ") ?: emptyList<String>())
}