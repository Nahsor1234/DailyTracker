plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nahsor1234.dailytracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nahsor1234.dailytracker"
        minSdk = 26
        targetSdk = 35 // Revisit against current Google Play requirements at release
        versionCode = 7
        versionName = "1.6"\n\n        buildConfigField("String", "SUPABASE_URL", "\\\"https://clirtdxtqtiatuqkitmy.supabase.co\\\"")\n        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\\\"sb_publishable_VjQYGoJjFoAQXHSGwOohtQ_XL1XWIjU\\\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
