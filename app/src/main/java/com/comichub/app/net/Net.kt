package com.comichub.app.net

import android.webkit.CookieManager
import android.webkit.WebSettings
import com.comichub.app.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 一次請求的結果；url 是轉址後的最後網址 */
class Resp(val status: Int, val url: String, val text: String)

/**
 * OkHttp 跟 WebView 共用同一份 cookie（CookieManager）：
 * 在登入頁（WebView）登入後，一般請求也帶得到登入狀態；反過來伺服器用 Set-Cookie 換的新 token 也會寫回 WebView。
 */
object WebCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = CookieManager.getInstance()
        cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
        return raw.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

object Net {
    /**
     * 全部用同一個「電腦版 Chrome」UA（版本跟手機上的 WebView 一致）：
     * 網站才會給跟桌面版一樣的頁面格式，Cloudflare 的通行 cookie 也綁 UA，WebView 和 OkHttp 要用同一個。
     */
    val userAgent: String by lazy {
        val chrome = runCatching { WebSettings.getDefaultUserAgent(App.instance) }.getOrNull()
            ?.let { Regex("Chrome/([\\d.]+)").find(it)?.groupValues?.get(1) } ?: "130.0.0.0"
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    /** 網頁、API 用 */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cookieJar(WebCookieJar)
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder().header("User-Agent", userAgent)
                // 圖片 CDN 會看 Referer，依網址判斷是哪個站
                if (req.header("Referer") == null) {
                    SiteConfig.siteForUrl(req.url.toString())?.let { b.header("Referer", SiteConfig.base(it) + "/") }
                }
                chain.proceed(b.build())
            }
            .build()
    }

    /** 這次開 App 期間被 Cloudflare 擋過的站，之後直接走 WebView，不用每次先被擋一次 */
    private val viaWeb = HashSet<String>().apply { add("jm") }

    fun full(site: String, path: String) = if (path.startsWith("http")) path else SiteConfig.base(site) + path

    fun isChallenge(status: Int, text: String) =
        (status == 403 || status == 503) &&
            Regex("challenge-platform|Just a moment|cf-chl|Attention Required", RegexOption.IGNORE_CASE).containsMatchIn(text.take(20000))

    /** 抓網頁（GET），回傳 HTML / JSON 文字；失敗丟出看得懂的錯誤訊息 */
    suspend fun text(site: String, path: String): String {
        val r = fetch(site, path)
        if (r.status !in 200..299) throw NetException(errorText(r), r.status)
        return r.text
    }

    suspend fun json(site: String, path: String, method: String = "GET"): JSONObject {
        val r = fetch(site, path, method)
        if (r.status !in 200..299) throw NetException(errorText(r), r.status)
        return JSONObject(r.text.ifBlank { "{}" })
    }

    /** 原始請求（不判斷狀態碼） */
    suspend fun fetch(site: String, path: String, method: String = "GET"): Resp {
        val url = full(site, path)
        try {
            if (method == "GET" && site in viaWeb && SiteConfig.siteForUrl(url) == site) return WebFetcher.get(site, url)
            var token = if (site == "nh") nhToken() else null
            var r = okFetch(site, url, method, token)
            if (site == "nh" && r.status == 401 && token != null && nhRefresh()) {
                token = nhToken()
                r = okFetch(site, url, method, token)
            }
            if (isChallenge(r.status, r.text)) {
                if (method != "GET") throw NetException("被網站驗證擋下，請先在「帳號」頁開啟網站瀏覽一下再試", r.status)
                viaWeb.add(site)
                return WebFetcher.get(site, url)
            }
            return r
        } catch (e: UnknownHostException) {
            throw NetException("連不上網站（網路中斷或網站被擋）")
        } catch (e: java.net.SocketTimeoutException) {
            throw NetException("連線逾時，請稍後再試")
        } catch (e: javax.net.ssl.SSLException) {
            throw NetException("連不上網站（連線被中斷，可能被擋）")
        } catch (e: java.net.ConnectException) {
            throw NetException("連不上網站（網路中斷或網站被擋）")
        }
    }

    private suspend fun okFetch(site: String, url: String, method: String, token: String?): Resp = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("Referer", SiteConfig.base(site) + "/")
        if (token != null) b.header("Authorization", "User $token")
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete()
            else -> b.method(method, "".toRequestBody("application/json".toMediaType()))
        }
        http.newCall(b.build()).execute().use { r -> Resp(r.code, r.request.url.toString(), r.body?.string().orEmpty()) }
    }

    fun errorText(r: Resp): String {
        if (r.status == 404) return "找不到（可能已被刪除）"
        if (r.status == 429) return "請求太頻繁，稍等一下再試"
        val msg = runCatching {
            val j = JSONObject(r.text)
            j.optString("error").ifBlank { j.optString("detail") }
        }.getOrNull()
        return if (!msg.isNullOrBlank()) msg else "載入失敗（HTTP ${r.status}）"
    }

    /** 下載用：抓圖片原始位元組，失敗重試兩次 */
    suspend fun bytes(site: String, url: String): Pair<ByteArray, String> {
        var last: Exception? = null
        repeat(3) { i ->
            try {
                return withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(url).header("Referer", SiteConfig.base(site) + "/").build()
                    http.newCall(req).execute().use { r ->
                        if (r.code == 404) throw NetException("圖片不存在（HTTP 404）", 404)
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        r.body!!.bytes() to (r.header("Content-Type") ?: "")
                    }
                }
            } catch (e: NetException) {
                throw e
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
                delay(1000L * (i + 1))
            }
        }
        throw NetException("圖片下載失敗：${last?.message ?: ""}")
    }

    // ---------- NH 登入 token ----------

    fun cookie(url: String, name: String): String? =
        CookieManager.getInstance().getCookie(url)?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.takeIf { it.isNotEmpty() }

    fun nhToken(): String? = cookie(SiteConfig.base("nh"), "access_token")

    /** 網站本身的換票方式：refresh token 在 cookie 裡，POST /auth/refresh 會寫回新的 access_token */
    suspend fun nhRefresh(): Boolean = runCatching {
        okFetch("nh", SiteConfig.base("nh") + "/auth/refresh", "POST", null).status in 200..299
    }.getOrDefault(false)

    /** 清掉某個站（含子網域）的 cookie＝登出 */
    fun clearCookies(site: String) {
        val cm = CookieManager.getInstance()
        val root = SiteConfig.rootDomain(SiteConfig.host(site))
        val urls = listOf(SiteConfig.base(site), "https://$root", "https://www.$root", "https://forums.$root")
        for (u in urls) {
            val names = cm.getCookie(u)?.split(';')?.map { it.trim().substringBefore('=') }.orEmpty()
            for (n in names) {
                cm.setCookie(u, "$n=; Max-Age=0; Path=/")
                cm.setCookie(u, "$n=; Max-Age=0; Path=/; Domain=.$root")
            }
        }
        cm.flush()
        WebFetcher.reset(site)
    }
}
