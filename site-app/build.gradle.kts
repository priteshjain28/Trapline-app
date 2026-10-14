// Plugins go on one shared classpath here (the Kotlin and Android plugins must see each other).
// The Android ones load only when an Android SDK is present; :core builds without it.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    rootProject.file("local.properties").exists()

buildscript {
    val hasSdk = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").exists()
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.1.0")
        classpath("app.cash.sqldelight:gradle-plugin:2.0.2")
        if (hasSdk) {
            classpath("com.android.tools.build:gradle:8.7.3")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.0")
            classpath("io.github.takahirom.roborazzi:roborazzi-gradle-plugin:1.26.0")
        }
    }
}

tasks.register("ciCheck") {
    description = "Everything CI runs."
    dependsOn(":core:test")
    if (hasAndroidSdk) dependsOn(":app:testDebugUnitTest", ":app:assembleDebug")
}
