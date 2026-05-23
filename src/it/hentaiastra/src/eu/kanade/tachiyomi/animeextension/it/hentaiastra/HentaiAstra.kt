package eu.kanade.tachiyomi.animeextension.it.hentaiastra

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

class HentaiAstra : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "HentaiAstra"
    override val baseUrl = "https://hentaiastra.com"
    override val lang = "it"
    override val supportsLatest = true

private val preferences by lazy {
    val app = Class.forName("android.app.ActivityThread")
        .getMethod("currentApplication")
        .invoke(null) as android.app.Application
  app.getSharedPreferences("source_${id}", 0)
}

    // Header con User-Agent desktop — il sito restituisce 403 a bot evidenti
    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "it-IT,it;q=0.9,en;q=0.5")
        .add("Referer", "$baseUrl/")

    // ── Popular ───────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/hentai?orderby=views&page=$page", headers)

    // Selector generico per card hentai — adattati ai nomi classi comuni di WordPress/custom theme
    override fun popularAnimeSelector(): String =
        "div.film-item, div.item, article.anime-card, div.card-hentai, li.hentai-item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href*='/hentai/'], a[href*='/watch/'], a.film-poster, a.thumb")!!
        setUrlWithoutDomain(link.attr("href"))
        title = link.attr("title").ifBlank {
            element.selectFirst("h2,h3,.film-name,.title")?.text() ?: link.text()
        }
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            ?: element.selectFirst("img[data-src]")?.attr("data-src")
    }

    override fun popularAnimeNextPageSelector(): String =
        "a.next, a[rel=next], li.page-item:last-child:not(.disabled) a, a.nextpostslink"

    // ── Latest ────────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/hentai?orderby=date&page=$page", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(e: Element) = popularAnimeFromElement(e)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // ── Search ────────────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/hentai".toHttpUrl().newBuilder()
        if (query.isNotBlank()) url.addQueryParameter("s", query)
        filters.forEach { f -> if (f is SelectFilter) f.addTo(url) }
        url.addQueryParameter("page", page.toString())
        return GET(url.build().toString(), headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(e: Element) = popularAnimeFromElement(e)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ── Details ───────────────────────────────────────────────────────────────

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.film-name, h1.entry-title, h1")?.text() ?: ""
        thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("div.film-poster img, img.cover, img.poster")?.attr("abs:src")
        description = document.selectFirst("div.film-description, div.entry-content p, div.trama, div.synopsis")?.text()
            ?: document.selectFirst("meta[name='description']")?.attr("content")
        genre = document.select("a[href*='/genere/'], a[href*='/genre/'], a[href*='?genre='], span.genre a")
            .joinToString { it.text() }
        author = document.selectFirst("a[href*='/studio/'], span.studio, .info-item:contains(Studio)")?.text()
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
        // Prova lista episodi dedicata, poi link singoli nella pagina
        val epLinks = doc.select(episodeListSelector())
        if (epLinks.isEmpty()) {
            // Pagina singolo episodio (OVA/special)
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.encodedPath)
                    name = doc.selectFirst("h1")?.text() ?: "Episodio 1"
                    episode_number = 1f
                },
            )
        }
        return epLinks.mapIndexed { i, el -> episodeFromElement(el, i) }.reversed()
    }

    override fun episodeListSelector(): String =
        "div.ep-item a, ul.episodes a, a[href*='/episodio-'], a[href*='/episode-'], a.btn-episode, a.ep-link"

    override fun episodeFromElement(element: Element): SEpisode = episodeFromElement(element, 0)

    private fun episodeFromElement(element: Element, index: Int): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val raw = element.text().trim().ifBlank {
            element.attr("title").ifBlank { "Episodio ${index + 1}" }
        }
        name = raw
        episode_number = Regex("""(?:episodio|episode|ep)[. _-]?(\d+)""", RegexOption.IGNORE_CASE)
            .find(element.attr("href") + " " + raw)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: (index + 1).toFloat()
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    override fun videoListRequest(episode: SEpisode) = GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val ref = headers.newBuilder().add("Referer", doc.location()).build()
        val videoList = mutableListOf<Video>()

        // 1) JWPlayer / VideoJS nel sorgente HTML
        val html = doc.html()
        Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            .find(html)?.groupValues?.get(1)?.let { videoList.addAll(resolveUrl(it, ref)) }
        if (videoList.isNotEmpty()) return videoList

        // 2) Tag <source src> o <video src>
        doc.selectFirst("source[src], video[src]")?.attr("abs:src")
            ?.takeIf { it.isNotBlank() }
            ?.let { videoList.addAll(resolveUrl(it, ref)) }
        if (videoList.isNotEmpty()) return videoList

        // 3) data-src / data-file nascosto in un div player
        doc.selectFirst("[data-src*='.m3u8'],[data-src*='.mp4'],[data-file]")
            ?.let { el ->
                val u = el.attr("data-src").ifBlank { el.attr("data-file") }
                if (u.isNotBlank()) videoList.addAll(resolveUrl(u, ref))
            }
        if (videoList.isNotEmpty()) return videoList

        // 4) iframe → analisi interna
        doc.selectFirst("iframe[src]")?.attr("abs:src")?.let { iframeSrc ->
            runCatching {
                val inner = client.newCall(GET(iframeSrc, ref)).execute().asJsoup()
                val innerHtml = inner.html()
                Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                    .find(innerHtml)?.groupValues?.get(1)
                    ?.let { videoList.addAll(resolveUrl(it, ref)) }
                if (videoList.isEmpty()) {
                    inner.selectFirst("source[src],video[src]")?.attr("abs:src")
                        ?.let { videoList.addAll(resolveUrl(it, ref)) }
                }
            }
        }

        return videoList
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
                val full = if (link.startsWith("http")) link
                else url.substringBeforeLast("/") + "/" + link
                Video(full, qualities.getOrElse(i) { "Q${i + 1}" }, full, headers = ref)
            }
        }.getOrElse { listOf(Video(url, "HLS", url, headers = ref)) }
    }

    override fun List<Video>.sort(): List<Video> {
        val q = preferences.getString("preferred_quality", "1080")!!
        return sortedByDescending { it.quality.contains(q) }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(e: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(d: Document) = throw UnsupportedOperationException()

    // ── Filters ───────────────────────────────────────────────────────────────

    abstract class SelectFilter(name: String, val param: String, val labels: Array<String>, val vals: Array<String>)
        : AnimeFilter.Select<String>(name, labels) {
       fun addTo(b: okhttp3.HttpUrl.Builder) { if (state != 0) b.addQueryParameter(param, vals[state]) }
    }

    private class GenreFilter : SelectFilter(
        "Genere", "genre",
        arrayOf("Tutti","3D","Ahegao","Anal","BDSM","Big Boobs","Blow Job","Bondage","Boob Job","Censored","Comedy","Cosplay","Creampie","Dark Skin","Facial","Fantasy","Filmed","Foot Job","Futanari","Gangbang","Glasses","Hand Job","Harem","Horror","Incest","Lactation","Maid","Masturbation","Milf","Mind Break","Mind Control","Monster","NTR","Nurse","Orgy","Plot","POV","Pregnant","Public Sex","Rimjob","Scat","School Girl","Softcore","Swimsuit","Teacher","Tentacle","Threesome","Toys","Trap","Tsundere","Ugly Bastard","Uncensored","Vanilla","Virgin","Watersports","X-Ray","Yaoi","Yuri"),
        arrayOf("","3d","ahegao","anal","bdsm","big-boobs","blow-job","bondage","boob-job","censored","comedy","cosplay","creampie","dark-skin","facial","fantasy","filmed","foot-job","futanari","gangbang","glasses","hand-job","harem","horror","incest","lactation","maid","masturbation","milf","mind-break","mind-control","monster","ntr","nurse","orgy","plot","pov","pregnant","public-sex","rimjob","scat","school-girl","softcore","swimsuit","teacher","tentacle","threesome","toys","trap","tsundere","ugly-bastard","uncensored","vanilla","virgin","watersports","x-ray","yaoi","yuri"),
    )

    private class SortFilter : SelectFilter(
        "Ordina per", "orderby",
        arrayOf("Standard","Ultime aggiunte","Più visti","A-Z","Voto maggiore"),
        arrayOf("","date","views","title","rating"),
    )

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("I filtri vengono ignorati con la ricerca testuale"),
        GenreFilter(),
        SortFilter(),
    )

    // ── Preferences ───────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "preferred_quality"; title = "Qualità preferita"
            entries = arrayOf("1080p","720p","480p","360p","240p")
            entryValues = arrayOf("1080","720","480","360","240")
            setDefaultValue("1080"); summary = "%s"
            setOnPreferenceChangeListener { _, v -> preferences.edit().putString(key, v as String).commit() }
        }.also(screen::addPreference)
    }
}
