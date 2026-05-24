package eu.kanade.tachiyomi.animeextension.it.hentaiastra

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HentaiAstra :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiAstra"
    override val baseUrl = "https://hentaiastra.com"
    override val lang = "it"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<android.app.Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "it-IT,it;q=0.9,en;q=0.5")
        .add("Referer", "$baseUrl/")

    private fun pageUrl(page: Int, query: String? = null): String {
        val path = if (page <= 1) "/" else "/page/$page/"
        if (query.isNullOrBlank()) return "$baseUrl$path"
        val encoded = query.trim().replace(" ", "+")
        return if (page <= 1) {
            "$baseUrl/?s=$encoded"
        } else {
            "$baseUrl/page/$page/?s=$encoded"
        }
    }

    private fun posterUrl(element: Element): String? {
        val poster = element.selectFirst("div.poster") ?: return element.selectFirst("img")?.attr("abs:src")
        val style = poster.attr("data-style").ifBlank { poster.attr("style") }
        if (style.isNotBlank()) {
            Regex("""url\((['"]?)([^'")]+)\1\)""").find(style)?.groupValues?.get(2)?.let { return it }
        }
        return element.selectFirst("img")?.attr("abs:src")
    }

    // ── Popular ───────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request =
        GET(pageUrl(page), headers)

    override fun popularAnimeSelector(): String = "div.MovieItem"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]")!!
        setUrlWithoutDomain(link.attr("href"))
        title = element.selectFirst("h4")?.text()?.trim().orEmpty().ifBlank { link.text() }
        thumbnail_url = posterUrl(element)
    }

    override fun popularAnimeNextPageSelector(): String =
        "a.nextpostslink, a.next.page-numbers, a[rel=next]"

    // ── Latest ────────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request =
        GET(pageUrl(page), headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(e: Element) = popularAnimeFromElement(e)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // ── Search ────────────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            return GET(pageUrl(page, query), headers)
        }
        val url = "$baseUrl/series".toHttpUrl().newBuilder()
        filters.forEach { f ->
            if (f is SelectFilter) f.addTo(url)
        }
        if (page > 1) url.addQueryParameter("page", page.toString())
        return GET(url.build().toString(), headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(e: Element) = popularAnimeFromElement(e)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ── Details ───────────────────────────────────────────────────────────────

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1, div.postSingle h1, div.titleArea h1")?.text()?.trim().orEmpty()
        thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: posterUrl(document.selectFirst("div.postSingle, div.MovieItem") ?: document)
        description = document.selectFirst("div.postSingle p, div.entry-content p, div.trama, div.synopsis")?.text()
            ?: document.selectFirst("meta[name='description']")?.attr("content")
        genre = document.select("span.genre, a[href*='/genere/'], a[href*='/genre/']")
            .joinToString { it.text() }
        val txt = document.text()
        status = when {
            txt.contains("In corso", ignoreCase = true) -> SAnime.ONGOING
            txt.contains("Finito", ignoreCase = true) || txt.contains("Completato", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ── Episodes ──────────────────────────────────────────────────────────────

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val epLinks = doc.select(episodeListSelector())
        if (epLinks.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(watchUrl(response.request.url.encodedPath))
                    name = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { "Episodio 1" }
                    episode_number = 1f
                },
            )
        }
        return epLinks.mapIndexed { i, el -> episodeFromElement(el, i) }.reversed()
    }

    override fun episodeListSelector(): String =
        "div.EpisodesList a[href*='-episode-'], div.ep-item a, ul.episodes a"

    override fun episodeFromElement(element: Element): SEpisode = episodeFromElement(element, 0)

    private fun episodeFromElement(element: Element, index: Int): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(watchUrl(element.attr("href")))
        val raw = element.text().trim().ifBlank {
            element.attr("title").ifBlank { "Episodio ${index + 1}" }
        }
        name = raw
        episode_number = Regex("""(?:episodio|episode|ep)[. _-]?(\d+)""", RegexOption.IGNORE_CASE)
            .find(element.attr("href") + " " + raw)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: (index + 1).toFloat()
    }

    private fun watchUrl(path: String): String {
        val clean = path.substringBefore("?")
        return if (clean.contains("watch=1")) clean else "$clean?watch=1"
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode): Request {
        val path = watchUrl(episode.url)
        return GET(baseUrl + path, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val ref = headers.newBuilder().add("Referer", doc.location()).build()
        val videoList = mutableListOf<Video>()

        val servers = doc.select("ul#watch li[data-watch]")
            .mapNotNull { it.attr("data-watch").takeIf { url -> url.isNotBlank() } }
            .sortedByDescending { it.contains("turbovidhls", true) || it.contains(".m3u8", true) }

        servers.forEach { server ->
            runCatching {
                videoList.addAll(extractFromEmbed(server, ref))
            }
        }
        if (videoList.isNotEmpty()) return videoList.distinctBy { it.url }

        Regex("""(?:videoUrl|const\s+videoUrl)\s*=\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""")
            .find(doc.html())?.groupValues?.get(1)
            ?.let { videoList.addAll(resolveUrl(it, ref)) }
        if (videoList.isNotEmpty()) return videoList

        Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            .find(doc.html())?.groupValues?.get(1)
            ?.let { videoList.addAll(resolveUrl(it, ref)) }
        if (videoList.isNotEmpty()) return videoList

        doc.selectFirst("source[src], video[src]")?.attr("abs:src")
            ?.takeIf { it.isNotBlank() }
            ?.let { videoList.addAll(resolveUrl(it, ref)) }
        if (videoList.isNotEmpty()) return videoList

        doc.selectFirst("iframe[src]")?.attr("abs:src")?.let { iframeSrc ->
            runCatching { videoList.addAll(extractFromEmbed(iframeSrc, ref)) }
        }

        return videoList
    }

    private fun extractFromEmbed(url: String, ref: okhttp3.Headers): List<Video> {
        if (url.contains(".m3u8", true) || url.contains(".mp4", true)) {
            return resolveUrl(url, ref)
        }
        val inner = client.newCall(GET(url, ref)).execute().asJsoup()
        Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""")
            .findAll(inner.html())
            .map { it.value }
            .firstOrNull()
            ?.let { return resolveUrl(it, ref) }
        Regex("""(?:videoUrl|const\s+videoUrl)\s*=\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""")
            .find(inner.html())?.groupValues?.get(1)
            ?.let { return resolveUrl(it, ref) }
        Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            .find(inner.html())?.groupValues?.get(1)
            ?.let { return resolveUrl(it, ref) }
        inner.selectFirst("source[src],video[src]")?.attr("abs:src")
            ?.let { return resolveUrl(it, ref) }
        return emptyList()
    }

    private fun resolveUrl(url: String, ref: okhttp3.Headers): List<Video> {
        if (!url.contains(".m3u8")) return listOf(Video(url, "MP4", url, headers = ref))
        return runCatching {
            val playlist = client.newCall(GET(url, ref)).execute().body.string()
            val qualities = Regex("""RESOLUTION=\d+x(\d+)""").findAll(playlist)
                .map { "${it.groupValues[1]}p" }.toList()
            val links = Regex("""(?m)^(?!#)[^\s]+""").findAll(playlist).map { it.value }.toList()
            if (links.isEmpty()) return listOf(Video(url, "HLS", url, headers = ref))
            links.mapIndexed { i, link ->
                val full = if (link.startsWith("http")) link else url.substringBeforeLast("/") + "/" + link
                Video(full, qualities.getOrElse(i) { "Q${i + 1}" }, full, headers = ref)
            }
        }.getOrElse { listOf(Video(url, "HLS", url, headers = ref)) }
    }

    override fun List<Video>.sort(): List<Video> {
        val q = preferences.getString("preferred_quality", "1080") ?: "1080"
        return sortedByDescending { it.quality.contains(q) }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(e: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(d: Document) = throw UnsupportedOperationException()

    // ── Filters ───────────────────────────────────────────────────────────────

    abstract class SelectFilter(
        name: String,
        val param: String,
        val filterLabels: Array<String>,
        val filterValues: Array<String>,
    ) : AnimeFilter.Select<String>(name, filterLabels) {
        fun addTo(b: okhttp3.HttpUrl.Builder) {
            if (state != 0) b.addQueryParameter(param, filterValues[state])
        }
    }

    private class GenreFilter : SelectFilter(
        "Genere",
        "genre",
        arrayOf(
            "Tutti", "3D", "Ahegao", "Anal", "BDSM", "Big Boobs", "Blow Job", "Bondage",
            "Boob Job", "Censored", "Comedy", "Cosplay", "Creampie", "Dark Skin", "Facial",
            "Fantasy", "Filmed", "Foot Job", "Futanari", "Gangbang", "Glasses", "Hand Job",
            "Harem", "Horror", "Incest", "Lactation", "Maid", "Masturbation", "Milf",
            "Mind Break", "Mind Control", "Monster", "NTR", "Nurse", "Orgy", "Plot", "POV",
            "Pregnant", "Public Sex", "Rimjob", "Scat", "School Girl", "Softcore", "Swimsuit",
            "Teacher", "Tentacle", "Threesome", "Toys", "Trap", "Tsundere", "Ugly Bastard",
            "Uncensored", "Vanilla", "Virgin", "Watersports", "X-Ray", "Yaoi", "Yuri",
        ),
        arrayOf(
            "", "3d", "ahegao", "anal", "bdsm", "big-boobs", "blow-job", "bondage",
            "boob-job", "censored", "comedy", "cosplay", "creampie", "dark-skin", "facial",
            "fantasy", "filmed", "foot-job", "futanari", "gangbang", "glasses", "hand-job",
            "harem", "horror", "incest", "lactation", "maid", "masturbation", "milf",
            "mind-break", "mind-control", "monster", "ntr", "nurse", "orgy", "plot", "pov",
            "pregnant", "public-sex", "rimjob", "scat", "school-girl", "softcore", "swimsuit",
            "teacher", "tentacle", "threesome", "toys", "trap", "tsundere", "ugly-bastard",
            "uncensored", "vanilla", "virgin", "watersports", "x-ray", "yaoi", "yuri",
        ),
    )

    private class SortFilter : SelectFilter(
        "Ordina per",
        "orderby",
        arrayOf("Standard", "Ultime aggiunte", "Più visti", "A-Z", "Voto maggiore"),
        arrayOf("", "date", "views", "title", "rating"),
    )

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("La ricerca testuale usa il motore del sito (?s=)"),
        GenreFilter(),
        SortFilter(),
    )

    // ── Preferences ───────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Qualità preferita"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"
            setOnPreferenceChangeListener { _, v ->
                preferences.edit().putString(key, v as String).commit()
            }
        }.also(screen::addPreference)
    }
}
