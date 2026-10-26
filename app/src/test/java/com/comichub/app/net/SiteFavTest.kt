package com.comichub.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 網站「加入收藏」表單的解析（樣本照網站表單的結構寫，不含站名） */
class SiteFavTest {
    // EH 已登入、已經收在 Favorites 3 的樣子：radio 包在自己的 div，名稱在旁邊的 div
    private val eh = """
        <form action="https://example.org/gallerypopups.php?gid=1&amp;t=abc&amp;act=addfav" method="post">
        <div id="galpop">
          <div style="height:25px" onclick="">
            <div style="float:left"><input type="radio" name="favcat" value="0" id="fav0" /></div>
            <div class="i"></div><div style="float:left">Favorites 0</div>
          </div>
          <div style="height:25px">
            <div style="float:left"><input type="radio" name="favcat" value="3" id="fav3" checked="checked" /></div>
            <div class="i"></div><div style="float:left">我的最愛</div>
          </div>
          <div style="height:25px">
            <div style="float:left"><input type="radio" name="favcat" value="favdel" id="favdel" /></div>
            <div style="float:left">Remove from Favorites</div>
          </div>
          <textarea name="favnote">舊備註</textarea>
          <input type="submit" name="apply" value="Apply Changes" />
          <input type="hidden" name="update" value="1" />
        </div>
        </form>
    """.trimIndent()

    // WN 的樣子：select 選書架
    private val wn = """
        <div class="box_title">加入書架</div>
        <form id="fav_form" action="/users-save_fav-id-123.html" method="post">
          <select name="favc_id"><option value="11">預設書架</option><option value="12" selected>看完了</option></select>
          <input type="text" name="note" value="" />
          <input type="hidden" name="aid" value="123" />
          <input type="submit" value="加入" onclick="Mui.form.sendAuto('fav_form')" />
        </form>
    """.trimIndent()

    @Test
    fun ehForm() {
        val f = SiteFav.parseForm(eh, "https://example.org/gallerypopups.php")!!
        assertEquals("https://example.org/gallerypopups.php?gid=1&t=abc&act=addfav", f.action)
        assertEquals("favcat", f.choiceName)
        assertEquals(listOf("0" to "Favorites 0", "3" to "我的最愛", "favdel" to "Remove from Favorites"), f.choices)
        assertEquals("3", f.selected)
        val body = f.body("0").toMap()
        assertEquals("0", body["favcat"])
        assertEquals("舊備註", body["favnote"])
        assertEquals("Apply Changes", body["apply"])
        assertEquals("1", body["update"])
    }

    @Test
    fun wnForm() {
        val f = SiteFav.parseForm(wn, "https://example.org/users-addfav-id-123.html")!!
        assertEquals("https://example.org/users-save_fav-id-123.html", f.action)
        assertEquals("favc_id", f.choiceName)
        assertEquals(2, f.choices.size)
        assertEquals("12", f.selected)
        val body = f.body(null).toMap()
        assertEquals("12", body["favc_id"])
        assertEquals("123", body["aid"])
        assertTrue("note" in body)
    }

    @Test
    fun noForm() {
        assertNull(SiteFav.parseForm("<div>沒有表單</div>", "https://example.org/"))
    }
}
