import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "app.site"
    // 34 while Robolectric 4.13 (screenshot tests) tops out at 34; raise with it before Play submission (needs 35).
    compileSdk = 34

    defaultConfig {
        applicationId = "app.site.demo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Supabase project URL and anon (public) key. Empty = demo mode with built-in sample data.
        // Set them as environment variables or in ~/.gradle/gradle.properties; never commit them.
        fun setting(env: String, prop: String) =
            providers.environmentVariable(env).orElse(providers.gradleProperty(prop)).getOrElse("")
        buildConfigField("String", "SUPABASE_URL", "\"${setting("SUPABASE_URL", "site.supabaseUrl")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${setting("SUPABASE_ANON_KEY", "site.supabaseAnonKey")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStackTraces = false
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("app.cash.sqldelight:android-driver:2.0.2")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.26.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.26.0")
}
