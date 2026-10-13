// A stand-in bookOrbit server for the tests of the modules that talk to one.
plugins {
    id("com.android.library")
}

android {
    namespace = "org.evsyukov.perigee.sync.fake"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
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
