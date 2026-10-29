plugins {
    alias(libs.plugins.android.application)
}

val authenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

android {
    namespace = "com.diplay.ts7"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.hilllaoya.ts7play"
        minSdk = 25
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }
    authenticationAssets?.let { sourceSets.getByName("main").assets.directories.add(it.absolutePath) }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":common"))
    testImplementation(libs.junit)
    testImplementation(libs.androidx.activity)
    testImplementation("org.robolectric:robolectric:4.17")
}

tasks.withType<Test>().configureEach {
    // Robolectric's API 35+ shared-memory shadow uses this JDK interface, as in :home.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

val verifyAuthenticationAssets by tasks.registering {
    val allowed = authenticationAssets?.let { directory ->
        listOf("identity.pk8", "certificate.p7b")
            .map { directory.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    val credentials = files(android.sourceSets.flatMap { it.assets.directories }.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.pem", "**/*.key",
                "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    })
    inputs.files(credentials)
    doLast {
        check(allowed.all { it.isFile && it.length() > 0 }) { "Authentication assets are incomplete" }
        check(credentials.files.all { it.canonicalFile in allowed }) { "Unexpected credential asset" }
    }
}
tasks.named("preBuild") { dependsOn(verifyAuthenticationAssets) }
val requireStandaloneAuthentication by tasks.registering {
    val directory = authenticationAssets
    doLast {
        check(directory != null) { "Set DIPLAY_AUTH_ASSETS_DIR for a standalone car-test APK" }
    }
}
tasks.named("preBuild") { mustRunAfter(requireStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    dependsOn(requireStandaloneAuthentication, "assembleDebug")
}
