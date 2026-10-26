package com.comichub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comichub.app.Prefs
import com.comichub.app.net.Lang
import com.comichub.app.net.SITES
import com.comichub.app.net.SiteConfig
import com.comichub.app.net.site

object HomeState {
    var site by mutableStateOf(Prefs.homeSite)
}

@Composable
fun HomeScreen() {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 搜尋列（點了進搜尋頁）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 8.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Surface)
                .clickable { Nav.search("") }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = Muted)
            Spacer(Modifier.width(8.dp))
            Text("搜尋所有站，或輸入編號 / 網址", color = Muted, fontSize = 14.sp)
        }
        LangRow()
        val tabs = listOf("all" to "全部") + SiteConfig.KEYS.map { it to SiteConfig.label(it) }
        ChipRow(tabs, HomeState.site, Modifier.padding(top = 0.dp), colorOf = { if (it == "all") Accent else siteColor(it) }) {
            HomeState.site = it
            Prefs.homeSite = it
        }
        val lang = Nav.lang
        if (HomeState.site == "all") AllSites(lang) else SiteHome(HomeState.site, lang)
    }
}

/** 「全部」：四站各一排最新更新 */
@Composable
private fun AllSites(lang: String) {
    val feeds = SiteConfig.KEYS.map { k -> k to Feeds.get("home_$k") { site(k).latest(it) } }
    val allFailed by remember(lang) { derivedStateOf { feeds.all { it.second.error != null } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (allFailed) item {
            Column(
                Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(8.dp)).background(Surface).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("目前連不上網站。已下載的漫畫可以在「書櫃」離線閱讀", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { Nav.tab = Tab.Shelf }) { Text("前往書櫃") }
            }
        }
        for ((k, feed) in feeds) {
            item(key = k) {
                LaunchedEffect(feed) { if (feed.items.isEmpty() && !feed.end && feed.error == null) feed.loadMore() }
                Column(Modifier.padding(top = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SiteBadge(k, small = false)
                        Spacer(Modifier.width(8.dp))
                        SectionTitle("最新更新", Modifier.weight(1f))
                        TextButton(onClick = {
                            HomeState.site = k
                            Prefs.homeSite = k
                        }) { Text("看更多 →", fontSize = 13.sp) }
                    }
                    when {
                        feed.items.isNotEmpty() -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(feed.items.take(24), key = { it.key }) { ItemCard(it, Modifier.width(112.dp)) }
                        }
                        feed.error != null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ErrorBox(feed.error!!) { feed.refresh() } }
                        feed.loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else -> Text("沒有${Lang.NAMES[lang] ?: ""}的作品", color = Muted, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

/** 單一站：排序＋無限捲動 */
@Composable
private fun SiteHome(key: String, lang: String) {
    val s = site(key)
    var sort by remember(key) { mutableStateOf(Prefs.sort(key, s.sorts[0].first).takeIf { v -> s.sorts.any { it.first == v } } ?: s.sorts[0].first) }
    Column(Modifier.fillMaxSize()) {
        if (s.sorts.size > 1) ChipRow(s.sorts, sort, colorOf = { siteColor(key) }) {
            sort = it
            Prefs.setSort(key, it)
        }
        val feed = remember(key, sort, lang) { Feeds.get("site_${key}_$sort") { p -> s.latest(p, sort) } }
        FeedGrid(feed)
    }
}

@Composable
fun SearchScreen(route: Route.Search) {
    var text by rememberSaveable { mutableStateOf(route.state?.q.orEmpty()) }
    var sites by remember { mutableStateOf(route.sites) }
    val focus = remember { FocusRequester() }
    fun go(keyword: Boolean = false) {
        val q = text.trim()
        if (q.isEmpty()) return
        if (sites.isEmpty()) {
            toast("至少要選一個站")
            return
        }
        Prefs.searchSites = sites
        route.state = SearchState(q, sites, keyword)
    }
    LaunchedEffect(Unit) { if (route.state == null) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("書名、作者、標籤、編號或網址") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { go() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Surface, unfocusedContainerColor = Surface,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = { go() }) { Icon(Icons.Filled.Search, "搜尋", tint = Color.White) }
        }
        // 要搜哪幾站（可複選）
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            items(SiteConfig.KEYS) { k ->
                Chip(SiteConfig.label(k), k in sites, siteColor(k)) {
                    sites = if (k in sites) sites - k else SiteConfig.KEYS.filter { it in sites || it == k }
                }
            }
        }
        LangRow()
        val st = route.state
        if (st == null) CenterText("輸入關鍵字搜尋勾選的站；輸入編號（例如 JM123456、#123456）或貼上作品網址會直接找那一本")
        else SearchResults(st) {
            text = st.q
            go(keyword = true)
        }
    }
}

@Composable
private fun SearchResults(st: SearchState, onKeyword: () -> Unit) {
    val merged = st.merged()
    val nearEnd by remember(st) {
        derivedStateOf {
            val last = st.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= st.grid.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(st) {
        snapshotFlow { nearEnd }.collect { if (it && st.merged().isNotEmpty()) st.more(st.shown.filter { s -> s.error == null }) }
    }
    LazyVerticalGrid(
        columns = GridColumns,
        state = st.grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = GridPadding,
        horizontalArrangement = GridSpacing,
        verticalArrangement = GridSpacing,
    ) {
        fullWidth("head") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(if (st.byId != null) "編號 ${st.byId.id} 的作品" else "「${st.q}」的搜尋結果", Modifier.weight(1f))
                    if (st.byId?.plain == true) TextButton(onClick = onKeyword) { Text("改用關鍵字搜尋", fontSize = 13.sp) }
                }
                // 每站各自的結果數：… = 載入中、! = 失敗
                val opts = listOf("all" to "全部 ${st.states.sumOf { it.items.size }}") + st.states.map { s ->
                    s.site to "${SITES.getValue(s.site).label} ${if (s.busy) "…" else if (s.error != null) "!" else s.items.size.toString()}"
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    items(opts) { (k, label) -> Chip(label, st.filter == k, if (k == "all") Accent else siteColor(k)) { st.filter = k } }
                }
            }
        }
        items(merged, key = { it.key }) { ItemCard(it) }
        fullWidth("foot") {
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for (s in st.shown) s.error?.let { e ->
                    ErrorBox("${SITES.getValue(s.site).label}：$e") {
                        s.hasMore = true
                        st.more(listOf(s))
                    }
                }
                when {
                    st.shown.any { it.busy } -> CircularProgressIndicator()
                    st.shown.any { it.hasMore && it.error == null } -> TextButton(onClick = { st.more() }) { Text("載入更多") }
                    merged.isEmpty() && st.shown.none { it.error != null } -> Text("沒有結果", color = Muted)
                }
            }
        }
    }
}
