<div align="center">
<h1>Rivo</h1>
<p><strong>Una app de YouTube nativa para Android con alma de terminal.</strong></p>
<p>Una lista filtrable por nivel · reproducción en segundo plano · descargas que sobreviven al cierre de la app</p>
<p>
<a href="https://github.com/iseaprojects/rivo/actions/workflows/android.yml"><img src="https://github.com/iseaprojects/rivo/actions/workflows/android.yml/badge.svg?branch=main" alt="CI"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/licencia-MIT-2ac3de?style=flat-square" alt="Licencia MIT"></a>
<a href="#de-un-vistazo"><img src="https://img.shields.io/badge/Android-8.0%2B-2ac3de?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+"></a>
<a href="#de-un-vistazo"><img src="https://img.shields.io/badge/Kotlin-2.4-2ac3de?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin 2.4"></a>
<a href="#de-un-vistazo"><img src="https://img.shields.io/badge/Compose-Material%203-2ac3de?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Compose Material 3"></a>
<a href="#compilar"><img src="https://img.shields.io/badge/versi%C3%B3n-0.8.7--rivo.2-2ac3de?style=flat-square" alt="Versión 0.8.7-rivo.2"></a>
</p>
<p><a href="README.md">English</a> · <a href="README.es.md">Español</a></p>
</div>

Rivo es un port nativo a Android de [yt-x](https://github.com/Benexl/yt-x) 0.8.7 — el navegador
de YouTube para terminal construido sobre `fzf`, `yt-dlp` y `mpv`. No es un envoltorio del script:
el modelo de menús anidados, el formato de extensiones declarativas, el archivo de configuración
`CONFIG_*` y la gramática del filtro de búsqueda están reimplementados en Kotlin + Jetpack
Compose, y la reproducción usa Media3/ExoPlayer en lugar de mpv.

La idea del port es conservar lo que hace bueno a yt-x — navegación por teclado, una lista
filtrable por nivel, vistas previas, menús de acciones por elemento, configuración en texto
plano — y arreglar lo que una terminal no puede hacer en un teléfono: audio en segundo plano,
descargas que sobreviven al cierre de la app, una sesión multimedia y vídeo DASH con audio.

## Por qué Rivo

- **El modelo mental de yt-x, nativo.** Cada pantalla es el mismo `MenuScreen<T>`: filtro siempre
  enfocado, vistas previas, paginación explícita, migas de pan y los atajos originales. Ningún
  menú es una lista hecha a mano, así que todos filtran, paginan y previsualizan igual.
- **Una interfaz, dos extractores.** NewPipeExtractor responde por YouTube al instante; el yt-dlp
  incluido cubre el resto de sitios y el conjunto completo de opciones. Los errores son tipados,
  no un genérico "error".
- **La reproducción sobrevive a la pantalla.** Un `MediaSessionService` es dueño del reproductor,
  así que pantalla de bloqueo, notificación, Bluetooth, PiP y audio en segundo plano manejan la
  misma sesión.
- **Las descargas son una función real.** Cola persistente, servicio en primer plano, reanudación
  por rangos, destinos MediaStore/SAF/directorio y muxing MP4 sin FFmpeg.
- **La configuración es un archivo.** Las mismas claves `CONFIG_*` que el original, editables en
  la app o por SAF, con comentarios preservados y overrides en memoria que nunca lo tocan.
- **Nada oculto.** Sin telemetría, sin autoactualización silenciosa, MIT con el aviso original.

## De un vistazo

| | |
| --- | --- |
| **Plataforma** | Android 8.0+ (`minSdk` 26), `targetSdk` 37 |
| **Stack** | Kotlin 2.4.21 · Jetpack Compose · Material 3 · Media3 1.11.1 · Room · WorkManager |
| **Toolchain** | AGP 9.5.1 con Kotlin integrado · Gradle 9.9.1 · JDK 17 |
| **Idiomas** | Inglés · Español · Portugués (Brasil), más packs `rivo.lang/1` |
| **Temas** | Tokyo Night (por defecto) · Catppuccin Mocha, ampliables con `*.theme.json` |
| **Flavors** | `fast` (NewPipeExtractor) · `ytdlp` (NewPipeExtractor + youtubedl-android) |
| **Licencia** | MIT, con el copyright original preservado |

## Qué hace

| Área | Qué incluye |
| --- | --- |
| **Menús** | Menús anidados filtrables, hoja de vista previa en teléfonos y panel fijo en tablets, paginación explícita `Siguiente`/`Anterior`, pila de migas de pan, acciones por elemento, atajos originales, glifos Nerd Font con alternativa ASCII |
| **Feeds y búsqueda** | Inicio, suscripciones, tendencias, Ver más tarde, Me gusta, historial, clips; búsqueda de vídeos/listas/canales/películas con los filtros `sp` de YouTube, sugerencias en vivo y búsqueda dentro de un canal |
| **Extracción** | `VideoExtractor` sobre NewPipeExtractor y youtubedl-android, errores tipados (`PRIVATE`, `AGE_RESTRICTED`, `DRM_PROTECTED`, `EXTRACTOR_BROKEN`, …), re-resolución de URLs firmadas, cookies desde `cookies.txt` o el WebView integrado, almacén cifrado y tokens PO |
| **Reproducción** | Progresivo, DASH y HLS; audio en segundo plano; `MediaSessionService`; PiP; subtítulos; capítulos; SponsorBlock; velocidad, calidad y bucle A-B; mapa de "más reproducido"; colas desde vídeos, listas, mixes y canales; salida a reproductor externo |
| **Descargas** | Colas de vídeo, solo audio y listas/canales completos; tareas pausadas, reanudadas, reintentadas y canceladas; MediaStore, árbol SAF o directorio simple; muxing MP4 con `MediaMuxer`; archivo de descargas; reproducción local, compartir y abrir con |
| **Biblioteca** | Recientes, vídeos guardados, listas propias, listas remotas guardadas, historial de búsqueda y suscripciones en Room, con ids por proveedor |
| **Configuración** | Ajustes en GUI y editor de texto sobre el mismo `files/config`; tema, idioma, paginación, cookies, extractor, retención de caché, extensiones y actualizaciones |
| **Extensiones** | Parches de menú `rivo.ui/1`, idiomas `rivo.lang/1`, paletas `rivo.theme/1`, documentos `.sites` y `.cmds` cargados desde una carpeta SAF; solo datos, nunca código (D43) |
| **Superficies Android** | Quick Settings, widget de recientes de cuatro filas, atajos estáticos y dinámicos, broadcast de Tasker, enlaces `rivo://`, proveedor de documentos, reglas de backup y transferencia |
| **Actualizaciones** | Chequeo de releases de GitHub con comparación de versiones punteadas, por canal (`stable`/prerelease), sin instalar APK: abre la página del release (D45) |

```
Tu Feed · Buscar · Feed de Suscripciones · Tendencias · Ver Más Tarde · Vídeos que me gustan
Historial de Reproducción · Clips · Listas de Reproducción
Listas de Reproducción Personalizadas · Vídeos guardados · Recientes
Canales · Misceláneo
```

## Compilar

Requisitos: JDK 17, Android SDK 37 (Build Tools 36+), Gradle 9.9.1. El wrapper de Gradle,
`local.properties` y el SDK son la única configuración local; nada más depende de la máquina.

```bash
./gradlew :app:assembleFastDebug     # solo NewPipeExtractor
./gradlew :app:assembleYtdlpDebug    # + youtubedl-android (CPython, aria2)
./gradlew testFastDebugUnitTest      # 344 casos (337 offline + 7 live opcionales)
./gradlew :app:lintFastDebug
```

Artefactos actuales: `app-fast-debug.apk` 28.12 MB, `app-ytdlp-debug.apk` 105.50 MB y
`app-fast-release-unsigned.apk` 3.82 MB (R8 + reducción de recursos).

> `ProcessNavigationXmlTask` de AGP 9.5.1 todavía no es serializable por Gradle 9.9, así que
> `org.gradle.configuration-cache` está desactivado en `gradle.properties`. Reactívalo cuando
> las notas de AGP incluyan ese arreglo.

Las versiones son `<base upstream>-rivo.<revisión>` (`0.8.7-rivo.2` en esta compilación). El
chequeo de actualizaciones compara la etiqueta completa, incluidos los identificadores de
pre-lanzamiento con puntos, así que subir la revisión es una actualización real. El chequeo usa
la API pública de GitHub sin token: un repositorio **privado** responde `404` y la pantalla
muestra `Check failed`; empieza a funcionar cuando el repositorio sea público o la API devuelva
una lista de releases.

### Firma

La firma de release se lee del entorno, así que ningún keystore se guarda en el repositorio:

```bash
RIVO_KEYSTORE=/ruta/release.jks RIVO_KEYSTORE_PASSWORD=… RIVO_KEY_ALIAS=… RIVO_KEY_PASSWORD=… \
  ./gradlew :app:assembleFastRelease
```

`tools/release.sh` envuelve eso y lee los mismos cuatro valores de un `keystore.properties`
ignorado cuando las variables no están definidas (`tools/release.sh ytdlp`,
`tools/release.sh fast --unsigned` para comprobar sin claves).

### Flavors

| Flavor | Extracción | Uso |
| --- | --- | --- |
| `fast` | NewPipeExtractor v0.27.1 | Por defecto. Búsqueda en menos de un segundo, APK pequeño, solo YouTube. |
| `ytdlp` | NewPipeExtractor + youtubedl-android 0.18.1 | Sitios fuera de YouTube, capítulos, paridad completa de opciones de yt-dlp, autoactualización. |

El flavor `ytdlp` incluye **yt-dlp 2025.11.12** (y yt-dlp-ejs 0.3.1) dentro de
youtubedl-android, que conserva su nombre histórico de librería; es el yt-dlp mantenido, no el
youtube-dl discontinuado. `CONFIG_EXTRACTOR` elige `auto` (por defecto: NewPipe para YouTube,
yt-dlp para el resto), `newpipe` o `ytdlp`, y `CONFIG_YTDLP_CHANNEL` selecciona el feed de
releases que usa la actualización interna, cuya acción y canal viven en la pantalla del
extractor. YouTube con el extractor incluido es el único camino que necesita esa actualización
(o `CONFIG_PO_TOKEN`) para pasar el `403` del CDN a finales de 2026; `auto` lo evita con
NewPipe. La misma pantalla expone la acción de actualizar y el canal
`stable`/`nightly`/`master`.

`youtubedl-android` se resuelve desde Maven Central (`io.github.junkfood02.youtubedl-android`,
el fork mantenido); el repositorio JitPack del original dejó de publicar después de 0.14.0 y
sus etiquetas `0.18.x` no compilan allí.

El flavor `ytdlp` deliberadamente **no** incluye FFmpegKit. Las descargas que necesitan unir
pistas se remuxean con el `MediaMuxer` de la plataforma (vídeo MP4 progresivo + audio MP4, sin
recodificar), lo que mantiene el APK pequeño y evita el coste de licencia y tamaño de un FFmpeg
completo; los manifiestos HLS/DASH que no se pueden bajar como un solo archivo se delegan al
yt-dlp incluido.

### Fuente

Los glifos Nerd Font son opcionales. Copia cualquier fuente parcheada como
`app/src/main/assets/fonts/*.ttf` (por ejemplo `JetBrainsMonoNerdFont-Regular.ttf`) y la app la
toma; sin ella todos los menús siguen funcionando con los iconos ASCII de reserva. El binario de
la fuente está ignorado por git.

## Enlaces profundos

Todo lo que entra desde Android pasa por un solo router: enlaces `rivo://`, texto compartido,
intents `VIEW` de URLs de YouTube, broadcasts de Tasker y atajos.

| Enlace | Abre |
| --- | --- |
| `rivo://` | Menú principal |
| `rivo://search?q=…&type=video\|playlist\|channel\|shorts\|movie` | Búsqueda, opcionalmente acotada |
| `rivo://feed?kind=subscriptions\|trending\|watch-later\|liked\|history\|clips` | Feed |
| `rivo://saved` · `rivo://recent` | Biblioteca |
| `rivo://downloads` | Cola de descargas y archivos |
| `rivo://misc` · `rivo://config` | Varios y configuración |
| `rivo://channels` · `rivo://channel?name=…&tab=…` | Canales |
| `rivo://playlist?name=…&id=…` · `rivo://custom-playlist?name=…` | Listas |
| `rivo://custom-cmd?name=…` | Un comando personalizado guardado |
| `rivo://watch?v=…` · `rivo://media?url=…` | Acciones de un vídeo o de cualquier URL soportada |

`exit=true` cierra el reproductor o la pantalla del comando al terminar; `playlist-skip=true`
empieza una lista por el segundo elemento. La automatización puede pasar los mismos overrides
como extras del intent (`RIVO_ACTION`, `RIVO_PLAY`, `RIVO_LISTEN`, `RIVO_DOWNLOAD`, `RIVO_INPUT`,
`RIVO_PER_PAGE`, `RIVO_CONFIG`), y una tarea de Tasker emite
`io.github.iseaprojects.rivo.action.TASKER_FIRE` con `rivo_uri`. Los overrides se aplican en
memoria y nunca se escriben en `files/config`.

## Cómo funciona

```
MainActivity ──intent──▶ LaunchSpecParser ──▶ LaunchInbox ──▶ LaunchRouter
                                                                    │
                                                          MenuNavController
                                                                    │
                                             MenuEngine ──▶ MenuCatalog (+ parches .ui.json)
                                                  │                 │
                                            handlers          lista de MenuNode localizada
                                                  │                 │
                                                  └────────▶ MenuScreen<T> (catálogo + medios)
```

- **Los menús son datos.** Un `MenuNode` declara sus claves de etiqueta e icono y un menú hijo,
  una lista `children` en línea o un id de handler. `MenuEngine` construye → filtra → ordena →
  inyecta → localiza, el equivalente directo de las funciones `_menu_*` de yt-x más los
  argumentos `[sort]`, `filter_regex`, `extra_actions` y `handler` de una extensión `.ui`.
- **La configuración es un archivo.** `filesDir/config` sigue siendo la única fuente de verdad,
  con los nombres `CONFIG_*` originales, comentarios preservados al escribir y claves faltantes
  añadidas al arrancar. La pantalla de ajustes y el editor de texto escriben los mismos bytes, y
  el proveedor de documentos integrado lo expone por SAF.
- **Todo es una sola actividad.** Cada nivel es la misma pantalla de lista de entradas, así que
  la navegación es una pila de `MenuFrame`s, no un grafo de composables.
- **La biblioteca es Room; el resto son archivos.** Vídeos guardados, recientes, suscripciones,
  listas propias, listas remotas guardadas e historial viven en una base Room de siete tablas con
  ids por proveedor. Los menús ven una instantánea inmutable (`MenuLibrary`) en lugar de
  consultar, así que "Guardar" o "Quitar" es una decisión síncrona que reetiqueta todas las filas
  a la vez, y un diálogo Prompt/Confirm reenvía el mismo handler con la respuesta en vez de
  guardar estado de "espera".
- **La extracción es una interfaz con dos backends.** `VideoExtractor` devuelve modelos de la
  app, nunca objetos del extractor, así que las listas sobreviven a la muerte del proceso y la UI
  nunca sabe qué backend respondió. `auto` manda YouTube a NewPipeExtractor y el resto a yt-dlp;
  un parseo roto de NewPipe se reintenta una vez con yt-dlp, y las URLs firmadas se vuelven a
  resolver cuando expiran. Los fallos son tipados para que las pantallas expliquen una negativa
  en vez de mostrar "error".
- **La reproducción sobrevive a la pantalla.** `PlaybackService` es dueño del `ExoPlayer` y de la
  `MediaSession`; `PlayerActivity` es una vista sobre un `MediaController`, sin peticiones
  propias. Cerrar el reproductor mantiene el audio (`CONFIG_DISOWN_PLAYER`), la notificación y
  los controles Bluetooth son los mismos comandos de sesión, y PiP reparenta un `TextureView`
  que sobrevive al cambio de ventana. La cola se resuelve de forma perezosa tras una pequeña
  ventana de precarga, así que `Reproducir todo` empieza por el primer elemento en vez de
  extraer veintiséis antes.
- **Las cookies son credenciales.** Una importación de `cookies.txt` o la pantalla de inicio de
  sesión integrada llenan un almacén `EncryptedSharedPreferences`; las cookies se adjuntan solo
  por origen, y solo con `CONFIG_COOKIES_ENABLED` activo. Los tokens PO son un valor de
  configuración, traducido al formato de cada backend.

Más detalle: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), decisiones de diseño en
[`docs/DECISIONS.md`](docs/DECISIONS.md), la capa de extracción en
[`docs/EXTRACTORS.md`](docs/EXTRACTORS.md), el mapeo de funciones del original en
[`docs/PARITY.md`](docs/PARITY.md) y la lista de verificación en
[`docs/VERIFICATION.md`](docs/VERIFICATION.md).

## Reproducción: el bug de audio DASH

yt-x en Android reproduce a través de Termux + mpv, y el apaño habitual para el síntoma "se ve el
vídeo pero no se oye" es forzar un formato progresivo (muxeado), lo que limita la calidad a 720p
o menos. El port lo arregla en el origen: el vídeo y el audio DASH se resuelven como flujos
separados y se entregan a ExoPlayer como `MergingMediaSource(video, audio)`, con
`ProgressiveMediaSource` solo para flujos realmente muxeado y `HlsMediaSource` para directo. Eso
significa vídeo 1080p/1440p/4K con audio, subtítulos buscables y duración correcta, sin
recodificar nada.

## Uso previsto

Rivo es un cliente para contenido que el usuario ya tiene permitido ver: YouTube público,
PeerTube autoalojado, Dailymotion y cualquier sitio que soporte yt-dlp. Está pensado para uso
personal: navegar suscripciones, guardar vídeos para verlos sin conexión en el transporte,
escuchar pódcasts y música en segundo plano.

No salta DRM, muros de pago, controles de edad ni restricciones regionales, y reporta esos casos
como errores tipados (`Availability.DRM_PROTECTED`, `AGE_RESTRICTED`, `GEO_BLOCKED`) en lugar de
intentar rodearlos. Las cookies existen para que un usuario con sesión iniciada vea sus propias
suscripciones y listas, no para suplantar a nadie.

Nada de esto sustituye los términos de servicio de los sitios que lee; esa responsabilidad es de
quien lo ejecuta.

## Distribución

Las políticas de dispositivo y contenido de Google Play hacen inadmisible una app de extractores
de propósito general, así que se distribuye como APK firmado: GitHub Releases en este
repositorio, y repositorios estilo F-Droid que compilan desde el código. La app incluye un
chequeo de actualizaciones que lee los releases de Rivo en GitHub y abre la página del release;
instalar el APK queda en manos del navegador y del instalador del sistema, así que no existe una
autoactualización silenciosa.

## Estado

El port está completo para el alcance F1–F8: todas las pantallas están conectadas, los dos flavors
compilan y la suite cubre configuración, menús, rutas de arranque, temas, extracción y biblioteca.

<details>
<summary><strong>Historial de fases (F1–F8)</strong></summary>

| Fase | Alcance | Estado |
| --- | --- | --- |
| **F1** | Arquitectura, build, manifest, motores de config/i18n/tema, menús declarativos, cargador de extensiones, launcher/enlaces, proveedor de documentos | entregada |
| **F2** | Router `VideoExtractor`, backends NewPipeExtractor + youtubedl-android, fallos tipados, re-resolución de URL, cookies, tokens PO | entregada |
| **F3** | `MenuScreen<T>` genérico: filtro difuso, panel/hoja de vista previa, paginación, listas de medios | entregada |
| **F4** | Biblioteca Room: recientes, guardados, listas propias, historial de búsqueda, suscripciones | entregada |
| **F5** | Reproductor: Media3 progresivo/DASH/HLS, audio en segundo plano, MediaSessionService, PiP, subtítulos, SponsorBlock | entregada |
| **F6** | Descargas: destinos MediaStore/SAF/directorio, cola persistente, servicio en primer plano, reanudación, muxing MP4, archivo, reproducción local | entregada |
| **F7** | Editor de ajustes, extensiones desde SAF, tile/Tasker/widget/atajos, chequeo de actualizaciones | entregada |
| **F8** | Cierre de paridad, tests, lista de verificación | entregada |

F1 compilaba como app de menús completa: arrancaba, pintaba el menú principal, navegaba, editaba
configuración, cambiaba de tema e idioma y respondía a enlaces `rivo://`. Los menús de fases
posteriores (reproducción, descargas) resolvían a una pantalla que decía qué acción faltaba
conectar; esas pantallas se volvieron reales en F5 y F6.

F2 añadió la capa de extracción que consumen las fases siguientes: una interfaz, dos backends,
fallos tipados, refresco de URLs firmadas y manejo de cookies/tokens PO. F3 reemplazó el bucle de
terminal por el armazón de listas: filtro siempre enfocado, filas Nerd Font, paginación explícita,
hoja de vista previa en teléfonos y los atajos originales. F4 le dio memoria a la app: Room guarda
vídeos, recientes, suscripciones, listas e historial, y los menús deciden desde una instantánea
para reetiquetar al instante. F5 la convirtió en reproductor: un `MediaSessionService` tras una
superficie a pantalla completa, controles superpuestos, mapa de calor, capítulos, SponsorBlock y
colas perezosas que re-resuelven URLs expiradas. F6 convirtió las descargas en función: una cola
persistente trabajada por un servicio en primer plano, tres tipos de destino, muxing con
MediaMuxer, reanudación por rangos y archivo. F7 convirtió la configuración restante en pantallas
y añadió tile, widget, atajos y las entradas de Tasker.

Las limitaciones de F7 son deliberadas: las extensiones son datos y no pueden incluir código; las
opciones de yt-dlp de los comandos personalizados se entregan solo a Termux (`YT_X_YTDLP_OPTS`) y
nunca evitan la interfaz del extractor; el chequeo de actualizaciones nunca instala un APK; y un
broadcast de Tasker abre la UI directamente solo si Rivo tiene "Mostrar sobre otras apps"; si no,
publica una notificación que abre el mismo enlace (y se registra y descarta si las notificaciones
también están apagadas).

</details>

## Estructura del repositorio

```
.
├── README.md                       versión en inglés
├── README.es.md                    este archivo
├── CONTRIBUTING.md                 matriz de build y reglas de revisión
├── CHANGELOG.md                    historial de versiones
├── SECURITY.md                     reportes privados de vulnerabilidades
├── LICENSE                         MIT, aviso original preservado
├── tools/check_strings.py          cobertura de TXT_* (debe dar 0 faltantes)
├── tools/release.sh                build de release firmado desde keystore.properties
├── .github/workflows/android.yml   CI: tests, lint, ambos APK debug
├── build.gradle.kts                build raíz (solo plugins)
├── settings.gradle.kts             repositorios, lista de módulos
├── gradle.properties               JVM args, AndroidX, R no transitivo
├── gradle/
│   ├── libs.versions.toml          todas las versiones fijadas
│   └── wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts            flavors, KSP, buildConfig, dependencias
│   ├── proguard-rules.pro          reglas keep para serialización y extractores
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml componentes declarados por fase (F5-F7 añaden los suyos)
│       │   ├── assets/
│       │   │   ├── config.default              valores CONFIG_* por defecto
│       │   │   └── extensions/
│       │   │       ├── langs/en|es|pt-BR.lang.json
│       │   │       └── themes/tokyo-night|catppuccin-mocha.theme.json
│       │   ├── java/io/github/iseaprojects/rivo/
│       │   │   ├── RivoApp.kt                   Application + AppGraph
│       │   │   ├── core/
│       │   │   │   ├── config/                 ConfigKeys, ConfigParser, ConfigStore, ConfigSnapshot
│       │   │   │   ├── i18n/Strings.kt         bundles de idioma, resolución de locale
│       │   │   │   ├── launch/                 LaunchSpec, LaunchRouter, LaunchInbox
│       │   │   │   ├── menu/                   MenuNode, catálogo, motor, navegador, parches, iconos
│       │   │   │   ├── menu/extension/         cargador rivo.ui/1, carpeta SAF, sitios/comandos
│       │   │   │   ├── update/                 feed de releases de GitHub, comparación de versiones
│       │   │   │   ├── model/Media.kt          MediaSummary, Playlist/Channel, FeedKind, PageInfo
│       │   │   │   ├── model/LibraryKeys.kt    identidad por proveedor, recuerdo `!N`
│       │   │   │   └── theme/ThemeSpec.kt      modelo de tema + parseo hex
│       │   │   ├── data/
│       │   │   │   ├── extract/                VideoExtractor, router, backend NewPipe, mapper yt-dlp
│       │   │   │   ├── library/                entidades Room, DAO, repositorio, mapeo
│       │   │   │   └── net/                    almacén de cookies, cookies.txt, token PO, OkHttp
│       │   │   ├── di/AppGraph.kt              DI manual
│       │   │   ├── menu/BuiltInMenus.kt        menús principal, varios y configuración
│       │   │   ├── menu/MediaMenus.kt          acciones por elemento, canal, lista y biblioteca
│       │   │   ├── menu/Glyphs.kt              glifos Nerd Font literales
│       │   │   ├── menu/handlers/              navegación, config, URL, compartir, biblioteca
│       │   │   ├── platform/                    notificaciones, tile, receiver, widget, atajos
│       │   │   ├── provider/RivoDocumentsProvider.kt
│       │   │   └── ui/
│       │   │       ├── MainActivity.kt         actividad única, entrada de intents
│       │   │       ├── RivoViewModel.kt         pila de vuelta + inbox ante rotación
│       │   │       ├── RivoRoot.kt              cableado de estado, atrás, snackbars, diálogos
│       │   │       ├── login/WebLoginActivity.kt  inicio de sesión WebView para cookies
│       │   │       ├── screens/MenuScreen.kt     armazón de lista: filtro, paginador, vista previa, teclas
│       │   │       ├── screens/PromptDialogs.kt  prompt de texto + confirmación destructiva
│       │   │       ├── media/                   pantallas de extractor y biblioteca
│       │   │       └── theme/Theme.kt, Fonts.kt
│       │   └── res/                            strings del sistema, temas, iconos, atajos, widget
│       └── test/java/io/github/iseaprojects/rivo/          tests de config, menú, launch, tema, extractor, biblioteca
└── docs/
    ├── ARCHITECTURE.md
    ├── DECISIONS.md
    ├── EXTRACTORS.md
    ├── PARITY.md
    ├── VERIFICATION.md
    └── extensions/                             ejemplos de extensiones anotados
```

## Proyecto

Rivo se desarrolla en [iseaprojects/rivo](https://github.com/iseaprojects/rivo). Ahí viven los
issues y los releases; [CONTRIBUTING.md](CONTRIBUTING.md) tiene la matriz de build y las reglas
que no se negocian. `yt-x` en este repositorio siempre nombra al proyecto original de terminal;
`Rivo` es la app Android. Rivo no está afiliado a YouTube ni al proyecto original.

## Licencia

MIT, igual que el yt-x original. El aviso de copyright se conserva:

```
MIT License

Copyright (c) 2024 Benexl
Copyright (c) 2026 Rivo contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
