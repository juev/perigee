// Contract tests: the engine on a device against a real bookOrbit server
// (contract/docker-compose.yml). They run only when the server URL is given:
//   ./gradlew :contract:connectedDebugAndroidTest \
//       -Pandroid.testInstrumentationRunnerArguments.bookorbitUrl=http://10.0.2.2:3070
plugins {
    id("com.android.library")
}

android {
    namespace = "org.evsyukov.perigee.contract"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

dependencies {
    androidTestImplementation(project(":engine"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
