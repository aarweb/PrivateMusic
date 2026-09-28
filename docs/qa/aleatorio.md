# Regresión del modo aleatorio — 2.37

## Caso y causa

Seleccionar la última canción de una playlist, activar Aleatorio desde el
reproductor y pulsar Siguiente debe permitir recorrer el resto de la playlist.

La versión 2.36 solo barajaba el tramo posterior al índice actual. La prueba
en el emulador confirmó que, tras seleccionar la cuarta canción y activar
Aleatorio, el índice seguía siendo `3` y la cola continuaba `[1, 2, 3, 4]`.
La prueba de regresión falló en esa versión.

La corrección coloca la canción actual al comienzo del nuevo recorrido y
baraja todas las demás. Android aplica una permutación mediante movimientos
de Media3, conservando la pista en reproducción y su posición. Cada copia de
una canción se sigue por su índice original. Al desactivar Aleatorio se
recupera el orden anterior. Escritorio aplica el mismo recorrido y conserva
las canciones añadidas o eliminadas mientras el modo está activado.

## Prueba en emulador

- Android 15 / API 35, AVD Pixel 6 x86_64 independiente.
- APK de depuración compilado con `-Pemulator`.
- Playlist de cuatro pistas de prueba con audio PCM local de 120 segundos.
- Navegación y pulsaciones mediante Compose Test sobre la actividad real;
  la reproducción utiliza `MediaController`, el servicio y ExoPlayer reales.

`ShufflePlaybackTest` ejecuta estos casos:

1. Seleccionar la última canción desde la playlist, avanzar a 12 segundos,
   activar Aleatorio y pulsar Siguiente hasta recorrer las otras tres pistas.
   Comprueba que la canción actual no se reinicia y que la cola conserva todas
   las canciones una sola vez.
2. Seleccionar una canción intermedia, pausar y avanzar a 12 segundos.
   Activar Aleatorio debe incluir toda la playlist; desactivarlo debe restaurar
   el orden, el índice, la posición y el estado de pausa.

Resultado en 2.37: **2 pruebas instrumentadas, 0 fallos**. La comprobación del
núcleo y de `DesktopPlayerNextTest` sumó **319 pruebas JVM, 0 fallos**.

## Repetir la comprobación

Con un emulador API 35 x86_64 arrancado y el SDK configurado:

```bash
./gradlew :app:connectedDebugAndroidTest -Pemulator -Pandroid.testInstrumentationRunnerArguments.class=com.aar.privatemusic.player.ShufflePlaybackTest
./gradlew :core:jvmTest :desktop:test --tests 'com.aar.privatemusic.desktop.player.DesktopPlayerNextTest'
```

El informe Android queda en `app/build/reports/androidTests/connected/debug/`.
Las fixtures se crean y eliminan desde la propia prueba.
