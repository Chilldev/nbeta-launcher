import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.mali.nbeta"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mali.nbeta"
        minSdk = 29
        targetSdk = 36
        // -Pnbeta.versionCode=N overrides (used to test the updater against a published release).
        versionCode = (project.findProperty("nbeta.versionCode") as String?)?.toInt() ?: 4
        versionName = "1.3"
        buildConfigField("String", "UPDATE_REPO", "\"Chilldev/nbeta-launcher\"")
    }

    // signing/keystore.properties (git-ignored) holds the release key. Without it, release builds fall back to the
    // debug key so the project still builds anywhere.
    val keystoreProps = rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use(::load) }
    }
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = rootProject.file("signing/" + keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "kotlin/**", "DebugProbesKt.bin")
    }
}

tasks.withType<Test>().configureEach {
    // Lets LocalPageProbe run against a saved page: -Pprobe.html=/path/page.html
    providers.gradleProperty("probe.html").orNull?.let { systemProperty("probe.html", it) }
    providers.gradleProperty("probe.url").orNull?.let { systemProperty("probe.url", it) }
    testLogging { showStandardStreams = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.work.runtime)
    implementation(libs.browser)
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
