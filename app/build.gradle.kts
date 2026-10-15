plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The same list as in :engine.
val abis = (findProperty("perigeeAbis") as String?)?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "org.evsyukov.perigee"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.evsyukov.perigee"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The engine is built for these three; a library that also comes for
        // another one must not make the app look installable there.
        ndk { abiFilters += abis }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // One build per store. The code is the same; the stores differ in what
    // they accept, and a difference, when one comes, has a place to live.
    flavorDimensions += "store"
    productFlavors {
        create("fdroid") { dimension = "store" }
        create("play") { dimension = "store" }
    }
    // The list of dependencies the build tools put into the package is sealed
    // with a key of Google's: F-Droid cannot read it and turns the build down.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    // The view models are checked on the JVM under Robolectric, which reads the app's strings.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// The license texts the app shows are the files of the source tree.
abstract class LegalAssetsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notice: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val license: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenses: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(notice) { into("legal") }
            from(license) { into("legal") }
            from(licenses) { into("legal/licenses") }
            into(outputDir)
        }
    }
}

val legalAssets = tasks.register<LegalAssetsTask>("legalAssets") {
    notice.set(rootProject.layout.projectDirectory.file("NOTICE"))
    license.set(rootProject.layout.projectDirectory.file("LICENSE"))
    licenses.set(rootProject.layout.projectDirectory.dir("licenses"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(legalAssets, LegalAssetsTask::outputDir)
    }
    // The flavors hold the same code: the tests on a device run on one of them.
    beforeVariants(selector().withFlavor("store" to "play")) { it.androidTest.enable = false }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":data"))
    implementation(project(":opds"))
    implementation(project(":sync"))
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":sync-fake"))
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(project(":sync-fake"))
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// LicensesTest reads these from the source tree: a change in them is a reason to run it again.
tasks.withType<Test>().configureEach {
    // Robolectric sets up Android's shared memory through the JDK's internal access to file descriptors.
    jvmArgs("--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED")
    inputs.file(rootProject.layout.projectDirectory.file("NOTICE")).withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(rootProject.layout.projectDirectory.dir("licenses")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("engine/src/main/cpp/cmake/deps.cmake"))
        .withPathSensitivity(PathSensitivity.NONE)
}
