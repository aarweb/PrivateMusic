# Auditoría de mercado 2026 — PrivateMusic v1.4

*Fecha: 2026-07-03. Método: 5 subagentes de investigación web en paralelo (streaming gigantes, nicho audiófilo, competencia directa, mecánicas de retención, IA/audio avanzado), todo con fuentes de finales de 2025 y 2026. Cruzado con el estado actual de la app.*

## 1. Conclusiones ejecutivas

**a) Los grandes están cobrando por lo que nosotros ya damos gratis.** En 2026 YouTube Music puso las **letras tras paywall**, Spotify gatea en Premium la cola avanzada, el reshuffle, las descargas en segundo plano y el lossless. PrivateMusic ya tiene letras sincronizadas offline, cola completa y descarga a máxima calidad: **el posicionamiento es "todo lo que ellos cobran, gratis, offline y tuyo para siempre"**.

**b) El nicho de "YT Music sin Google" está en crisis y sus usuarios buscan refugio.** ViMusic, RiMusic y OuterTune murieron o quedaron abandonados en 2025-26; NewPipe se rompe con cada cambio de YouTube; Spotube recibió un cease & desist. Los usuarios citan como dolores: apps que mueren, errores "not a bot", calidad baja, backups que fallan. Nuestra arquitectura (archivos reales + yt-dlp auto-actualizable) es inmune a la mitad de esos males. **La mayor fuente de usuarios de 2026 son los refugiados de esas apps → importadores y backup fiable son la prioridad de adquisición.**

**c) Lo que retiene en 2026 no es el catálogo, es el ritual.** El churn de streaming subió al 5,5% y el precio es el driver nº1 de cancelación; lo que retiene es el hábito diario: Wrapped/Replay (ahora también mensual), daylist, widgets, rachas suaves, tarjetas compartibles. **Todo eso se calcula desde el historial local que ya tenemos.**

**d) La frontera técnica 2026 es la inteligencia on-device sobre tu propia biblioteca.** Apple demostró con AutoMix (iOS 26) que las transiciones DJ con beat-matching corren en el móvil sin nube. Plexamp (Super Sonic) demostró que los embeddings sónicos locales dan radio infinita, mixes y "pistas similares" sin servidor. Demucs ya corre en ONNX on-device (karaoke offline). **Un player offline puede tener hoy IA real sin conexión; nadie lo ofrece completo en Android.**

## 2. Matriz consolidada (funcionalidades que aparecen en ≥2 informes)

| Funcionalidad | Quién la explota | Valor | Coste | Estado en PrivateMusic |
|---|---|---|---|---|
| Recap tipo Wrapped local (anual/mensual) + tarjetas PNG compartibles | Spotify, Apple, Tidal, Deezer, YT | ★★★★★ | S | Stats básicas; falta recap/share |
| Importador de playlists (URL Spotify/YT Music, CSV) con matching y descarga | Spotify+TuneMyMusic, Metrolist | ★★★★★ | M | No existe |
| Descarga de playlist completa de un toque | Seal, YTDLnis | ★★★★★ | S | Solo canción a canción |
| **Observar playlists/canales con auto-descarga y sync** | YTDLnis (único) | ★★★★★ | M | No existe — killer feature |
| Widgets de homescreen/lockscreen | Todos + Oto | ★★★★☆ | M | No existe |
| Mixes diarios / daylist local (por franja horaria e historial) | Spotify, Amazon Weekly Vibe, Deezer Flow | ★★★★☆ | M | Base: historial ya existe |
| Análisis local BPM + tonalidad + embeddings sónicos | Plexamp, Spotify (Smart Reorder), Tidal DJ | ★★★★☆ | L | No existe — infraestructura de todo lo demás |
| Radio por similitud / autoplay de similares / Sonic Adventure | Plexamp, familia InnerTune, Neutron | ★★★★☆ | M (sobre análisis) | No existe |
| AutoMix (transiciones beat-matched) + Smart Reorder por BPM/Camelot | Apple iOS 26/27, Spotify Premium | ★★★★☆ | L | Crossfade-lite actual |
| Backup/restore total + export M3U/CSV + import desde apps muertas | Musicolet, Metrolist (falla), Poweramp | ★★★★☆ | S | No existe |
| Múltiples colas con memoria de posición | Musicolet (la feature más amada del nicho) | ★★★☆☆ | M | Una cola |
| SponsorBlock al descargar (recortar intros/outros no-música) | Tubular, RiMusic | ★★★☆☆ | S | No existe |
| Ecualizador (10 bandas, presets por salida BT) + crossfeed | Poweramp (razón nº1 de pago), Roon MUSE | ★★★☆☆ | M | No existe |
| Snooze de canción + botón Reshuffle | Spotify may-2026 | ★★★☆☆ | S | No existe |
| Carpetas de playlists + pins arriba de la biblioteca | Spotify may-2026, Apple iOS 26 | ★★★☆☆ | S | No existe |
| Normalización EBU R128 con modo álbum + signal path visible | Plexamp, Roon | ★★★☆☆ | M | RMS simple actual |
| Scrobbling Last.fm/ListenBrainz | BlackPlayer, forks | ★★☆☆☆ | S | No existe |
| Chromecast/DLNA | Oto, Neutron | ★★☆☆☆ | M | No existe |
| Karaoke / quitar voz por lotes (Demucs ONNX on-device) | Apple Sing, Moises (nube) | ★★★☆☆ | XL | No existe — nadie lo tiene 100% offline |
| Traducción de letras offline (ML Kit) | Apple iOS 26/27 | ★★☆☆☆ | M | Letras sí; traducción no |
| Metadatos ricos (créditos/bios MusicBrainz cacheados) / SongDNA | Roon, Spotify SongDNA beta | ★★☆☆☆ | M | No existe |

## 3. Plan propuesto

### Fase 4 — Ritual y retención ✅ COMPLETADA (v1.5, 2026-07-03)
1. **Recap estilo Wrapped**: anual + mensual siempre disponible, con Listening Personality (arquetipo), Listening Archive (días memorables), hitos/badges y **exportar tarjeta PNG compartible**.
2. **Mix diario / daylist local**: playlist que se regenera cada día (y por franja horaria) desde historial + favoritos + olvidadas, con nombres tipo "Tu miércoles enérgico".
3. **Widgets**: "seguir escuchando" + mix del día (Glance).
4. **Backup/export**: backup automático de BD + export/import M3U/CSV de playlists.
5. **Snooze (30 días) + Reshuffle** en shuffle/mixes; **pins** de playlists/artistas arriba de la Biblioteca.
6. **Time capsule estacional** ("Tu verano 2026") auto-generada.

### Fase 5 — Adquisición: el aspirador de refugiados ✅ COMPLETADA (v1.6, 2026-07-03)
7. **Importador de playlists**: pegar URL de playlist de YouTube/YT Music (y CSV de Spotify vía export) → resolver cada pista → descargar todo con cola visible.
8. **Descarga de playlist completa** de un toque desde la búsqueda.
9. **Observar playlists/canales** (estilo YTDLnis): auto-descarga de lo nuevo con WorkManager periódico + notificación.
10. **SponsorBlock** al descargar (API pública, recorta segmentos no-música con el ffmpeg embebido).
11. **Scrobbling Last.fm/ListenBrainz** opcional.

### Fase 6 — Inteligencia on-device ✅ NÚCLEO COMPLETADO (v1.8, 2026-07-03)
*Implementado: análisis BPM/tonalidad Camelot/huella sónica (DSP propio, sin ML), radio por similitud, Smart Reorder, EQ con presets. Pendiente como apuestas XL: AutoMix con time-stretch real (requiere doble pipeline de audio con SoundTouch/Oboe) y karaoke Demucs-ONNX (~300 MB de modelo).*
12. **Análisis de biblioteca en background**: BPM + tonalidad (TarsosDSP/Essentia) + embeddings (MusiCNN/Essentia-TF) al descargar. Habilita todo lo siguiente.
13. **Radio de similitud** ("reproducir similares", autoplay infinito) + **Sonic Adventure** (ruta entre dos canciones).
14. **Smart Reorder** de playlists por BPM/rueda de Camelot (feature Premium de Spotify, gratis aquí).
15. **AutoMix**: crossfade beat-matched con time-stretch (SoundTouch/Oboe) — la feature con más buzz de 2025-26.
16. **EQ 10 bandas + presets por dispositivo de salida** + normalización EBU R128 modo álbum + signal path visible.
17. *(Opcional, XL)* **Modo karaoke offline**: separación de voz por lotes con htdemucs-ONNX; ningún player Android lo ofrece 100% local.

### Reglas de posicionamiento (de los 5 informes)
- Comunicar en la app: "Letras, cola y máxima calidad: gratis y para siempre" (lo que YT Music/Spotify cobran).
- Nunca encarcelar datos: export siempre disponible (el anti-lock-in retiene al power user).
- Simplicidad Material You ante todo: Poweramp/Neutron pierden usuarios por complejidad; Oto/Musicolet ganan por ligereza.

## 4. Informes fuente
Los 5 informes completos de los subagentes (con ~90 fuentes de 2025-2026: Spotify Newsroom, MacRumors/9to5Mac, Deezer Newsroom, Plex, Roon, GitHub issues de InnerTune/Metrolist/YTDLnis, estudios de churn de eMarketer/Deloitte/Nature) están resumidos en las secciones anteriores; los rankings individuales por informe coinciden en el orden de las fases propuestas.
