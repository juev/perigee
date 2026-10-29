plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The same list as in :engine.
val abis = (findProperty("perigeeAbis") as String?)?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")

// The Play build is signed with the upload key when one is given, as the release
// workflow and scripts/build-release-macos.sh do. Without it the bundle is unsigned.
val uploadKeystore: String? = providers.environmentVariable("PERIGEE_UPLOAD_KEYSTORE").orNull
val uploadPassword: String? = providers.environmentVariable("PERIGEE_UPLOAD_PASSWORD").orNull
if (uploadKeystore != null && uploadPassword.isNullOrEmpty()) {
    throw GradleException("PERIGEE_UPLOAD_KEYSTORE is set, PERIGEE_UPLOAD_PASSWORD is not")
}

// The F-Droid build is signed with the release key when one is given, as the release
// workflow does for the APKs of a GitHub release. Without it the APK is unsigned:
// F-Droid signs its own build.
val releaseKeystore: String? = providers.environmentVariable("PERIGEE_RELEASE_KEYSTORE").orNull
val releasePassword: String? = providers.environmentVariable("PERIGEE_RELEASE_PASSWORD").orNull
if (releaseKeystore != null && releasePassword.isNullOrEmpty()) {
    throw GradleException("PERIGEE_RELEASE_KEYSTORE is set, PERIGEE_RELEASE_PASSWORD is not")
}

android {
    namespace = "org.evsyukov.perigee"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.evsyukov.perigee"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.0-rc3"
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
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = uploadPassword
                keyAlias = "perigee"
                keyPassword = uploadPassword
            }
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releasePassword
                keyAlias = "perigee"
                keyPassword = releasePassword
            }
        }
    }
    // Room, WorkManager, Compose and the engine bring their own rules for R8.
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    // One build per store. The code is the same; the stores differ in what
    // they accept, and a difference, when one comes, has a place to live.
    flavorDimensions += "store"
    productFlavors {
        // F-Droid builds the app from source and signs it with its own key.
        // The APKs of a GitHub release are this build signed with the release key.
        create("fdroid") {
            dimension = "store"
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        // Google Play takes a bundle signed with the upload key and signs the app itself.
        // The key of a build type comes first: the debug build keeps the debug key.
        create("play") {
            dimension = "store"
            if (uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
        }
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
    // Books and folders made up for the tests serve both the JVM's and the device's.
    sourceSets {
        getByName("test").kotlin.srcDir("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/kotlin")
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
    implementation("androidx.browser:browser:1.10.0")
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
