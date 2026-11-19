plugins {
    id("com.android.application")
    kotlin("plugin.compose") version "2.0.21"
}

android {
    namespace = "io.github.nobooooody.intent_modifier"
    compileSdk = 36

    defaultConfig {
        // Custom package name: override with -PIM_APP_ID=com.example.app
        // (same convention as InstallerX-Revived / fcitx5-android's APP_ID,
        // but project-scoped so a global ~/.gradle APP_ID for other projects
        // cannot leak into this build).
        applicationId = project.findProperty("IM_APP_ID") as String?
            ?: "io.github.nobooooody.intent_modifier"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        // Provider authority follows the applicationId so custom builds
        // can coexist with the official package.
        manifestPlaceholders["providerAuthority"] = "$applicationId.provider"
        buildConfigField("String", "MODULE_PACKAGE", "\"$applicationId\"")
        buildConfigField("String", "PROVIDER_AUTHORITY", "\"$applicationId.provider\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CI release signing: use RELEASE_STORE_FILE when provided,
            // otherwise fall back to the debug keystore so the release APK
            // is always installable (replace with a real key for production).
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")
            signingConfig = if (!storeFilePath.isNullOrEmpty() && file(storeFilePath).exists()) {
                signingConfigs.create("ciRelease") {
                    storeFile = file(storeFilePath)
                    storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                }
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.coordinatorlayout)
    compileOnly(libs.xposed.api)

    // Eclipse JDT Java Compiler for Android
    implementation("org.eclipse.jdt:ecj:3.26.0")

    // D8 for class to dex compilation (local jar)
    implementation(files("libs/d8.jar"))

    // Java 8 stubs for Android (javax.lang.model, javax.tools, etc.)
    implementation(project(":compiler-jdk8"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
}
