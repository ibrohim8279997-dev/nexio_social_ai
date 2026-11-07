plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nexiolab.socialai.data"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Tests assert on English text, so they must not depend on the machine's language.
        unitTests.all { it.jvmArgs("-Duser.language=en", "-Duser.country=US") }
    }
}

// Room's MigrationTestHelper reads the exported schema JSON files as assets of the unit-test app.
androidComponents {
    onVariants { variant ->
        (variant as? com.android.build.api.variant.HasUnitTest)?.unitTest?.sources?.assets?.addStaticSourceDirectory("$projectDir/schemas")
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    api(project(":domain"))
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.guava) // Above 33.7.2 for GHSA-xxph-c9ww-hj94; Media3 alone resolves an older one.
    implementation(libs.gms.nearby)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
    testImplementation("androidx.sqlite:sqlite-framework:2.6.2")
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
}
