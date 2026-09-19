import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sakurafubuki.yume.core.data"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.android.jvm.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.android.jvm.get().toInt())
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.android.jvm.get()))
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    implementation(project(":core:database"))
    implementation(project(":core:cache"))
    implementation(project(":core:media"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:datastore"))
    implementation(libs.yume.lib)

    implementation(libs.androidx.core.ktx)
    implementation(libs.coil.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sardine.android) {
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "stax", module = "stax-api")
        exclude(group = "stax", module = "stax")
    }
    implementation(libs.androidx.security.crypto)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm)
}
