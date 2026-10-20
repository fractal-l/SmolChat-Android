plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization") version "2.1.0"
}

val releaseKeystoreFile = rootProject.file(System.getenv("RELEASE_KEYSTORE_FILE") ?: "release.p12")
val releaseKeystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeystoreAlias = System.getenv("RELEASE_KEYSTORE_ALIAS")
val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
val hasReleaseSigning =
    releaseKeystoreFile.exists() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeystoreAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "io.shubham0204.smollmandroid"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.shubham0204.smollmandroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "16"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseKeystoreFile
                storeType = System.getenv("RELEASE_KEYSTORE_TYPE") ?: "PKCS12"
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeystoreAlias
                this.keyPassword = releaseKeyPassword
            }
        }
    }

    // https://gitlab.com/fdroid/fdroiddata/-/merge_requests/21563#note_2890971890
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    buildTypes {
        getByName("debug") {
            // Sign CI debug APKs with the release key when it is available so
            // debug and release builds can update each other seamlessly.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigningConfig = signingConfigs.getByName("release")
            // Fall back to the debug key so forks and local builds can produce an APK without
            // access to the release keystore.
            signingConfig =
                if (releaseSigningConfig.storeFile != null) {
                    releaseSigningConfig
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    applicationVariants.configureEach {
        kotlin.sourceSets {
            getByName(name) {
                kotlin.srcDir("build/generated/ksp/$name/kotlin")
            }
        }
    }
    configurations {
        create("cleanedAnnotations")
        implementation {
            exclude(group = "org.jetbrains", module = "annotations")
        }
    }
}

ksp {
    arg("KOIN_CONFIG_CHECK", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.icons.extended)
    implementation(libs.androidx.compose.navigation)

    implementation(project(":smollm"))
    implementation(project(":hf-model-hub-api"))

    // Koin: dependency injection
    implementation(libs.koin.android)
    implementation(libs.koin.annotations)
    implementation(libs.koin.androidx.compose)
    implementation(libs.androidx.ui.text.google.fonts)
    ksp(libs.koin.ksp.compiler)

    // compose-markdown: Markdown rendering in Compose
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:syntax-highlight:4.6.2")
    implementation("io.noties:prism4j:2.0.0")
    annotationProcessor("io.noties:prism4j-bundler:2.0.0")

    // Jetpack Paging3: loading paged data for Compose
    val pagingVersion = "3.3.5"
    implementation("androidx.paging:paging-runtime:$pagingVersion")
    implementation("androidx.paging:paging-compose:$pagingVersion")

    // Android Room: Local persistence with SQLite
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    // compose-icons: Feather icons pack
    // https://github.com/DevSrSouza/compose-icons
    implementation(libs.composeIcons.feather)

    // Serialization for typed navigation routes
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Kotlin Immutable Collections
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")

    // moonshine-ai for speech recognition
    implementation(libs.moonshine.voice)

    implementation("com.github.khushpanchal:Ketch:2.0.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
