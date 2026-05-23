package eu.kanade.tachiyomi.animeextension.it.hentaisaturn

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
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HentaiSaturn :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiSaturn"
    override val baseUrl = "https://www.hentaisaturn.tv"
    override val lang = "it"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private fun fmt(t: String) = t
        .replace("(ITA) ITA", "Dub ITA")
        .replace("(ITA)", "Dub ITA")
        .replace("Sub ITA", "")
        .trim()

    // ── Popular ───────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/toplist")

    override fun popularAnimeSelector() = "div.col-md-2.float-left.hentai-img-box-col.hentai-padding-top"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        val img = element.selectFirst("a img.img-fluid.w-100.rounded.hentai-img")!!
        title = fmt(img.attr("title"))
        thumbnail_url = img.attr("src")
    }

    override fun popularAnimeNextPageSelector() = "li.page-item.active:not(li:last-child)"

    // ── Latest ────────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/newest?page=$page")

    override fun latestUpdatesSelector() = "div.card.mb-4.shadow-sm"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = fmt(element.selectFirst("a")!!.attr("title"))
        thumbnail_url = element.selectFirst("a img.new-hentai")!!.attr("src")
    }

    override fun latestUpdatesNextPageSelector() = "li.page-item.active:not(li:last-child)"

    // ── Search ────────────────────────────────────────────────────────────────

    private var filterSearch = false

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val parameters = getSearchParameters(filters)
        return if (parameters.isEmpty()) {
            filterSearch = false
            GET("$baseUrl/hentailist?search=$query")
        } else {
            filterSearch = true
            GET("$baseUrl/filter?$parameters&page=$page")
        }
    }

    override fun searchAnimeSelector() = if (filterSearch) {
        "div.hentai-card-newhentai.main-hentai-card"
    } else {
        "div.item-archivio"
    }

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        if (filterSearch) {
            setUrlWithoutDomain(element.selectFirst("div.card.mb-4.shadow-sm a")!!.attr("href"))
            title = fmt(element.selectFirst("div.card.mb-4.shadow-sm a")!!.attr("title"))
            thumbnail_url = element.selectFirst("div.card.mb-4.shadow-sm a img.new-hentai")!!.attr("src")
        } else {
            setUrlWithoutDomain(element.selectFirst("a.thumb.image-wrapper")!!.attr("href"))
            title = fmt(element.selectFirst("a.thumb.image-wrapper img.rounded.copertina-archivio")!!.attr("alt"))
            thumbnail_url = element.select("a.thumb.image-wrapper img.rounded.copertina-archivio").attr("src")
        }
    }

    override fun searchAnimeNextPageSelector() = "li.page-item.active:not(li:last-child)"

    // ── Details ───────────────────────────────────────────────────────────────

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = fmt(document.select("div.container.hentai-title-as.mb-3.w-100 b").text())
        val temp = document.select("div.container.shadow.rounded.bg-dark-as-box.mb-3.p-3.w-100.text-white").text()
        val idxA = temp.indexOf("Stato:")
        author = temp.substring(7, idxA).trim()
        val s1 = temp.indexOf("Stato:") + 6
        val s2 = temp.indexOf("Data di uscita:")
        status = when {
            temp.substring(s1, s2).contains("In corso") -> SAnime.ONGOING
            temp.substring(s1, s2).contains("Finito") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        genre = document.select("div.container.shadow.rounded.bg-dark-as-box.mb-3.p-3.w-100 a.badge.badge-dark.generi-as.mb-1")
            .joinToString { it.text() }
        thumbnail_url = document.selectFirst("img.img-fluid.cover-anime.rounded")!!.attr("src")
        val alt = fmt(document.selectFirst("div.box-trasparente-alternativo.rounded")!!.text()).replace("Dub ITA", "").trim()
        val d1 = document.selectFirst("div#trama div#shown-trama")?.ownText()
        val d2 = document.selectFirst("div#full-trama.d-none")?.ownText()
        description = when {
            d1 == null -> d2
            d2 == null -> d1
            d1.length > d2.length -> d1
            else -> d2
        }
        if (!title.contains(alt, true)) {
            description = (description ?: "") + "\n\nTitolo Alternativo: $alt"
        }
    }

    // ── Episodes ──────────────────────────────────────────────────────────────

    override fun episodeListParse(response: Response): List<SEpisode> =
        response.asJsoup()
            .select(episodeListSelector())
            .map { episodeFromElement(it) }
            .reversed()

    override fun episodeListSelector() = "div.btn-group.episodes-button.episodi-link-button"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val btn = element.selectFirst("a.btn.btn-dark.mb-1.bottone-ep")!!
        setUrlWithoutDomain(btn.attr("href"))
        val t = btn.text()
        val n = t.substringAfter("Episodio ")
        episode_number = if (n.contains("-")) {
            n.substringBefore("-").toFloat()
        } else {
            n.toFloatOrNull() ?: 1f
        }
        name = t
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode): Request {
        val page = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val watchUrl = page.select("a[href*=/watch]").attr("href")
        return GET("$watchUrl&s=alt")
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val url = if (doc.html().contains("jwplayer(")) {
            doc.html().substringAfter("file: \"").substringBefore("\"")
        } else {
            doc.select("source").attr("src")
        }
        val ref = doc.location()
        return if (url.endsWith("playlist.m3u8")) {
            val pl = client.newCall(GET(url)).execute().body.string()
            val qs = Regex("""(?<=RESOLUTION=)\d+x\d+""").findAll(pl).map { it.value.substringAfter('x') + "p" }.toList()
            val ls = Regex("""(?<=\n)./.+""").findAll(pl).map { url.substringBefore("playlist.m3u8") + it.value.substringAfter("./") }.toList()
            ls.mapIndexed { i, l -> Video(l, qs.getOrElse(i) { "${i + 1}" }, l) }
        } else {
            listOf(Video(url, "Qualità predefinita", url, headers = Headers.headersOf("Referer", ref)))
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val q = preferences.getString("preferred_quality", "1080")!!
        return sortedByDescending { it.quality.contains(q) }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(e: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(d: Document) = throw UnsupportedOperationException()

    // ── Filters ───────────────────────────────────────────────────────────────

    internal class Genre(val id: String) : AnimeFilter.CheckBox(id)
    private class GenreList(g: List<Genre>) : AnimeFilter.Group<Genre>("Generi", g)
    private fun getGenres() = listOf(
        "3D", "Ahegao", "Anal", "BDSM", "Big Boobs", "Blow Job", "Bondage",
        "Boob Job", "Censored", "Comedy", "Cosplay", "Creampie", "Dark Skin",
        "Erotic Game", "Facial", "Fantasy", "Filmed", "Foot Job", "Futanari",
        "Gangbang", "Glasses", "Hand Job", "Harem", "HD", "Horror", "Incest",
        "Inflation", "Lactation", "Loli", "Maid", "Masturbation", "Milf",
        "Mind Break", "Mind Control", "Monster", "NTR", "Nurse", "Orgy",
        "Plot", "POV", "Pregnant", "Public Sex", "Rape", "Reverse Rape",
        "Rimjob", "Scat", "School Girl", "Shota", "Softcore", "Swimsuit",
        "Teacher", "Tentacle", "Threesome", "Toys", "Trap", "Tsundere",
        "Ugly Bastard", "Uncensored", "Vanilla", "Virgin", "Watersports",
        "X-Ray", "Yaoi", "Yuri",
    ).map { Genre(it) }

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(y: List<Year>) : AnimeFilter.Group<Year>("Anno", y)
    private fun getYears() = (2021 downTo 1996).map { Year(it.toString()) }

    internal class State(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class StateList(s: List<State>) : AnimeFilter.Group<State>("Stato", s)
    private fun getStates() = listOf(
        State("0", "In corso"),
        State("1", "Finito"),
        State("2", "Non rilasciato"),
        State("3", "Droppato"),
    )

    internal class Lang(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class LangList(l: List<Lang>) : AnimeFilter.Group<Lang>("Lingua", l)
    private fun getLangs() = listOf(Lang("0", "Subbato"), Lang("1", "Doppiato"))

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Ricerca per titolo ignora i filtri e viceversa"),
        GenreList(getGenres()),
        YearList(getYears()),
        StateList(getStates()),
        LangList(getLangs()),
    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        val sb = StringBuilder()
        var gI = 0
        var sI = 0
        var yI = 0
        filters.forEach { f ->
            when (f) {
                is GenreList -> f.state.forEach { if (it.state) sb.append("&categories%5B${gI++}%5D=${it.id}") }
                is YearList -> f.state.forEach { if (it.state) sb.append("&years%5B${yI++}%5D=${it.id}") }
                is StateList -> f.state.forEach { if (it.state) sb.append("&states%5B${sI++}%5D=${it.id}") }
                is LangList -> f.state.forEach { if (it.state) sb.append("&language%5B0%5D=${it.id}") }
                else -> {}
            }
        }
        return sb.toString()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Qualità preferita"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "144p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "144")
            setDefaultValue("1080")
            summary = "%s"
            setOnPreferenceChangeListener { _, v -> preferences.edit().putString(key, v as String).commit() }
        }.also(screen::addPreference)
    }
}
