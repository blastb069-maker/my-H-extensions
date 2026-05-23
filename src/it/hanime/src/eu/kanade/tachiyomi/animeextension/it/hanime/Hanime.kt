package eu.kanade.tachiyomi.animeextension.it.hanime

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element


class Hanime : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Hanime.tv"
    override val baseUrl = "https://hanime.tv"
    override val lang = "it"
    override val supportsLatest = true

    private val apiBase = "https://hanime.tv"
    private val searchApi = "https://search.hanime.tv"

 private val preferences by lazy {
    val app = Class.forName("android.app.ActivityThread")
        .getMethod("currentApplication")
        .invoke(null) as android.app.Application
    app.getSharedPreferences("source_${id}", 0)
}

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // Headers richiesti dall'API di hanime.tv
    override fun headersBuilder() = super.headersBuilder()
        .add("Accept", "application/json, text/plain, */*")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ── Modelli JSON ──────────────────────────────────────────────────────────

    @Serializable
    data class HentaiVideo(
        val id: Int = 0,
        val name: String = "",
        val slug: String = "",
        @SerialName("cover_url") val coverUrl: String = "",
        @SerialName("poster_url") val posterUrl: String = "",
        val description: String = "",
        val views: Long = 0,
        val likes: Int = 0,
        val tags: List<Tag> = emptyList(),
        @SerialName("hentai_video") val videoInfo: VideoInfo? = null,
        @SerialName("hentai_franchise_hentai_videos") val franchiseVideos: List<FranchiseVideo> = emptyList(),
        val streams: List<Stream> = emptyList(),
    )

    @Serializable
    data class Tag(val text: String = "")

    @Serializable
    data class VideoInfo(
        val id: Int = 0,
        val slug: String = "",
        val name: String = "",
        @SerialName("cover_url") val coverUrl: String = "",
        val tags: List<Tag> = emptyList(),
        val brands: List<Brand> = emptyList(),
        @SerialName("hentai_videos") val episodes: List<EpisodeInfo> = emptyList(),
    )

    @Serializable
    data class Brand(val title: String = "")

    @Serializable
    data class EpisodeInfo(
        val id: Int = 0,
        val name: String = "",
        val slug: String = "",
        @SerialName("cover_url") val coverUrl: String = "",
    )

    @Serializable
    data class FranchiseVideo(
        val id: Int = 0,
        val name: String = "",
        val slug: String = "",
        @SerialName("cover_url") val coverUrl: String = "",
    )

    @Serializable
    data class Stream(
        val width: Int = 0,
        val height: Int = 0,
        val url: String = "",
        @SerialName("size_mbs") val sizeMbs: Float = 0f,
        @SerialName("is_guest_allowed") val isGuestAllowed: Boolean = true,
    )

    @Serializable
    data class SearchResult(
        val hits: List<HitItem> = emptyList(),
        @SerialName("nbPages") val nbPages: Int = 1,
    )

    @Serializable
    data class HitItem(
        val id: Int = 0,
        val name: String = "",
        val slug: String = "",
        @SerialName("cover_url") val coverUrl: String = "",
        @SerialName("poster_url") val posterUrl: String = "",
    )

    @Serializable
    data class TrendingResponse(
        @SerialName("hentai_videos") val hentaiVideos: List<HentaiVideo> = emptyList(),
    )

    @Serializable
    data class VideoResponse(
        @SerialName("hentai_video") val hentaiVideo: HentaiVideo = HentaiVideo(),
        val streams: List<Stream> = emptyList(),
    )

    // ── Popular ───────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request =
        GET("$apiBase/api/v8/hentai-videos?order_by=views&ordering=desc&page=${page - 1}&tags_mode=OR", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<TrendingResponse>(response.body.string())
        val animes = parsed.hentaiVideos.map { it.toSAnime() }
        return AnimesPage(animes, animes.size >= 24)
    }

    // ── Latest ────────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$apiBase/api/v8/hentai-videos?order_by=created_at_unix&ordering=desc&page=${page - 1}&tags_mode=OR", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ── Search ────────────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // Se c'è testo usa l'endpoint di ricerca Algolia-based
        val tagFilter = filters.filterIsInstance<TagFilter>().firstOrNull()
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        val selectedTags = tagFilter?.state
            ?.filterIndexed { _, cb -> cb.state }
            ?.map { it.name } ?: emptyList()

        return if (query.isNotBlank() || selectedTags.isNotEmpty()) {
            val body = buildString {
                append("{")
                append("\"search_text\":\"$query\",")
                append("\"tags\":${selectedTags.map { "\"$it\"" }},")
                append("\"tags_mode\":\"OR\",")
                append("\"brands\":[],")
                append("\"blacklist\":[],")
                append("\"order_by\":\"${sortFilter?.selected ?: "created_at_unix"}\",")
                append("\"ordering\":\"desc\",")
                append("\"page\":${page - 1}")
                append("}")
            }
            POST("$searchApi/", headers, body.toRequestBody("application/json".toMediaType()))
        } else {
            val sort = sortFilter?.selected ?: "created_at_unix"
            GET("$apiBase/api/v8/hentai-videos?order_by=$sort&ordering=desc&page=${page - 1}&tags_mode=OR", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val raw = response.body.string()
        return try {
            // Risposta ricerca (ha "hits")
            val sr = json.decodeFromString<SearchResult>(raw)
            val animes = sr.hits.map { hit ->
                SAnime.create().apply {
                    setUrlWithoutDomain("/hentai-videos/${hit.slug}")
                    title = hit.name
                    thumbnail_url = hit.coverUrl.ifBlank { hit.posterUrl }
                }
            }
            AnimesPage(animes, sr.nbPages > 1)
        } catch (e: Exception) {
            // Fallback lista normale
            popularAnimeParse(response)
        }
    }

    // ── Details ───────────────────────────────────────────────────────────────

    override fun animeDetailsRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfterLast("/")
        return GET("$apiBase/api/v8/video?id=$slug", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val vr = json.decodeFromString<VideoResponse>(response.body.string())
        val v = vr.hentaiVideo
        return SAnime.create().apply {
            title = v.videoInfo?.name ?: v.name
            thumbnail_url = v.videoInfo?.coverUrl?.ifBlank { null } ?: v.coverUrl
            description = v.description
            genre = (v.videoInfo?.tags ?: v.tags).joinToString { it.text }
            author = v.videoInfo?.brands?.joinToString { it.title }
            status = SAnime.UNKNOWN
        }
    }

    // ── Episodes ──────────────────────────────────────────────────────────────

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfterLast("/")
        return GET("$apiBase/api/v8/video?id=$slug", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val vr = json.decodeFromString<VideoResponse>(response.body.string())
        val episodes = vr.hentaiVideo.videoInfo?.episodes
            ?: vr.hentaiVideo.franchiseVideos.map {
                EpisodeInfo(id = it.id, name = it.name, slug = it.slug, coverUrl = it.coverUrl)
            }

        return if (episodes.isEmpty()) {
            // Video singolo (OVA / standalone)
            listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain("/hentai-videos/${vr.hentaiVideo.slug}")
                    name = vr.hentaiVideo.name
                    episode_number = 1f
                },
            )
        } else {
            episodes.mapIndexed { i, ep ->
                SEpisode.create().apply {
                    setUrlWithoutDomain("/hentai-videos/${ep.slug}")
                    name = ep.name.ifBlank { "Episodio ${i + 1}" }
                    episode_number = Regex("""(\d+)""").find(ep.name)?.groupValues?.get(1)?.toFloatOrNull()
                        ?: (i + 1).toFloat()
                }
            }.reversed()
        }
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode): Request {
        val slug = episode.url.substringAfterLast("/")
        return GET("$apiBase/api/v8/video?id=$slug", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val vr = json.decodeFromString<VideoResponse>(response.body.string())
        val streams = vr.streams.ifEmpty { vr.hentaiVideo.streams }
        return streams
            .filter { it.isGuestAllowed && it.url.isNotBlank() }
            .mapNotNull { s ->
                val quality = if (s.height > 0) "${s.height}p" else "Default"
                Video(s.url, quality, s.url, headers = headers)
            }
            .sortedByDescending { v ->
                val q = preferences.getString("preferred_quality", "1080")!!
                v.quality.contains(q)
            }
    }

    override fun List<Video>.sort(): List<Video> {
        val q = preferences.getString("preferred_quality", "1080")!!
        return sortedByDescending { it.quality.contains(q) }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun HentaiVideo.toSAnime() = SAnime.create().apply {
        setUrlWithoutDomain("/hentai-videos/$slug")
        title = name
        thumbnail_url = coverUrl.ifBlank { posterUrl }
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    private class TagCheckBox(name: String) : AnimeFilter.CheckBox(name)
    private class TagFilter : AnimeFilter.Group<TagCheckBox>(
        "Tag",
        listOf(
            "Ahegao","Anal","BDSM","Big Boobs","Blow Job","Bondage","Boob Job","Censored",
            "Comedy","Cosplay","Creampie","Dark Skin","Facial","Fantasy","Filmed","Foot Job",
            "Futanari","Gangbang","Glasses","Hand Job","Harem","Horror","Incest","Inflation",
            "Lactation","Maid","Masturbation","Milf","Mind Break","Mind Control","Monster",
            "NTR","Nurse","Orgy","Plot","POV","Pregnant","Public Sex","Rape","Rimjob","Scat",
            "School Girl","Softcore","Swimsuit","Teacher","Tentacle","Threesome","Toys","Trap",
            "Tsundere","Ugly Bastard","Uncensored","Vanilla","Virgin","Watersports","Yaoi","Yuri",
        ).map { TagCheckBox(it) },
    )

    private class SortFilter : AnimeFilter.Select<String>(
        "Ordina per",
        arrayOf("Più recenti", "Più visti", "Likes", "A-Z"),
    ) {
        val vals = arrayOf("created_at_unix", "views", "likes", "title")
        val selected get() = vals[state]
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("I tag vengono ignorati se si usa la ricerca testuale"),
        TagFilter(),
        SortFilter(),
    )

    // ── Preferences ───────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"; title = "Qualità preferita"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080"); summary = "%s"
            setOnPreferenceChangeListener { _, v -> preferences.edit().putString(key, v as String).commit() }
        }.also(screen::addPreference)
    }
}
