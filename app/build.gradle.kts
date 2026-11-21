import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.pocketlab"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.pocketlab"
        minSdk = 29
        targetSdk = 36
        // CI passes these from the release tag (v1.2.3 -> name 1.2.3, code 1002003). Local builds use the defaults.
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 2
        versionName = (findProperty("appVersionName") as String?) ?: "0.2.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Tagged release builds pass -PrequireReleaseSigning: never ship an APK signed with a throwaway
            // debug key, because Android refuses to update an install signed with a different key.
            if (keystoreProps.isEmpty() && findProperty("requireReleaseSigning") != null) {
                throw GradleException("requireReleaseSigning is set but keystore.properties is missing")
            }
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // What CI builds for every PR: the release build (same R8 shrinking) under its own app ID,
        // "Pocketlab Dev" (src/dev/res), so it installs next to the real app with separate settings.
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // One APK per CPU type: the terminal's native library is ~3 MB per ABI. Install the arm64-v8a
    // APK on any phone from the last ~8 years; the others are for old 32-bit phones and emulators.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/INDEX.LIST", "META-INF/*.kotlin_module",
                // BouncyCastle's certificate-path report strings: unused (we never validate X.509 paths with BC).
                "org/bouncycastle/**/*.properties",
            )
        }
        // Compressed inside the APK: a smaller download/sideload file at the cost of a one-off extract on install.
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.hierynomus:sshj:0.41.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.87")
    implementation("org.connectbot:termlib:0.3.15")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.1")
}

tasks.withType<Test>().configureEach {
    // Integration tests against a local sshd only run when it's configured.
    System.getenv("POCKETLAB_TEST_SSHD")?.let { environment("POCKETLAB_TEST_SSHD", it) }
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    maxHeapSize = "3g"
}

// Prints the release runtime classpath (as Android class jars) so R8 can be run by hand in
// class-file mode to test the shrunk code on the JVM. See scripts/r8-probe.sh.
tasks.register("printReleaseClasspath") {
    val files = configurations.getByName("releaseRuntimeClasspath").incoming.artifactView {
        attributes { attribute(org.gradle.api.attributes.Attribute.of("artifactType", String::class.java), "android-classes-jar") }
    }.files
    inputs.files(files)
    doLast { println("CP=" + files.files.joinToString(File.pathSeparator)) }
}
