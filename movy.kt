package it.blvcktop.providers

import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class MovyConfig(
    val tmdbApiKey: String,
    val autoSyncKey: String? = null,
    val tmdbBaseUrl: String = "https://api.themoviedb.org/3",
    val sites: List<String> = listOf("https://www.movy.sx", "https://movy.sx"),
    val apiUrl: String = "https://api.wecollege.net",
    val servers: List<String> = listOf(
        "miami", "boise", "atlanta", "orlando", "phoenix", "seattle", "denver", "portland",
        "dallas", "tampa", "paris", "cancun", "berlin", "austin", "delhi", "munich",
    ),
    val measuredServers: Set<String> = setOf("miami", "boise"),
    val serversBudgetMs: Long = 8000,
    val vixDomainsUrl: String = "https://raw.githubusercontent.com/realbestia1/domains/refs/heads/main/domains.json",
    val vixBaseUrl: String = "https://vixsrc.to",
    val toastflixUrl: String = "https://toastflix.stremio-italia.eu",
    val autoSyncApiUrl: String? = "https://autosync.stremio-italia.eu/plugin/jobs",
) {
    init {
        require(tmdbApiKey.isNotBlank()) { "TMDB API key is required" }
        require(sites.isNotEmpty() && servers.isNotEmpty()) { "Movy sites and servers must not be empty" }
        require(serversBudgetMs > 0) { "Server budget must be positive" }
        require(autoSyncKey == null || autoSyncKey.isNotBlank()) { "AutoSync key must not be blank" }
    }
}

internal data class QualityInfo(val label: String, val resolution: String, val bandwidth: Int)
internal val MOVY_QUALITY_INFO = mapOf(
    "2160" to QualityInfo("4K", "3840x2160", 16_000_000),
    "1440" to QualityInfo("1440p", "2560x1440", 10_000_000),
    "1080" to QualityInfo("1080p", "1920x1080", 6_000_000),
    "720" to QualityInfo("720p", "1280x720", 3_000_000),
    "480" to QualityInfo("480p", "854x480", 1_500_000),
    "360" to QualityInfo("360p", "640x360", 800_000),
)
private val MOVY_QUALITIES = setOf("2160", "1080", "720")

internal fun movyQualityKey(quality: String): String? {
    if (quality.contains("4k", true)) return "2160"
    return Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(quality)?.groupValues?.get(1)
        ?.takeIf { it in MOVY_QUALITY_INFO }
}

internal data class HlsSubtitle(val uri: String, val name: String, val language: String, val forced: Boolean)
internal data class ItalianTracks(
    val audio: String,
    val englishAudio: String?,
    val subtitles: List<HlsSubtitle>,
    val headers: Map<String, String>,
)

internal fun buildMovyMaster(videoUrl: String, quality: QualityInfo, italian: ItalianTracks): String {
    fun quoteSafe(value: String): String = value.filter { it.code in 0x20..0x7e && it != '"' }
    val lines = mutableListOf("#EXTM3U", "#EXT-X-VERSION:6", "#EXT-X-INDEPENDENT-SEGMENTS")
    lines += """#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Italiano",LANGUAGE="it",DEFAULT=YES,AUTOSELECT=YES,URI="${quoteSafe(italian.audio)}""""
    for (subtitle in italian.subtitles) {
        val automatic = if (subtitle.forced) "YES" else "NO"
        lines += """#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="${quoteSafe(subtitle.name)}",LANGUAGE="it",DEFAULT=NO,AUTOSELECT=$automatic,FORCED=$automatic,URI="${quoteSafe(subtitle.uri)}""""
    }
    lines += "#EXT-X-STREAM-INF:BANDWIDTH=${quality.bandwidth},RESOLUTION=${quality.resolution},AUDIO=\"aud\"" +
        (if (italian.subtitles.isNotEmpty()) ",SUBTITLES=\"subs\"" else "")
    lines += quoteSafe(videoUrl)
    return lines.joinToString("\n", postfix = "\n")
}

class MovyProvider(
    private val config: MovyConfig,
    private val http: ProviderHttp = OkHttpProviderHttp(),
    private val logger: ProviderLogger = DefaultProviderLogger,
) : StreamProvider {
    private data class Endpoint(val site: String, val api: String)
    private data class Metadata(val title: String, val year: String, val imdbId: String, val runtime: Double)
    private data class Source(val url: String, val quality: String, val server: String, val measured: Boolean)
    private data class Sources(
        val sources: List<Source> = emptyList(),
        val subtitles: List<Subtitle> = emptyList(),
        val headers: Map<String, String> = emptyMap(),
    )

    @Volatile
    private var endpoint = Endpoint(config.sites.first(), config.apiUrl)
    private val movyUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val vixUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override suspend fun getStreams(rawId: String, mediaType: String, season: Int?, episode: Int?): List<Stream> {
        require(rawId.isNotBlank()) { "Media ID must not be blank" }
        val isTv = mediaType in setOf("tv", "series", "show")
        require(!isTv || (season != null && episode != null)) { "Series require season and episode" }
        require(season == null || season >= 0) { "Season must not be negative" }
        require(episode == null || episode > 0) { "Episode must be positive" }
        val id = resolveTmdbId(rawId, isTv) ?: run {
            logger.warn("[MovyITA] No TMDB match for IMDb ID", null)
            return emptyList()
        }
        val meta = metadata(id, isTv)
        val (movy, italian, measured) = coroutineScope {
            val sources = async {
                logger.attempt("[MovyITA] Movy source lookup failed") { movySources(id, isTv, season, episode, meta) }
                    ?: Sources()
            }
            val audio = async {
                logger.attempt("[MovyITA] Italian audio lookup failed") { italianTracks(id, isTv, season, episode) }
            }
            val sync = async { syncStatus(meta, isTv, season, episode) }
            Triple(sources.await(), audio.await(), sync.await())
        }
        if (movy.sources.isEmpty()) {
            logger.warn("[MovyITA] No playable Movy sources found", null)
            return emptyList()
        }
        if (italian == null) logger.warn("[MovyITA] Italian audio not found; returning original audio", null)
        if (italian != null && measured.info?.status != "ok" && measured.jobStatus != "incompatible") {
            reportUnmeasured(meta, isTv, season, episode, movy, italian)
        }
        val unmeasured = if (measured.jobStatus != null) measured
            else SyncStatus(SyncLevel.UNKNOWN, "non misurato", "server con file diverso da Miami")
        val heading = "\uD83D\uDCC1 ${meta.title}" +
            (if (isTv) " S${pad2(requireNotNull(season))}E${pad2(requireNotNull(episode))}" else "") +
            (if (meta.year.isNotEmpty()) " (${meta.year})" else "")
        return movy.sources.sortedByDescending { it.quality.toInt() }.map { source ->
            val quality = MOVY_QUALITY_INFO.getValue(source.quality)
            val sync = if (source.measured) measured else unmeasured
            val badge = syncBadge(sync)
            val details = (if (italian != null)
                badge.line + (sync.info?.let { "\n${syncInfoLine(it)}" } ?: "") +
                    "\n\uD83C\uDDEE\uD83C\uDDF9 Audio ITA (SC) + \uD83C\uDF0D originale"
            else "\uD83C\uDF0D Solo audio originale (ITA non trovato)") +
                "\n\uD83C\uDFAC Movy \u00B7 ${source.server}" +
                (if (italian?.subtitles?.isNotEmpty() == true) " \u00B7 Sub ITA" else "")
            val manifest = italian?.let { buildMovyMaster(source.url, quality, it) }
            val delay = if (italian != null) autoDelayMs(sync.info) else null
            Stream(
                name = sortPrefix(source.quality.toInt(), if (italian != null) sync.level.priority else SyncLevel.UNKNOWN.priority) +
                    "\uD83C\uDFAC MovyITA ${quality.label}" +
                    (if (italian != null) " \uD83C\uDDEE\uD83C\uDDF9 ${badge.short}" else ""),
                title = "$heading\n$details",
                size = details,
                url = if (manifest != null)
                    "data:application/vnd.apple.mpegurl;base64," +
                        Base64.getEncoder().encodeToString(manifest.toByteArray(Charsets.UTF_8))
                else source.url,
                quality = quality.label,
                provider = "movyita",
                headers = movy.headers,
                subtitles = movy.subtitles,
                audioSync = if (italian != null) sync.info else null,
                behaviorHints = delay?.let { BehaviorHints(audioDelayMs = it) },
                hlsManifest = manifest,
                audioHeaders = italian?.headers.orEmpty(),
            )
        }
    }

    private fun tmdbUrl(path: String, params: Map<String, String> = emptyMap()): String =
        withQuery(config.tmdbBaseUrl.trimEnd('/') + path, mapOf("api_key" to config.tmdbApiKey) + params)

    private suspend fun resolveTmdbId(rawId: String, isTv: Boolean): String? {
        val id = rawId.trim().replace(Regex("^tmdb:", RegexOption.IGNORE_CASE), "")
        if (Regex("""\d+""").matches(id)) {
            require(id.toLongOrNull() != null) { "TMDB ID is out of range" }
            return id
        }
        val imdb = Regex("""tt\d+""").find(id)?.value ?: throw IllegalArgumentException("Unsupported media ID")
        val data = http.json(tmdbUrl("/find/$imdb", mapOf("external_source" to "imdb_id")))
        return data.array(if (isTv) "tv_results" else "movie_results").filterIsInstance<JsonObject>().firstOrNull()?.text("id")
    }

    private suspend fun metadata(id: String, isTv: Boolean): Metadata {
        val data = http.json(tmdbUrl("/${if (isTv) "tv" else "movie"}/$id", mapOf("append_to_response" to "external_ids")))
        val title = data.text(if (isTv) "name" else "title")?.takeIf(String::isNotEmpty)
            ?: throw ProviderException("TMDB title missing")
        val runtime = data.number("runtime")?.takeIf { it > 0 }
            ?: data.array("episode_run_time").firstOrNull()?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
            }?.takeIf { it > 0 } ?: 90.0
        return Metadata(
            title, data.text(if (isTv) "first_air_date" else "release_date")?.take(4).orEmpty(),
            data.obj("external_ids")?.text("imdb_id") ?: data.text("imdb_id").orEmpty(), runtime,
        )
    }

    private fun movyHeaders(site: String): Map<String, String> = mapOf(
        "User-Agent" to movyUserAgent, "Accept" to "*/*",
        "Origin" to site, "Referer" to "${site.trimEnd('/')}/",
    )

    private suspend fun discoverEndpoint(): Endpoint? {
        for (site in config.sites) {
            val discovered = logger.attempt("[MovyITA] Movy site discovery failed") {
                val response = http.request("${site.trimEnd('/')}/",
                    mapOf("User-Agent" to movyUserAgent, "Accept" to "text/html"), 8000)
                val api = Regex("""https://api\.[a-z0-9-]+(?:\.[a-z0-9-]+)+""", RegexOption.IGNORE_CASE)
                    .findAll(response.body).map { it.value }
                    .firstOrNull { !Regex("tmdb|themoviedb|google|cloudflare", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                Endpoint(origin(response.url), api ?: config.apiUrl)
            }
            if (discovered != null) return discovered
        }
        return null
    }

    private suspend fun seed(endpoint: Endpoint, id: String): String =
        http.json(withQuery("${endpoint.api.trimEnd('/')}/seed", mapOf("mediaId" to id)), movyHeaders(endpoint.site))
            .text("seed")?.takeIf(String::isNotEmpty) ?: throw ProviderException("Movy seed missing")

    private suspend fun movySources(id: String, isTv: Boolean, season: Int?, episode: Int?, meta: Metadata): Sources {
        var current = endpoint
        var seed = logger.attempt("[MovyITA] Movy API failed; trying site discovery") { seed(current, id) }
        if (seed == null) {
            val found = discoverEndpoint()
            if (found == null || found == current) throw ProviderException("Movy API unavailable")
            endpoint = found
            current = found
            seed = seed(current, id)
        }
        val headers = movyHeaders(current.site)
        val sources = mutableListOf<Source>()
        val subtitles = mutableListOf<Subtitle>()
        val have = mutableSetOf<String>()
        val directoryStatus = mutableMapOf<String, Boolean>()
        val started = System.nanoTime()
        fun overBudget(): Boolean = (System.nanoTime() - started) / 1_000_000 > config.serversBudgetMs
        for ((index, server) in config.servers.withIndex()) {
            if ("2160" in have && "1080" in have) break
            if (index > 0 && overBudget()) break
            fun add(url: String, key: String) {
                if (key !in MOVY_QUALITIES || !have.add(key)) return
                sources += Source(url, key, server.replaceFirstChar { it.titlecase(Locale.ROOT) }, server in config.measuredServers)
            }
            // The upstream API expects an already URI-encoded title inside its query parameter.
            val encodedTitle = URLEncoder.encode(meta.title, "UTF-8").replace("+", "%20")
                .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")
            val params = mutableMapOf(
                "title" to encodedTitle, "mediaType" to if (isTv) "tv" else "movie",
                "year" to meta.year, "tmdbId" to id, "imdbId" to meta.imdbId, "enc" to "2", "seed" to seed,
            )
            if (isTv) {
                params["seasonId"] = requireNotNull(season).toString()
                params["episodeId"] = requireNotNull(episode).toString()
            }
            logger.attempt("[MovyITA] Source lookup failed for $server") {
                val encoded = http.text(withQuery("${current.api.trimEnd('/')}/$server/sources", params),
                    headers, if (index < 2) 15_000 else 6000)
                if (encoded.isBlank()) throw ProviderException("Movy returned an empty payload")
                val numericId = id.toLongOrNull() ?: throw ProviderException("Invalid upstream TMDB ID")
                val data = parseObject(MovyCipher.decrypt(encoded.trim(), seed, numericId))
                for (source in data.array("sources").filterIsInstance<JsonObject>()) {
                    if (index > 0 && overBudget()) break
                    val url = source.text("url")?.takeIf {
                        Regex("""\.m3u8(?:$|[?#])""", RegexOption.IGNORE_CASE).containsMatchIn(it)
                    } ?: continue
                    val key = movyQualityKey(source.text("quality").orEmpty())
                    val directory = url.substringBefore('?').substringBeforeLast('/') + "/"
                    if ((key != null && (key in have || key !in MOVY_QUALITIES)) || directoryStatus[directory] == false) continue
                    if (key != null && directoryStatus[directory] == true) {
                        add(url, key)
                        continue
                    }
                    val body = logger.attempt("[MovyITA] Playlist unavailable on $server") {
                        http.text(url, headers + ("Range" to "bytes=0-65535"), 8000)
                    }
                    when {
                        body != null && body.contains("#EXT-X-STREAM-INF") -> {
                            for (variant in hlsVariants(body, url)) {
                                val variantKey = when {
                                    variant.width >= 3200 -> "2160"
                                    variant.width >= 2400 -> "1440"
                                    variant.width >= 1800 -> "1080"
                                    variant.width >= 1200 -> "720"
                                    variant.width >= 800 -> "480"
                                    variant.width > 0 -> "360"
                                    else -> null
                                }
                                if (variantKey != null) add(variant.url, variantKey)
                            }
                        }
                        body != null && body.contains("#EXTINF") -> {
                            directoryStatus[directory] = true
                            if (key != null) add(url, key)
                        }
                        else -> {
                            directoryStatus[directory] = false
                            logger.warn("[MovyITA] Unplayable playlist on $server", null)
                        }
                    }
                }
                for (subtitle in data.array("subtitles").filterIsInstance<JsonObject>()) {
                    val url = subtitle.text("url")?.takeIf(String::isNotBlank) ?: continue
                    if (subtitles.any { it.url == url }) continue
                    subtitles += Subtitle(
                        url, subtitle.text("language") ?: subtitle.text("lang") ?: "en",
                        subtitle.text("label") ?: subtitle.text("name") ?: subtitle.text("language") ?: subtitle.text("lang") ?: "Sub",
                    )
                }
            }
        }
        return Sources(sources, subtitles, headers)
    }

    private suspend fun lookupVixBase(): String? = logger.attempt("[MovyITA] Vix domain discovery failed") {
        val text = http.text(withQuery(config.vixDomainsUrl, mapOf("_" to System.currentTimeMillis().toString())),
            mapOf("Accept" to "application/json"), 6000)
        val corrected = text.replace(Regex("""("[^"\r\n]+")\s*("[^"]+"\s*:)"""), "$1,$2")
        val base = parseObject(corrected).text("vixsrc")?.trim()?.trimEnd('/')
            ?: throw ProviderException("Vix domain missing")
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(base)) throw ProviderException("Invalid Vix domain")
        base
    }

    private suspend fun vixPayload(base: String, path: String): JsonObject =
        http.json(withQuery(base.trimEnd('/') + path, mapOf("lang" to "it")), mapOf(
            "User-Agent" to vixUserAgent, "Referer" to "${base.trimEnd('/')}/", "Accept" to "application/json",
            "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
        ), 12_000)

    private suspend fun italianTracks(id: String, isTv: Boolean, season: Int?, episode: Int?): ItalianTracks? {
        val path = if (isTv) "/api/tv/$id/$season/$episode" else "/api/movie/$id"
        var base = config.vixBaseUrl
        var payload = logger.attempt("[MovyITA] Vix API failed; trying domain discovery") { vixPayload(base, path) }
        if (payload == null) {
            val alternate = lookupVixBase()
            if (alternate == null || alternate == base) throw ProviderException("Vix API unavailable")
            base = alternate
            payload = vixPayload(base, path)
        }
        val src = payload.text("src")?.takeIf(String::isNotEmpty) ?: return null
        val embed = absoluteUrl(src, "${base.trimEnd('/')}/")
        val html = http.text(embed, mapOf("User-Agent" to vixUserAgent, "Referer" to "${base.trimEnd('/')}/"), 12_000)
        fun credential(key: String): String? = Regex("""['"]$key['"]\s*:\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1)
        val token = credential("token") ?: throw ProviderException("Vix token missing")
        val expires = credential("expires") ?: throw ProviderException("Vix expiry missing")
        val playlist = Regex("""url\s*:\s*['"]([^'"]+/playlist/\d+[^'"]*)['"]""")
            .find(html)?.groupValues?.get(1) ?: throw ProviderException("Vix playlist missing")
        val fullHd = Regex("""window\.canPlayFHD\s*=\s*true""").containsMatchIn(html) ||
            Regex("""[?&]canPlayFHD=1""").containsMatchIn(embed)
        val params = mutableMapOf("token" to token, "expires" to expires, "lang" to "it")
        if (fullHd) params["h"] = "1"
        val masterUrl = withQuery(playlist, params)
        val headers = mapOf("User-Agent" to vixUserAgent, "Referer" to embed, "Origin" to base)
        val master = http.text(masterUrl, headers, 12_000)
        if (!master.trimStart().startsWith("#EXTM3U")) throw ProviderException("Invalid Vix HLS playlist")
        var audio: String? = null
        var english: String? = null
        val subtitles = mutableListOf<HlsSubtitle>()
        for (line in master.lineSequence().map(String::trim)) {
            if (!line.startsWith("#EXT-X-MEDIA:")) continue
            val attributes = hlsAttributes(line)
            val uri = attributes["URI"]?.takeIf(String::isNotEmpty)?.let { absoluteUrl(it, masterUrl) } ?: continue
            val language = attributes["LANGUAGE"].orEmpty().lowercase()
            val name = attributes["NAME"].orEmpty()
            when (attributes["TYPE"]) {
                "AUDIO" -> {
                    if (audio == null && (language in setOf("ita", "it") || name.startsWith("ita", true))) audio = uri
                    else if (english == null && (language in setOf("eng", "en") ||
                            name.startsWith("eng", true) || name.contains("english", true))) english = uri
                }
                "SUBTITLES" -> {
                    val forced = "$name $language".contains("forced", true) || attributes["FORCED"] == "YES"
                    if (subtitles.none { it.uri == uri || it.forced == forced }) {
                        subtitles += HlsSubtitle(uri, name.ifEmpty { "Italiano" }, language.ifEmpty { "ita" }, forced)
                    }
                }
            }
        }
        return audio?.let { ItalianTracks(it, english, subtitles, headers) }
    }

    private fun mediaKey(meta: Metadata, isTv: Boolean, season: Int?, episode: Int?): String =
        "${if (isTv) "series" else "movie"}:${meta.imdbId}:${if (isTv) season else 0}:${if (isTv) episode else 0}"

    private suspend fun syncStatus(meta: Metadata, isTv: Boolean, season: Int?, episode: Int?): SyncStatus {
        if (meta.imdbId.isEmpty()) return SyncStatus(SyncLevel.RED, "no IMDb", "titolo senza IMDb")
        return logger.attempt("[MovyITA] Audio sync service unavailable") {
            val data = http.json(withQuery("${config.toastflixUrl.trimEnd('/')}/dual/offset/status", mapOf(
                "imdb" to meta.imdbId, "type" to if (isTv) "series" else "movie",
                "season" to if (isTv) season.toString() else "0",
                "episode" to if (isTv) episode.toString() else "0",
                "provider" to "movy", "audio_source" to "vixsrc",
            )), mapOf("Accept" to "application/json"), 8000)
            if (data.flag("found") && data.text("status") == "ok") return@attempt measuredSync(data)
            val jobs = config.autoSyncApiUrl?.let { api ->
                logger.attempt("[MovyITA] AutoSync job status unavailable") {
                    http.json(withQuery(api.replace(Regex("/jobs/?$"), "/jobs/status"),
                        mapOf("media_key" to mediaKey(meta, isTv, season, episode))),
                        mapOf("Accept" to "application/json"), 2500)
                }
            }?.array("items")?.filterIsInstance<JsonObject>().orEmpty()
            val done = jobs.firstOrNull { it.text("status") == "done" && it.obj("result")?.text("status") == "ok" }
            if (done != null) return@attempt measuredSync(requireNotNull(done.obj("result")))
            val running = jobs.firstOrNull { it.text("status") == "running" }
            if (running != null) {
                val stage = running.text("stage") ?: "tier1"
                return@attempt SyncStatus(SyncLevel.YELLOW, if (stage == "tier2") "analisi approfondita" else "in calcolo (~10s)",
                    if (stage == "tier2") "scansione su 7 punti in corso" else "calcolo in corso",
                    jobStatus = "running", stage = stage)
            }
            val queued = jobs.filter { it.text("status") == "queued" }
            if (queued.isNotEmpty()) {
                val ahead = queued.mapNotNull { it.integer("queue_ahead") }.minOrNull() ?: 0
                return@attempt SyncStatus(SyncLevel.YELLOW, if (ahead == 0) "prossimo in coda" else "${ahead + 1}\u00B0 in coda",
                    "in coda", jobStatus = "queued", queueAhead = ahead)
            }
            if (jobs.any { it.text("status") == "incompatible" } || data.text("status") == "incompatible") {
                return@attempt SyncStatus(SyncLevel.RED, "versioni diverse", "versioni audio/video diverse", jobStatus = "incompatible")
            }
            val failed = jobs.firstOrNull { it.text("status") == "failed" }
            if (failed != null) {
                val sameProvider = jobs.lastOrNull { it.text("status") == "failed" &&
                    it.text("provider").orEmpty().equals(failed.text("provider").orEmpty(), true) } ?: failed
                val raw = sameProvider.text("error") ?: sameProvider.text("last_error") ?: "errore analisi"
                val error = when {
                    raw.contains("502") -> "server offline (502 Bad Gateway)"
                    raw.contains("404") -> "audio/video non trovato (404)"
                    raw.contains("403") -> "accesso bloccato dal server (403)"
                    raw.contains("timeout", true) -> "timeout connessione"
                    raw.contains("non correlabili", true) -> "tracce non correlabili"
                    else -> raw.take(60)
                }
                return@attempt SyncStatus(SyncLevel.RED, "fallito", error, jobStatus = "failed")
            }
            SyncStatus(SyncLevel.YELLOW, "da misurare", "offset da misurare", jobStatus = "new")
        } ?: SyncStatus(SyncLevel.UNKNOWN, "sync n/d", "ToastFlix non raggiungibile")
    }

    private suspend fun reportUnmeasured(
        meta: Metadata, isTv: Boolean, season: Int?, episode: Int?, movy: Sources, italian: ItalianTracks,
    ) {
        val api = config.autoSyncApiUrl
        val key = config.autoSyncKey
        if (api == null || key == null) {
            logger.warn("[MovyITA] AutoSync submission disabled: endpoint or key not configured", null)
            return
        }
        if (meta.imdbId.isEmpty()) {
            logger.warn("[MovyITA] AutoSync submission skipped: IMDb ID missing", null)
            return
        }
        val groups = movy.sources.filter { it.quality.toInt() >= 1080 }.groupBy { it.server }.entries.take(1)
        if (groups.isEmpty()) return
        fun jsonHeaders(headers: Map<String, String>): JsonObject = buildJsonObject {
            headers.forEach { (name, value) -> put(name, value) }
        }
        fun track(language: String, uri: String): JsonObject = buildJsonObject {
            put("lang", language)
            put("base_url", uri)
            put("headers", jsonHeaders(italian.headers))
            put("source", "vixsrc")
        }
        val body = buildJsonObject {
            put("media_key", mediaKey(meta, isTv, season, episode))
            put("audio_tracks", buildJsonArray {
                add(track("ita", italian.audio))
                italian.englishAudio?.let { add(track("eng", it)) }
            })
            put("audio", buildJsonObject {
                put("source", "vixsrc")
                put("base_url", italian.audio)
                put("headers", jsonHeaders(italian.headers))
            })
            put("items", buildJsonArray {
                for ((server, renditions) in groups) {
                    add(buildJsonObject {
                        put("provider", "movy")
                        put("server", server)
                        put("video_duration", meta.runtime * 60)
                        put("headers", jsonHeaders(movy.headers))
                        put("renditions", buildJsonArray {
                            renditions.forEach { source ->
                                add(buildJsonObject {
                                    put("resolution", source.quality.toInt())
                                    put("url", source.url)
                                })
                            }
                        })
                    })
                }
            })
        }
        logger.attempt("[MovyITA] AutoSync submission failed") {
            http.request(api, mapOf("Content-Type" to "application/json", "X-PriSynx-Key" to key), 2500, body.toString())
        }
    }
}
