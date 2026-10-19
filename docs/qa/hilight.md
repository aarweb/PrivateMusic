# HiLight durante la reproducción

Versión de entrega: **2.39** (versionCode **140**).

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

Es un pulso tenue mientras se reproduce audio local, incluidos auriculares.
No analiza el micrófono ni sincroniza con el ritmo de la canción. Pausa,
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
  solo permite comprobar soporte, emitir un pulso fijo y apagar. No acepta
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

Resultado local: **58 pruebas unitarias correctas**, y **5 pruebas Compose
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
