# Plan de diseño — PrivateMusic Desktop

## 1. El diagnóstico: en qué se equivoca hoy nuestra ventana

Nuestra ventana está construida como una app de móvil estirada, y se nota en cuatro decisiones concretas del código:

- **`PlayerScreen` es un overlay que TAPA todo y `RETORNA`.** En `App.kt`, si `showPlayer && current != null`, se dibuja `PlayerScreen` a `fillMaxSize` y se abandona el resto del árbol: desaparecen el `NavigationRail` y el `MiniPlayer`. Es decir: **en cuanto pulsas play y abres el reproductor, no puedes buscar la siguiente canción, ni ver la cola, ni navegar a un álbum.** Este es el pecado capital. Las 6 apps analizadas en detalle lo resuelven al revés: el "ahora suena" es un panel o un estado del layout, nunca una pantalla que roba la navegación.

- **El `MiniPlayer` es un mini-reproductor de móvil, no una barra de escritorio.** `Cover` 44dp, `LinearProgressIndicator` de **2dp** (Spotify, Apple, Feishin y foobar coinciden: la barra de progreso fina es "apenas visible y no permite seek preciso"), sin volumen, sin shuffle, sin repeat, sin cola, sin tiempos. Desperdicia todo el ancho de una ventana de escritorio.

- **El `NavigationRail` de 4 iconos no escala.** Cuando lleguen Álbumes, Artistas, Canciones, Buscar, Favoritas e Historial, cuatro ranuras fijas se quedan cortas. Además `PlaylistsScreen` es una **lista plana que ignora `folderId` e `isPinned`**, datos que YA existen en el modelo.

- **`LibraryScreen` es una `LazyColumn` de `SongRow` con carátula 48dp por fila, sin columnas, sin orden, sin selección múltiple.** Para una biblioteca grande es justo lo contrario de lo que hacen foobar (17-19px/fila, 40-50 filas visibles), Apple (tabla estilo iTunes) y Feishin (filas conmutables 40/64/88px). Mostramos ~12 canciones donde cabrían 25-30.

- **Datos ricos sin usar.** `play_history` está poblada por `DesktopPlayer` y el DAO ya expone `observeMostPlayed`, `observeForgotten`, `topArtists`, `statsTotals`… y el desktop **no consume ni una**. Tenemos `bpm`, `camelot`, `loudnessDb`, `isFavorite`, `codec`/`bitrateKbps` y no hay ni vista de Favoritas ni de calidad.

## 2. Las convenciones que rompemos (no son opiniones)

Cuando 5-6 clientes de escritorio independientes coinciden, es una convención del medio, no un gusto:

1. **Barra de reproducción fija a lo ancho de toda la ventana** (o arriba en Apple Music). Spotify, YTM, Feishin (90px), foobar (toolbars) y Apple (52px arriba) la tienen; nunca hace scroll ni se oculta. Nosotros la matamos al abrir el reproductor.
2. **El "Now Playing" es un panel/estado, no un overlay de navegación.** Spotify (sidebar derecho), Apple (barra superior + panel), YTM (expandible con pestañas), Feishin (overlay con store propio, no ruta), foobar (panel acoplado), Plexamp (modal criticado justamente por tapar la navegación). Todos conservan el acceso a la biblioteca.
3. **Búsqueda global separada del filtro por vista.** Spotify (Ctrl+K global vs Ctrl+F filtro), Apple (buscador vs "campo de filtro"), foobar (F3 biblioteca vs Ctrl+F playlist), Feishin (paleta de comandos vs filtro regex). Nosotros solo tenemos el filtro local dentro de Biblioteca y lo llamamos "búsqueda".
4. **Sidebar redimensionable de dos estados (rail de iconos ↔ expandido) con las playlists listadas inline.** Spotify, Apple, YTM y Feishin (80px ↔ 260-400px). Nuestro rail es de ancho fijo y manda las playlists a otra pestaña.
5. **Atajos de teclado globales.** Todas los documentan (Space, flechas, Ctrl+K/F). Nosotros: cero.
6. **Vista de álbum y de artista con cabecera grande + tracklist.** Spotify, Apple, YTM, Feishin. Nosotros: lista plana única.

## 3. Ola 1 — La estructura (la sensación de "app de escritorio de verdad")

El objetivo de esta ola: que el reproductor deje de secuestrar la ventana y que la barra inferior sea de escritorio. Es lo de mayor impacto x frecuencia.

### 1.1 — Reescribir el layout raíz: barra global fija + panel Now Playing hermano
**Qué:** En `App.kt`, eliminar la rama que hace `PlayerScreen(...); return`. El árbol pasa a ser siempre:
```
Column(fillMaxSize)
├─ Row(weight 1f)
│  ├─ Sidebar (ver 2.1)
│  ├─ Box(weight 1f)  ← contenido / páginas
│  └─ NowPlayingPanel  ← AnimatedVisibility, 320dp, redimensionable (300-360dp), persistido en settings
└─ PlayerBar(fillMaxWidth)  ← barra global, SIEMPRE visible si hay canción
```
`showPlayer` deja de ser "pantalla completa" y pasa a togglear el panel derecho.
**Dónde:** `App.kt` (layout), nuevo composable `NowPlayingPanel` reciclando el contenido de `PlayerScreen.kt` (carátula ~260dp en vez de 320, título, artista, badge codec).
**Coste:** alto — es el cambio estructural. **Respaldo:** Spotify (sidebar derecho Now Playing, "paneles hermanos, no overlays"), Feishin (`full-screen-player.store`, estado del layout no ruta), foobar (panel persistente), Plexamp (idea explícita: "evitar su error, que el rail siga accesible").

### 1.2 — `PlayerBar`: barra de reproducción de 3 zonas, 80dp
**Qué:** Sustituir `MiniPlayer` por `PlayerBar`, `Surface` `fillMaxWidth`, altura **80dp**, `Row` de tres pesos:
- **Izquierda (weight 1f):** `Cover` **56dp** + título/artista (enlazado al álbum/artista) + corazón favorito.
- **Centro (weight 1f):** shuffle · anterior · **`FilledIconButton` play/pausa 48dp (círculo)** · siguiente · repeat, y debajo `Slider` de progreso con tiempos transcurrido/restante a los lados (adiós al `LinearProgressIndicator` de 2dp).
- **Derecha (weight 1f):** toggle panel Now Playing · botón Cola · slider de volumen (falta hoy) · (hueco futuro Cast).

`DesktopPlayer` ya expone `shuffle`, `setShuffle`, `toggleFavorite`, `seekTo`; el volumen se añade al `AudioEngine`.
**Dónde:** nuevo `PlayerBar` en `Components.kt`, reemplaza `MiniPlayer`.
**Coste:** bajo-medio. **Respaldo:** Spotify (tres tercios, botón central círculo el más grande), Feishin (grid 1fr/1fr/1fr de 90px, colapsa a 0.5/1/0.5 bajo 760px), Apple (transporte + volumen + scrubber).

### 1.3 — Atajos de teclado globales
**Qué:** `Modifier.onPreviewKeyEvent` en la raíz de la ventana: **Space** play/pausa (salvo foco en `TextField`), **Ctrl+←/→** anterior/siguiente, **Ctrl+K** buscar global, **Ctrl+F** filtrar la vista, **toggle panel** derecho, **Esc** cierra el panel Now Playing, **F** favorito.
**Dónde:** `App.kt` / `Main.kt`.
**Coste:** bajo (máximo impacto en "sensación de nativo"). **Respaldo:** las 6 apps. Es la idea marcada "coste bajo" en Spotify, Apple, YTM, foobar y Plexamp — unanimidad.

### 1.4 — Separar búsqueda global de filtro local
**Qué:** El `OutlinedTextField` actual de `LibraryScreen` es un **filtro local** (Ctrl+F) y debe quedarse en la vista. Añadir aparte un buscador global (Ctrl+K) en la cabecera del sidebar que prepare el terreno para la futura pestaña Buscar/descargar (YouTube/Deezer/Internet Archive).
**Dónde:** cabecera del `Sidebar`; `LibraryScreen` conserva su filtro.
**Coste:** medio. **Respaldo:** Spotify (Ctrl+K vs Ctrl+F, explícito), Apple, foobar (F3 vs Ctrl+F), Feishin (paleta de comandos).

## 4. Ola 2 — La biblioteca (álbumes, artistas, densidad, orden)

Todo esto es factible **sin migraciones** según el inventario: `album`, `albumArtist`, `year`, `trackNumber`, `artist`, `isFavorite`, `play_history`, `folderId`, `isPinned` ya existen.

### 2.1 — Sidebar de dos estados con playlists inline
**Qué:** Sustituir el `NavigationRail` por un `Sidebar` con estado **colapsado (rail de iconos 80dp)** ↔ **expandido redimensionable (constreñido 260-360dp)**. En expandido: destinos fijos (Inicio · Biblioteca · Álbumes · Artistas · Favoritas · Historial · Ajustes), buscador global arriba, y **lista de playlists inline** agrupadas por `PlaylistFolder`/`folderId` (carpetas colapsables) con las `isPinned` arriba. Reordenables por drag.
**Dónde:** nuevo `Sidebar` en `App.kt`; consume `observeFolders`, `observePlaylists`, `Playlist.isPinned/folderId` (hoy ignorados). Absorbe la actual `PlaylistsScreen`.
**Coste:** medio. **Respaldo:** Feishin (80↔260-400, playlists inline, carpetas con "/"), YTM (hamburguesa + playlists), Apple (secciones con encabezado), Spotify (pin + drag).

### 2.2 — Tabla de canciones densa, con columnas y altura conmutable
**Qué:** Reemplazar la `LazyColumn` de `SongRow` de `LibraryScreen` por una tabla: `Row` de cabecera **sticky 40dp** con columnas ordenables (**# · Título · Artista · Álbum · Año · Duración · ★**), sobre `LazyColumn` de filas con **altura conmutable en Ajustes: compacta 40dp / normal 64dp / grande 88dp**, sin carátula en modo compacto. Orden por columna (título/artista/álbum/año/añadida; "más escuchada" cruzando con `observePlayCounts` en memoria — el inventario avisa: posible pero código nuevo).
**Dónde:** nuevo `SongTable` en `Components.kt`, usado por `LibraryScreen`, `AlbumScreen`, `ArtistScreen`.
**Coste:** alto. **Respaldo:** Feishin (enum exacto 40/64/88 + cabecera 40), foobar (densidad tabular, "de ~12 a ~25-30 filas"), Apple (tabla iTunes ordenable).

### 2.3 — Vista de Álbum y grid de Álbumes
**Qué:** `AlbumsScreen` = `LazyVerticalGrid` responsivo (celdas ~160dp, columnas por ancho con `BoxWithConstraints`) agrupando `songs` por `album`, carátula desde `artPath`. `AlbumDetail` = cabecera con **`Cover` ~200dp** + metadatos (`albumArtist`, `year`) + botones **Reproducir / Aleatorio**, y `SongTable` ordenada por `trackNumber`. **La cabecera SCROLLEA** (evitar la queja de Apple de cabecera que roba espacio).
**Dónde:** nuevos `AlbumsScreen` / `AlbumDetail`.
**Coste:** alto. **Respaldo:** Apple (cabecera + Reproducir/Aleatorio, "que SÍ scrollee"), Spotify (página detalle), Feishin (grid responsivo AutoSizer).

### 2.4 — Vista de Artista
**Qué:** `ArtistsScreen` agrupando por `artist`/`albumArtist`; `ArtistDetail` con sus álbumes en grid + "populares" (cruzando `topSongs`/play counts). **El nombre del artista en toda `SongRow`/tabla y en el `PlayerBar` es un `Text` clicable** que navega aquí. Gestión de historial atrás/adelante.
**Dónde:** nuevos `ArtistsScreen` / `ArtistDetail`; navegación con back/forward.
**Coste:** alto. **Respaldo:** Spotify (nombre de artista "siempre un enlace", botones atrás/adelante), Apple, YTM.
**Nota de datos:** listar funciona; **biografía e imagen de artista NO** (no hay tabla de artistas → requiere datos nuevos).

### 2.5 — Favoritas e Historial/Replay (fruta madura)
**Qué:** Destino **Favoritas** (`isFavorite`/`favoritesOnce`) y destino **Historial/Replay** consumiendo lo YA escrito en el DAO: "Escuchadas recientemente", "Más escuchadas", "Olvidadas", top artistas/días, `statsTotals`. Hoy el desktop no toca ni una de estas consultas.
**Dónde:** `FavoritesScreen`, `ReplayScreen`.
**Coste:** bajo-medio (las consultas existen y `play_history` ya está poblada). **Respaldo:** nuestro propio inventario (sección 3), coherente con el "descubrimiento" de Plexamp.

## 5. Ola 3 — El carácter (color, cola, interacción)

### 3.1 — Cola con casa: pestañas dentro del panel Now Playing
**Qué:** El `NowPlayingPanel` (1.1) lleva abajo un `TabRow`: **Ahora suena · Cola · Info** (no "Letras" todavía, ver §6). La pestaña **Cola** es un `LazyColumn` con cabecera "Sonando ahora" fija + "A continuación" reordenable por **drag-and-drop** y quitar por botón. `DesktopPlayer` ya tiene `queue`, `index`, `addToQueue`; falta exponer reordenar/quitar.
**Dónde:** `NowPlayingPanel` + `QueueList` en `Components.kt`.
**Coste:** medio-alto (el drag-drop es lo caro). **Respaldo:** YTM (pestañas Próximas/Letras/Relacionado dentro del player, "la cola por fin tiene casa"), Spotify (cola a la derecha), Feishin (panel de cola acoplable), Plexamp (cola por scroll con analizador).

### 3.2 — Fondo UltraBlur derivado de la carátula
**Qué:** Detrás del `NowPlayingPanel` (y luego de `AlbumDetail`/`ArtistDetail`), pintar un `Box` con gradiente muy desenfocado y oscurecido a partir de 2-3 colores dominantes muestreados del `ImageBitmap` de la carátula (en JVM no hay `androidx.palette`: muestrear la mitad de píxeles y cachear por `songId`). Encaja con nuestro `Theme` oscuro OLED existente.
**Dónde:** nuevo `UltraBlurBackground`, usado por el panel y las vistas de detalle.
**Coste:** medio. **Respaldo:** Plexamp (es SU seña de identidad, "cada canción cambia el ambiente"; da carácter sin coste de contenido).

### 3.3 — Menú contextual y selección múltiple
**Qué:** `ContextMenuArea`/`DropdownMenu` (clic derecho) en toda fila de `SongTable` y en los metadatos del `PlayerBar`: **añadir a la cola · añadir a playlist · ir al álbum · ir al artista · favorito**. Selección múltiple con **Ctrl** (individual), **Shift** (rango), teclado (flechas navegan, Enter reproduce, Space selecciona) para encolar/borrar en lote.
**Dónde:** `SongTable` + composable `SongContextMenu` reutilizable.
**Coste:** medio. **Respaldo:** Plexamp (navegación a álbum/artista vive en el menú contextual), Feishin (Ctrl/Shift+click, flechas/Enter/Space exactos), foobar.

### 3.4 — Controles contextuales al hover + barra de estado
**Qué:** (a) En la carátula del `NowPlayingPanel`, mostrar controles con fade-in solo al `hover` (`onPointerEvent` Enter/Exit sobre alpha), clic en carátula alterna carátula↔(futura letra/visualizador). (b) Barra de estado de una línea (12sp, ~24dp) entre la tabla y el `PlayerBar`: "N canciones · duración total de la selección · codec/bitrate de lo que suena" (tenemos `codec`, `bitrateKbps`, `sampleRateHz`).
**Dónde:** `NowPlayingPanel`; nuevo `StatusBar`.
**Coste:** bajo. **Respaldo:** Plexamp (controles que desaparecen), foobar (barra de estado densa "gratis", justifica la selección múltiple).

## 6. Descartado y por qué

- **Letras sincronizadas** (Spotify, YTM, Plexamp, foobar las piden): **requiere migración.** El inventario es claro: no hay campo de letra en `Song` ni entidad de letras en el esquema desktop, pese a que la app Android las tiene (v1.63). Dejamos hueco en la pestaña "Info" del panel, pero no se implementa sin añadir columna/entidad + relleno.
- **Vista/filtro por Género** (Apple column browser, foobar Facets): **requiere migración.** No existe columna de género en `Song`; exige columna nueva + migración + relleno.
- **Ejecutar Playlists inteligentes:** mostrar la lista es trivial (`observeSmartPlaylists`), pero **el motor `SmartRuleEngine` no está en el módulo desktop.** Ejecutarlas requiere localizarlo (¿`app/` Android?) y portarlo/compartirlo. Fuera de esta tanda hasta verificar dónde vive.
- **Biografía e imagen de artista** (Spotify "About the artist", tour dates, merch): no hay tabla de álbumes ni de artistas, se derivan agrupando `Song`. Listar sí; metadatos propios de artista, no, sin añadir datos.
- **Escribir tags de vuelta al fichero** (editar album/year en disco): el DAO solo toca la BD; sin librería de tagging queda fuera de alcance.
- **Cola sincronizada móvil↔PC:** declarada objetivo futuro en `DesktopPlayer`; hoy la cola es local. La vista de cola (§3.1) es local y suficiente; la sincronización es otra fase.
- **No copiar el error de Plexamp/overlay:** el modelo "el player tapa todo" es precisamente lo que abandonamos en la Ola 1; se descarta como patrón aunque estéticamente sea vistoso.
- **No copiar la rigidez de foobar:** su falta de responsive (paneles que no colapsan) es la "lección inversa". Definimos breakpoint explícito (~760dp, como Feishin): bajo ese ancho el `PlayerBar` pasa a pesos 0.5/1/0.5 y el panel derecho se cierra antes que el sidebar.

---

**Orden recomendado por (impacto x frecuencia) / coste:** 1.3 atajos (bajo) → 1.2 PlayerBar (bajo-medio) → 1.1 panel Now Playing (alto, desbloquea todo) → 2.5 Favoritas/Replay (bajo, datos gratis) → 2.1 Sidebar (medio) → 3.2 UltraBlur (medio, carácter barato) → 2.2 tabla densa (alto) → 3.1 cola (medio-alto) → 2.3/2.4 álbum/artista (alto) → 3.3/3.4 (interacción).