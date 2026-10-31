import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.aurora"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.aurora"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The Play subscription that is aurora plus. Override with -Paurora.plusProductId=... once Play Console has it.
        val plusProduct = providers.gradleProperty("aurora.plusProductId").getOrElse("aurora_plus")
        buildConfigField("String", "PLUS_PRODUCT_ID", "\"$plusProduct\"")
    }

    // The Play upload key. keystore.properties (never committed; see docs/RELEASE.md) names storeFile,
    // storePassword, keyAlias and keyPassword. Without it, release builds stay unsigned, as on CI.
    val upload = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (upload != null) create("upload") {
            storeFile = file(upload.getProperty("storeFile"))
            storePassword = upload.getProperty("storePassword")
            keyAlias = upload.getProperty("keyAlias")
            keyPassword = upload.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            if (upload != null) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        // Release speed under the debug key, so it installs over a debug build and keeps the app's data.
        create("phone") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric renders Compose with the app's real resources and fonts.
        unitTests.isIncludeAndroidResources = true
        // Robolectric's Android 16 runtime reaches into JDK internals.
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            // Hundreds of full-screen renders in one JVM; the default 512 MB runs out.
            it.maxHeapSize = "2g"
        }
    }

    // UI tests in sharedTest run twice: on the JVM under Robolectric (every CI run) and on a device.
    sourceSets {
        named("test") { kotlin.directories += "src/sharedTest/java" }
        named("androidTest") { kotlin.directories += "src/sharedTest/java" }
    }

    lint {
        // New warnings fail the build. The four Phase 1 warnings are recorded in the baseline.
        warningsAsErrors = true
        baseline = file("lint-baseline.xml")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.billing)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
