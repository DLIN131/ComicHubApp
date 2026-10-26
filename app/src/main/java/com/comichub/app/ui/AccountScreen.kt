package com.comichub.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.comichub.app.App
import com.comichub.app.Prefs
import com.comichub.app.data.Library
import com.comichub.app.net.Auth
import com.comichub.app.net.AuthState
import com.comichub.app.net.Net
import com.comichub.app.net.SiteConfig
import com.comichub.app.net.WebFetcher
import com.comichub.app.util.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

// ================= 帳號與設定 =================

@Composable
fun AccountScreen() {
    val scope = rememberCoroutineScope()
    val status = remember { mutableStateMapOf<String, AuthState?>() }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        SiteConfig.KEYS.forEach { status[it] = null }
        SiteConfig.KEYS.map { k -> async { k to Auth.status(k) } }.forEach { val (k, s) = it.await(); status[k] = s }
    }
    var editUrl by remember { mutableStateOf<String?>(null) }
    var changelog by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var sizes by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(Unit) {
        sizes = withContext(Dispatchers.IO) { Library.totalSize() to App.instance.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("帳號", Modifier.weight(1f))
            IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, "重新檢查", tint = Color.White) }
        }
        Text("每個站各自登入（開該站自己的登入頁，帳密不經過本 App），登入後就能在「收藏」看網站上的收藏。", color = Muted, fontSize = 12.sp)
        for (k in SiteConfig.KEYS) {
            val s = status[k]
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).background(Surface).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SiteBadge(k, small = false)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            s == null -> "檢查中…"
                            s.loggedIn -> "已登入" + if (s.name.isNotBlank()) "：${s.name}" else ""
                            s.error != null -> "無法確認：${s.error}"
                            s.expired -> "登入已過期"
                            else -> "未登入"
                        },
                        color = if (s?.loggedIn == true) Color(0xFF7CD992) else Muted,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (s?.expired == true) Text(Auth.expiredText(k), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (s?.loggedIn == true) OutlinedButton(onClick = {
                        scope.launch {
                            Auth.logout(k)
                            FavState.auth.remove(k)
                            Feeds.clear()
                            refresh++
                            toast("已登出 ${SiteConfig.label(k)}")
                        }
                    }) { Text("登出") }
                    else Button(onClick = { Nav.push(Route.Login(k)) }) { Text("登入") }
                    OutlinedButton(onClick = { Nav.push(Route.Web(k, SiteConfig.base(k))) }) { Text("開啟網站") }
                    TextButton(onClick = { editUrl = k }) { Text("網址") }
                }
                if (k == "jm") {
                    // 收藏網址要帳號名稱；登入時會自動記下，抓不到時讓使用者自己填
                    var name by remember { mutableStateOf(Auth.accountName("jm")) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(name, { name = it }, label = { Text("帳號名稱（讀收藏用）") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            Auth.setAccountName("jm", name)
                            FavState.auth.remove("jm")
                            Feeds.clear()
                            toast("已儲存")
                        }) { Text("儲存") }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("閱讀")
        var vertical by remember { mutableStateOf(Prefs.vertical) }
        var rtl by remember { mutableStateOf(Prefs.rightToLeft) }
        SettingSwitch("上下捲動（關掉＝左右翻頁）", vertical) {
            vertical = it
            Prefs.vertical = it
        }
        SettingSwitch("左右翻頁時由右往左（日漫順序）", rtl) {
            rtl = it
            Prefs.rightToLeft = it
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("儲存空間")
        Text("書櫃：${sizes?.first?.let(::formatSize) ?: "…"}　（${Library.dir.absolutePath}）", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Text("解除安裝 App 時書櫃的漫畫也會一起刪除，要保留可以先在書櫃用「分享」存到別的地方。", color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("圖片快取：${sizes?.second?.let(::formatSize) ?: "…"}（上限 150 MB，會自動清舊的）", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { App.instance.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                    sizes = sizes?.copy(second = 0)
                    toast("已清除快取")
                }
            }) { Text("清除") }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("關於")
        Text("ComicHub ${Updater.currentVersionName}", color = Color.White, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    try {
                        val r = Updater.check()
                        if (r != null) Updater.pending = r else toast("已經是最新版")
                    } catch (e: Exception) {
                        toast("檢查失敗：${e.message}")
                    } finally {
                        checking = false
                    }
                }
            }) { Text(if (checking) "檢查中…" else "檢查更新") }
            OutlinedButton(onClick = { changelog = true }) { Text("更新紀錄") }
        }
        Spacer(Modifier.height(24.dp))
    }

    editUrl?.let { k ->
        var v by remember(k) { mutableStateOf(SiteConfig.base(k)) }
        var err by remember(k) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editUrl = null },
            title = { Text("${SiteConfig.label(k)} 網址") },
            text = {
                Column {
                    Text("網站換網域時改這裡。留空 = 用預設網址。", color = Muted, fontSize = 13.sp)
                    OutlinedTextField(v, { v = it; err = null }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    err?.let { Text(it, color = ErrorColor, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = v.trim().trimEnd('/')
                    val def = SiteConfig.DEFS.getValue(k).base
                    if (t.isNotEmpty() && !Regex("^https?://[^/]+$").matches(t)) {
                        err = "網址格式要像 https://example.com"
                        return@TextButton
                    }
                    val map = Prefs.siteOverrides.toMutableMap()
                    if (t.isEmpty() || t == def) map.remove(k) else map[k] = t
                    Prefs.siteOverrides = map
                    SiteConfig.overrides.clear()
                    SiteConfig.overrides.putAll(map)
                    WebFetcher.reset(k)
                    Feeds.clear()
                    editUrl = null
                    toast("已儲存")
                }) { Text("儲存") }
            },
            dismissButton = { TextButton(onClick = { editUrl = null }) { Text("取消") } },
        )
    }
    if (changelog) ChangelogDialog(since = 0, title = "更新紀錄") { changelog = false }
}

@Composable
private fun SettingSwitch(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

// ================= 登入 / 開啟網站（WebView） =================

/** JM 登入頁一開就有整頁廣告蓋住表單，直接藏起來 */
private const val JM_CSS =
    "#outsidenoticeModal,#shunt-modal,#billboard-modal,#bouns-popup,#guide-modal,#headelikes_modal,.modal-backdrop{display:none!important}" +
        "body.modal-open{overflow:auto!important;padding-right:0!important}"

@SuppressLint("SetJavaScriptEnabled")
private fun newWebView(ctx: android.content.Context, site: String, onPage: (WebView, String) -> Unit): WebView = WebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // 跟背景抓資料用同一個 UA（Cloudflare 的通行 cookie 綁 UA），網頁是電腦版排版，可以縮放
    settings.userAgentString = Net.userAgent
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.setSupportMultipleWindows(false)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    webViewClient = object : WebViewClient() {
        // 廣告會把頁面導到別的網站，只允許留在同一個站
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url.toString()
            if (!u.startsWith("http")) return true
            return SiteConfig.siteForUrl(u) != site
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (site == "jm") view.evaluateJavascript(injectCss(JM_CSS), null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (site == "jm") view.evaluateJavascript(injectCss(JM_CSS), null)
            CookieManager.getInstance().flush()
            onPage(view, url)
        }
    }
}

private fun injectCss(css: String) =
    "(function(){if(document.getElementById('__ch_css'))return;var s=document.createElement('style');s.id='__ch_css';s.textContent=${JSONObject.quote(css)};(document.head||document.documentElement).appendChild(s);})()"

@Composable
private fun WebTopBar(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(Surface).statusBarsPadding().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
        Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
fun LoginScreen(route: Route.Login) {
    val site = route.site
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }
    var typed by remember { mutableStateOf("") }
    var closing by remember { mutableStateOf(false) }

    fun finish() {
        if (closing) return
        closing = true
        scope.launch {
            WebFetcher.reset(site)
            val s = Auth.status(site)
            if (s.loggedIn && typed.isNotBlank()) {
                Auth.setLastLogin(site, typed)
                if (s.name.isBlank()) Auth.setAccountName(site, typed)
            }
            FavState.auth.remove(site)
            Feeds.clear()
            toast(if (s.loggedIn) "${SiteConfig.label(site)} 已登入" + (s.name.ifBlank { typed }.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "") else "${SiteConfig.label(site)} 尚未登入")
            if (Nav.top === route) Nav.pop()
        }
    }

    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else finish()
    }
    // JM：記下使用者在「看得到的」帳號欄打的字（頁面上還有兩個藏在彈窗裡），收藏網址要用；不碰密碼
    if (site == "jm") LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        while (true) {
            delay(1000)
            w.evaluateJavascript(
                "(function(){var all=[].slice.call(document.querySelectorAll('input[name=\"username\"]'));" +
                    "var seen=all.filter(function(u){return u.offsetParent!==null&&u.value.trim()});" +
                    "return ((seen[0]||all.filter(function(u){return u.value.trim()})[0])||{}).value||''})()",
            ) { raw ->
                val v = runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrDefault("")
                if (v.isNotBlank()) typed = v.trim()
            }
        }
    }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        WebTopBar("登入 ${SiteConfig.label(site)}", ::finish) { TextButton(onClick = ::finish) { Text("完成", color = Color.White) } }
        AndroidView(
            factory = { ctx ->
                newWebView(ctx, site) { view, url ->
                    if (site == "jm" && Regex("login", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                        // 預設勾「記住我」（不然網站那邊一陣子沒動就登出），重新登入時先填好上次的帳號
                        view.evaluateJavascript(
                            "document.querySelectorAll('input[name=\"id_remember\"],input[name=\"login_remember\"]').forEach(function(c){c.checked=true});" +
                                "document.querySelectorAll('input[name=\"username\"]').forEach(function(u){if(!u.value)u.value=${JSONObject.quote(Auth.lastLogin("jm"))}});0",
                            null,
                        )
                    }
                    // 登入成功（網站跳離登入頁）就自動關掉
                    if (!Regex("login", RegexOption.IGNORE_CASE).containsMatchIn(url) && checkJob?.isActive != true) {
                        checkJob = scope.launch {
                            val s = runCatching { Auth.status(site) }.getOrNull()
                            if (s?.loggedIn == true) finish()
                        }
                    }
                }.also {
                    web = it
                    it.loadUrl(SiteConfig.loginUrl(site))
                }
            },
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        )
    }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
}

@Composable
fun WebScreen(route: Route.Web) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf(SiteConfig.label(route.site)) }
    fun close() {
        if (route.verify) {
            WebFetcher.reset(route.site)
            toast("請回到剛才的頁面重試")
        }
        if (Nav.top === route) Nav.pop()
    }
    BackHandler {
        val w = web
        if (w != null && w.canGoBack() && !route.verify) w.goBack() else close()
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        WebTopBar(if (route.verify) "完成驗證後按返回" else title, ::close) {
            IconButton(onClick = { web?.reload() }) { Icon(Icons.Filled.Refresh, "重新整理", tint = Color.White) }
        }
        AndroidView(
            factory = { ctx ->
                newWebView(ctx, route.site) { view, _ -> view.title?.let { if (it.isNotBlank()) title = it } }.also {
                    web = it
                    it.loadUrl(route.url)
                }
            },
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        )
    }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
}

// ================= 更新 =================

@Composable
fun UpdateDialog(release: Updater.Release, onDismiss: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) } // null = 沒在下載
    var file by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun tryInstall(apk: File) {
        if (Updater.canInstall(context)) {
            message = null
            Updater.install(context, apk)
        } else {
            message = "請在接下來的設定畫面允許本 App「安裝不明應用程式」，返回後再按「安裝」。"
            if (!Updater.openInstallPermissionSettings(context)) {
                message = "找不到設定頁面，請到系統設定允許本 App 安裝不明應用程式，再回來按「安裝」。"
            }
        }
    }

    fun start() {
        if (progress != null) return
        file?.let {
            tryInstall(it)
            return
        }
        message = null
        progress = 0f
        job = scope.launch {
            try {
                val apk = Updater.download(context, release) { progress = it }
                file = apk
                progress = null
                tryInstall(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress = null
                message = "下載失敗：${e.message}"
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("發現新版本") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${Updater.currentVersionName}  →  ${release.name.removePrefix("ComicHub ")}", color = Accent)
                if (release.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("更新內容", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(release.notes, lineHeight = 20.sp)
                }
                progress?.let { p ->
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { p }, color = Accent, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text("下載中 ${(p * 100).toInt()}%", color = Muted, fontSize = 13.sp)
                }
                message?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Muted, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::start) {
                Text(
                    when {
                        progress != null -> "下載中…"
                        file != null -> "安裝"
                        else -> "立即更新"
                    }
                )
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = ::dismiss) { Text("稍後再說") }
                TextButton(onClick = {
                    job?.cancel()
                    onSkip()
                }) { Text("略過這個版本") }
            }
        },
    )
}

/** 更新紀錄（讀 GitHub Releases）；since > 0 時只列這之後的版本 */
@Composable
fun ChangelogDialog(since: Int, title: String, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf<List<Updater.Release>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            list = Updater.changelog().filter { it.versionCode > since && it.versionCode <= maxOf(Updater.currentVersionCode, since + 1000) }
        } catch (e: Exception) {
            error = "讀不到更新紀錄：${e.message}"
        }
    }
    // 更新後自動顯示時，讀不到就算了，不要擋著畫面
    if (since > 0 && (error != null || list?.isEmpty() == true)) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val l = list
            when {
                error != null -> Text(error!!, color = ErrorColor)
                l == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("讀取中…")
                }
                l.isEmpty() -> Text("還沒有更新紀錄", color = Muted)
                else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(l) { r ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Row {
                                Text("v1.0.${r.versionCode}", color = if (r.versionCode == Updater.currentVersionCode) Accent else Color.White, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text(r.date, color = Muted, fontSize = 12.sp)
                                if (r.versionCode == Updater.currentVersionCode) Text("（目前版本）", color = Accent, fontSize = 12.sp)
                            }
                            Text(r.notes.ifBlank { "（沒有說明）" }, color = Color.White, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}
