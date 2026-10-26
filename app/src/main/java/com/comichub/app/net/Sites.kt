package com.comichub.app.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// 四個站的資料轉接層（跟桌面版 src/sites.js 同一套邏輯，DOMParser 換成 Jsoup）。
// parseXxx 只吃字串、不連網路，JVM 單元測試直接拿存下來的網頁測。

internal fun html(s: String): Document = Jsoup.parse(s)

internal fun abs(u: String?, base: String): String = when {
    u.isNullOrBlank() -> ""
    u.startsWith("//") -> "https:$u"
    u.startsWith("/") -> base + u
    else -> u
}

internal fun Element?.txt(): String = this?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

private fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name)

abstract class Site(val key: String, val short: String, val color: Long, val sorts: List<Pair<String, String>>) {
    val label get() = SiteConfig.label(key)
    val base get() = SiteConfig.base(key)

    abstract suspend fun latest(page: Int, sort: String = sorts[0].first): PageResult
    abstract suspend fun search(q: String, page: Int, sort: String = sorts[0].first): PageResult
    abstract suspend fun detail(id: String): Detail
    abstract suspend fun read(d: Detail, chapterId: String?): List<ReaderPage>
    abstract suspend fun favorites(page: Int): PageResult

    /** 下載檔名裡的編號，例如 JM123、#123 */
    open fun idTag(id: String) = SiteConfig.prefix(key) + id
}

// ============ NH（官方 v2 API） ============

object Nh : Site(
    "nh", "NH", 0xFFED2553,
    listOf("date" to "最新", "popular-today" to "今日熱門", "popular-week" to "本週熱門", "popular" to "歷來熱門"),
) {
    private val LANG_IDS = mapOf(29963 to "zh", 12227 to "en", 6346 to "ja")
    private val LANG_NAMES = mapOf("zh" to "chinese", "en" to "english", "ja" to "japanese")

    fun img(prefix: String, p: String?): String {
        if (p.isNullOrBlank()) return ""
        if (p.startsWith("http")) return p
        var h = 0
        for (c in p) h = h * 31 + c.code
        val n = Math.abs(h % 4) + 1
        return "https://$prefix$n.${SiteConfig.host(key)}/${p.trimStart('/')}"
    }

    private fun thumbPath(o: JSONObject, name: String): String? =
        o.optJSONObject(name)?.str("path") ?: o.str(name).takeIf { it.isNotBlank() }

    fun item(o: JSONObject): Item {
        val title = o.str("english_title").ifBlank { o.str("japanese_title") }
            .ifBlank { o.optJSONObject("title")?.str("english").orEmpty() }.ifBlank { "#${o.optInt("id")}" }
        val ids = o.optJSONArray("tag_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
        // 翻譯本同時有 japanese 以外的語言標籤，先看中英
        val lang = listOf("zh", "en", "ja").firstOrNull { l -> ids.any { LANG_IDS[it] == l } }
            ?: Lang.detect(Lang.bracketTags(title))
        val pages = o.optInt("num_pages")
        return Item("nh", o.optInt("id").toString(), title, img("t", thumbPath(o, "thumbnail")), "${if (pages > 0) pages else "?"} 頁", pages.takeIf { it > 0 }, lang)
    }

    fun parsePage(o: JSONObject, page: Int): PageResult {
        val a = o.optJSONArray("result") ?: JSONArray()
        return PageResult((0 until a.length()).map { item(a.getJSONObject(it)) }, page < o.optInt("num_pages", 1))
    }

    /** 有語言篩選時把 language:xxx 加進查詢，伺服器就只回那個語言 */
    private fun withLang(q: String): String =
        listOf(q.trim(), LANG_NAMES[Lang.filter]?.let { "language:$it" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")

    override suspend fun latest(page: Int, sort: String): PageResult {
        if (sort == "date" && Lang.filter.isEmpty()) return parsePage(Net.json(key, "/api/v2/galleries?page=$page&per_page=25"), page)
        return search("", page, sort)
    }

    override suspend fun search(q: String, page: Int, sort: String): PageResult {
        val query = enc(withLang(q).ifBlank { "pages:>0" })
        return parsePage(Net.json(key, "/api/v2/search?query=$query&sort=$sort&page=$page"), page)
    }

    override suspend fun detail(id: String): Detail = parseDetail(Net.json(key, "/api/v2/galleries/$id?include=related"))

    private val TYPE_NAME = linkedMapOf(
        "artist" to "作者", "group" to "社團", "parody" to "原作", "character" to "角色", "tag" to "標籤", "language" to "語言", "category" to "分類",
    )

    fun parseDetail(o: JSONObject): Detail {
        val id = o.optInt("id").toString()
        val order = TYPE_NAME.keys.toList()
        val rawTags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        val tags = rawTags.sortedBy { order.indexOf(it.str("type")) }.map {
            val type = it.str("type")
            TagRef(TYPE_NAME[type] ?: type, it.str("name"), "$type:\"${it.str("name")}\"", type)
        }
        val title = o.optJSONObject("title")
        val pages = o.optJSONArray("pages")?.let { a ->
            (0 until a.length()).map {
                val p = a.getJSONObject(it)
                ReaderPage(img("i", p.str("path")), 0, p.optInt("width"), p.optInt("height"))
            }
        }.orEmpty()
        val related = o.optJSONArray("related")?.let { a -> (0 until a.length()).map { item(a.getJSONObject(it)) } }.orEmpty()
        val upload = o.optLong("upload_date")
        val langNames = rawTags.filter { it.str("type") == "language" && it.str("name") != "translated" }.joinToString(" ") { it.str("name") }
        return Detail(
            site = key,
            id = id,
            title = title?.str("english")?.ifBlank { title.str("pretty") }?.ifBlank { null } ?: "#$id",
            subtitle = title?.str("japanese").orEmpty(),
            cover = img("t", thumbPath(o, "cover") ?: thumbPath(o, "thumbnail")),
            tags = tags,
            lang = Lang.detect(langNames),
            info = listOf(
                "編號" to "#$id",
                "頁數" to "${o.optInt("num_pages")}",
                "收藏數" to (if (o.has("num_favorites")) "${o.optInt("num_favorites")}" else "-"),
                "上傳" to (if (upload > 0) SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(upload * 1000)) else "-"),
            ),
            chapters = emptyList(),
            pages = o.optInt("num_pages").takeIf { it > 0 },
            related = related,
            url = "$base/g/$id/",
            reader = pages,
        )
    }

    override suspend fun read(d: Detail, chapterId: String?): List<ReaderPage> = d.reader ?: detail(d.id).reader.orEmpty()

    override suspend fun favorites(page: Int): PageResult = parsePage(Net.json(key, "/api/v2/favorites?page=$page"), page)

    suspend fun isFavorite(id: String): Boolean {
        val o = Net.json(key, "/api/v2/galleries/$id/favorite")
        return o.optBoolean("favorited", o.optBoolean("is_favorite", o.optBoolean("favorite")))
    }

    suspend fun setFavorite(id: String, on: Boolean) {
        Net.json(key, "/api/v2/galleries/$id/favorite", if (on) "POST" else "DELETE")
    }
}

// ============ JM（網頁，有 Cloudflare，走 WebView） ============

object Jm : Site(
    "jm", "JM", 0xFFFF8A00,
    listOf("mr" to "最新", "mv" to "最多觀看", "tf" to "最多喜歡", "mp" to "最多圖片"),
) {
    private val LANG_TAG = mapOf("zh" to "中文", "en" to "英文", "ja" to "日文")

    fun parseList(s: String, page: Int, base: String = this.base): PageResult {
        val doc = html(s)
        val seen = HashSet<String>()
        val items = ArrayList<Item>()
        for (a in doc.select("a[href*=\"/album/\"]")) {
            val m = Regex("/album/(\\d+)").find(a.attr("href")) ?: continue
            val img = a.selectFirst("img") ?: continue
            val src = listOf(img.attr("data-original"), img.attr("data-src"), img.attr("src")).firstOrNull { it.isNotBlank() && !it.contains("blank.") }.orEmpty()
            // 選單、廣告裡也有連到作品的圖示，只收封面在 /media/albums/ 的
            val id = m.groupValues[1]
            if (!src.contains("/media/albums/") || id in seen) continue
            seen.add(id)
            val box = a.closest(".list-col, .well, .p-b-15") ?: a.parent()?.parent()
            val cat = listOf(box?.selectFirst(".label-category").txt(), box?.selectFirst(".label-sub").txt()).filter { it.isNotEmpty() }.joinToString("・")
            val title = box?.selectFirst(".video-title").txt().ifBlank { img.attr("title") }.ifBlank { img.attr("alt") }.ifBlank { "JM$id" }
            val tagText = box?.select("a.tag")?.joinToString(" ") { it.txt() }.orEmpty()
            items.add(Item("jm", id, title, abs(src, base), cat, null, Lang.detect(tagText, cat, Lang.bracketTags(title))))
        }
        val hasMore = doc.selectFirst(".pagination a[href*=\"page=${page + 1}\"]") != null
        return PageResult(items, hasMore)
    }

    override suspend fun latest(page: Int, sort: String): PageResult {
        // 有語言篩選時改搜該語言標籤（中文／英文／日文），不然英日文的本子很少出現在最新列表
        if (Lang.filter.isNotEmpty()) return search("", page, sort)
        return parseList(Net.text(key, "/albums?o=$sort&page=$page"), page)
    }

    override suspend fun search(q: String, page: Int, sort: String): PageResult {
        var query = q.trim()
        val tag = LANG_TAG[Lang.filter]
        // 站內語法「+A +B」= 兩個都要有；使用者自己有用語法的就不動
        if (tag != null) query = if (query.isEmpty()) tag else if (Regex("[\\s+-]").containsMatchIn(query)) query else "+$query +$tag"
        return parseList(Net.text(key, "/search/photos?search_query=${enc(query)}&o=$sort&page=$page"), page)
    }

    override suspend fun detail(id: String): Detail = parseDetail(id, Net.text(key, "/album/$id/"))

    private val TYPE_NAME = linkedMapOf("works" to "作品", "actor" to "角色", "tags" to "標籤", "author" to "作者")

    fun parseDetail(id: String, s: String, base: String = this.base): Detail {
        val doc = html(s)
        val tags = ArrayList<TagRef>()
        for (span in doc.select("span[data-type]")) {
            val type = span.attr("data-type")
            for (a in span.select("a[class*=web-]")) {
                val name = a.txt()
                if (name.isNotEmpty() && tags.none { it.name == name && it.type == type }) tags.add(TagRef(TYPE_NAME[type] ?: type, name, name, type))
            }
        }
        val order = TYPE_NAME.keys.toList()
        tags.sortBy { order.indexOf(it.type) }
        val chapters = ArrayList<Chapter>()
        val dup = Regex("^(第\\s*\\d+\\s*話)\\s+(.*?)\\s*\\1$")
        for (a in doc.select(".episode a[href*=\"/photo/\"]")) {
            val m = Regex("/photo/(\\d+)").find(a.attr("href")) ?: continue
            val cid = m.groupValues[1]
            if (chapters.any { it.id == cid }) continue
            // 標題常是「第1話 書名 第1話」，把尾巴重複的話數拿掉
            val t = a.selectFirst("h3, li").txt().ifBlank { a.txt() }.replace(dup, "$1 $2")
            chapters.add(Chapter(cid, t))
        }
        val pageCount = Regex("\\d+").find(doc.selectFirst(".pagecount").txt())?.value
        val date = doc.selectFirst("[itemprop=datePublished]")?.attr("content").orEmpty()
        val cover = doc.selectFirst("img[src*=\"/media/albums/$id\"]")?.attr("src")
        val title = doc.selectFirst("#book-name, h1").txt().ifBlank { "JM$id" }
        return Detail(
            site = key,
            id = id,
            title = title,
            subtitle = "",
            cover = abs(cover, base),
            tags = tags,
            lang = Lang.detect(tags.filter { it.type == "tags" }.joinToString(" ") { it.name }, Lang.bracketTags(title)),
            info = listOf(
                "編號" to "JM$id",
                "頁數" to (pageCount ?: "-"),
                "上架" to date.ifBlank { "-" },
                "章節" to (if (chapters.isNotEmpty()) "${chapters.size} 話" else "單本"),
            ),
            chapters = chapters,
            pages = pageCount?.toIntOrNull(),
            related = parseList(s, 1, base).items.filter { it.id != id }.take(18),
            url = "$base/album/$id/",
        )
    }

    override suspend fun read(d: Detail, chapterId: String?): List<ReaderPage> =
        parseReader(chapterId ?: d.id, Net.text(key, "/photo/${chapterId ?: d.id}"))

    /** 閱讀頁：圖片被橫切成好幾條上下倒置，每張要切幾段由編號和檔名的 md5 決定 */
    fun parseReader(id: String, s: String, base: String = this.base): List<ReaderPage> {
        val aid = Regex("var aid = (\\d+);").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: id.toLong()
        val scramble = Regex("var scramble_id = (\\d+);").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 220980
        var files: List<String> = runCatching {
            val a = JSONArray(Regex("var page_arr = (\\[[^\\]]*\\]);").find(s)?.groupValues?.get(1) ?: "[]")
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
        val urls = LinkedHashMap<String, String>()
        for (m in Regex("data-original=\"([^\"]*/media/photos/$aid/([^\"?/]+)[^\"]*)\"").findAll(s)) {
            urls.putIfAbsent(m.groupValues[2], m.groupValues[1])
        }
        if (files.isEmpty()) files = urls.keys.toList()
        val cdn = urls.values.firstOrNull()?.replace(Regex("/media/photos/.*$"), "")?.ifBlank { null } ?: base.replace("//", "//cdn-msp.")
        if (files.isEmpty()) throw NetException("找不到圖片")
        return files.map { f ->
            ReaderPage(urls[f] ?: "$cdn/media/photos/$aid/$f", if (f.endsWith(".gif")) 0 else segments(scramble, aid, f.substringBeforeLast('.')))
        }
    }

    fun segments(scrambleId: Long, aid: Long, file: String): Int {
        if (aid < scrambleId) return 0
        if (aid < 268850) return 10
        val x = if (aid < 421926) 10 else 8
        val h = MessageDigest.getInstance("MD5").digest("$aid$file".toByteArray()).joinToString("") { "%02x".format(it) }
        return (h.last().code % x) * 2 + 2
    }

    /** 收藏網址要帳號名稱 */
    var username = ""

    override suspend fun favorites(page: Int): PageResult {
        if (username.isEmpty()) throw NetException("已登入，但讀不到帳號名稱。請到「帳號」頁填寫你的帳號名稱，就能讀取收藏")
        val s = Net.text(key, "/user/${enc(username)}/favorite/albums?page=$page")
        val r = parseList(s, page)
        // 第一頁就空的時候說清楚是哪個原因，不要只顯示空白
        if (page == 1 && r.items.isEmpty()) {
            val title = html(s).title()
            if (Regex("^login\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) throw NetException("登入已失效，請到「帳號」頁重新登入")
            throw NetException("帳號「$username」的收藏是空的。如果網站上明明有收藏，可能是帳號名稱不對，請到「帳號」頁確認或修改")
        }
        return r
    }
}

// ============ WN（網頁） ============

object Wn : Site("wn", "WN", 0xFF3D8BFD, listOf("create_time_DESC" to "最新")) {
    /** 分類編號 → 語言（同人誌／單行本／雜誌／韓漫 各自分漢化、日語、English） */
    private val CATE_LANG = mapOf(1 to "zh", 9 to "zh", 10 to "zh", 20 to "zh", 12 to "ja", 13 to "ja", 14 to "ja", 16 to "en", 17 to "en", 18 to "en")

    fun parseList(s: String, page: Int, base: String = this.base): PageResult {
        val doc = html(s)
        val seen = HashSet<String>()
        val items = ArrayList<Item>()
        for (a in doc.select("a[href*=photos-index-aid-]")) {
            val m = Regex("aid-(\\d+)").find(a.attr("href")) ?: continue
            val img = a.selectFirst("img") ?: continue
            val id = m.groupValues[1]
            if (id in seen) continue
            seen.add(id)
            val box = a.closest("li") ?: a.parent()?.parent()
            val info = box?.selectFirst(".info_col").txt()
            val pages = Regex("(\\d+)\\s*張").find(info)?.groupValues?.get(1)?.toIntOrNull()
            val title = a.attr("title").ifBlank { img.attr("alt") }.ifBlank { box?.selectFirst(".title").txt() }.ifBlank { "#$id" }
            val cate = Regex("cate-(\\d+)").find(box?.selectFirst("[class*=cate-]")?.className().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            items.add(
                Item(
                    "wn", id, title, abs(img.attr("data-original").ifBlank { img.attr("src") }, base),
                    pages?.let { "$it 頁" }.orEmpty(), pages, CATE_LANG[cate] ?: Lang.detect(Lang.bracketTags(title)),
                )
            )
        }
        val pager = doc.selectFirst(".paginator, .f_left.paginator")
        val next = Regex("(page-|p=)${page + 1}\\b")
        val hasMore = pager != null && pager.select("a").any { Regex("後頁|下一頁|>").containsMatchIn(it.txt()) || next.containsMatchIn(it.attr("href")) }
        return PageResult(items, hasMore)
    }

    override suspend fun latest(page: Int, sort: String): PageResult {
        if (Lang.filter.isEmpty()) return parseList(Net.text(key, "/albums-index-page-$page.html"), page)
        // 有語言篩選時，把該語言的幾個分類各抓一頁合起來，依編號（新到舊）排
        val cates = CATE_LANG.filterValues { it == Lang.filter }.keys
        val pages = coroutineScope {
            cates.map { c -> async { parseList(Net.text(key, "/albums-index-page-$page-cate-$c.html"), page) } }.awaitAll()
        }
        val items = pages.flatMap { it.items }.sortedByDescending { it.id.toLongOrNull() ?: 0 }
        return PageResult(items, pages.any { it.hasMore })
    }

    override suspend fun search(q: String, page: Int, sort: String): PageResult =
        parseList(Net.text(key, "/search/index.php?q=${enc(q)}&m=&syn=yes&f=_all&s=create_time_DESC&p=$page"), page)

    override suspend fun detail(id: String): Detail = parseDetail(id, Net.text(key, "/photos-index-aid-$id.html"))

    fun parseDetail(id: String, s: String, base: String = this.base): Detail {
        val doc = html(s)
        // 不存在的編號網站一樣回 200，只是沒有封面和標題
        val coverImg = doc.selectFirst(".uwthumb img") ?: throw NetException("找不到（可能已被刪除）", 404)
        val labels = doc.select(".uwconn label").map { it.txt() }.filter { it.isNotEmpty() }
        val info = arrayListOf("編號" to "${SiteConfig.prefix(key)}$id")
        var pages: Int? = null
        for (l in labels) {
            val parts = l.split(Regex("[：:]"))
            if (parts.size < 2) continue
            val k = parts[0].trim()
            val v = parts.drop(1).joinToString(":").trim()
            if (k.contains("頁數")) pages = Regex("\\d+").find(v)?.value?.toIntOrNull()
            if (!k.contains("編號")) info.add(k to v)
        }
        val tags = doc.select("a.tagshow").map { TagRef("標籤", it.txt(), it.txt()) }
        val category = info.firstOrNull { it.first.contains("分類") }?.second.orEmpty()
        val intro = doc.selectFirst(".uwconn p").txt().replace(Regex("^簡介[：:]\\s*"), "")
        if (intro.isNotEmpty()) info.add("簡介" to intro)
        val title = doc.selectFirst("#bodywrap h2, h2").txt().ifBlank { "#$id" }
        return Detail(
            site = key,
            id = id,
            title = title,
            subtitle = "",
            cover = abs(coverImg.attr("src"), base),
            tags = tags,
            lang = Lang.detect(category, Lang.bracketTags(title)),
            info = info,
            chapters = emptyList(),
            pages = pages,
            related = parseList(s, 1, base).items.filter { it.id != id }.take(18),
            url = "$base/photos-index-aid-$id.html",
        )
    }

    override suspend fun read(d: Detail, chapterId: String?): List<ReaderPage> =
        parseReader(Net.text(key, "/photos-gallery-aid-${d.id}.html"))

    fun parseReader(js: String, base: String = this.base): List<ReaderPage> {
        val re = Regex("url:\\s*fast_img_host\\+\\\\?\"([^\"\\\\]+)\\\\?\",\\s*caption:\\s*\\\\?\"([^\"\\\\]*)")
        val list = ArrayList<Pair<String, String>>()
        for (m in re.findAll(js)) {
            val (u, cap) = m.destructured
            // 最後一張是站方的「加入收藏」宣傳圖
            if (Regex("/(themes|static)/").containsMatchIn(u) || cap.contains("收藏")) continue
            list.add(abs(u, base) to cap)
        }
        val num = { c: String -> Regex("\\d+").find(c)?.value?.toLongOrNull() }
        val sorted = if (list.all { num(it.second) != null }) list.sortedBy { num(it.second) } else list
        if (sorted.isEmpty()) throw NetException("找不到圖片")
        return sorted.map { ReaderPage(it.first) }
    }

    override suspend fun favorites(page: Int): PageResult = parseList(Net.text(key, "/users-users_fav-page-$page.html"), page)
}

// ============ EH（網頁） ============

object Eh : Site("eh", "EH", 0xFFB5835A, listOf("latest" to "最新", "popular" to "熱門")) {
    /** 列表的分類 → 沒有語言標籤時當成日文的分類 */
    private val JA_CATS = Regex("doujinshi|manga|artist cg|game cg|non-h", RegexOption.IGNORE_CASE)
    private val LANG_NAMES = mapOf("zh" to "chinese", "en" to "english")

    /** 網站用「next=上一頁最後一本」翻頁，記下每個列表第 N 頁的網址 */
    private val cursors = ConcurrentHashMap<String, MutableMap<Int, String>>()

    /** 編號 = gid_token（兩個都要有才能開作品頁） */
    fun split(id: String): Pair<String, String> = id.substringBefore('_') to id.substringAfter('_', "")

    override fun idTag(id: String) = "EH" + split(id).first

    private fun withLang(q: String) =
        listOf(q.trim(), LANG_NAMES[Lang.filter]?.let { "language:$it$" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")

    fun parseList(s: String): List<Item> {
        val doc = html(s)
        val items = ArrayList<Item>()
        val seen = HashSet<String>()
        val re = Regex("/g/(\\d+)/([0-9a-f]{10})")
        for (a in doc.select("a[href*=/g/]")) {
            val m = re.find(a.attr("href")) ?: continue
            val gid = m.groupValues[1]
            if (gid in seen) continue
            val row = a.closest("tr, .gl1t") ?: continue
            val title = a.selectFirst(".glink").txt().ifBlank { row.selectFirst(".glink").txt() }
            if (title.isEmpty()) continue
            seen.add(gid)
            val img = row.selectFirst("img[data-src], .glthumb img, img")
            val src = listOf(img?.attr("data-src").orEmpty(), img?.attr("src").orEmpty()).firstOrNull { it.isNotBlank() && !it.startsWith("data:") }.orEmpty()
            val cat = row.selectFirst(".cn, .cs").txt()
            val pages = Regex("(\\d+)\\s*pages?").find(row.txt())?.groupValues?.get(1)?.toIntOrNull()
            val langTag = row.select(".gt, .gtl").map { it.attr("title") }.firstOrNull { it.startsWith("language:") && !it.endsWith("translated") }
            items.add(
                Item(
                    "eh", "${gid}_${m.groupValues[2]}", title, abs(src, base),
                    listOf(cat, pages?.let { "$it 頁" }.orEmpty()).filter { it.isNotEmpty() }.joinToString("・"), pages,
                    Lang.detect(langTag, Lang.bracketTags(title)).ifEmpty { if (JA_CATS.containsMatchIn(cat)) "ja" else "" },
                )
            )
        }
        return items
    }

    fun nextUrl(s: String): String? = html(s).selectFirst("a#dnext, a#unext")?.attr("href")?.ifBlank { null }

    private suspend fun listPage(first: String, page: Int): PageResult {
        val url = if (page > 1) cursors[first]?.get(page) ?: return PageResult(emptyList(), false) else first
        val s = Net.text(key, url)
        val next = nextUrl(s)
        if (next != null) cursors.getOrPut(first) { ConcurrentHashMap() }[page + 1] = next
        return PageResult(parseList(s), next != null)
    }

    override suspend fun latest(page: Int, sort: String): PageResult {
        if (sort == "popular") return if (page > 1) PageResult(emptyList(), false) else PageResult(parseList(Net.text(key, "/popular")), false)
        val q = withLang("")
        return listPage(if (q.isNotEmpty()) "/?f_search=${enc(q)}" else "/", page)
    }

    override suspend fun search(q: String, page: Int, sort: String): PageResult = listPage("/?f_search=${enc(withLang(q))}", page)

    override suspend fun detail(id: String): Detail {
        val (gid, token) = split(id)
        if (token.isEmpty()) throw NetException("需要完整的作品網址（含 token）")
        val url = "/g/$gid/$token/"
        var s = Net.text(key, url)
        // 有些作品先出「內容警告」頁，帶 nw=always 才會進去
        if (html(s).selectFirst("#gn") == null && s.contains("nw=always")) s = Net.text(key, "$url?nw=always")
        return parseDetail(id, s)
    }

    private val NS_NAME = mapOf(
        "language" to "語言", "parody" to "原作", "character" to "角色", "group" to "社團", "artist" to "作者", "cosplayer" to "Coser",
        "male" to "標籤", "female" to "標籤", "mixed" to "標籤", "other" to "標籤", "reclass" to "分類",
    )

    fun parseDetail(id: String, s: String): Detail {
        val doc = html(s)
        val (gid, token) = split(id)
        if (doc.selectFirst("#gn") == null) throw NetException(doc.selectFirst(".d p, p").txt().ifBlank { "找不到（可能已被刪除）" }, 404)
        val tags = ArrayList<TagRef>()
        for (tr in doc.select("#taglist tr")) {
            val ns = tr.selectFirst(".tc").txt().removeSuffix(":")
            for (a in tr.select("a")) {
                val name = a.txt()
                tags.add(TagRef(NS_NAME[ns] ?: ns, if (ns in listOf("male", "female", "mixed")) "${ns[0]}:$name" else name, "$ns:\"$name$\"", ns))
            }
        }
        val order = listOf("作者", "社團", "原作", "角色", "語言", "標籤")
        tags.sortBy { order.indexOf(it.label).let { i -> if (i < 0) 99 else i } }
        val info = arrayListOf("編號" to gid)
        val zh = mapOf("Posted" to "上傳", "File Size" to "大小", "Length" to "頁數", "Favorited" to "收藏數")
        for (tr in doc.select("#gdd tr")) {
            val k = tr.selectFirst(".gdt1").txt().removeSuffix(":")
            val v = tr.selectFirst(".gdt2").txt()
            zh[k]?.let { info.add(it to v.replace(Regex("\\s*pages?$"), "").replace(Regex("\\s*times$"), "")) }
        }
        val pages = Regex("(\\d+)\\s*pages?").find(doc.selectFirst("#gdd").txt())?.groupValues?.get(1)?.toIntOrNull()
        val cover = Regex("url\\(([^)]+)\\)").find(doc.selectFirst("#gd1 div")?.attr("style").orEmpty())?.groupValues?.get(1).orEmpty()
        val langNames = tags.filter { it.type == "language" && it.name != "translated" }.joinToString(" ") { it.name }
        val title = doc.selectFirst("#gn").txt()
        return Detail(
            site = key,
            id = "${gid}_$token",
            title = title,
            subtitle = doc.selectFirst("#gj").txt(),
            cover = cover,
            tags = tags,
            lang = Lang.detect(langNames, Lang.bracketTags(title)).ifEmpty { if (langNames.isEmpty()) "ja" else "" },
            info = info,
            chapters = emptyList(),
            pages = pages,
            related = emptyList(),
            url = "$base/g/$gid/$token/",
            firstHtml = s,
            siteFav = favFolder(doc),
        )
    }

    /** 作品頁右邊的收藏連結：沒收藏是「Add to Favorites」，收藏了會顯示收藏夾名稱 */
    fun favFolder(doc: org.jsoup.nodes.Document): String? =
        doc.selectFirst("#favoritelink").txt().takeIf { it.isNotEmpty() && !it.contains("Add to Favorites", true) }

    fun pageLinks(s: String): List<String> = html(s).select("#gdt a[href*=/s/]").map { it.attr("href") }

    /** 縮圖頁只有每頁的閱讀網址（/s/...），真正的圖要再開那一頁才拿得到，所以閱讀時才逐頁解析 */
    override suspend fun read(d: Detail, chapterId: String?): List<ReaderPage> {
        val (gid, token) = split(d.id)
        val first = pageLinks(d.firstHtml ?: Net.text(key, "/g/$gid/$token/"))
        val perPage = first.size.coerceAtLeast(1)
        val total = d.pages ?: first.size
        val rest = generateSequence(1) { it + 1 }.takeWhile { it * perPage < total }.toList()
        val more = ArrayList<String>()
        for (batch in rest.chunked(4)) {
            more += coroutineScope { batch.map { p -> async { pageLinks(Net.text(key, "/g/$gid/$token/?p=$p")) } }.awaitAll() }.flatten()
        }
        val all = (first + more).distinct()
        if (all.isEmpty()) throw NetException("找不到圖片")
        return all.map { sp -> ReaderPage("", resolve = { retry -> resolve(sp, retry) }) }
    }

    private val nl = ConcurrentHashMap<String, String>()

    /** 開閱讀頁拿圖片網址；retry 時帶 nl 參數換一台圖片伺服器 */
    suspend fun resolve(spage: String, retry: Boolean): String {
        var url = spage
        if (retry) nl[spage]?.let { url += (if ('?' in url) "&" else "?") + "nl=$it" }
        val s = Net.text(key, url)
        val (src, n) = parseImagePage(s)
        if (n != null) nl[spage] = n
        if (src == null) throw NetException(if (Regex("limit", RegexOption.IGNORE_CASE).containsMatchIn(s)) "已達網站的圖片瀏覽上限，請稍後再試或登入" else "找不到圖片")
        return src
    }

    fun parseImagePage(s: String): Pair<String?, String?> =
        Regex("<img id=\"img\" src=\"([^\"]+)\"").find(s)?.groupValues?.get(1)?.let { org.jsoup.parser.Parser.unescapeEntities(it, true) } to
            Regex("nl\\('([^']+)'\\)").find(s)?.groupValues?.get(1)

    override suspend fun favorites(page: Int): PageResult = listPage("/favorites.php", page)
}

val SITES: Map<String, Site> = linkedMapOf("nh" to Nh, "jm" to Jm, "wn" to Wn, "eh" to Eh)

fun site(key: String): Site = SITES.getValue(key)

internal fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

// ============ 編號搜尋、書名整理 ============

class IdQuery(val sites: List<String>, val id: String, val plain: Boolean = false)

object Query {
    /**
     * 判斷搜尋字是不是編號：
     *   站別代號、站名、各站編號前綴＋編號（例如 JM123、#123）→ 只查那一站
     *   作品網址 → 依網址判斷是哪一站
     *   純數字 → 每個勾選的站都用這個編號查一次
     */
    fun parseId(q: String): IdQuery? {
        val s = q.trim()
        val t = s.replace(Regex("\\s+"), "")
        fun one(site: String, id: String) = IdQuery(listOf(site), id.toLong().toString())
        fun hostName(k: String) = SiteConfig.host(k).split('.').takeLast(2).first()
        Regex("/g/(\\d+)/([0-9a-f]{10})").find(s)?.takeIf { s.contains(hostName("eh")) }?.let { return IdQuery(listOf("eh"), "${it.groupValues[1]}_${it.groupValues[2]}") }
        Regex("/g/(\\d+)").find(s)?.takeIf { s.contains(hostName("nh")) }?.let { return one("nh", it.groupValues[1]) }
        Regex("/(?:album|photo)/(\\d+)").find(s)?.let { return one("jm", it.groupValues[1]) }
        Regex("aid-(\\d+)").find(s)?.let { return one("wn", it.groupValues[1]) }
        val numbered = SiteConfig.KEYS.filter { it != "eh" }
        for (k in numbered) {
            val label = SiteConfig.label(k)
            val names = listOf(k, SiteConfig.prefix(k), label, label.take(2), hostName(k)).filter { it.isNotEmpty() }.map { Regex.escape(it) }
            Regex("^(?:${names.joinToString("|")})(\\d{1,9})$", RegexOption.IGNORE_CASE).find(t)?.let { return one(k, it.groupValues[1]) }
        }
        Regex("^\\d{1,8}$").find(t)?.let { return IdQuery(numbered, it.value.toLong().toString(), plain = true) }
        return null
    }

    /** 書名開頭的 [社團 (作者)] 或 [作者]，拆出作者名 */
    fun parseAuthors(title: String): List<String> {
        val m = Regex("^\\s*(?:\\([^)]*\\)\\s*)?\\[([^\\]]+)]").find(title) ?: return emptyList()
        val head = m.groupValues[1]
        if (Regex("翻訳|翻譯|汉化|漢化|chinese|english|中文|DL版|digital", RegexOption.IGNORE_CASE).containsMatchIn(head)) return emptyList()
        val inner = Regex("^(.*?)\\s*\\(([^)]+)\\)\\s*$").find(head)
        val names = if (inner != null) inner.groupValues[2].split(Regex("[,、，]")) + inner.groupValues[1] else head.split(Regex("[,、，]"))
        return names.map { it.trim() }.filter { it.isNotEmpty() && it.length < 40 }.distinct()
    }

    /** 拿掉 [社團]、(活動)、{漢化組}、【…】 這些標記，剩下書名本體，拿來到各站搜同一本 */
    fun cleanTitle(t: String): String {
        val s = t.split(Regex("\\s[|｜]\\s"))[0]
            .replace(Regex("\\[[^\\]]*]|\\([^)]*\\)|\\{[^}]*}|【[^】]*】|（[^）]*）"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return if (s.length >= 2) s else t.trim()
    }
}
