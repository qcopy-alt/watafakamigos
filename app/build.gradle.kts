plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.qcopy.watafakamigos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qcopy.watafakamigos"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags("-static")
                abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("com.scottyab:rootbeer-lib:0.1.1")
}

tasks.register("copyWrapper") {
    doLast {
        copy {
            from("build/intermediates/cmake/debug/obj/arm64-v8a/wrapper")
            into("src/main/assets/exploit/")
            fileMode = 0b111101101 // 0755
        }
    }
}

tasks.preBuild {
    dependsOn("copyWrapper")
}

val buildWrapper = tasks.register("buildWrapper") {
    group = "build"
    description = "compile wrapper using cmake"

    doLast {
        val cmakeDir = "${android.sdkDirectory}/cmake/3.22.1/bin"
        val cmakeExe = file("$cmakeDir/cmake.exe")
        val ninjaExe = file("$cmakeDir/ninja.exe")

        val buildDir = file("$buildDir/cmake-wrapper")
        val srcDir = file("src/main/cpp")
        val outDir = file("src/main/assets/exploit")

        buildDir.deleteRecursively()

        buildDir.mkdirs()
        outDir.mkdirs()

        if (!cmakeExe.exists()) error("CMake doesnt found: ${cmakeExe.absolutePath}")
        if (!ninjaExe.exists()) error("Ninja doesnt found: ${ninjaExe.absolutePath}")
        if (!srcDir.exists()) error("source doesnt found: ${srcDir.absolutePath}")

        println("🔧 compile wrapper...")
        println("📁 build dir: ${buildDir.absolutePath}")

        exec {
            commandLine(
                cmakeExe.absolutePath,
                "-S", srcDir.absolutePath,
                "-B", buildDir.absolutePath,
                "-G", "Ninja",
                "-DANDROID_ABI=arm64-v8a",
                "-DANDROID_PLATFORM=android-24",
                "-DANDROID_STL=c++_static",
                "-DCMAKE_TOOLCHAIN_FILE=${android.sdkDirectory}/ndk/27.0.12077973/build/cmake/android.toolchain.cmake",
                "-DCMAKE_MAKE_PROGRAM=${ninjaExe.absolutePath}",
                "-DCMAKE_BUILD_TYPE=Release"
            )
        }

        exec {
            commandLine(
                cmakeExe.absolutePath,
                "--build", buildDir.absolutePath,
                "--config", "Release",
                "--target", "wrapper"
            )
        }

        val binary = file("${buildDir}/wrapper")
        if (!binary.exists()) {
            throw GradleException("wrapper doesnt found: ${binary.absolutePath}")
        }
        binary.copyTo(file("${outDir}/wrapper"), overwrite = true)
        file("${outDir}/wrapper").setExecutable(true, false)
        println("✅ wrapper compiled and moved to assets")
    }
}

afterEvaluate {
    tasks.findByName("mergeDebugAssets")?.let { it.dependsOn(buildWrapper) }
    tasks.findByName("mergeReleaseAssets")?.let { it.dependsOn(buildWrapper) }
}