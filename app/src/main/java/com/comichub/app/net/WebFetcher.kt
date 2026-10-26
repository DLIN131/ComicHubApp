package com.comichub.app.net

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.comichub.app.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** 被 Cloudflare 擋下，需要使用者在看得到的網頁裡手動過驗證 */
class BlockedException(val site: String) : Exception("${SiteConfig.label(site)} 需要通過網站驗證，請按「開啟驗證頁」完成後再試")

/**
 * 有 Cloudflare 的站（JM，或一般請求被擋時），用隱藏的 WebView（真正的 Chromium）先打開該站過驗證，
 * 之後在它裡面用 fetch() 抓 HTML，透過 JS bridge 傳回來。每個站一個 WebView，停在該站的 robots.txt（沒有腳本、不耗資源）。
 */
object WebFetcher {
    private val webs = HashMap<String, WebView>()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val seq = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Resp>>()

    /** 最近一次被擋、需要使用者手動驗證的站；錯誤畫面看到它就顯示「開啟驗證頁」 */
    var blockedSite by mutableStateOf<String?>(null)

    private class Bridge {
        @JavascriptInterface
        fun done(id: Int, status: Int, url: String, body: String) {
            pending.remove(id)?.complete(Resp(status, url, body))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(site: String): WebView = webs[site] ?: WebView(App.instance).also { w ->
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = Net.userAgent
            // 只拿 HTML，不需要載圖
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = WebViewClient()
        w.addJavascriptInterface(Bridge(), "AppBridge")
        w.resumeTimers()
        webs[site] = w
    }

    /** 使用者在驗證頁過完驗證、或登入 / 登出後呼叫，下次請求重新暖機 */
    fun reset(site: String) {
        ready.remove(site)
        if (blockedSite == site) blockedSite = null
    }

    suspend fun get(site: String, url: String): Resp {
        ensureReady(site)
        val r = jsFetch(site, url)
        if (!Net.isChallenge(r.status, r.text)) return r
        // fetch 被驗證擋下（還沒有通行 cookie，或過期了）：跟桌面版一樣，直接在隱藏 WebView 打開那一頁，
        // 讓驗證頁自己跑完，拿畫面上的 HTML；過關後的 cookie 留著，之後的 fetch 就能直接用
        return lock(site).withLock {
            ready.remove(site)
            val html = load(site, url) ?: throw blocked(site)
            Resp(200, html.first, html.second)
        }
    }

    private fun lock(site: String) = locks.getOrPut(site) { Mutex() }

    private fun blocked(site: String): BlockedException {
        blockedSite = site
        return BlockedException(site)
    }

    private val CHALLENGE = Regex("Just a moment|請稍候|Attention Required|cf-chl|challenge-platform", RegexOption.IGNORE_CASE)

    /** 在隱藏 WebView 打開網頁，等驗證跑完（最多 25 秒），回傳（最後網址, HTML）；過不了回傳 null */
    private suspend fun load(site: String, url: String): Pair<String, String>? = withContext(Dispatchers.Main) {
        val w = webView(site)
        w.loadUrl(url)
        val r = withTimeoutOrNull(25_000) {
            delay(800)
            while (true) {
                val s = w.eval("document.readyState+'|'+document.title+'|'+document.documentElement.outerHTML.slice(0,4000)")
                if (s.startsWith("complete") && !CHALLENGE.containsMatchIn(s)) {
                    val href = w.eval("location.href")
                    val html = w.eval("document.documentElement.outerHTML")
                    return@withTimeoutOrNull href to html
                }
                delay(700)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        if (r == null) w.stopLoading() else CookieManager.getInstance().flush()
        r
    }

    /** 停在該站的 robots.txt（同網域、沒有腳本），之後的 fetch 都從這裡發 */
    private suspend fun ensureReady(site: String) {
        if (site in ready) return
        lock(site).withLock {
            if (site in ready) return
            load(site, SiteConfig.base(site) + "/robots.txt") ?: throw blocked(site)
            ready.add(site)
            if (blockedSite == site) blockedSite = null
        }
    }

    /** 送表單（加入收藏等）：在隱藏 WebView 裡用 fetch POST，帶網站自己的 cookie */
    suspend fun post(site: String, url: String, body: String): Resp {
        ensureReady(site)
        val init = "{credentials: 'include', method: 'POST', body: ${JSONObject.quote(body)}, " +
            "headers: {'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest'}}"
        val r = jsFetch(site, url, init)
        if (Net.isChallenge(r.status, r.text)) {
            ready.remove(site)
            throw blocked(site)
        }
        return r
    }

    private suspend fun jsFetch(site: String, url: String, init: String = "{credentials: 'include'}"): Resp {
        val id = seq.incrementAndGet()
        val result = CompletableDeferred<Resp>()
        pending[id] = result
        val js = """
            fetch(${JSONObject.quote(url)}, $init)
              .then(function (r) { return r.text().then(function (t) { AppBridge.done($id, r.status, r.url, t); }); })
              .catch(function (e) { AppBridge.done($id, -1, '', String(e)); });
        """.trimIndent()
        withContext(Dispatchers.Main) { webView(site).evaluateJavascript(js, null) }
        return try {
            val r = withTimeout(40_000) { result.await() }
            if (r.status == -1) {
                ready.remove(site)
                throw NetException("連不上網站（網路中斷或網站被擋）")
            }
            r
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun WebView.eval(js: String): String = suspendCancellableCoroutine { cont ->
        evaluateJavascript(js) { raw ->
            // 回傳值是 JSON 字串
            val s = runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrDefault("")
            if (cont.isActive) cont.resume(s)
        }
    }
}
