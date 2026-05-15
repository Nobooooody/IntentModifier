plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.nobooooody.intent_modifier"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nobooooody.intent_modifier"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
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
}