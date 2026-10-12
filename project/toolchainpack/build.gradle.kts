plugins {
    id("com.android.dynamic-feature")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nizam.app.toolchainpack"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    // must match the base app's debug build type exactly, or the dynamic-feature plugin refuses to build
    buildTypes { debug { isDebuggable = false; isJniDebuggable = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":app"))
    // a transitive dep of :app resolves as compileOnly in this module's graph, which the dynamic-feature
    // plugin rejects; pinning it to implementation here settles that
    implementation("com.google.j2objc:j2objc-annotations:2.8")
    // implementation(project(":app")) only exposes :app's own API, not its transitive deps — Compose and
    // coroutines need to be declared directly here too since Terminal.kt uses them
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
