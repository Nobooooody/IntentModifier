// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

extra["compileSdkVersion"] = 36
extra["minSdkVersion"] = 26
extra["targetSdkVersion"] = 36
extra["sdkName"] = "android-36.0"