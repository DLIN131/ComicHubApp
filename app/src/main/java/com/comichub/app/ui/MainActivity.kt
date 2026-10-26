package com.comichub.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.comichub.app.Prefs
import com.comichub.app.data.Downloads
import com.comichub.app.data.History
import com.comichub.app.data.Library
import com.comichub.app.data.Store
import com.comichub.app.util.Updater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** 更新後第一次開：顯示這次更新了什麼（從哪一版之後） */
    private var whatsNewSince by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Store.load()
        History.load()
        Downloads.load()
        AppScope.launch { runCatching { Library.refresh() } }
        handleIntent(intent)
        val last = Prefs.lastVersion
        if (last in 2 until Updater.currentVersionCode) whatsNewSince = last
        Prefs.lastVersion = Updater.currentVersionCode
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            AppTheme {
                val holder = rememberSaveableStateHolder()
                BackHandler(enabled = Nav.stack.size > 1 || Nav.tab != Tab.Home) {
                    if (!Nav.pop()) Nav.tab = Tab.Home
                }
                Box(Modifier.fillMaxSize().background(Background)) {
                    val route = Nav.top
                    holder.SaveableStateProvider(route.key) {
                        when (route) {
                            Route.Main -> MainScreen()
                            is Route.Search -> SearchScreen(route)
                            is Route.Detail -> DetailScreen(route)
                            is Route.Reader -> ReaderScreen(route)
                            is Route.Login -> LoginScreen(route)
                            is Route.Web -> WebScreen(route)
                            Route.Downloads -> DownloadsScreen()
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    // 本機打包的開發版不自動跳更新（要更新可到「帳號」頁手動檢查）
                    if (Updater.isDev) return@LaunchedEffect
                    runCatching { Updater.check() }.getOrNull()
                        ?.takeIf { it.versionCode != Prefs.skippedVersion }
                        ?.let { Updater.pending = it }
                }
                Updater.pending?.let { release ->
                    UpdateDialog(
                        release = release,
                        onDismiss = { Updater.pending = null },
                        onSkip = {
                            Prefs.skippedVersion = release.versionCode
                            Updater.pending = null
                        },
                    )
                }
                if (whatsNewSince > 0 && Updater.pending == null) {
                    ChangelogDialog(since = whatsNewSince, title = "這次更新了什麼") { whatsNewSince = 0 }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 從檔案管理員用 ComicHub 開 CBZ：複製進書櫃後直接開始看 */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        intent.data = null
        AppScope.launch {
            try {
                val b = Library.import(uri)
                toast("已加入書櫃")
                Nav.tab = Tab.Shelf
                Nav.readBook(b)
            } catch (e: Exception) {
                toast("無法開啟：${e.message}")
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Store.flush()
    }
}

@Composable
private fun MainScreen() {
    Scaffold(
        containerColor = Background,
        // 各分頁自己加 statusBarsPadding；這裡不再算一次，不然手機上方會空兩倍高
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = Surface) {
                val icons = mapOf(
                    Tab.Home to Icons.Filled.Home,
                    Tab.Favorites to Icons.Filled.Favorite,
                    Tab.History to Icons.Filled.List,
                    Tab.Shelf to Icons.Filled.Star,
                    Tab.Account to Icons.Filled.AccountCircle,
                )
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = Nav.tab == t,
                        onClick = { Nav.tab = t },
                        icon = { Icon(icons.getValue(t), t.label) },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.White,
                            indicatorColor = Accent,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().background(Background).padding(pad)) {
            when (Nav.tab) {
                Tab.Home -> HomeScreen()
                Tab.Favorites -> FavoritesScreen()
                Tab.History -> HistoryScreen()
                Tab.Shelf -> ShelfScreen()
                Tab.Account -> AccountScreen()
            }
        }
    }
}
