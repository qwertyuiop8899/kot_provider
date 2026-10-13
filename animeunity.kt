package it.blvcktop.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

data class AnimeUnityConfig(
    val tmdbApiKey: String,
    val baseUrl: String = "https://www.animeunity.so",
    val tmdbBaseUrl: String = "https://api.themoviedb.org/3",
    val mappingBases: List<String> = listOf(
        "https://anime.stremio-italia.eu",
        "https://animemapping.realbestia.com",
    ),
    val vixCloudBaseUrl: String = "https://vixcloud.co",
) {
    init {
        require(tmdbApiKey.isNotBlank()) { "TMDB API key is required" }
        require(mappingBases.isNotEmpty()) { "At least one mapping service is required" }
    }
}

internal data class AnimeRequest(
    val provider: String,
    val externalId: String,
    val season: Int?,
    val episode: Int,
    val isTv: Boolean,
)

internal fun parseAnimeRequest(
    rawId: String,
    mediaType: String,
    requestedSeason: Int?,
    requestedEpisode: Int?,
): AnimeRequest {
    require(rawId.isNotBlank()) { "Media ID must not be blank" }
    require(requestedSeason == null || requestedSeason >= 0) { "Season must not be negative" }
    require(requestedEpisode == null || requestedEpisode > 0) { "Episode must be positive" }
    val id = rawId.trim()
    var season = requestedSeason
    var episode = requestedEpisode
    val match = Regex(
        """^(?:(kitsu|mal|anilist|anidb|tmdb|imdb):)?(tt\d+|\d+)(?::(\d+))?(?::(\d+))?$""",
        RegexOption.IGNORE_CASE,
    ).matchEntire(id) ?: throw IllegalArgumentException("Unsupported media ID")
    val externalId = match.groupValues[2].lowercase()
    val provider = match.groupValues[1].lowercase().ifEmpty {
        if (externalId.startsWith("tt")) "imdb" else "tmdb"
    }
    require((provider == "imdb") == externalId.startsWith("tt")) { "Media ID does not match its provider" }
    if (match.groupValues[4].isNotEmpty()) {
        season = match.groupValues[3].toIntOrNull() ?: throw IllegalArgumentException("Season is out of range")
        episode = match.groupValues[4].toIntOrNull() ?: throw IllegalArgumentException("Episode is out of range")
    } else if (match.groupValues[3].isNotEmpty()) {
        episode = match.groupValues[3].toIntOrNull() ?: throw IllegalArgumentException("Episode is out of range")
    }
    val isTv = mediaType in setOf("tv", "series", "show") ||
        (season != null && episode != null) || (episode ?: 0) > 1
    return AnimeRequest(
        provider, externalId,
        season?.takeIf { it > 0 } ?: if (isTv) 1 else null,
        episode?.takeIf { it > 0 } ?: 1,
        isTv,
    )
}

class AnimeUnityProvider(
    private val config: AnimeUnityConfig,
    private val http: ProviderHttp = OkHttpProviderHttp(),
    private val logger: ProviderLogger = DefaultProviderLogger,
) : StreamProvider {
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val playHeaders = mapOf("User-Agent" to userAgent, "Referer" to "${config.vixCloudBaseUrl.trimEnd('/')}/")

    override suspend fun getStreams(rawId: String, mediaType: String, season: Int?, episode: Int?): List<Stream> {
        val request = parseAnimeRequest(rawId, mediaType, season, episode)
        var mapping = mappingPayload(request) ?: run {
            logger.warn("[AnimeUnity] No mapping found", null)
            return emptyList()
        }
        var paths = animePaths(mapping)
        if (paths.isEmpty()) {
            val kitsuId = mapping.obj("requested")?.text("resolvedKitsuId") ?: mapping.obj("kitsu")?.text("id")
            if (kitsuId != null && (request.provider != "kitsu" || request.externalId != kitsuId)) {
                mappingPayload(request.copy(provider = "kitsu", externalId = kitsuId))?.let {
                    mapping = it
                    paths = animePaths(it)
                }
            }
        }
        if (paths.isEmpty()) {
            logger.warn("[AnimeUnity] Mapping contains no AnimeUnity paths", null)
            return emptyList()
        }
        val kitsu = mapping.obj("kitsu")
        val title = kitsu?.text("canonicalTitle") ?: kitsu?.obj("titles")?.text("it")
            ?: kitsu?.obj("titles")?.text("en") ?: "Anime"
        val year = kitsu?.text("startDate")?.take(4).orEmpty()
        val targetEpisode = sequenceOf(
            kitsu?.integer("episode"),
            mapping.obj("requested")?.integer("episode"),
            mapping.obj("mappings")?.obj("tmdb_episode")?.integer("episode"),
            mapping.obj("mappings")?.obj("tmdb_episode")?.integer("rawEpisodeNumber"),
            request.episode,
        ).filterNotNull().first { it > 0 }
        val heading = "\uD83D\uDCC1 $title" +
            (if (request.isTv) " Ep ${pad2(targetEpisode)}" else "") +
            (if (year.isNotEmpty()) " ($year)" else "")
        val streams = mutableListOf<Stream>()
        for (path in paths) {
            val found = logger.attempt("[AnimeUnity] Failed to resolve an anime path") {
                val embedUrl = episodeEmbedUrl(path, targetEpisode)
                    ?: throw ProviderException("Episode embed not found")
                val (masterUrl, fhd) = vixCloudMaster(embedUrl)
                val variants = hlsVariants(http.text(masterUrl, playHeaders), masterUrl)
                    .sortedWith(compareByDescending<HlsVariant> { it.height }.thenByDescending { it.bandwidth })
                    .distinctBy { it.quality }
                    .ifEmpty {
                        listOf(HlsVariant(
                            masterUrl, if (fhd) 1920 else 1280, if (fhd) 1080 else 720,
                            0, 0, if (fhd) "1080p" else "720p", emptyMap(),
                        ))
                    }
                val isIta = Regex("""(?:^|[-_/])ita(?:[-_/]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(path)
                val lang = if (isIta) "\uD83C\uDDEE\uD83C\uDDF9 ITA" else "\uD83C\uDDEF\uD83C\uDDF5 SUB"
                val audio = if (isIta) "\uD83D\uDD0A \uD83C\uDDEE\uD83C\uDDF9 Italiano"
                    else "\uD83D\uDD0A \uD83C\uDDEF\uD83C\uDDF5 Giapponese \u00B7 \uD83D\uDCAC Sub ITA"
                variants.map { variant ->
                    val technical = listOf(
                        if (variant.width > 0 && variant.height > 0)
                            "\uD83C\uDF9E\uFE0F ${variant.width}\u00D7${variant.height}"
                        else "\uD83C\uDF9E\uFE0F ${variant.quality}",
                        codecLabel(variant.attributes["CODECS"].orEmpty()),
                        formatBitrate(variant.displayBandwidth).let { if (it.isNotEmpty()) "\uD83D\uDCF6 $it" else "" },
                    ).filter(String::isNotEmpty).joinToString(" \u00B7 ")
                    val details = "$technical\n$audio\n\uD83C\uDFAC AnimeUnity \u00B7 VixCloud \u00B7 $lang"
                    Stream(
                        name = sortPrefix(variant.height, 0) + "\uD83C\uDFEE AnimeUnity ${variant.quality} $lang",
                        title = "$heading\n$details",
                        size = details,
                        url = variant.url,
                        quality = variant.quality,
                        provider = "animeunity",
                        headers = playHeaders,
                        behaviorHints = BehaviorHints(
                            notWebReady = true,
                            proxyHeaders = ProxyHeaders(playHeaders),
                            headers = playHeaders,
                            filename = "animeunity-${variant.quality}.m3u8",
                        ),
                    )
                }
            }
            if (found != null) streams += found
        }
        if (streams.isEmpty()) logger.warn("[AnimeUnity] No playable streams resolved", null)
        return streams.sortedBy { it.name }
    }

    private suspend fun mappingPayload(request: AnimeRequest, noFallback: Boolean = false): JsonObject? {
        val query = mutableMapOf("ep" to request.episode.toString(), "lang" to "it")
        request.season?.let { query["s"] = it.toString() }
        for (base in config.mappingBases) {
            val payload = logger.attempt("[AnimeUnity] Mapping service request failed") {
                http.json(
                    withQuery("${base.trimEnd('/')}/${request.provider}/${request.externalId}", query),
                    mapOf("User-Agent" to userAgent, "Accept" to "application/json"), 5000,
                )
            }
            if (payload?.flag("ok") == true &&
                payload.obj("mappings")?.get("animeunity")?.let { it !is JsonNull } == true
            ) return payload
        }
        if (noFallback) return null
        if (request.provider == "imdb") {
            val data = logger.attempt("[AnimeUnity] IMDb to TMDB lookup failed") {
                http.json(tmdbUrl("/find/${request.externalId}", mapOf("external_source" to "imdb_id")), timeoutMs = 5000)
            }
            val preferred = if (request.isTv) "tv_results" else "movie_results"
            val alternate = if (request.isTv) "movie_results" else "tv_results"
            val result = (data?.array(preferred).orEmpty() + data?.array(alternate).orEmpty())
                .filterIsInstance<JsonObject>().firstOrNull()
            result?.text("id")?.let { return mappingPayload(request.copy(provider = "tmdb", externalId = it), true) }
        } else if (request.provider == "tmdb") {
            val endpoints = if (request.isTv)
                listOf("/tv/${request.externalId}/external_ids", "/movie/${request.externalId}")
            else listOf("/movie/${request.externalId}", "/tv/${request.externalId}/external_ids")
            for (endpoint in endpoints) {
                val data = logger.attempt("[AnimeUnity] TMDB to IMDb lookup failed") {
                    http.json(tmdbUrl(endpoint), timeoutMs = 4000)
                }
                val imdbId = data?.text("imdb_id")?.takeIf { Regex("""tt\d+""").matches(it) } ?: continue
                mappingPayload(request.copy(provider = "imdb", externalId = imdbId), true)?.let { return it }
            }
        }
        return null
    }

    private fun tmdbUrl(path: String, params: Map<String, String> = emptyMap()): String =
        withQuery(config.tmdbBaseUrl.trimEnd('/') + path, mapOf("api_key" to config.tmdbApiKey) + params)

    private fun animePaths(payload: JsonObject): List<String> {
        val raw = payload.obj("mappings")?.get("animeunity") ?: return emptyList()
        val items = if (raw is JsonArray) raw.toList() else listOf(raw)
        return items.mapNotNull { item ->
            when (item) {
                is JsonObject -> item.text("path") ?: item.text("url") ?: item.text("href")
                is JsonPrimitive -> item.takeUnless { it is JsonNull }?.content
                else -> null
            }
        }.filter(String::isNotBlank).map {
            "/" + it.replace(Regex("""^https?://[^/]+""", RegexOption.IGNORE_CASE), "").trimStart('/')
        }.distinct()
    }

    private suspend fun episodeEmbedUrl(path: String, episode: Int): String? {
        val pageUrl = absoluteUrl(path, "${config.baseUrl.trimEnd('/')}/")
        val html = http.text(pageUrl, mapOf(
            "User-Agent" to userAgent,
            "Referer" to "${config.baseUrl.trimEnd('/')}/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        ))
        val player = Jsoup.parse(html).selectFirst("video-player")
            ?: throw ProviderException("AnimeUnity video-player element missing")
        val episodes = player.attr("episodes").takeIf(String::isNotBlank)?.let {
            logger.attempt("[AnimeUnity] Invalid episode list") {
                parseJson(it) as? JsonArray ?: throw ProviderException("Expected an episode array")
            }
        }.orEmpty()
        var target = episodes.filterIsInstance<JsonObject>().firstOrNull { it.number("number") == episode.toDouble() }
        if (target == null && episodes.isNotEmpty()) {
            val anime = player.attr("anime").takeIf(String::isNotBlank)?.let {
                logger.attempt("[AnimeUnity] Invalid anime metadata") { parseObject(it) }
            }
            val animeId = anime?.text("id") ?: Regex("""/anime/(\d+)""").find(path)?.groupValues?.get(1)
            if (animeId != null) {
                val start = (episode.toLong() - 1) / 120 * 120 + 1
                val data = logger.attempt("[AnimeUnity] Episode pagination failed") {
                    http.json(
                        withQuery("${config.baseUrl.trimEnd('/')}/info_api/$animeId/1", mapOf(
                            "start_range" to start.toString(), "end_range" to (start + 119).toString(),
                        )),
                        mapOf("User-Agent" to userAgent, "Referer" to pageUrl,
                            "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json"),
                    )
                }
                target = data?.array("episodes")?.filterIsInstance<JsonObject>()
                    ?.firstOrNull { it.number("number") == episode.toDouble() }
            }
        }
        target?.text("embed_url")?.takeIf(String::isNotBlank)?.let { return absoluteUrl(it, pageUrl) }
        target?.text("id")?.let { episodeId ->
            val embed = logger.attempt("[AnimeUnity] Episode embed endpoint failed") {
                http.text("${config.baseUrl.trimEnd('/')}/embed-url/$episodeId", mapOf(
                    "User-Agent" to userAgent, "Referer" to pageUrl, "X-Requested-With" to "XMLHttpRequest",
                )).trim()
            }
            if (embed != null && Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(embed)) return embed
        }
        return player.attr("embed_url").takeIf { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    }

    private suspend fun vixCloudMaster(embedUrl: String): Pair<String, Boolean> {
        val html = http.text(embedUrl, playHeaders)
        fun credential(name: String): String? =
            Regex("""['"]$name['"]\s*:\s*['"]([^'"]*)['"]""").find(html)?.groupValues?.get(1)
        val token = credential("token")?.takeIf(String::isNotEmpty)
            ?: throw ProviderException("VixCloud token missing")
        val expires = credential("expires")?.takeIf(String::isNotEmpty)
            ?: throw ProviderException("VixCloud expiry missing")
        val playlist = Regex("""url\s*:\s*['"]([^'"]+/playlist/\d+[^'"]*)['"]""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""/embed/(\d+)""").find(embedUrl)?.groupValues?.get(1)
                ?.let { "${config.vixCloudBaseUrl.trimEnd('/')}/playlist/$it" }
            ?: throw ProviderException("VixCloud playlist missing")
        val fhd = Regex("""canPlayFHD\s*=\s*true""", RegexOption.IGNORE_CASE).containsMatchIn(html) ||
            Regex("""[?&]canPlayFHD=1""").containsMatchIn(embedUrl)
        val parsed = playlist.toHttpUrlOrNull()
            ?: throw ProviderException("Invalid VixCloud playlist URL")
        val builder = parsed.newBuilder()
        if (!parsed.encodedPath.endsWith(".m3u8")) builder.encodedPath(parsed.encodedPath + ".m3u8")
        val params = mutableMapOf("token" to token, "expires" to expires)
        credential("asn")?.takeIf(String::isNotEmpty)?.let { params["asn"] = it }
        if (fhd) params["h"] = "1"
        return withQuery(builder.build().toString(), params) to fhd
    }
}
