package com.comichub.app.net

import com.comichub.app.Prefs
import org.json.JSONObject

class AuthState(val loggedIn: Boolean, val name: String = "", val error: String? = null, val expired: Boolean = false)

/** 各站登入狀態、帳號名稱（跟桌面版 fetcher.js 的 checkAuth / authStatus 一樣） */
object Auth {
    private fun acc(site: String): JSONObject = Prefs.accounts.optJSONObject(site) ?: JSONObject()

    private fun update(site: String, fn: (JSONObject) -> Unit) {
        val all = Prefs.accounts
        val o = all.optJSONObject(site) ?: JSONObject()
        fn(o)
        all.put(site, o)
        Prefs.accounts = all
    }

    fun accountName(site: String): String = acc(site).optString("name")

    fun setAccountName(site: String, name: String) {
        update(site) { if (name.isBlank()) it.remove("name") else it.put("name", name.trim()) }
        if (site == "jm") Jm.username = name.trim()
    }

    /** 上次登入時打的帳號（下次登入先幫忙填好；只記帳號，不記密碼） */
    fun lastLogin(site: String): String = acc(site).optString("login").ifBlank { accountName(site) }

    fun setLastLogin(site: String, login: String) = update(site) { it.put("login", login.trim()) }

    private suspend fun check(site: String): AuthState = try {
        when (site) {
            "nh" -> {
                if (Net.nhToken() == null && !Net.nhRefresh()) AuthState(false)
                else {
                    val r = Net.fetch("nh", "/api/v2/user")
                    if (r.status !in 200..299) AuthState(false, error = if (r.status == 401) null else Net.errorText(r))
                    else JSONObject(r.text).let { AuthState(true, it.optString("username").ifBlank { it.optString("name") }) }
                }
            }
            "jm" -> {
                // 「我的」頁 /user/：沒登入會被導到 /login，登入後是自己的頁面
                val r = Net.fetch("jm", "/user/")
                val loginPage = Regex("/login(?:[/?#]|$)").containsMatchIn(r.url) || Regex("<title>\\s*Login\\b", RegexOption.IGNORE_CASE).containsMatchIn(r.text)
                if (r.status !in 200..299 && !loginPage) AuthState(false, error = Net.errorText(r))
                else if (loginPage) AuthState(false)
                else AuthState(true, accountName("jm").ifBlank { jmNameFrom(r.url, r.text) })
            }
            "wn" -> {
                val r = Net.fetch("wn", "/users.html")
                if (r.status !in 200..299) AuthState(false, error = Net.errorText(r))
                else if (r.url.contains("users-login.html") || !Regex("users-logout|退出|登出").containsMatchIn(r.text)) AuthState(false)
                else {
                    val m = Regex("歡迎[：:,，\\s]*(?:<[^>]+>)*\\s*([^<\\s]+)").find(r.text)
                        ?: Regex("class=\"[^\"]*user_name[^\"]*\"[^>]*>\\s*([^<]+)").find(r.text)
                    AuthState(true, m?.groupValues?.get(1)?.trim().orEmpty())
                }
            }
            "eh" -> {
                // 登入後論壇會在整個網域寫 ipb_member_id / ipb_pass_hash
                val id = Net.cookie(SiteConfig.base("eh"), "ipb_member_id")
                if (id == null || id == "0") AuthState(false)
                else {
                    val r = runCatching { Net.fetch("eh", SiteConfig.forumUrl("/index.php")) }.getOrNull()
                    val m = r?.let { Regex("Logged in as:?\\s*(?:<[^>]+>\\s*)*([^<]+)<", RegexOption.IGNORE_CASE).find(it.text) }
                    AuthState(true, m?.groupValues?.get(1)?.trim().orEmpty())
                }
            }
            else -> AuthState(false)
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        AuthState(false, error = e.message ?: e.toString())
    }

    /**
     * 記住「這個站登入過」。之後變成沒登入（網站那邊的登入過期了）就回 expired，
     * 畫面才能說「登入已過期，請重新登入」，而不是只顯示空的收藏。
     */
    suspend fun status(site: String): AuthState {
        val s = check(site)
        if (s.loggedIn && !acc(site).optBoolean("seen")) update(site) { it.put("seen", true) }
        if (site == "jm" && s.loggedIn) Jm.username = s.name
        if (!s.loggedIn && s.error == null && acc(site).optBoolean("seen")) return AuthState(false, expired = true)
        return s
    }

    suspend fun logout(site: String) {
        if (site == "nh") runCatching { Net.fetch("nh", "/auth/logout", "POST") }
        Net.clearCookies(site)
        // 自己登出的就不算「過期」，帳號名稱也一起清掉；只留下次登入要預先填的帳號
        val login = acc(site).optString("login")
        val all = Prefs.accounts
        all.remove(site)
        if (login.isNotBlank()) all.put(site, JSONObject().put("login", login))
        Prefs.accounts = all
        if (site == "jm") Jm.username = ""
    }

    fun expiredText(site: String): String {
        val label = SiteConfig.label(site)
        if (site == "jm") return "$label 的登入已失效。這個站同一個帳號只能在一個地方保持登入，在瀏覽器或電腦版登入過就會把這裡登出，請重新登入。"
        return "$label 的登入已過期，請重新登入"
    }

    /** 從 JM「我的」頁找帳號名稱；抓不到就回空字串讓使用者自己填，不要猜 */
    private fun jmNameFrom(url: String, html: String): String {
        fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        Regex("/user/([^/?#]+)").find(url)?.let { return dec(it.groupValues[1]) }
        Regex("/user/([^\"/?#\\s]+)/favorite").find(html)?.let { return dec(it.groupValues[1]) }
        val menu = Regex("href=\"(?:https?://[^/\"]+)?/user/([^\"/?#]+)/?\"[^>]*>[\\s\\S]{0,200}?我的").find(html)
            ?: Regex("href=\"(?:https?://[^/\"]+)?/user/([^\"/?#]+)/?\"[^>]*>\\s*<i class=\"fas? fa-user-circle").find(html)
        return menu?.let { dec(it.groupValues[1]) }.orEmpty()
    }
}
