# Provider Kotlin: AnimeUnity e MovyITA

Porting nativo Kotlin/JVM di [animeunity.js](../preview/animeunity.js) e
[movy.js](../preview/movy.js). Non usa JavaScript, WebView, Node, Hermes o QuickJS
durante l'esecuzione. Gli originali JS restano invariati.

Questo progetto produce **una libreria**, non un APK o un server gia avviabile.
Le classi possono essere integrate nell'app Android oppure nel backend JVM.

## File principali

- [AnimeUnityProvider.kt](src/main/kotlin/it/blvcktop/providers/AnimeUnityProvider.kt):
  mapping TMDB/IMDb/Kitsu/MAL/AniList/AniDB, episodi e paginazione,
  parsing AnimeUnity/VixCloud, qualita HLS e header di riproduzione.
- [MovyProvider.kt](src/main/kotlin/it/blvcktop/providers/MovyProvider.kt):
  metadati, discovery dei domini, ricerca sui server Movy, audio italiano,
  sottotitoli, master HLS combinato e integrazione ToastFlix/AutoSync.
- [MovyCipher.kt](src/main/kotlin/it/blvcktop/providers/MovyCipher.kt):
  decodifica del payload Movy, con aritmetica unsigned a 32 bit e gestione
  degli slot non inizializzati come nel JS.
- [MovySync.kt](src/main/kotlin/it/blvcktop/providers/MovySync.kt):
  classificazione della sincronizzazione e calcolo del ritardo audio.
- [Models.kt](src/main/kotlin/it/blvcktop/providers/Models.kt):
  risultati tipizzati e serializzabili in JSON.
- [Http.kt](src/main/kotlin/it/blvcktop/providers/Http.kt):
  trasporto OkHttp asincrono, timeout e cancellazione tramite coroutine.

## Compilazione e test

Serve un **JDK 17 o 21**, con `JAVA_HOME` configurato. Il wrapper scarica Gradle
e le dipendenze al primo utilizzo.

```sh
cd kotlin
./gradlew test jar
```

Su Windows usare `gradlew.bat test jar`.

L'artefatto e `build/libs/blvcktop-providers-1.0.0.jar`.
Il JAR **non include le dipendenze**: per l'app o il server e preferibile
importare il modulo Gradle, invece di copiare il solo JAR.

I test sono offline rispetto ai servizi dei provider: usano fixture sintetiche,
vettori di decodifica verificati contro il JS originale e un server HTTP locale.
Coprono anche soglie di 25 ms/60 s, arrotondamenti negativi, fallback,
header audio/video separati, payload AutoSync, timeout e cancellazione.
Non certificano la disponibilita attuale dei siti ne la riproduzione su un dispositivo.

## Utilizzo dal backend Kotlin

```kotlin
import it.blvcktop.providers.AnimeUnityConfig
import it.blvcktop.providers.AnimeUnityProvider
import it.blvcktop.providers.MovyConfig
import it.blvcktop.providers.MovyProvider
import it.blvcktop.providers.OkHttpProviderHttp
import it.blvcktop.providers.Stream

val tmdbKey = requireNotNull(System.getenv("TMDB_API_KEY"))
val http = OkHttpProviderHttp()

val animeUnity = AnimeUnityProvider(
    AnimeUnityConfig(tmdbApiKey = tmdbKey),
    http = http,
)
val movy = MovyProvider(
    MovyConfig(
        tmdbApiKey = tmdbKey,
        autoSyncKey = System.getenv("AUTOSYNC_KEY"),
    ),
    http = http,
)

suspend fun animeEpisode(): List<Stream> =
    animeUnity.getStreams("kitsu:1234", "series", season = 1, episode = 2)

suspend fun movie(): List<Stream> =
    movy.getStreams("tmdb:603", "movie")
```

Le chiavi originali **non sono state copiate**. La chiave TMDB va fornita alla
configurazione; quella AutoSync e facoltativa. Senza `autoSyncKey` restano
disponibili la consultazione degli stati e degli offset, ma non vengono inviati
nuovi job: la mancata configurazione viene segnalata nel logger.
Impostando `autoSyncApiUrl = null` si disabilitano sia le richieste di stato
AutoSync sia l'invio di job; ToastFlix rimane attivo.

Le classi leggono gli endpoint dalle rispettive configurazioni: e possibile
aggiornarli senza modificare la logica. Le API esterne di mapping e sincronizzazione
rimangono necessarie alle funzionalita che le usavano nel JS; questo porting non
reimplementa i loro server.

Per serializzare il contratto completo, inclusi i campi con valori di default
come `type = "hls"`, configurare il serializer del proprio backend:

```kotlin
import it.blvcktop.providers.Stream
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

val apiJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

suspend fun movieResponse(): String =
    apiJson.encodeToString(ListSerializer(Stream.serializer()), movie())
```

Il backend puo esporre questi risultati in una propria API. Non sono inclusi
route HTTP, autenticazione, storage delle playlist o un proxy multimediale.

## Integrazione Android

La libreria genera bytecode JVM 8. Per questo porting usare **Android API 26+**
(`java.util.Base64`) oppure verificare e configurare il desugaring necessario
per una versione minima inferiore. La compilazione JVM e testata; un APK e il
relativo player vanno ancora realizzati e verificati.

Nel progetto Android e possibile includere questa directory come modulo:

```kotlin
// settings.gradle.kts dell'app: adattare il percorso.
include(":providers")
project(":providers").projectDir = file("../blvckTOP-main/kotlin")
```

```kotlin
// build.gradle.kts del modulo app
dependencies {
    implementation(project(":providers"))
}
```

Allineare le versioni del plugin Kotlin a quelle del progetto Android. Se il
progetto centralizza i repository in `settings.gradle.kts`, spostare li
`mavenCentral()` dal build del modulo.

L'app deve avere il permesso `android.permission.INTERNET`. Chiamare i provider
da una coroutine legata al lifecycle, spostando il lavoro di parsing su
`Dispatchers.IO` o `Dispatchers.Default`; la cancellazione raggiunge anche le
chiamate HTTP. Se la risoluzione avviene sul server, all'app bastano il client
della propria API e il player: non deve includere questa libreria.

Non incorporare chiavi riservate nell'APK: conservarle sul backend.

## Riproduzione: cosa deve fare l'app o il server

- `url`: URL HLS diretto, oppure data URI standard per il master combinato Movy.
- `headers`: header della sorgente video.
- `audioHeaders`: header della sorgente audio Vix e dei suoi sottotitoli HLS.
  Non sovrascriverli con quelli Movy: possono servire header differenti per host.
- `hlsManifest`: testo del master combinato, quando viene trovato l'audio ITA.
  Il server puo servirlo da un proprio URL HTTPS con content type
  `application/vnd.apple.mpegurl` e restituire `stream.copy(url = playlistUrl)`.
  Il testo contiene URL upstream: servirlo dal backend **non equivale** a fare
  proxy dei segmenti. Se necessario, il proxy deve gestire anche playlist figlie,
  segmenti, eventuali chiavi HLS, sottotitoli e relativi header.
- `subtitles`: sottotitoli esterni Movy; quelli HLS Vix sono nel master.
- `audioSync`: stato, offset, rapporto di velocita e presenza di tagli.
- `behaviorHints.audioDelayMs`: indicazione per il player, **non una correzione
  gia applicata**. Un valore positivo significa ritardare l'audio. Vengono
  mantenuti i passi da 25 ms e il limite di +/-60 s del JS.

Con Media3/ExoPlayer impostare esplicitamente il MIME HLS, soprattutto per i
data URI, e verificare il caricamento del master e delle tracce esterne.
Il ritardo della sola traccia audio richiede integrazione specifica nel player
o una correzione nel backend: non basta passare il campo JSON a ExoPlayer.
Differenze di velocita o di montaggio vengono segnalate, non corrette.
AutoSync misura le tracce tramite il servizio esterno; la libreria non include
un algoritmo locale di analisi audio.

I token delle sorgenti possono scadere o avere vincoli di rete/IP. Evitare una
cache permanente degli URL; verificare il percorso server-dispositivo prima
di decidere se sia necessario il proxy.

## Differenze intenzionali rispetto ai plugin Nuvio

- Nessun redirect speciale dell'ID `603` verso Death Note: era una sonda Nuvio,
  non una risoluzione del titolo richiesto.
- Il master Movy usa `data:application/vnd.apple.mpegurl;base64,...` e newline
  standard, non i workaround `data://`, CR e `#.m3u8` specifici di mpv/Nuvio.
- I messaggi non dichiarano un ritardo audio gia applicato dal player.
- Le richieste AutoSync vengono attese, con timeout di 2,5 secondi, invece di
  avviare un task scollegato dal lifecycle della chiamata.
- I casi incompatibili non inviano un nuovo job AutoSync e i titoli senza IMDb
  non inviano job con una chiave media incompleta.
- Il fallback IMDb/TMDB considera il tipo di contenuto anche quando un array
  di risultati e vuoto. Gli URL relativi e i parametri delle playlist vengono
  risolti con il parser URL, preservando le query gia presenti.
- Input non validi producono `IllegalArgumentException`. Errori di trasporto
  o parsing senza fallback possono propagare `IOException` o `ProviderException`.
  I fallback recuperabili sono registrati tramite `ProviderLogger`; la
  cancellazione non viene trasformata in un successo con lista vuota.

Il logger predefinito evita di stampare messaggi upstream che potrebbero
contenere URL firmati o chiavi. Un logger personalizzato riceve anche la causa:
applicare lo stesso accorgimento nei log del proprio backend.
