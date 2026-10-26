package com.comichub.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.comichub.app.data.Favorites
import com.comichub.app.net.SiteConfig
import com.comichub.app.net.SiteFav
import com.comichub.app.net.site
import kotlinx.coroutines.runBlocking
import com.github.takahirom.roborazzi.captureRoboImage
import com.comichub.app.net.Lang
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 畫面測試（Robolectric，不用手機也不用模擬器）：截圖存在 app/build/ui/，看排版用。
 * 會真的連網（NH / WN / EH 用一般 HTTP 就能抓；JM 要 WebView，這裡抓不到是正常的），
 * 所以預設不跑：設環境變數 UI_TEST=1 才跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** 導覽、列表快取是整個 App 共用的，同一次跑好幾個測試時要先回到首頁的初始狀態 */
    @Before
    fun reset() {
        rule.runOnUiThread {
            while (Nav.pop()) Unit
            Nav.tab = Tab.Home
            HomeState.site = "all"
            Feeds.clear()
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/ui/$name.png")
    }

    /** 等首頁這幾站的列表載完（Robolectric 的時鐘要自己推） */
    /** 等首頁自己建立、載完這幾站的列表（不自己建，免得搶在首頁前面建出空的） */
    private fun waitFeeds(keys: List<String>, seconds: Int = 60): List<Pair<String, Feed>> {
        fun current() = keys.map { k -> k to Feeds.find("home_$k") }
        repeat(seconds) {
            val now = current()
            if (now.all { (_, f) -> f != null && (f.items.isNotEmpty() || f.error != null || f.end) }) return now.map { it.first to it.second!! }
            Thread.sleep(1000)
            rule.mainClock.advanceTimeBy(1000)
            rule.waitForIdle()
        }
        val now = current()
        for ((k, f) in now) assertTrue("首頁沒有建立 $k 的列表", f != null)
        return now.map { it.first to it.second!! }
    }

    private fun pump(seconds: Int) = repeat(seconds * 2) {
        Thread.sleep(500)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    /** 作品頁按「收藏」→「收藏」分頁的本機收藏看得到 → 長按取消 */
    @Test
    fun localFavorites() {
        assumeTrue(System.getenv("UI_TEST") == "1")
        rule.runOnUiThread {
            Nav.changeLang("")
            Favorites.items.toList().forEach { Favorites.remove(it) }
        }
        val nh = waitFeeds(listOf("nh")).first().second
        assertTrue("首頁 NH 沒有載入", nh.items.isNotEmpty())
        val item = nh.items.first()
        rule.runOnUiThread { Nav.openItem(item) }
        repeat(30) { if ((Nav.top as? Route.Detail)?.detail == null) pump(1) }
        shot("detail")
        rule.onNodeWithText("收藏").performClick()
        rule.waitForIdle()
        assertTrue("按了收藏但沒有收進去", Favorites.has(item.site, item.id))
        shot("detail_faved")
        rule.onNodeWithText("已收藏").assertExists()

        rule.runOnUiThread {
            Nav.pop()
            FavState.site = LOCAL_FAV
            Nav.tab = Tab.Favorites
        }
        pump(2)
        shot("favorites_local")
        rule.onNodeWithText("本機收藏").assertExists()
        rule.onNodeWithText(item.title, substring = true).assertExists()

        // 長按 → 確認取消
        rule.onNodeWithText(item.title, substring = true).performTouchInput { longClick() }
        rule.waitForIdle()
        shot("favorites_remove")
        rule.onAllNodesWithText("取消收藏").onLast().performClick()
        rule.waitForIdle()
        assertTrue("取消收藏沒有成功", !Favorites.has(item.site, item.id))
    }

    /** 沒登入時按「加到網站收藏」→ 跳出需要登入；EH / WN 的收藏表單真的連網，沒登入要被認出來 */
    @Test
    fun siteFavoriteNeedsLogin() {
        assumeTrue(System.getenv("UI_TEST") == "1")
        rule.runOnUiThread { Nav.changeLang("") }
        val feeds = waitFeeds(listOf("nh", "wn", "eh")).toMap()
        val nh = feeds.getValue("nh").items.first()
        rule.runOnUiThread { Nav.openItem(nh) }
        repeat(30) { if ((Nav.top as? Route.Detail)?.detail == null) pump(1) }
        val label = SiteConfig.label("nh")
        rule.onNodeWithText("☆ 加到 $label 收藏").performClick()
        repeat(20) { if (rule.onAllNodesWithText("需要登入").fetchSemanticsNodes().isEmpty()) pump(1) }
        shot("site_fav_login")
        rule.onNodeWithText("需要登入").assertExists()

        for (k in listOf("eh", "wn")) {
            val item = feeds.getValue(k).items.first()
            val r = runBlocking {
                val d = site(k).detail(item.id)
                runCatching { SiteFav.form(d) }
            }
            println("$k form while logged out: ${r.exceptionOrNull()?.javaClass?.simpleName} ${r.exceptionOrNull()?.message}")
            assertTrue("$k 沒登入時應該要求登入", r.exceptionOrNull() is SiteFav.NeedLogin)
        }
    }

    @Test
    fun homeLanguageFilter() {
        assumeTrue(System.getenv("UI_TEST") == "1")
        val sites = listOf("nh", "wn", "eh")
        rule.runOnUiThread { Nav.changeLang("") }
        for ((k, f) in waitFeeds(sites)) println("all $k: items=${f.items.size} error=${f.error} langs=${f.items.groupingBy { it.lang }.eachCount()}")
        shot("home_all")

        // 切成中文，首頁每一排都要重新載入、只剩中文
        rule.runOnUiThread { Nav.changeLang("zh") }
        rule.waitForIdle()
        val zh = waitFeeds(sites, 90)
        shot("home_zh")
        for ((k, f) in zh) {
            println("zh $k: filter=${Lang.filter} items=${f.items.size} error=${f.error} langs=${f.items.groupingBy { it.lang }.eachCount()}")
            assertTrue("$k 中文列表沒有載入", f.items.isNotEmpty())
            assertTrue("$k 有非中文的作品", f.items.all { it.lang == "zh" })
        }

        // 單一站：站別分頁＋排序
        rule.runOnUiThread { HomeState.site = "nh" }
        repeat(20) { Thread.sleep(500); rule.mainClock.advanceTimeBy(500) }
        shot("home_nh")
        rule.runOnUiThread {
            HomeState.site = "all"
            Nav.changeLang("")
            Nav.tab = Tab.History
        }
        shot("history")
        rule.runOnUiThread { Nav.tab = Tab.Shelf }
        shot("shelf")
        rule.runOnUiThread { Nav.tab = Tab.Favorites }
        repeat(10) { Thread.sleep(500); rule.mainClock.advanceTimeBy(500) }
        shot("favorites")
    }
}
