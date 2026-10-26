package com.comichub.app.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.comichub.app.Prefs
import com.comichub.app.data.Book
import com.comichub.app.data.Collection
import com.comichub.app.data.Library
import com.comichub.app.data.ZipPage
import com.comichub.app.net.Detail
import com.comichub.app.net.Item
import com.comichub.app.net.Lang
import com.comichub.app.net.PageResult
import com.comichub.app.net.Query
import com.comichub.app.net.ReaderPage
import com.comichub.app.net.SiteConfig
import com.comichub.app.net.site
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 畫面上的資料都掛在這個 scope，跟著 App 走（切頁、回上一頁時不會中斷載入） */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

private fun msg(e: Throwable) = e.message ?: e.toString()

/**
 * 可無限往下捲的列表。依語言篩選後可能一本都不剩，這時自動往後多翻幾頁（最多 5 頁），免得看到空白。
 * 狀態（含捲動位置）存在物件裡，回上一頁時列表和位置都還在。
 */
class Feed(private val loader: suspend (page: Int) -> PageResult) {
    val items = mutableStateListOf<Item>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var end by mutableStateOf(false)
        private set
    val grid = LazyGridState()
    private var page = 0
    private var job: Job? = null

    fun loadMore() {
        if (loading || end) return
        loading = true
        error = null
        job = AppScope.launch {
            try {
                val seen = items.mapTo(HashSet()) { it.key }
                var added = 0
                for (i in 0 until 5) {
                    val r = loader(page + 1)
                    page++
                    val fresh = r.items.filter { Lang.pass(it) && seen.add(it.key) }
                    items.addAll(fresh)
                    added += fresh.size
                    if (!r.hasMore || r.items.isEmpty()) {
                        end = true
                        break
                    }
                    if (added > 0) break
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = msg(e)
            } finally {
                loading = false
            }
        }
    }

    fun refresh() {
        job?.cancel()
        loading = false
        items.clear()
        page = 0
        end = false
        error = null
        AppScope.launch { grid.scrollToItem(0) }
        loadMore()
    }
}

/** 列表快取：同一個站 / 排序 / 語言的列表留著，切回來不用重載 */
object Feeds {
    private val map = HashMap<String, Feed>()
    fun get(key: String, loader: suspend (Int) -> PageResult): Feed = map.getOrPut("$key|${Lang.filter}") { Feed(loader) }
    fun clear() = map.clear()
}

/** 多站搜尋：每站各自載入，「全部」時交錯排列（才不會一站洗版） */
class SearchState(val q: String, sites: List<String>, val keyword: Boolean) {
    class PerSite(val site: String) {
        val items = mutableStateListOf<Item>()
        var page = 0
        var hasMore by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        var busy by mutableStateOf(false)
    }

    /** 輸入編號（或作品網址）時直接查那一本，不做關鍵字搜尋 */
    val byId = if (keyword) null else Query.parseId(q)
    val states: List<PerSite> = run {
        val use = byId?.let { b -> if (b.sites.size == 1) b.sites else b.sites.filter { it in sites } } ?: sites
        SiteConfig.KEYS.filter { it in use }.map { PerSite(it) }
    }
    var filter by mutableStateOf("all")
    val grid = LazyGridState()
    val lang = Lang.filter

    val shown get() = if (filter == "all") states else states.filter { it.site == filter }

    fun merged(): List<Item> {
        val lists = shown.map { it.items }
        val out = ArrayList<Item>()
        var i = 0
        while (lists.any { i < it.size }) {
            for (l in lists) if (i < l.size) out.add(l[i])
            i++
        }
        return out
    }

    fun more(list: List<PerSite> = shown) {
        for (s in list) {
            if (s.busy || !s.hasMore) continue
            s.busy = true
            s.error = null
            AppScope.launch {
                try {
                    if (byId != null) {
                        // 指定編號時不管語言篩選，一定顯示
                        val d = site(s.site).detail(byId.id)
                        s.items.add(d.toItem())
                        s.hasMore = false
                    } else {
                        val seen = s.items.mapTo(HashSet()) { it.id }
                        for (i in 0 until 5) {
                            val r = site(s.site).search(q, s.page + 1)
                            s.page++
                            val fresh = r.items.filter { (lang.isEmpty() || it.lang == lang) && seen.add(it.id) }
                            s.items.addAll(fresh)
                            s.hasMore = r.hasMore && r.items.isNotEmpty()
                            if (fresh.isNotEmpty() || !s.hasMore) break
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val m = msg(e)
                    s.error = if (byId != null && Regex("找不到|404").containsMatchIn(m)) "沒有這個編號" else m
                    if (byId != null) s.hasMore = false
                } finally {
                    s.busy = false
                }
            }
        }
    }

    init {
        more(states)
    }
}

/** 閱讀器的一頁：線上的圖，或 CBZ 裡的圖 */
sealed class PageSrc {
    class Web(val site: String, val page: ReaderPage) : PageSrc()
    class Zip(val page: ZipPage) : PageSrc()
}

enum class Tab(val label: String) { Home("首頁"), Favorites("收藏"), History("紀錄"), Shelf("書櫃"), Account("帳號") }

sealed class Route {
    /** 給 SaveableStateHolder 用的鍵 */
    val key: String = "route${nextKey++}"

    object Main : Route()

    class Search(q: String, val sites: List<String>, val keyword: Boolean = false) : Route() {
        var state by mutableStateOf(if (q.isBlank()) null else SearchState(q, sites, keyword))
    }

    class Detail(val item: Item) : Route() {
        var detail by mutableStateOf<com.comichub.app.net.Detail?>(null)
        var error by mutableStateOf<String?>(null)
        /** NH 站的網站收藏狀態（null = 還不知道 / 沒登入） */
        var favorite by mutableStateOf<Boolean?>(null)
        val grid = LazyGridState()
    }

    /**
     * 閱讀器。線上作品給 detail（多話作品用 chapter 指定第幾話）；書櫃 / 合集直接給 pages。
     * toc：合集裡每本的標題和起始頁。
     */
    class Reader(
        val title: String,
        val pkey: String,
        val detail: com.comichub.app.net.Detail? = null,
        chapter: Int = -1,
        val pages: List<PageSrc>? = null,
        val toc: List<Pair<String, Int>> = emptyList(),
        val startPage: Int = 0,
    ) : Route() {
        var chapter by mutableIntStateOf(chapter)
    }

    class Login(val site: String) : Route()

    /** 在 App 裡開網站；verify＝通過 Cloudflare 驗證用，關掉時重新暖機 */
    class Web(val site: String, val url: String, val verify: Boolean = false) : Route()

    object Downloads : Route()
}

private var nextKey = 0

object Nav {
    val stack = mutableStateListOf<Route>(Route.Main)
    val top: Route get() = stack.last()
    var tab by mutableStateOf(Tab.Home)
    var lang by mutableStateOf(Lang.filter)
        private set

    fun push(r: Route) {
        stack.add(r)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun changeLang(l: String) {
        Lang.filter = l
        Prefs.lang = l
        lang = l
    }

    fun openItem(item: Item) = push(Route.Detail(item))

    fun search(q: String, sites: List<String> = Prefs.searchSites, keyword: Boolean = false) =
        push(Route.Search(q, sites, keyword))

    /** 線上作品：從記住的進度開始（或指定的話數 / 頁） */
    fun read(d: Detail, chapter: Int = -1, page: Int = 0) {
        push(Route.Reader(d.title, "${d.site}_${d.id}", d, chapter, startPage = page))
    }

    fun readBook(b: Book, page: Int = 0) {
        push(Route.Reader(b.title + if (b.chapter.isNotBlank()) " ${b.chapter}" else "", b.key, pages = b.pages.indices.map { PageSrc.Zip(b.page(it)) }, startPage = page))
    }

    fun readCollection(c: Collection, page: Int = 0) {
        val books = Library.booksOf(c)
        if (books.isEmpty()) {
            toast("合集裡的漫畫都不在書櫃了")
            return
        }
        val toc = ArrayList<Pair<String, Int>>()
        val pages = ArrayList<PageSrc>()
        for (b in books) {
            toc.add((b.title + if (b.chapter.isNotBlank()) " ${b.chapter}" else "") to pages.size)
            b.pages.indices.mapTo(pages) { PageSrc.Zip(b.page(it)) }
        }
        push(Route.Reader(c.name, c.key, pages = pages, toc = toc, startPage = page))
    }
}
