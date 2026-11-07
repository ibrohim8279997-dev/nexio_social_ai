plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

// Tests assert on English text, so they must not depend on the machine's language.
tasks.test { jvmArgs("-Duser.language=en", "-Duser.country=US") }

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.room.common)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
