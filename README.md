# PrivateMusic

App de Android (Kotlin + Jetpack Compose) para descargar música de YouTube a la máxima calidad disponible, organizarla en playlists y reproducirla offline.

## Calidad de audio

- Se descarga siempre el stream `bestaudio` original con **yt-dlp**, sin recodificar: Opus ~160 kbps (formato 251) en vídeos normales, y Opus/AAC 256 kbps cuando el vídeo es de YouTube Music.
- No se convierte a MP3 a propósito: cualquier conversión perdería calidad. Los archivos se guardan en su códec original (`.webm`/Opus o `.m4a`/AAC), que ExoPlayer reproduce de forma nativa.
- yt-dlp se auto-actualiza al arrancar la app para sobrevivir a los cambios de YouTube.

## Arquitectura

| Capa | Tecnología |
|---|---|
| UI | Jetpack Compose + Material 3 (color dinámico en Android 12+) |
| Reproducción | Media3 / ExoPlayer con `MediaSessionService` (segundo plano + notificación multimedia) |
| Descarga y búsqueda | [youtubedl-android](https://github.com/yausername/youtubedl-android) (yt-dlp + ffmpeg empaquetados) |
| Persistencia | Room (canciones, playlists, relación N:M con orden) |
| Imágenes | Coil (carátulas descargadas junto al audio como JPG) |

Pantallas: **Buscar** (búsqueda en YouTube y descarga con progreso), **Biblioteca** (todas las canciones, menú para añadir a playlist o borrar), **Playlists** (crear/borrar, detalle con reproducción en cola) y **Reproductor** (barra de progreso, aleatorio, repetición) con mini-reproductor persistente.

También puedes **compartir un enlace de YouTube desde cualquier app** hacia PrivateMusic y se descarga directamente.

## Compilar

Requiere JDK 17+ y el SDK de Android (API 35). `local.properties` debe apuntar a tu SDK.

```bash
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

Los archivos de audio se guardan en el almacenamiento privado de la app (`Android/data/com.aar.privatemusic/files/music`), por lo que no se necesitan permisos de almacenamiento.

## Nota legal

Descargar contenido de YouTube puede infringir sus condiciones de servicio. Uso personal y privado bajo tu responsabilidad.
