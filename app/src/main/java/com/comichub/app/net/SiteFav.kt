package com.comichub.app.net

import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * 加到網站帳號的收藏（NH 另外用 API，在 [Nh.setFavorite]）。
 *
 * EH、WN：先打開網站自己的「加入收藏」表單（要登入才看得到），照表單的欄位送出；
 * 有好幾個收藏夾時把選項列出來讓使用者選。這樣不用猜欄位名稱，網站改版也比較不會壞。
 * JM：網站的收藏按鈕是 POST /ajax/favorite_album（album_id、fid＝收藏夾，0 是預設），回傳 JSON 的訊息直接顯示。
 */
object SiteFav {
    /** 網頁上的表單：送到哪、要送的欄位、可以選的收藏夾 */
    class Form(
        val action: String,
        val fields: List<Pair<String, String>>,
        /** 收藏夾欄位名稱（沒有選項時是 null） */
        val choiceName: String?,
        /** 收藏夾選項：值 to 顯示名稱 */
        val choices: List<Pair<String, String>>,
        /** 目前選的（EH 已經收藏過時是現在的收藏夾） */
        val selected: String?,
    ) {
        fun body(choice: String?): List<Pair<String, String>> {
            val out = fields.filter { it.first != choiceName }.toMutableList()
            val v = choice ?: selected
            if (choiceName != null && v != null) out.add(choiceName to v)
            return out
        }
    }

    /** 抓不到表單或需要登入 */
    class NeedLogin(site: String) : Exception("還沒登入${SiteConfig.label(site)}，登入後才能加到網站收藏")

    private fun txt(s: String?) = s.orEmpty().replace(Regex("\\s+"), " ").trim()

    /** 解析網頁上的第一個表單（radio / select 當成收藏夾選項） */
    fun parseForm(html: String, pageUrl: String): Form? {
        val doc = Jsoup.parse(html, pageUrl)
        val f = doc.selectFirst("form") ?: return null
        val action = f.absUrl("action").ifBlank { pageUrl }
        val fields = ArrayList<Pair<String, String>>()
        var submitAdded = false
        var choiceName: String? = null
        val choices = ArrayList<Pair<String, String>>()
        var selected: String? = null
        // 收藏夾：第一組有兩個以上選項的 radio，沒有的話用第一個 select
        val radios = f.select("input[type=radio][name]").groupBy { it.attr("name") }.entries.firstOrNull { it.value.size > 1 }
        if (radios != null) {
            choiceName = radios.key
            for (r in radios.value) {
                val id = r.id()
                // 名稱可能在 <label for>、或同一列旁邊的 div（radio 自己包在一個空的 div 裡），往上找幾層有字的
                val label = (if (id.isNotEmpty()) f.selectFirst("label[for=$id]")?.text() else null)?.let(::txt)?.ifEmpty { null }
                    ?: generateSequence(r.parent()) { it.parent() }.take(3).map { txt(it.text()) }.firstOrNull { it.isNotEmpty() }
                    ?: r.attr("value")
                choices.add(r.attr("value") to label)
                if (r.hasAttr("checked")) selected = r.attr("value")
            }
        } else f.selectFirst("select[name]")?.let { s ->
            choiceName = s.attr("name")
            for (o in s.select("option")) {
                choices.add(o.attr("value").ifEmpty { o.text() } to txt(o.text()))
                if (o.hasAttr("selected")) selected = o.attr("value").ifEmpty { o.text() }
            }
        }
        if (selected == null) selected = choices.firstOrNull()?.first
        for (i in f.select("input[name]")) {
            val name = i.attr("name")
            when (i.attr("type").lowercase()) {
                "radio" -> if (name != choiceName && i.hasAttr("checked")) fields.add(name to i.attr("value"))
                "checkbox" -> if (i.hasAttr("checked")) fields.add(name to i.attr("value").ifEmpty { "on" })
                "submit", "button", "image" -> if (!submitAdded && i.attr("type").lowercase() == "submit") {
                    fields.add(name to i.attr("value"))
                    submitAdded = true
                }
                "reset", "file" -> {}
                else -> fields.add(name to i.attr("value"))
            }
        }
        for (t in f.select("textarea[name]")) fields.add(t.attr("name") to t.text())
        for (s in f.select("select[name]")) if (s.attr("name") != choiceName) {
            val o = s.selectFirst("option[selected]") ?: s.selectFirst("option")
            fields.add(s.attr("name") to (o?.attr("value") ?: ""))
        }
        return Form(action, fields, choiceName, choices, selected)
    }

    private fun isLoginPage(html: String) =
        Regex("requires you to log on|login_form|ipb_login_form|users-check_login|name=\"login_name\"", RegexOption.IGNORE_CASE).containsMatchIn(html)

    /** EH、WN：拿網站的「加入收藏」表單 */
    suspend fun form(d: Detail): Form {
        val url = when (d.site) {
            "eh" -> Eh.split(d.id).let { (gid, t) -> "/gallerypopups.php?gid=$gid&t=$t&act=addfav" }
            "wn" -> "/users-addfav-id-${d.id}.html"
            else -> throw NetException("這個站不用表單")
        }
        val r = Net.fetch(d.site, url)
        if (r.status !in 200..299) throw NetException(Net.errorText(r), r.status)
        if (isLoginPage(r.text)) throw NeedLogin(d.site)
        return parseForm(r.text, Net.full(d.site, url)) ?: throw NetException("網站的收藏表單跟預期的不一樣")
    }

    /** 送出表單，回傳給使用者看的結果 */
    suspend fun submit(d: Detail, form: Form, choice: String?): String {
        val r = Net.postForm(d.site, form.action, form.body(choice))
        if (r.status !in 200..299) throw NetException(Net.errorText(r), r.status)
        if (isLoginPage(r.text)) throw NeedLogin(d.site)
        val removing = choice == "favdel"
        return message(r.text) ?: if (removing) "已從網站收藏移除" else "已加到網站收藏"
    }

    /** JM：直接送收藏（fid 0 = 預設收藏夾） */
    suspend fun jm(d: Detail): String {
        val r = Net.postForm("jm", "/ajax/favorite_album", listOf("album_id" to d.id, "fid" to "0"))
        if (r.status !in 200..299) throw NetException(Net.errorText(r), r.status)
        val o = runCatching { JSONObject(r.text) }.getOrNull()
            ?: throw NetException(if (isLoginPage(r.text) || r.text.contains("login", true)) "還沒登入${SiteConfig.label("jm")}" else "網站回應看不懂")
        val msg = o.optString("msg").ifBlank { o.optString("message") }
        // status 1 = 成功；其他是網站的錯誤訊息（例如沒登入、已經收藏過）
        if (o.optInt("status", 1) != 1) throw NetException(msg.ifBlank { "收藏失敗" })
        return msg.ifBlank { "已加到網站收藏" }
    }

    /** 網站回的訊息：JSON 的 msg，或 HTML 裡的文字（太長就不顯示） */
    private fun message(text: String): String? {
        runCatching { JSONObject(text) }.getOrNull()?.let { o ->
            val m = o.optString("msg").ifBlank { o.optString("message") }.ifBlank { o.optString("html") }
            if (m.isNotBlank()) return Jsoup.parse(m).text().take(80)
        }
        val t = txt(Jsoup.parse(text).body()?.text())
        return t.takeIf { it.isNotEmpty() && it.length <= 60 }
    }
}
