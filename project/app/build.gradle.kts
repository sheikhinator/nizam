plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nizam.app"
    compileSdk = 36

    lint {
        // these flag calls already wrapped in try/catch or opt-in markers; they never crash the app
        disable += setOf("MissingPermission", "UnsafeOptInUsageError", "StartActivityAndCollapseDeprecated", "RestrictedApi")
    }

    defaultConfig {
        applicationId = "com.nizam.app"
        minSdk = 26
        targetSdk = 34
        // The GitHub build number is the version, so the in-app updater can tell exactly which build you have
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = maxOf(11, run)
        versionName = if (run > 0) "build-$run" else "1.0-dev"
        buildConfigField("int", "BUILD_NUMBER", run.toString())
        buildConfigField("String", "BUILD_SHA", "\"" + (System.getenv("GITHUB_SHA") ?: "local").take(7) + "\"")
        buildConfigField("long", "BUILD_TIME", System.currentTimeMillis().toString() + "L")
        // Only the processor type the Infinix GT 50 Pro uses (64-bit ARM) — about half the download size
        ndk { abiFilters += listOf("arm64-v8a") }
        // Your own API keys, built in: taken from the build machine's environment (GitHub Actions secrets
        // named ATLAS_KEY_…), never written in the source. Empty when a secret isn't set.
        listOf("GEMINI", "OPENROUTER", "CUSTOM", "CUSTOM_URL", "CUSTOM_MODEL", "FAST", "FAST_URL", "FAST_MODEL", "HADITH",
            "TMDB", "OMDB", "JAMENDO", "HF", "TOMTOM").forEach { k ->
            buildConfigField("String", "KEY_$k", "\"" + (System.getenv("ATLAS_KEY_$k") ?: "").replace("\"", "") + "\"")
        }
    }

    // One fixed key for every build, so a new APK installs OVER the old one and your data survives.
    // Without this, CI generates a fresh debug key each run, Android rejects the update, and
    // uninstalling to get around it wipes the database and settings.
    signingConfigs {
        create("atlas") {
            storeFile = file("atlas.keystore")
            storePassword = "atlas1234"
            keyAlias = "atlas"
            keyPassword = "atlas1234"
        }
    }

    buildTypes {
        // CI builds the debug APK, so it must carry the same fixed key as release — otherwise each
        // build gets a fresh debug key and Android refuses to update the installed app.
        debug {
            signingConfig = signingConfigs.getByName("atlas")
            // Speed: a debuggable app runs Compose far slower (no full ART optimisation, extra checks).
            // The phone build isn't debugged, so it's built as a fast, non-debuggable app.
            isDebuggable = false
            isJniDebuggable = false
        }
        release {
            signingConfig = signingConfigs.getByName("atlas")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.maxHeapSize = "3g"
                it.ignoreFailures = true
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // emulator cores must exist as real files in the app's native library folder so the Arcade can load them by path
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md", "META-INF/NOTICE", "META-INF/LICENSE")
        }
    }
}

dependencies {
    // this comes in compileOnly through another library's own POM, which this project's build rejects
    implementation("com.google.j2objc:j2objc-annotations:2.8")
    // Real on-device Java compiler (ECJ) and dexer (D8, the same one Gradle uses) for the on-phone
    // build toolchain. Both run in-process, no native binary, so none of Android's restrictions on
    // running downloaded/unbundled code apply.
    implementation("org.eclipse.jdt:ecj:3.42.0")
    implementation("com.android.tools:r8:9.5.23")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appfunctions:appfunctions:1.0.0-alpha08")
    implementation("androidx.appfunctions:appfunctions-service:1.0.0-alpha08")
    implementation("androidx.xr.projected:projected:1.0.0-alpha09")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-alpha08")
    // installs the libraries' built-in startup/scroll profiles on sideloaded APKs, so code is precompiled
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("net.zetetic:sqlcipher-android:4.19.1@aar")
    implementation("androidx.sqlite:sqlite:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha12")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    // World maps (OpenStreetMap), no API key
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Torrent engine so torrent sources stream inside Cinema (libtorrent, with its native library per CPU)
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media3:media3-effect:1.3.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // Atlas Terminal: the terminal emulator and view Termux is built on (Apache 2.0)
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    // Atlas's PC: zstd-compressed image layers
    implementation("io.airlift:aircompressor:0.27") { isTransitive = false }
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // On-device LLM for offline answers (the model file is imported by the user, not bundled)
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // Atlas Arcade: the libretro front-end. The emulator cores themselves are real native binaries,
    // fetched by the CI build into jniLibs (see .github/workflows/build-apk.yml) — not bundled here.
    implementation("com.github.Swordfish90:LibretroDroid:0.14.0")

    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // screenshots of every main screen, rendered on the build server
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

ksp {
    arg("appfunctions:aggregateAppFunctions", "true")
}
