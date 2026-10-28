plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional built-in Site. With -PhcSiteUrl=https://your-site.example the app accepts connection
// codes only for that exact origin and talks only to it (a "pinned" build). Without it the app is
// generic: it accepts a code from any public HTTPS Site, but only after the user confirms the
// hostname, and afterwards talks only to that confirmed origin.
val siteUrl: String = (findProperty("hcSiteUrl") as String?)?.trim()?.trimEnd('/').orEmpty()
require(siteUrl.isEmpty() || Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$").matches(siteUrl)) {
    "hcSiteUrl must be an https:// origin without a path, for example https://example.chatgpt.site"
}

// Release signing comes only from the environment, never from files in the repository.
// Set all four to sign; set none for an unsigned release. -PhcRequireReleaseSigning=true (used by
// the signed-release workflow) fails the build unless all four are present.
val releaseSigningNames = listOf("HC_RELEASE_STORE_FILE", "HC_RELEASE_STORE_PASSWORD", "HC_RELEASE_KEY_ALIAS", "HC_RELEASE_KEY_PASSWORD")
val releaseSigningEnv: Map<String, String?> = releaseSigningNames.associateWith { name -> System.getenv(name)?.takeIf { it.isNotEmpty() } }
val releaseSigningRequired = (findProperty("hcRequireReleaseSigning") as String?)?.toBoolean() == true
val releaseSigningMissing = releaseSigningEnv.filterValues { it == null }.keys
if ((releaseSigningRequired || releaseSigningMissing.size < releaseSigningNames.size) && releaseSigningMissing.isNotEmpty()) {
    throw GradleException("Release signing needs ${releaseSigningMissing.joinToString()} (environment variables). Set all four, or none for an unsigned build.")
}
val releaseSigningConfigured = releaseSigningMissing.isEmpty()
if (releaseSigningConfigured && !file(releaseSigningEnv.getValue("HC_RELEASE_STORE_FILE")!!).isFile) {
    throw GradleException("HC_RELEASE_STORE_FILE does not point to a keystore file.")
}

android {
    namespace = "dev.hcsummaries.export"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hcsummaries.export"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
        buildConfigField("String", "SITE_URL", "\"${siteUrl.replace("\"", "")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseSigningEnv.getValue("HC_RELEASE_STORE_FILE")!!)
                storePassword = releaseSigningEnv.getValue("HC_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningEnv.getValue("HC_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningEnv.getValue("HC_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Debug builds keep Android's default debug key, so they install over earlier debug builds.
        release {
            signingConfig = if (releaseSigningConfigured) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20250517")
}
