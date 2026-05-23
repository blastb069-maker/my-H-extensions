package eu.kanade.tachiyomi.animeextension.it.hentaiworld

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
import keiyoushi.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HentaiWorld :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiWorld"
    override val baseUrl = "https://www.hentaiworld.me"
    override val lang = "it"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ── Popular ───────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/archive?sort=views&page=$page")

    override fun popularAnimeSelector() = "div.card"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = element.selectFirst("a[href^='/hentai/']")!!
        setUrlWithoutDomain(link.attr("href"))
        title = link.attr("title").ifBlank {
            element.selectFirst("h3,h4,.card-title")?.text() ?: link.text()
        }
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    override fun popularAnimeNextPageSelector() =
        "a[rel=next], li.page-item:last-child:not(.disabled) a"

    // ── Latest ────────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/archive?sort=date&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(e: Element) = popularAnimeFromElement(e)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // ── Search ────────────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/archive".toHttpUrl().newBuilder()
        if (query.isNotBlank()) url.addQueryParameter("search", query)
        filters.forEach { f ->
            if (f is SelectFilter) f.addTo(url)
        }
        url.addQueryParameter("page", page.toString())
        return GET(url.build().toString())
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(e: Element) = popularAnimeFromElement(e)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ── Details ───────────────────────────────────────────────────────────────

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1")?.text() ?: ""
        thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img[src*='/locandine/']")?.attr("abs:src")
        genre = document.select("a[href*='genre=']").joinToString { it.text() }
        description = document.selectFirst("div#trama,div.trama,div.description")?.text()
            ?: document.selectFirst("meta[name='description']")?.attr("content")
        val alt = document.selectFirst("h2")?.text()?.takeIf { it.isNotBlank() }
        if (alt != null && !title.contains(alt, true)) {
            description = (description ?: "") + "\n\nTitolo alternativo: $alt"
        }
        val txt = document.text()
        status = when {
            txt.contains("In corso") -> SAnime.ONGOING
            txt.contains("Finito") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ── Episodes ──────────────────────────────────────────────────────────────

    override fun episodeListParse(response: Response): List<SEpisode> =
        response.asJsoup().select("a[href*='/watch/']")
            .mapIndexed { i, el ->
                SEpisode.create().apply {
                    setUrlWithoutDomain(el.attr("href"))
                    val txt = el.text().removePrefix("Guarda ").trim()
                    name = txt.ifBlank { "Episodio ${i + 1}" }
                    episode_number = Regex("""[Ee]pisodio[- ]?(\d+)""")
                        .find(el.attr("href") + " " + txt)
                        ?.groupValues?.get(1)?.toFloatOrNull() ?: (i + 1).toFloat()
                }
            }.reversed()

    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(e: Element) = throw UnsupportedOperationException()

    // ── Video ─────────────────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode) = GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val ref = headers.newBuilder().add("Referer", doc.location()).build()

        doc.selectFirst("[data-id],[data-episode-id]")?.attr("data-id").orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { id ->
                runCatching {
                    val json = client.newCall(GET("$baseUrl/api/episode/serverPlayerInfo?id=$id", ref)).execute().body.string()
                    Regex(""""file"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                        ?.let { return resolveUrl(it, ref) }
                }
            }

        Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            .find(doc.html())?.groupValues?.get(1)
            ?.let { return resolveUrl(it, ref) }

        doc.selectFirst("source[src],video[src]")?.attr("abs:src")
            ?.takeIf { it.isNotBlank() }
            ?.let { return resolveUrl(it, ref) }

        doc.selectFirst("iframe[src]")?.attr("abs:src")?.let { src ->
            runCatching {
                val inner = client.newCall(GET(src, ref)).execute().asJsoup()
                Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                    .find(inner.html())?.groupValues?.get(1)
                    ?.let { return resolveUrl(it, ref) }
                inner.selectFirst("source[src],video[src]")?.attr("abs:src")
                    ?.let { return resolveUrl(it, ref) }
            }
        }

        return emptyList()
    }

    private fun resolveUrl(url: String, ref: okhttp3.Headers): List<Video> {
        if (!url.contains(".m3u8")) return listOf(Video(url, "MP4", url, headers = ref))
        return runCatching {
            val playlist = client.newCall(GET(url, ref)).execute().body.string()
            val qualities = Regex("""RESOLUTION=\d+x(\d+)""").findAll(playlist).map { "${it.groupValues[1]}p" }.toList()
            val links = Regex("""(?m)^(?!#)[^\s]+""").findAll(playlist).map { it.value }.toList()
            if (links.isEmpty()) return listOf(Video(url, "HLS", url, headers = ref))
            links.mapIndexed { i, link ->
                val full = if (link.startsWith("http")) link else url.substringBeforeLast("/") + "/" + link
                Video(full, qualities.getOrElse(i) { "Q${i + 1}" }, full, headers = ref)
            }
        }.getOrElse { listOf(Video(url, "HLS", url, headers = ref)) }
    }

    override fun List<Video>.sort(): List<Video> {
        val pref = preferences.getString("preferred_quality", "1080")!!
        return sortedByDescending { it.quality.contains(pref) }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(e: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

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
            "Mind Break", "Mind Control", "Monster", "Nekomimi", "NTR", "Nurse", "Orgy",
            "Plot", "POV", "Pregnant", "Public Sex", "Rimjob", "Scat", "School Girl",
            "Softcore", "Swimsuit", "Teacher", "Tentacle", "Threesome", "Toys", "Trap",
            "Tsundere", "Ugly Bastard", "Uncensored", "Vanilla", "Virgin", "Watersports",
            "X-Ray", "Yaoi", "Yuri",
        ),
        arrayOf(
            "", "3d", "ahegao", "anal", "bdsm", "big-boobs", "blow-job", "bondage",
            "boob-job", "censored", "comedy", "cosplay", "creampie", "dark-skin", "facial",
            "fantasy", "filmed", "foot-job", "futanari", "gangbang", "glasses", "hand-job",
            "harem", "horror", "incest", "lactation", "maid", "masturbation", "milf",
            "mind-break", "mind-control", "monster", "nekomimi", "ntr", "nurse", "orgy",
            "plot", "pov", "pregnant", "public-sex", "rimjob", "scat", "school-girl",
            "softcore", "swimsuit", "teacher", "tentacle", "threesome", "toys", "trap",
            "tsundere", "ugly-bastard", "uncensored", "vanilla", "virgin", "watersports",
            "x-ray", "yaoi", "yuri",
        ),
    )

    private class YearFilter : SelectFilter(
        "Anno",
        "year",
        arrayOf(
            "Tutti", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019",
            "2018", "2017", "2016", "2015", "2014", "2013", "2012", "2011", "2010",
            "2009", "2008", "2007", "2006", "2005", "2004", "2003", "2002", "2001",
            "2000", "1999", "1998", "1997", "1996",
        ),
        arrayOf(
            "", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019",
            "2018", "2017", "2016", "2015", "2014", "2013", "2012", "2011", "2010",
            "2009", "2008", "2007", "2006", "2005", "2004", "2003", "2002", "2001",
            "2000", "1999", "1998", "1997", "1996",
        ),
    )

    private class StatusFilter : SelectFilter(
        "Stato",
        "status",
        arrayOf("Tutti", "Finito", "In corso", "Non rilasciato", "Droppato"),
        arrayOf("", "Finito", "In corso", "Non rilasciato", "Droppato"),
    )

    private class LangFilter : SelectFilter(
        "Lingua",
        "language",
        arrayOf("Tutte", "SUB ITA", "SUB ENG", "DUB ITA"),
        arrayOf("", "SUB ITA", "SUB ENG", "DUB ITA"),
    )

    private class SortFilter : SelectFilter(
        "Ordina per",
        "sort",
        arrayOf("Standard", "Ultime aggiunte", "Più visti", "A-Z", "Voto maggiore"),
        arrayOf("", "date", "views", "alphabetical", "rating"),
    )

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("I filtri vengono ignorati con la ricerca testuale"),
        GenreFilter(),
        YearFilter(),
        StatusFilter(),
        LangFilter(),
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
            setOnPreferenceChangeListener { _, v -> preferences.edit().putString(key, v as String).commit() }
        }.also(screen::addPreference)
    }
}
