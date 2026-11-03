import java.util.Properties
import java.time.Duration
import java.net.URI
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Firebase's downloaded Android config is the default; Gradle properties remain CI overrides.
val googleServices = providers.fileContents(layout.projectDirectory.file("google-services.json")).asText.orNull
    ?.let { JsonSlurper().parseText(it) as Map<*, *> }
val firebaseClient = (googleServices?.get("client") as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull {
    val info = it["client_info"] as? Map<*, *>
    (info?.get("android_client_info") as? Map<*, *>)?.get("package_name") == "app.aurora"
}
require(googleServices == null || firebaseClient != null) {
    "app/google-services.json has no Android client for app.aurora. Download the matching Firebase Android app configuration."
}
val projectInfo = googleServices?.get("project_info") as? Map<*, *>
val clientInfo = firebaseClient?.get("client_info") as? Map<*, *>
val apiKey = (firebaseClient?.get("api_key") as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull()?.get("current_key") as? String ?: ""
val webClientId = (firebaseClient?.get("oauth_client") as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull {
    it["client_type"] == 3
}?.get("client_id") as? String ?: ""
fun authProperty(name: String, fallback: String = "") = providers.gradleProperty("aurora.$name").getOrElse(fallback).trim()
val authConfiguration = mapOf(
    "firebaseApiKey" to authProperty("firebaseApiKey", apiKey),
    "firebaseAppId" to authProperty("firebaseAppId", clientInfo?.get("mobilesdk_app_id") as? String ?: ""),
    "firebaseProjectId" to authProperty("firebaseProjectId", projectInfo?.get("project_id") as? String ?: ""),
    "googleWebClientId" to authProperty("googleWebClientId", webClientId),
    "serverUrl" to authProperty("serverUrl"),
    "entitlementPublicKey" to authProperty("entitlementPublicKey"),
)
val validateAuthConfiguration = tasks.register("validateAuthConfiguration") {
    group = "verification"
    description = "Rejects installable builds without Firebase sign-in and the account service."
    inputs.properties(authConfiguration)
    doLast {
        val missing = inputs.properties.filterValues { it.toString().isBlank() }.keys
        check(missing.isEmpty()) {
            "Aurora sign-in is not configured. Missing: ${missing.joinToString { "aurora.$it" }}. " +
                "Add app/google-services.json with Google sign-in enabled, or supply Gradle properties. See docs/ACCOUNT_SYSTEM.md."
        }
        check(inputs.properties.getValue("googleWebClientId").toString().endsWith(".apps.googleusercontent.com")) {
            "aurora.googleWebClientId must be a Google OAuth Web application client ID (client_type 3), not an Android client ID."
        }
        val server = URI(inputs.properties.getValue("serverUrl").toString())
        check(server.scheme == "https" && !server.host.isNullOrBlank() && server.userInfo == null && server.fragment == null) {
            "aurora.serverUrl must be an HTTPS account-service URL."
        }
        check(runCatching {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(
                Base64.getDecoder().decode(inputs.properties.getValue("entitlementPublicKey").toString())
            ))
        }.isSuccess) { "aurora.entitlementPublicKey must be the account service's Base64 X.509 RSA public key." }
    }
}
// CI checks code, not a shippable build, and has no Firebase project: it passes -Paurora.skipSignInCheck=true.
// Its APKs can't sign anyone in. Never pass this for a build people install.
val skipSignInCheck = providers.gradleProperty("aurora.skipSignInCheck").orNull == "true"
if (!skipSignInCheck) androidComponents.onVariants { variant ->
    val suffix = variant.name.replaceFirstChar { it.uppercaseChar() }
    tasks.matching { it.name in listOf("assemble$suffix", "package$suffix", "bundle$suffix", "install$suffix") }.configureEach {
        dependsOn(validateAuthConfiguration)
    }
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
        testInstrumentationRunner = "app.aurora.TestRunner"
        // The Play subscriptions that are aurora plus, comma-separated. One product with monthly and annual
        // base plans works too (docs/BILLING.md).
        val plusProducts = providers.gradleProperty("aurora.plusProductIds").getOrElse("aurora_plus_monthly,aurora_plus_annual")
        buildConfigField("String", "PLUS_PRODUCT_IDS", "\"$plusProducts\"")
        // The account service (server/). Empty: nobody can sign in to anything server-controlled on this build.
        buildConfigField("String", "SERVER_URL", JsonOutput.toJson(authConfiguration.getValue("serverUrl")))
        buildConfigField("String", "ENTITLEMENT_PUBLIC_KEY", JsonOutput.toJson(authConfiguration.getValue("entitlementPublicKey")))
        // The Firebase project. These identify the app and aren't secrets, so they may ship in the APK
        // (docs/ACCOUNT_SYSTEM.md). Set them in ~/.gradle/gradle.properties. Empty: sign-in says it isn't set up.
        fun property(name: String) = authConfiguration[name.removePrefix("aurora.")] ?: providers.gradleProperty(name).getOrElse("")
        val firebaseProject = property("aurora.firebaseProjectId")
        val linkHost = property("aurora.emailLinkHost").ifBlank { if (firebaseProject.isNotBlank()) "$firebaseProject.firebaseapp.com" else "aurora-not-configured.invalid" }
        buildConfigField("String", "FIREBASE_API_KEY", JsonOutput.toJson(property("aurora.firebaseApiKey")))
        buildConfigField("String", "FIREBASE_APP_ID", JsonOutput.toJson(property("aurora.firebaseAppId")))
        buildConfigField("String", "FIREBASE_PROJECT_ID", JsonOutput.toJson(firebaseProject))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", JsonOutput.toJson(property("aurora.googleWebClientId")))
        buildConfigField("String", "EMAIL_LINK_HOST", "\"$linkHost\"")
        buildConfigField("String", "SUPPORT_EMAIL", "\"${property("aurora.supportEmail")}\"")
        buildConfigField("String", "LEGAL_BASE_URL", "\"${property("aurora.legalBaseUrl")}\"")
        // Debug builds only: the Firebase Auth Emulator on this host (FirebaseIdentity checks BuildConfig.DEBUG).
        buildConfigField("String", "AUTH_EMULATOR_HOST", "\"${property("aurora.authEmulatorHost")}\"")
        manifestPlaceholders["emailLinkHost"] = linkHost
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
            // A deadlocked test fails the run instead of hanging CI until the job is killed.
            it.timeout.set(Duration.ofMinutes(40))
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
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)

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
