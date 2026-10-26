package com.comichub.app.net

import org.json.JSONObject

// 四個站的資料都轉成同一種格式，畫面那邊不用管是哪個站（跟桌面版 src/sites.js 一樣）

/** 列表裡的一本 */
data class Item(
    val site: String,
    val id: String,
    val title: String,
    val cover: String,
    val sub: String = "",
    val pages: Int? = null,
    val lang: String = "",
) {
    val key get() = "${site}_$id"

    fun toJson(): JSONObject = JSONObject()
        .put("site", site).put("id", id).put("title", title).put("cover", cover)
        .put("sub", sub).put("pages", pages ?: 0).put("lang", lang)

    companion object {
        fun fromJson(o: JSONObject) = Item(
            site = o.optString("site"),
            id = o.optString("id"),
            title = o.optString("title"),
            cover = o.optString("cover"),
            sub = o.optString("sub"),
            pages = o.optInt("pages").takeIf { it > 0 },
            lang = o.optString("lang"),
        )
    }
}

/** 作品頁的標籤；query 是點了以後在該站搜尋用的字 */
data class TagRef(val label: String, val name: String, val query: String, val type: String = "")

data class Chapter(val id: String, val title: String)

/**
 * 閱讀頁的一張圖。
 * segs > 0：JM 站切條打亂的圖，要重組。
 * resolve != null：EH 站要再開一頁才拿得到圖片網址（retry = true 時換一台圖片伺服器）。
 */
class ReaderPage(
    val url: String,
    val segs: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val resolve: (suspend (retry: Boolean) -> String)? = null,
)

data class Detail(
    val site: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val cover: String,
    val tags: List<TagRef>,
    val lang: String,
    val info: List<Pair<String, String>>,
    val chapters: List<Chapter>,
    val pages: Int?,
    val related: List<Item>,
    val url: String,
    /** NH：作品資料裡就有每頁網址 */
    val reader: List<ReaderPage>? = null,
    /** EH：作品頁的 HTML，閱讀時先用它的縮圖連結，不用再抓一次 */
    val firstHtml: String? = null,
    /** 已經在網站帳號的收藏裡時是收藏夾名稱（EH 的作品頁看得出來；其他站是 null＝不知道） */
    val siteFav: String? = null,
) {
    fun toItem() = Item(site, id, title, cover, pages?.let { "$it 頁" } ?: "", pages, lang)
}

class PageResult(val items: List<Item>, val hasMore: Boolean)

class NetException(message: String, val status: Int = 0) : Exception(message)

// ============ 語言 ============

object Lang {
    val NAMES = linkedMapOf("zh" to "中文", "en" to "English", "ja" to "日本語")
    val SHORT = mapOf("zh" to "中", "en" to "EN", "ja" to "日")

    /** 目前的語言篩選（"" = 全部）；NH、EH 可以請伺服器篩，另外兩站在畫面端篩 */
    @Volatile
    var filter: String = ""

    private val RULES = listOf(
        "zh" to Regex("chinese|中文|漢化|汉化|中国翻訳|中國翻譯|中国翻译|中譯|中译|个人翻译|個人翻譯|翻譯|翻译", RegexOption.IGNORE_CASE),
        "en" to Regex("english|英文|英訳|英語|英语", RegexOption.IGNORE_CASE),
        "ja" to Regex("japanese|日文|日語|日语|日本語", RegexOption.IGNORE_CASE),
    )

    /** 從分類、標籤、標題猜語言；越前面的線索越可信 */
    fun detect(vararg hints: String?): String {
        for (h in hints) {
            if (h.isNullOrBlank()) continue
            for ((lang, re) in RULES) if (re.containsMatchIn(h)) return lang
        }
        return ""
    }

    private val BRACKET = Regex("[\\[【(（][^\\]】)）]{1,30}[\\]】)）]")

    /** 標題裡 [Chinese]、[英訳] 這類方括號標記 */
    fun bracketTags(title: String?): String = BRACKET.findAll(title.orEmpty()).joinToString(" ") { it.value }

    fun pass(item: Item) = filter.isEmpty() || item.lang == filter
}

// ============ 站別設定 ============

/** 站名、網址、編號前綴以 Base64 存放，原始碼裡不出現明文 */
object SiteConfig {
    private fun b64(s: String) = String(java.util.Base64.getDecoder().decode(s), Charsets.UTF_8)

    class Def(val label: String, val base: String, val login: String?, val prefix: String)

    val DEFS: Map<String, Def> = linkedMapOf(
        "nh" to Def(b64("bmhlbnRhaQ=="), b64("aHR0cHM6Ly9uaGVudGFpLm5ldA=="), "/login/", "#"),
        "jm" to Def(b64("56aB5ryr5aSp5aCC"), b64("aHR0cHM6Ly8xOGNvbWljLnZpcA=="), "/login", "JM"),
        "wn" to Def(b64("57Sz5aOr5ryr55Wr"), b64("aHR0cHM6Ly93d3cud25hY2cuY29t"), "/users-login.html", b64("d25hY2c=")),
        "eh" to Def(b64("RS1IZW50YWk="), b64("aHR0cHM6Ly9lLWhlbnRhaS5vcmc="), null, ""),
    )

    val KEYS = DEFS.keys.toList()

    /** 使用者改過的網址（網站換網域時用） */
    val overrides = HashMap<String, String>()

    fun label(site: String) = DEFS[site]?.label ?: site
    fun base(site: String) = (overrides[site] ?: DEFS.getValue(site).base).trimEnd('/')
    fun prefix(site: String) = DEFS[site]?.prefix ?: ""
    fun host(site: String) = base(site).substringAfter("://").substringBefore('/')

    /** 取網域最後兩段，例如 cdn.example.com → example.com */
    fun rootDomain(host: String) = host.split('.').takeLast(2).joinToString(".")

    /** EH 的帳號在論壇子網域 */
    fun forumUrl(path: String) = base("eh").replace("://", "://forums.") + path

    fun loginUrl(site: String) =
        if (site == "eh") forumUrl("/index.php?act=Login&CODE=00") else base(site) + DEFS.getValue(site).login

    /** 網址是哪個站的（含圖片 CDN 子網域） */
    fun siteForUrl(url: String): String? {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
        if (host.isEmpty()) return null
        for (k in KEYS) {
            val root = rootDomain(host(k))
            if (host == root || host.endsWith(".$root")) return k
        }
        return null
    }
}
