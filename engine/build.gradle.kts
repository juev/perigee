plugins {
    id("com.android.library")
}

// The ABIs the engine is built for. A build that needs one of them only, as a
// run on an emulator does, names it: -PperigeeAbis=x86_64
val abis = (findProperty("perigeeAbis") as String?)?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "org.evsyukov.perigee.engine"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// The engine reads stylesheets and hyphenation patterns from files; they ship
// as assets taken straight from the pinned crengine tree.
abstract class CrengineAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dataDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(dataDir) {
                include("*.css", "hyph/*.pattern", "hyph/languages.json")
                into("crengine")
            }
            into(outputDir)
        }
    }
}

val crengineAssets = tasks.register<CrengineAssetsTask>("crengineAssets") {
    dataDir.set(rootProject.layout.projectDirectory.dir("third_party/crengine/cr3gui/data"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(crengineAssets, CrengineAssetsTask::outputDir)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
