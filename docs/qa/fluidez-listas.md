# Fluidez de listas de canciones

La preferencia de refresco se solicita solo durante el desplazamiento (incluida la inercia) de listas que contienen canciones. Android conserva el control de la tasa física. No se cambia la resolución ni el modo de pantalla. Con ahorro de batería, pausa de la actividad, lista vacía o navegación a otra pantalla, se restaura la preferencia anterior.

## Pruebas

Con JDK 17 y el SDK Android configurado:

```bash
JAVA_HOME=/home/aar/.jdks/jdk-17.0.19+10 ./gradlew :app:testDebugUnitTest --tests com.aar.privatemusic.util.RefreshRatePolicyTest
JAVA_HOME=/home/aar/.jdks/jdk-17.0.19+10 ./gradlew :app:connectedDebugAndroidTest -Pemulator -Pandroid.testInstrumentationRunnerArguments.class=com.aar.privatemusic.ui.SongListRefreshRateTest
```

La prueba instrumentada inserta 50 registros temporales de canciones; sus archivos de audio no existen y no se reproduce audio. Desplaza la biblioteca real y observa `Window.attributes.preferredRefreshRate` durante el movimiento, la pausa y el reposo. Un emulador de 60 Hz solo puede comprobar la petición de 60 Hz y su restauración; no demuestra una tasa física superior ni mide FPS.

### Resultado de 2.38 — 28 de septiembre de 2026

- Android JVM: 51 pruebas, núcleo compartido: 315, escritorio: 4; ninguna falla.
- Emulador Android 15/API 35 x86_64, 1080 × 2400, modo anunciado de aproximadamente 60 Hz: las dos pruebas de refresco y Canvas pasan. Se solicita el máximo durante el desplazamiento, se restaura la preferencia al pausar y terminar, y se conserva la solicitud al volver a primer plano si sigue la inercia. Al cambiar el Canvas a un archivo ausente desaparece el indicador.
- Las dos pruebas instrumentadas del modo aleatorio también pasan con este código; mantienen la mezcla de la playlist completa desde la última canción y la restauración del orden al desactivarlo.
- Las cuatro pruebas JVM de la política cubren modos inválidos, resolución distinta, tasas alternativas, solicitudes concurrentes, ahorro de batería y restauración de una preferencia previa distinta de cero.

La validación suma 374 casos distintos correctos. El refresco real a 90/120 Hz y el tiempo de cada fotograma quedan pendientes de medición en un móvil físico; estos resultados no cuantifican una mejora de FPS.

## Dispositivo de 90/120 Hz

En un móvil que anuncie 90 o 120 Hz para la resolución activa, abre Biblioteca con suficientes canciones y desplázala. Comprueba la **preferencia solicitada** leyendo `Window.attributes.preferredRefreshRate` en instrumentación durante el movimiento, al detenerte, al cambiar a Ajustes, al enviar la app al fondo y con ahorro de batería. La opción de desarrollo «Mostrar frecuencia de actualización» muestra la **tasa física observada**, no la preferencia: Android puede elegir otra tasa y otras animaciones, como el miniplayer, pueden mantenerla alta. Si se evalúan **FPS o jank**, mídelo por separado con Perfetto o Profile HWUI; esta prueba no los mide. Verifica también que el icono «Canvas disponible» solo aparece cuando la ruta de vídeo señala un archivo legible, tanto al entrar como al cambiar esa ruta.
