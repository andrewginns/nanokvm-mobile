plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.androidx.baselineprofile.producer) apply false
    alias(libs.plugins.cyclonedx.bom) apply false
}

allprojects {
    group = "org.nanokvm"
    version = "0.3.2"
}
