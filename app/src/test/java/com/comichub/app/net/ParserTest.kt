package com.comichub.app.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用真的網頁存檔測解析器。存檔含站名，不放進 repo：
 * 設定環境變數 FIXTURES 指到存檔資料夾才會跑，沒設就跳過。
 */
class ParserTest {
    private val dir = System.getenv("FIXTURES")?.let { File(it) }

    private fun read(name: String): String {
        assumeTrue("FIXTURES not set", dir != null && File(dir, name).exists())
        return File(dir, name).readText()
    }

    private fun show(label: String, items: List<Item>) {
        println("== $label: ${items.size}")
        items.take(3).forEach { println("  ${it.id} | ${it.lang} | ${it.sub} | ${it.title.take(40)} | ${it.cover.takeLast(40)}") }
    }

    @Test
    fun nh() {
        val list = Nh.parsePage(JSONObject(read("nh_latest.json")), 1)
        show("nh latest", list.items)
        assertTrue(list.items.size >= 20)
        assertTrue(list.hasMore)
        assertTrue(list.items.all { it.cover.startsWith("https://t") })
        val zh = Nh.parsePage(JSONObject(read("nh_search.json")), 1)
        assertTrue(zh.items.count { it.lang == "zh" } > zh.items.size / 2)
        val d = Nh.parseDetail(JSONObject(read("nh_detail.json")))
        println("nh detail: ${d.title} pages=${d.pages} tags=${d.tags.take(4)} reader=${d.reader?.size} first=${d.reader?.firstOrNull()?.url}")
        assertEquals(d.pages, d.reader?.size)
        assertTrue(d.reader!!.first().url.startsWith("https://i"))
    }

    @Test
    fun jm() {
        val list = Jm.parseList(read("jm_latest.html"), 1)
        show("jm latest", list.items)
        assertTrue(list.items.size >= 20)
        assertTrue(list.hasMore)
        val search = Jm.parseList(read("jm_search.html"), 1)
        show("jm search", search.items)
        assertTrue(search.items.isNotEmpty())
        val html = read("jm_detail.html")
        val id = Regex("/album/(\\d+)").find(html)!!.groupValues[1]
        val d = Jm.parseDetail(id, html)
        println("jm detail: $id ${d.title} pages=${d.pages} chapters=${d.chapters.take(3)} tags=${d.tags.take(5)} cover=${d.cover.takeLast(30)} related=${d.related.size}")
        assertTrue(d.title.isNotBlank() && d.cover.isNotBlank())
        val photo = read("jm_photo.html")
        val aid = Regex("var aid = (\\d+);").find(photo)!!.groupValues[1]
        val pages = Jm.parseReader(aid, photo)
        println("jm reader: ${pages.size} first=${pages[0].url.takeLast(40)} segs=${pages.take(5).map { it.segs }}")
        assertTrue(pages.size > 1)
    }

    @Test
    fun jmSegments() {
        // 跟桌面版 fetcher.js jmSegments 對照的數值
        assertEquals(0, Jm.segments(220980, 100000, "00001"))
        assertEquals(10, Jm.segments(220980, 250000, "00001"))
        val v = Jm.segments(220980, 500000, "00001")
        assertTrue(v in 2..16 && v % 2 == 0)
    }

    @Test
    fun wn() {
        val list = Wn.parseList(read("wn_latest.html"), 1)
        show("wn latest", list.items)
        assertTrue(list.items.size >= 10)
        assertTrue(list.hasMore)
        show("wn search", Wn.parseList(read("wn_search.html"), 1).items)
        val cate = Wn.parseList(read("wn_cate.html"), 1)
        assertTrue(cate.items.all { it.lang == "zh" })
        val d = Wn.parseDetail(list.items[0].id, read("wn_detail.html"))
        println("wn detail: ${d.title} pages=${d.pages} info=${d.info} tags=${d.tags.take(5)}")
        assertTrue(d.cover.isNotBlank())
        val pages = Wn.parseReader(read("wn_gallery.js"))
        println("wn reader: ${pages.size} first=${pages[0].url}")
        assertTrue(pages.size > 1)
        assertTrue(pages[0].url.startsWith("https:"))
    }

    @Test
    fun eh() {
        val s = read("eh_latest.html")
        val list = Eh.parseList(s)
        show("eh latest", list)
        assertTrue(list.size >= 20)
        println("eh next: ${Eh.nextUrl(s)}")
        assertTrue(Eh.nextUrl(s) != null)
        show("eh popular", Eh.parseList(read("eh_popular.html")))
        val dhtml = read("eh_detail.html")
        val m = Regex("/g/(\\d+)/([0-9a-f]{10})").find(dhtml)!!
        val d = Eh.parseDetail("${m.groupValues[1]}_${m.groupValues[2]}", dhtml)
        println("eh detail: ${d.title} pages=${d.pages} info=${d.info} tags=${d.tags.take(5)} cover=${d.cover}")
        assertTrue(d.cover.startsWith("http"))
        val links = Eh.pageLinks(dhtml)
        println("eh links: ${links.size} ${links.firstOrNull()}")
        assertTrue(links.isNotEmpty())
        val (src, nl) = Eh.parseImagePage(read("eh_spage.html"))
        println("eh image: $src nl=$nl")
        assertTrue(src != null)
    }

    @Test
    fun query() {
        assertEquals(listOf("jm"), Query.parseId("JM123456")!!.sites)
        assertEquals("123456", Query.parseId("jm 123456")!!.id)
        assertEquals(listOf("nh"), Query.parseId("#177013")!!.sites)
        assertEquals(3, Query.parseId("12345")!!.sites.size)
        assertEquals(null, Query.parseId("hello"))
        assertEquals(listOf("作者B", "社團A"), Query.parseAuthors("[社團A (作者B)] 書名 [中國翻譯]"))
        assertEquals("書名", Query.cleanTitle("(C99) [社團A (作者B)] 書名 [中國翻譯]"))
    }
}
