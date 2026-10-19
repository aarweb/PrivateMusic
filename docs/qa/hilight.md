# HiLight durante la reproducción

Versión de entrega: **2.41** (versionCode **142**).

Base: release publicada v2.38 (`df5d0a8`). Desarrollo aislado en `feat/hilight`,
sin modificar el checkout principal con cambios anteriores.

## Uso

En **Ajustes → Aplicación → HiLight al reproducir**, activa el interruptor.
Está apagado por defecto. Solo se muestra en Pixel 11 Pro, Pixel 11 Pro XL y
Pixel 11 Pro Fold con Android 17 o posterior. Además del modelo, se comprueban
ocho luces de tipo APPLICATION con control RGB y animaciones.

Si aparece «Inicia Shizuku», sigue **Configurar Shizuku**, inicia Shizuku y
vuelve a PrivateMusic. Pulsa **Autorizar acceso**, acepta en Shizuku y después
activa el interruptor. Tras reiniciar el móvil puede ser necesario iniciar
Shizuku de nuevo. Denegar el permiso mantiene la música funcionando y las
luces deshabilitadas; siempre se permite apagar una preferencia ya guardada.

Es un pulso multicolor mientras se reproduce audio local, incluidos auriculares.
Los ocho LED recorren rojo, naranja, amarillo, verde, cian, azul, violeta y
magenta, desplazando los colores con cada pulso. El máximo de cada canal pasa
de 16/255 a 192/255 (12 veces el valor anterior; no implica 12 veces el brillo
físico). Cada pulso sigue terminando en negro. El servicio Shizuku se versiona
con la app para reemplazar el código anterior tras actualizar.
El usuario confirmó que los colores de la 2.40 ya se ven físicamente.

Desde 2.41, los pulsos responden a ataques de energía del audio PCM, con énfasis
en graves, ventanas de 10 ms y un intervalo mínimo de 180 ms entre destellos.
No usa micrófono ni un metrónomo fijo: el silencio no genera pulsos. Los eventos
se comparan con la posición del AudioSink antes de enviarlos a las luces; se
eliminan al vaciar el audio o saltar, y se descartan si llegan más de 150 ms tarde.
La cola está limitada a 64 eventos. No modifica las muestras de sonido.
Analiza PCM de 16 bits y float, también en el segundo reproductor del crossfade;
una pista con volumen de fundido casi cero no dispara luces. Los formatos
comprimidos/offload o PCM no admitidos no generan pulsos. La latencia física de
Bluetooth, del firmware y del LED puede afectar la sincronía; pendiente de
comprobar en el Pixel. Es detección de ataques, no identificación musical de
cada tiempo del compás. Pausa,
buffering sin audio, final de cola, Cast y destrucción del servicio apagan la
luz. Durante un crossfade cuenta también el reproductor de salida.

## Implementación y límites

- API de animaciones Android 17: `ColorSequence`, `MultiLightEffect` y
  `LightsRequest.setEffect`. Adaptador aislado que resuelve estas clases en
  ejecución: se conserva compileSdk 36 porque el repositorio SDK instalado
  no ofrece `platforms;android-37`. No se eleva minSdk ni targetSdk.
- Primero se comprueba `CONTROL_DEVICE_LIGHTS`. Si Android no lo concede,
  se utiliza un servicio Shizuku 13.1.5 autorizado expresamente por el usuario.
  El proveedor está protegido por `INTERACT_ACROSS_USERS_FULL`.
- El proceso shell accede al servicio lights por Binder; su interfaz propia
  solo permite comprobar soporte, emitir un pulso multicolor finito y apagar. No acepta
  comandos de shell, rutas de archivos ni patrones arbitrarios.
- Las operaciones de hardware se serializan fuera del hilo de reproducción.
  Cada efecto es finito y termina en negro; no se usa repetición infinita.
  Un lease de 2,5 s y la muerte del Binder del cliente ordenan liberar las
  luces si se pierden las renovaciones. El helper no es un daemon persistente.
- El apagado escribe negro y cierra la sesión. Esto acredita la petición al
  sistema, no el apagado físico del LED; debe comprobarse en el dispositivo.
- El pulso no implica que se haya verificado físicamente el firmware del usuario.
  La compatibilidad efectiva puede variar con una actualización del sistema.

## Verificación

```bash
JAVA_HOME=/home/aar/.jdks/jdk-17.0.19+10 ANDROID_HOME=/home/aar/Android/Sdk ./gradlew :app:testDebugUnitTest
JAVA_HOME=/home/aar/.jdks/jdk-17.0.19+10 ANDROID_HOME=/home/aar/Android/Sdk ./gradlew :app:connectedDebugAndroidTest -Pemulator -Pandroid.testInstrumentationRunnerArguments.class=com.aar.privatemusic.ui.HiLightSettingTest
```

Las pruebas de lógica cubren gating por hardware/Android, opt-in, acceso,
reproducción local/Cast, crossfade, pausa, caducidad y errores de limpieza.
Las pruebas Compose cubren ocultación, permiso explícito, estado deshabilitado,
activación y desactivación tras perder acceso. Las capturas de estos estados
se guardan en el directorio externo `hilight-qa` de la app del emulador.

Resultado 2.41: **68 pruebas unitarias correctas**, incluidas diez nuevas
para detección a 60/120/180 BPM, silencio, tono continuo, estéreo en oposición
de fase, muestras float inválidas, espera de reproducción, descarte de eventos
antiguos y cola acotada. La compilación release se verifica antes de publicar.

Verificación de interfaz realizada en 2.39: **5 pruebas Compose
correctas en cada tamaño, 390×844 y 768×1024**, con capturas del componente
renderizado revisadas. `lintDebug` terminó sin errores en los archivos HiLight;
el informe general conserva 157 errores en líneas ya presentes en v2.38.
El adaptador tiene avisos de reflexión (`PrivateApi`), esperados al resolver
la API 37 desde SDK 36 y el Binder del proceso shell. No se considera una
validación física de HiLight ni un lint global limpio.

Evidencia local: `/home/aar/Projects/PrivateMusic-hilight-evidence/`.
La licencia MIT de Shizuku se incluye en `assets/licenses/shizuku.txt` del APK.

En el Pixel real, pendiente: activar y pausar con pantalla apagada; cambiar
de canción con crossfade; acabar la cola; interrumpir por llamada; pasar a
Cast; detener Shizuku; revocar el permiso; forzar cierre de PrivateMusic;
reiniciar el teléfono. Confirmar visualmente que la luz se apaga y que la
música sigue operativa sin Shizuku. Probar primero sin otra app controlando
HiLight. No se modifica la configuración del sistema ni la de otras apps.

## Referencias

- [API Android 17](https://developer.android.com/reference/android/hardware/lights/LightsRequest.Builder#setEffect(android.hardware.lights.MultiLightEffect))
- [Permisos y hardware observados por HiLight Studio](https://github.com/DhananjayBhosale/hilight-studio/blob/main/docs/TECHNICAL.md)
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)

Implementación propia; no se han copiado fuentes de HiLight Studio.
