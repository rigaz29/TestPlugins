package com.goodshort

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Document

class GoodShortProvider : MainAPI() {
    override var mainUrl = "https://www.goodshort.com"
    override var name = "GoodShort"
    override var lang = "id"
    override val hasMainPage = true
    override val hasSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // =========================================================
    //  MAIN PAGE
    // =========================================================

    override val mainPage = mainPageOf(
        "$mainUrl/id"                          to "🏠 Beranda",
        "$mainUrl/dramas/romansa-137-lakon"    to "💕 Romansa",
        "$mainUrl/dramas/fantasi-135-lakon"    to "🔮 Fantasi",
        "$mainUrl/dramas/urban-136-lakon"      to "🏙 Urban",
        "$mainUrl/dramas/komedi-138-lakon"     to "😂 Komedi",
        "$mainUrl/dramas/thriller-139-lakon"   to "😱 Thriller",
        "$mainUrl/dramas/superpower-140-lakon" to "⚡ Superpower",
        "$mainUrl/dramas/drama-kostum-141-lakon" to "👘 Drama Kostum",
        "$mainUrl/dramas/misteri-149-lakon"    to "🕵️ Misteri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHome = request.data.endsWith("/id")
        val url = if (isHome) request.data else "${request.data}?page=$page"
        val html = app.get(url).text
        val state = extractInitialState(html) ?: return newHomePageResponse(request.name, emptyList())

        val results = mutableListOf<SearchResponse>()

        if (isHome) {
            // Homepage: parse HomeModule.bookList (array of channel objects)
            val bookList = getNestedList(state, "HomeModule", "bookList")
            for (channel in bookList) {
                val channelMap = channel as? Map<*, *> ?: continue
                val items = channelMap["items"] as? List<*> ?: continue
                for (item in items) {
                    val book = item as? Map<*, *> ?: continue
                    parseBookToSearchResponse(book)?.let { results.add(it) }
                }
            }
        } else {
            // Browse page: parse Browse.bookList
            val bookList = getNestedList(state, "Browse", "bookList")
            for (item in bookList) {
                val book = item as? Map<*, *> ?: continue
                parseBookToSearchResponse(book)?.let { results.add(it) }
            }
        }

        return newHomePageResponse(request.name, results)
    }

    // =========================================================
    //  SEARCH
    // =========================================================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/results?q=${query.encodeUrl()}"
        val html = app.get(url).text
        val state = extractInitialState(html) ?: return emptyList()

        val records: List<*> = getNestedList(state, "SearchModule", "searchResult", "page", "records")

        return records.mapNotNull { item ->
            parseBookToSearchResponse(item as? Map<*, *> ?: return@mapNotNull null)
        }
    }

    // =========================================================
    //  LOAD (Drama detail + episode list)
    // =========================================================

    /**
     * `url` format:  https://www.goodshort.com/episodes/{bookResourceUrl}
     *
     * We load all episode pages (paginated) to collect chapter links,
     * then build a TvSeries response.
     */
    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text
        val state = extractInitialState(html) ?: return null

        // ---- Book metadata ----
        val book = getNestedMap(state, "BookInfoModule", "book") ?: return null

        val bookName        = book["bookName"]        as? String ?: return null
        val cover           = book["cover"]           as? String ?: ""
        val introduction    = book["introduction"]    as? String ?: ""
        val bookResourceUrl = book["bookResourceUrl"] as? String ?: return null
        val writeStatus     = book["writeStatusDisplay"] as? String
        val chapterCount    = (book["chapterCount"] as? Number)?.toInt() ?: 0

        val tags = (book["tagsList"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
            ?: emptyList()

        // ---- Episodes ----
        val catalogMap = getNestedMap(state, "BookCatalog")
        val totalPages  = (catalogMap?.get("pages") as? Number)?.toInt() ?: 1
        val pageSize    = (catalogMap?.get("pageSize") as? Number)?.toInt() ?: 24

        val allChapters = mutableListOf<Map<*, *>>()
        // First page chapters are already in the state
        (catalogMap?.get("chapterList") as? List<*>)?.forEach { ch ->
            (ch as? Map<*, *>)?.let { allChapters.add(it) }
        }

        // Fetch remaining pages
        for (p in 2..totalPages) {
            val pageHtml = app.get("$mainUrl/episodes/$bookResourceUrl?page=$p").text
            val pageState = extractInitialState(pageHtml) ?: continue
            val pageCatalog = getNestedMap(pageState, "BookCatalog") ?: continue
            (pageCatalog["chapterList"] as? List<*>)?.forEach { ch ->
                (ch as? Map<*, *>)?.let { allChapters.add(it) }
            }
        }

        val episodes = allChapters.mapIndexed { index, chapter ->
            val chapterResourceUrl = chapter["chapterResourceUrl"] as? String ?: return@mapIndexed null
            val epNum  = index + 1
            val epName = "EP ${chapter["chapterName"] as? String ?: epNum.toString()}"
            val thumb  = chapter["image"] as? String
            val isLocked = ((chapter["price"] as? Number)?.toInt() ?: 0) > 0

            newEpisode("$mainUrl/episode/$bookResourceUrl/$chapterResourceUrl") {
                this.name     = if (isLocked) "🔒 $epName" else epName
                this.episode  = epNum
                this.posterUrl = thumb
            }
        }.filterNotNull()

        return newTvSeriesLoadResponse(bookName, url, TvType.TvSeries, episodes) {
            this.posterUrl   = cover
            this.plot        = buildString {
                if (tags.isNotEmpty()) append("🏷 ${tags.joinToString(" • ")}\n\n")
                append(introduction)
                if (writeStatus != null) append("\n\n📊 Status: $writeStatus ($chapterCount episode)")
            }
        }
    }

    // =========================================================
    //  LOAD LINKS (actual m3u8 for an episode)
    // =========================================================

    /**
     * `data` format:  https://www.goodshort.com/episode/{bookResourceUrl}/{chapterResourceUrl}
     *
     * Strategy:
     *  1. Parse __INITIAL_STATE__ → BookRead.chapterInfo.m3u8Path  (fastest)
     *  2. Fall back to JSON-LD VideoObject.contentUrl
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(
            data,
            headers = mapOf(
                "Referer"    to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36"
            )
        ).text

        // --- Strategy 1: __INITIAL_STATE__ ---
        val state       = extractInitialState(html)
        val chapterInfo = getNestedMap(state, "BookRead", "chapterInfo")
        val m3u8Path    = chapterInfo?.get("m3u8Path") as? String

        if (!m3u8Path.isNullOrBlank()) {
            callback(
                ExtractorLink(
                    source    = this.name,
                    name      = this.name,
                    url       = m3u8Path,
                    referer   = "$mainUrl/",
                    quality   = Qualities.Unknown.value,
                    isM3u8    = true,
                    headers   = mapOf("Referer" to "$mainUrl/")
                )
            )
            return true
        }

        // --- Strategy 2: JSON-LD VideoObject ---
        val doc = org.jsoup.Jsoup.parse(html)
        for (scriptTag in doc.select("script[type=application/ld+json]")) {
            val jsonText = scriptTag.html()
            val parsed   = tryParseJson<Map<*, *>>(jsonText) ?: continue
            if (parsed["@type"] == "VideoObject") {
                val contentUrl = parsed["contentUrl"] as? String ?: continue
                callback(
                    ExtractorLink(
                        source  = this.name,
                        name    = this.name,
                        url     = contentUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        isM3u8  = true,
                        headers = mapOf("Referer" to "$mainUrl/")
                    )
                )
                return true
            }
        }

        return false
    }

    // =========================================================
    //  HELPERS
    // =========================================================

    /** Extract and parse window.__INITIAL_STATE__ = {...}; from HTML */
    private fun extractInitialState(html: String): Map<*, *>? {
        // The state is terminated by ";(function(){" or ";</script>"
        val regex = Regex(
            """window\.__INITIAL_STATE__=(\{.+?\});\(function\(\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = regex.find(html) ?: run {
            // Fallback: try without the trailing ;(function
            val fallback = Regex(
                """window\.__INITIAL_STATE__=(\{.+?\});</script>""",
                RegexOption.DOT_MATCHES_ALL
            )
            fallback.find(html)
        } ?: return null
        return tryParseJson<Map<*, *>>(match.groupValues[1])
    }

    /** Safely navigate nested maps */
    private fun getNestedMap(root: Map<*, *>?, vararg keys: String): Map<*, *>? {
        var current: Map<*, *>? = root
        for (key in keys) {
            current = current?.get(key) as? Map<*, *> ?: return null
        }
        return current
    }

    private fun getNestedMap(state: Map<*, *>?, vararg keys: String): Map<*, *>? {
        var current: Map<*, *>? = state
        for (key in keys) {
            current = (current?.get(key) as? Map<*, *>) ?: return null
        }
        return current
    }

    private fun getNestedList(root: Map<*, *>?, vararg keys: String): List<*> {
        if (keys.isEmpty()) return emptyList<Any>()
        val lastKey = keys.last()
        val parentKeys = keys.dropLast(1)
        var current: Map<*, *>? = root
        for (key in parentKeys) {
            current = (current?.get(key) as? Map<*, *>) ?: return emptyList<Any>()
        }
        return current?.get(lastKey) as? List<*> ?: emptyList<Any>()
    }

    /** Convert a raw book map to a CloudStream SearchResponse */
    private fun parseBookToSearchResponse(book: Map<*, *>): SearchResponse? {
        val bookName        = book["bookName"]        as? String ?: return null
        val cover           = book["cover"]           as? String ?: ""
        val bookResourceUrl = book["bookResourceUrl"] as? String ?: return null

        return newTvSeriesSearchResponse(
            name = bookName,
            url  = "$mainUrl/episodes/$bookResourceUrl",
        ) {
            this.posterUrl = cover
        }
    }

    /** Simple URL encoding for query strings */
    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
