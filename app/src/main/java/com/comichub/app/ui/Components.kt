package com.comichub.app.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.comichub.app.App
import com.comichub.app.net.Item
import com.comichub.app.net.Lang
import com.comichub.app.net.SiteConfig
import com.comichub.app.net.WebFetcher
import com.comichub.app.net.site

val Accent = Color(0xFF6C8CFF)
val Surface = Color(0xFF23262E)
val Surface2 = Color(0xFF2C3039)
val Background = Color(0xFF16181D)
val Muted = Color(0xFF9AA0AC)
val ErrorColor = Color(0xFFFF8A80)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = Accent,
            background = Background,
            surface = Background,
            surfaceVariant = Surface,
            surfaceContainer = Surface,
            surfaceContainerHigh = Surface,
            surfaceContainerLow = Surface,
            surfaceContainerHighest = Surface2,
        ),
        content = content,
    )
}

fun siteColor(key: String) = runCatching { Color(site(key).color) }.getOrDefault(Muted)

fun toast(msg: String) = Toast.makeText(App.instance, msg, Toast.LENGTH_SHORT).show()

/** 站別標籤（顯示站名，顏色跟桌面版一樣） */
@Composable
fun SiteBadge(key: String, modifier: Modifier = Modifier, small: Boolean = true) {
    Text(
        SiteConfig.label(key),
        color = Color.White,
        fontSize = if (small) 10.sp else 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(siteColor(key))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun LangBadge(lang: String, modifier: Modifier = Modifier) {
    val text = Lang.SHORT[lang] ?: return
    Text(
        text,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 封面卡片：封面（左上站名、右上語言）＋下面書名、副標 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Cover(
    model: Any?,
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    site: String? = null,
    lang: String = "",
    onLongClick: (() -> Unit)? = null,
    overlay: @Composable (BoxScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(6.dp))
                .background(Surface)
        ) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (site != null && site in SiteConfig.KEYS) SiteBadge(site, Modifier.align(Alignment.TopStart).padding(4.dp))
            LangBadge(lang, Modifier.align(Alignment.TopEnd).padding(4.dp))
            overlay?.invoke(this)
        }
        Text(
            title,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ItemCard(item: Item, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    Cover(item.cover, item.title, item.sub, modifier, item.site, item.lang, onLongClick) { Nav.openItem(item) }
}

fun LazyGridScope.fullWidth(key: Any? = null, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

val GridColumns = GridCells.Adaptive(108.dp)
val GridPadding = PaddingValues(10.dp)
val GridSpacing = Arrangement.spacedBy(10.dp)

/** 封面網格，捲到底自動載下一頁 */
@Composable
fun FeedGrid(feed: Feed, modifier: Modifier = Modifier, empty: String = "沒有結果", header: (LazyGridScope.() -> Unit)? = null) {
    LaunchedEffect(feed) { if (feed.items.isEmpty() && !feed.end) feed.loadMore() }
    val nearEnd by remember(feed) {
        derivedStateOf {
            val last = feed.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= feed.grid.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(feed) {
        snapshotFlow { nearEnd }.collect { if (it && feed.items.isNotEmpty() && feed.error == null) feed.loadMore() }
    }
    LazyVerticalGrid(
        columns = GridColumns,
        state = feed.grid,
        modifier = modifier.fillMaxSize(),
        contentPadding = GridPadding,
        horizontalArrangement = GridSpacing,
        verticalArrangement = GridSpacing,
    ) {
        header?.invoke(this)
        items(feed.items, key = { it.key }) { ItemCard(it) }
        fullWidth("footer") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                when {
                    feed.error != null -> ErrorBox(feed.error!!) { feed.loadMore() }
                    feed.loading -> CircularProgressIndicator()
                    feed.end && feed.items.isEmpty() -> Text(empty, color = Muted)
                    feed.end -> Text("沒有更多了", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

/** 錯誤訊息＋重試；被 Cloudflare 擋下時多一個「開啟驗證頁」 */
@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
        Text(message, color = ErrorColor, textAlign = TextAlign.Center, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WebFetcher.blockedSite?.let { s ->
                Button(onClick = { Nav.push(Route.Web(s, SiteConfig.base(s), verify = true)) }) { Text("開啟驗證頁") }
            }
            if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("重試") }
        }
    }
}

/**
 * 小圓角選項。不用 Material 的 Button：它會強制 48dp 的觸控高度，
 * 好幾排疊在一起時整片擠成一團（v1.0.2 手機上回報過）。
 */
@Composable
fun Chip(text: String, selected: Boolean, color: Color = Accent, onClick: () -> Unit) {
    Box(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) color else Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, maxLines = 1, color = if (selected) Color.White else Color(0xFFD5D8DE))
    }
}

/** 一排可以左右滑的選項（排序、書櫃分組） */
@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, colorOf: (String) -> Color = { Accent }, onSelect: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        items(options.size) { i ->
            val (key, label) = options[i]
            Chip(label, key == selected, colorOf(key)) { onSelect(key) }
        }
    }
}

/** 站別分頁：文字＋底下一條該站顏色的線，跟下面的排序選項長得不一樣，才分得出來 */
@Composable
fun SiteTabs(options: List<Pair<String, String>>, selected: String, colorOf: (String) -> Color, onSelect: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.fillMaxWidth()) {
        items(options.size) { i ->
            val (key, label) = options[i]
            val on = key == selected
            Column(
                Modifier.clickable { onSelect(key) }.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, fontSize = 14.sp, maxLines = 1, color = if (on) Color.White else Muted, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                Box(
                    Modifier.padding(top = 4.dp).height(3.dp).width(22.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (on) colorOf(key) else Color.Transparent)
                )
            }
        }
    }
}

/** 語言篩選：一顆小按鈕＋下拉選單（全部語言 / 中文 / English / 日本語），不再佔一整排 */
@Composable
fun LangButton(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val lang = Nav.lang
    Box(modifier) {
        Row(
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(if (lang.isEmpty()) Surface else Accent)
                .clickable { open = true }
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Lang.NAMES[lang] ?: "全部語言", fontSize = 13.sp, color = Color.White, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((k, label) in listOf("" to "全部語言") + Lang.NAMES.toList()) {
                DropdownMenuItem(
                    text = { Text(label, color = if (k == lang) Accent else Color.White) },
                    onClick = {
                        open = false
                        Nav.changeLang(k)
                    },
                )
            }
        }
    }
}

@Composable
fun CenterLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun CenterText(text: String, color: Color = Muted) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, color = color, textAlign = TextAlign.Center) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = modifier)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}
