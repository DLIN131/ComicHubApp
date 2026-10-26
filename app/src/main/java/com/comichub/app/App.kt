package com.comichub.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.webkit.CookieManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.comichub.app.data.ZipPageFetcher
import com.comichub.app.data.ZipPageKeyer
import com.comichub.app.net.Lang
import com.comichub.app.net.Net
import com.comichub.app.net.SiteConfig
import org.json.JSONObject

class App : Application(), ImageLoaderFactory {
    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // CookieManager 第一次要在主執行緒建立，之後 OkHttp 在背景讀寫才不會出問題
        CookieManager.getInstance().setAcceptCookie(true)
        // UA 要讀 WebView 的版本，也在主執行緒先算好（之後 OkHttp 在背景用）
        Net.userAgent
        SiteConfig.overrides.putAll(Prefs.siteOverrides)
        Lang.filter = Prefs.lang
        cacheDir.resolve("updates").deleteRecursively()
    }

    // 圖片快取上限 150 MB（桌面版被說過「看越多越吃磁碟」，這裡一開始就設上限）
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Net.http)
        .crossfade(true)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(150L * 1024 * 1024)
                .build()
        }
        .components {
            add(ZipPageFetcher.Factory())
            add(ZipPageKeyer())
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .build()
}

/** 偏好設定（小東西放 SharedPreferences；清單類放 [com.comichub.app.data.Store]） */
object Prefs {
    private val sp: SharedPreferences by lazy {
        App.instance.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    private fun str(key: String, def: String = "") = sp.getString(key, def) ?: def
    private fun put(key: String, v: String) = sp.edit().putString(key, v).apply()

    /** 語言篩選：""＝全部，否則 zh / en / ja */
    var lang: String
        get() = str("lang")
        set(v) = put("lang", v)

    /** 首頁停在哪個分頁：all 或站別 */
    var homeSite: String
        get() = str("homeSite", "all")
        set(v) = put("homeSite", v)

    var favSite: String
        get() = str("favSite", "nh")
        set(v) = put("favSite", v)

    fun sort(site: String, def: String) = str("sort_$site", def)
    fun setSort(site: String, v: String) = put("sort_$site", v)

    /** 搜尋要搜哪幾站 */
    var searchSites: List<String>
        get() = str("searchSites", SiteConfig.KEYS.joinToString(",")).split(',').filter { it in SiteConfig.KEYS }
        set(v) = put("searchSites", v.joinToString(","))

    /** 閱讀模式：true＝上下捲動，false＝左右翻頁 */
    var vertical: Boolean
        get() = sp.getBoolean("vertical", true)
        set(v) = sp.edit().putBoolean("vertical", v).apply()

    /** 左右翻頁時由右往左（日漫順序） */
    var rightToLeft: Boolean
        get() = sp.getBoolean("rtl", false)
        set(v) = sp.edit().putBoolean("rtl", v).apply()

    /** 使用者選「略過這個版本」的 versionCode，自動檢查時不再提示這一版 */
    var skippedVersion: Int
        get() = sp.getInt("skipped_version", 0)
        set(v) = sp.edit().putInt("skipped_version", v).apply()

    /** 上次開 App 的版本：更新後第一次開時顯示這次更新了什麼 */
    var lastVersion: Int
        get() = sp.getInt("last_version", 0)
        set(v) = sp.edit().putInt("last_version", v).apply()

    /** 使用者改過的網站網址 */
    var siteOverrides: Map<String, String>
        get() = runCatching {
            val o = JSONObject(str("sites", "{}"))
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        set(v) = put("sites", JSONObject(v).toString())

    /** 各站帳號：{ site: { name, login, seen } }；不存密碼 */
    var accounts: JSONObject
        get() = runCatching { JSONObject(str("accounts", "{}")) }.getOrDefault(JSONObject())
        set(v) = put("accounts", v.toString())
}
