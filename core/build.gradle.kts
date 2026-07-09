plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    compilerOptions {
        // El `expect object` que exige @ConstructedBy sigue marcado como beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            // `api`: quien use :core maneja RoomDatabase y Flow directamente.
            api(libs.room.kmp.runtime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.sqlite.bundled)
        }

        // Código que necesita la JVM (HttpURLConnection, javax.crypto, org.json)
        // pero no Android. Lo comparten el móvil y el escritorio.
        //
        // `org.json` es `compileOnly` aquí porque Android lo trae en su
        // framework: añadir el artefacto al APK duplicaría clases del sistema.
        // El escritorio sí lo necesita de verdad, y lo añade en jvmMain.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies { compileOnly(libs.json) }
        }
        androidMain.get().dependsOn(jvmCommonMain)
        jvmMain.get().dependsOn(jvmCommonMain)

        jvmMain.dependencies {
            implementation(libs.json)
            implementation(libs.jaudiotagger)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// El compilador de Room corre una vez por objetivo.
dependencies {
    add("kspAndroid", libs.room.kmp.compiler)
    add("kspJvm", libs.room.kmp.compiler)
}

android {
    namespace = "com.aar.privatemusic.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
