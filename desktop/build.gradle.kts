import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    // libVLC hace el trabajo sucio: decodifica los siete formatos, sabe buscar
    // dentro del fichero y trae ecualizador. En Linux usa el del sistema; en
    // Windows habrá que empaquetarlo.
    implementation(libs.vlcj)
    // Encontrar el móvil sin escribir su IP.
    implementation(libs.jmdns)
    // Mismo API que en Android: el código que lee /library se parece al que lo escribe.
    implementation(libs.json)
}

// La release la pasa el workflow desde la etiqueta (`-PdesktopVersion=1.68`).
// En local queda 1.0.0, y el updater la trata como compilación de trabajo.
//
// jpackage exige MAJOR.MINOR.BUILD y rechaza "1.68" con un error críptico; las
// versiones de la app tienen dos números, así que se rellena el tercero.
val desktopVersion = ((project.findProperty("desktopVersion") as String?) ?: "1.0.0")
    .split(".")
    .let { parts -> (0..2).joinToString(".") { parts.getOrElse(it) { "0" } } }

compose.desktop {
    application {
        mainClass = "com.aar.privatemusic.desktop.MainKt"
        nativeDistributions {
            // No hay compilación cruzada: el .deb sale en Linux y el .msi en
            // Windows. Cada uno en su runner.
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            packageName = "PrivateMusic"
            packageVersion = desktopVersion
            description = "Tu biblioteca de música, sincronizada con el móvil"
            vendor = "aarweb"
            linux { debMaintainer = "aarcarpas@gmail.com" }
            windows {
                menu = true
                // Fijo para siempre: si cambia, Windows instala una app nueva
                // en vez de actualizar la que ya está.
                upgradeUuid = "8e4b9a1c-3f2d-4c6a-9b5e-7d1f0a2c8e63"
            }
        }
    }
}
