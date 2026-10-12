plugins {
    id("com.android.dynamic-feature")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nizam.app.toolchainpack"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    // must match the base app's debug build type exactly, or the dynamic-feature plugin refuses to build
    buildTypes { debug { isDebuggable = false; isJniDebuggable = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":app"))
    // a transitive dep of :app resolves as compileOnly in this module's graph, which the dynamic-feature
    // plugin rejects; pinning it to implementation here settles that
    implementation("com.google.j2objc:j2objc-annotations:2.8")
}
