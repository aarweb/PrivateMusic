# Auditoría de PrivateMusic frente al mercado (Spotify · Apple Music · Amazon Music)

*Fecha: 2026-07-03. Fuente: investigación web con 3 subagentes (un informe por plataforma) cruzada con el código actual.*

## 1. Conclusión ejecutiva

PrivateMusic ya cubre el ciclo básico (buscar → descargar a máxima calidad → biblioteca → playlists → reproducción en segundo plano), pero comparado con los tres grandes le faltan las funcionalidades que definen un reproductor "premium" en 2026. La buena noticia: **casi todo lo valioso es aplicable offline** — lo que no aplica es lo social y lo algorítmico-en-servidor (AI DJ, Blend, colaborativas, Discover Weekly).

Consenso de las 3 plataformas — lo que más valor aporta y nos falta:

| # | Funcionalidad | ¿Quién la tiene? | Valor | Esfuerzo |
|---|---|---|---|---|
| 1 | Cola de reproducción visible y editable | Las 3 | ★★★★★ | M |
| 2 | Letras sincronizadas (.lrc) | Las 3 | ★★★★★ | M |
| 3 | Favoritos + Reproducidos recientemente | Las 3 | ★★★★☆ | S |
| 4 | Now Playing con color dinámico de carátula | Las 3 | ★★★★☆ | S |
| 5 | Badge de calidad de audio (códec/bitrate reales) | Amazon (referente), Apple, Spotify | ★★★★☆ | S |
| 6 | Crossfade configurable (0–12 s) | Spotify, Apple (Amazon NO) | ★★★★☆ | L |
| 7 | Búsqueda y ordenación en biblioteca | Las 3 | ★★★★☆ | S |
| 8 | Sleep timer con fade-out | Spotify (Apple NO — carencia criticada) | ★★★☆☆ | S |
| 9 | Reordenar playlists drag & drop | Las 3 | ★★★★☆ | M |
| 10 | Android Auto (MediaLibraryService) | Las 3 | ★★★★☆ | M |
| 11 | Normalización de volumen (R128/ReplayGain) | Spotify (referente) | ★★★☆☆ | M |
| 12 | Auto-playlists locales (Más escuchadas, Olvidadas, Recientes) | Emulación local de Daily Mix / Discovery Mix | ★★★☆☆ | M |
| 13 | Estadísticas tipo Replay (minutos, tops, rachas) | Apple (referente), Spotify Wrapped | ★★★☆☆ | M |
| 14 | Smart playlists por reglas (herencia iTunes) | Apple escritorio (nadie lo hace bien en móvil) | ★★★☆☆ | L |
| 15 | Gestión de almacenamiento (GB usados, por canción) | Amazon (referente) | ★★☆☆☆ | S |

Lo que **no** vamos a perseguir (requiere servidor/catálogo/social): AI DJ, Smart Shuffle con recomendaciones, playlists colaborativas/Jam/Blend, Canvas de artista, Dolby Atmos, Spotify Connect, traducción de letras.

## 2. Estado actual de PrivateMusic (auditoría interna)

**Ya cumple estándar de mercado:**
- Descarga `bestaudio` sin recodificar (supera a Spotify hasta 2025: ellos servían 320 kbps máx.; filosofía "max quality first" alineada con Amazon HD).
- Reproducción en segundo plano con MediaSession + notificación (estándar de las 3).
- Mini player persistente + pantalla Now Playing con shuffle/repeat (patrón Spotify/Apple).
- Carátulas offline (JPG junto al audio).
- Playlists con orden persistente; compartir URL desde otras apps (diferenciador propio).
- Gapless: ExoPlayer lo da por defecto en cola — cumplido de facto, falta verificarlo con álbumes.

**Carencias detectadas (gap analysis):**
- No hay cola visible ni "reproducir a continuación" — la interacción nº 1 de un player según los 3 informes.
- No hay favoritos, ni historial, ni contadores de reproducción (bloquea: Recently Played, auto-playlists, Replay, smart shuffle local).
- No hay búsqueda ni ordenación dentro de la biblioteca (crítico cuando crezca).
- Now Playing sin color dinámico; estética plana frente al estándar 2026.
- No se muestra la calidad real del archivo (códec/bitrate/sample rate) — barato y es nuestra bandera.
- Sin letras, crossfade, normalización, sleep timer, EQ, Android Auto.
- Sin reordenación drag & drop en playlists; sin portada de playlist (collage).
- Metadatos mínimos (título/artista): no hay álbum, año ni agrupación por artista — Apple señala que esto "es ruinoso cuando falta".
- Sin gestión de almacenamiento (la app descarga archivos y no reporta espacio).

## 3. Plan de aplicación priorizado

### Fase 1 — Paridad básica de reproductor ✅ COMPLETADA (v1.1, 2026-07-03)
1. **Favoritos** (`isFavorite` en `Song` + corazón en filas y Now Playing + playlist virtual "Favoritas").
2. **Historial y contadores** (tabla `play_history`; registrar desde `PlayerController`; fila "Recientes" en Biblioteca).
3. **Búsqueda + ordenación en Biblioteca** (campo filtro en vivo + menú ordenar: fecha/título/artista/duración).
4. **Badge de calidad** (leer códec/bitrate/sampleRate con `MediaExtractor` al terminar la descarga, guardarlo en `Song`, chip "OPUS · 160 kbps · 48 kHz" en Now Playing — patrón Amazon "Currently playing at").
5. **Sleep timer con fade-out** (temporizador 5–60 min o "fin de canción", bajando volumen los últimos segundos — carencia real de Apple Music).
6. **Color dinámico** (Palette API sobre la carátula → fondo degradado del Now Playing y mini player).

### Fase 2 — Las funciones "wow" ✅ COMPLETADA (v1.2, 2026-07-03)
7. **Cola de reproducción**: pantalla de cola desde Now Playing, "reproducir a continuación" y "añadir a la cola" en todos los menús contextuales, drag & drop y eliminar (espejo de la cola real de ExoPlayer).
8. **Letras sincronizadas**: descargar `.lrc` de LRCLIB (API gratuita, sin key) al bajar cada canción; vista de letras con auto-scroll y salto al tocar línea; fallback a letra plana.
9. **Drag & drop en playlists** (`position` ya existe en el esquema; UI con `detectDragGesturesAfterLongPress` o librería reorderable).
10. **Android Auto**: migrar `PlaybackService` a `MediaLibraryService` exponiendo árbol Biblioteca/Playlists/Favoritos.
11. **Auto-playlists locales**: "Más escuchadas", "Olvidadas" (sin reproducir >30 días), "Añadidas recientemente" — se alimentan del historial de Fase 1.

### Fase 3 — Diferenciadores premium ✅ COMPLETADA (v1.3, 2026-07-03)
*Notas de implementación: el crossfade es un fundido de salida/entrada en los bordes de pista (ExoPlayer no mezcla dos streams); la normalización mide RMS real por decodificación PCM (MediaCodec) al descargar y atenúa hacia -14 dB (nunca amplifica). El editor de tags edita la BD, no reescribe los archivos.*
12. **Crossfade 0–12 s** (segundo ExoPlayer + fundido de volumen; toggle en ajustes — superaríamos a Amazon, que no lo tiene).
13. **Normalización de volumen** (escaneo EBU R128 con el ffmpeg ya empaquetado al terminar cada descarga → guardar gain → `setVolume` por pista, tres niveles como Spotify).
14. **Replay/estadísticas** (pantalla con minutos escuchados, top canciones/artistas por mes/año — 100 % local).
15. **Smart playlists por reglas** (constructor: género/año/contador/favorito/fecha; evaluadas en vivo con Room — nadie del top 3 lo ofrece bien en móvil).
16. **Metadatos ricos + almacenamiento**: guardar álbum/año desde yt-dlp, editor de tags in-app (supera a Apple, que solo edita en escritorio), panel de GB usados.

## 4. Referencias
Informes completos por plataforma generados por los subagentes (Spotify Newsroom/Support, Apple Support/Newsroom, Amazon Help, prensa especializada). Destacados:
- Spotify: lossless llegó en sept 2025; carpetas de playlists y cola con selección múltiple en mayo 2026.
- Apple: crossfade 1–12 s, favoritos con playlist automática, Replay mensual, AutoMix (iOS 26).
- Amazon: badge SD/HD/Ultra HD con panel "Currently playing at" (el patrón a copiar), X-Ray, Car Mode con auto-arranque por Bluetooth.
