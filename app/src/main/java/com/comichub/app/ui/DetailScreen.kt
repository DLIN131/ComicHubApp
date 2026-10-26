package com.comichub.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.comichub.app.Prefs
import com.comichub.app.data.Downloads
import com.comichub.app.data.History
import com.comichub.app.net.Chapter
import com.comichub.app.net.Detail
import com.comichub.app.net.Net
import com.comichub.app.net.Nh
import com.comichub.app.net.Query
import com.comichub.app.net.site
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(route: Route.Detail) {
    val item = route.item
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(route, attempt) {
        if (route.detail != null) return@LaunchedEffect
        route.error = null
        try {
            route.detail = site(item.site).detail(item.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            route.error = e.message ?: e.toString()
        }
    }
    // NH：有登入就讀網站收藏狀態
    LaunchedEffect(route.detail) {
        if (item.site == "nh" && route.detail != null && Net.nhToken() != null) {
            route.favorite = runCatching { Nh.isFavorite(item.id) }.getOrNull()
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
            SiteBadge(item.site, small = false)
            Spacer(Modifier.width(8.dp))
            Text(item.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (route.favorite != null) {
                val on = route.favorite == true
                val scope = rememberCoroutineScope()
                IconButton(onClick = {
                    scope.launch {
                        try {
                            Nh.setFavorite(item.id, !on)
                            route.favorite = !on
                            toast(if (!on) "已加入網站收藏" else "已從網站收藏移除")
                        } catch (e: Exception) {
                            toast("收藏失敗：${e.message}")
                        }
                    }
                }) { Icon(if (on) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "收藏", tint = if (on) Color(0xFFFF5C7A) else Color.White) }
            }
        }
        val d = route.detail
        when {
            d != null -> DetailBody(route, d)
            route.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorBox(route.error!!) {
                    route.error = null
                    attempt++
                }
            }
            else -> CenterLoading()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(route: Route.Detail, d: Detail) {
    val context = LocalContext.current
    var showChapters by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val progress = History.progress("${d.site}_${d.id}")
    LazyVerticalGrid(
        columns = GridColumns,
        state = route.grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = GridPadding,
        horizontalArrangement = GridSpacing,
        verticalArrangement = GridSpacing,
    ) {
        fullWidth("head") {
            Column {
                Row {
                    AsyncImage(
                        model = d.cover.ifBlank { route.item.cover },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(130.dp).aspectRatio(0.7f).clip(RoundedCornerShape(6.dp)).background(Surface),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp)
                        if (d.subtitle.isNotBlank()) Text(d.subtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SiteBadge(d.site)
                            LangBadge(d.lang)
                        }
                        Spacer(Modifier.height(6.dp))
                        for ((k, v) in d.info.filter { it.first != "簡介" }) {
                            Text("$k：$v", color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 閱讀 / 繼續閱讀
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (progress != null) {
                        Button(onClick = {
                            val ci = d.chapters.indexOfFirst { it.id == progress.chapterId }
                            Nav.read(d, ci, progress.page)
                        }, modifier = Modifier.weight(1f)) { Text("繼續：${progress.text}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        OutlinedButton(onClick = { Nav.read(d, if (d.chapters.isNotEmpty()) 0 else -1, 0) }) { Text("從頭看") }
                    } else {
                        Button(onClick = { Nav.read(d, if (d.chapters.isNotEmpty()) 0 else -1, 0) }, modifier = Modifier.weight(1f)) { Text("開始閱讀") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        if (d.chapters.size > 1) showDownload = true else download(d, listOf(null))
                    }, modifier = Modifier.weight(1f)) { Text("下載 CBZ") }
                    OutlinedButton(onClick = { showSearch = true }, modifier = Modifier.weight(1f)) { Text("用此漫畫搜尋") }
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.url))) }
                    }) { Text("網頁") }
                }
                if (d.chapters.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("章節（${d.chapters.size} 話）", Modifier.weight(1f))
                        TextButton(onClick = { showChapters = true }) { Text("全部章節") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.chapters.take(12).forEachIndexed { i, c ->
                            ChapterChip(c, c.id == progress?.chapterId) { Nav.read(d, i, 0) }
                        }
                    }
                }
                d.info.firstOrNull { it.first == "簡介" }?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it.second, color = Muted, fontSize = 13.sp)
                }
                if (d.tags.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    // 標籤點了會在該站搜尋
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in d.tags) {
                            Text(
                                "${t.label}：${t.name}",
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Surface2)
                                    .clickable { Nav.search(t.query, listOf(d.site), keyword = true) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                if (d.related.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    SectionTitle("相關作品")
                }
            }
        }
        items(d.related, key = { "rel_" + it.key }) { ItemCard(it) }
    }

    if (showChapters) AlertDialog(
        onDismissRequest = { showChapters = false },
        title = { Text("章節") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                itemsIndexed(d.chapters) { i, c ->
                    Text(
                        c.title,
                        color = if (c.id == progress?.chapterId) Accent else Color.White,
                        modifier = Modifier.fillMaxWidth().clickable {
                            showChapters = false
                            Nav.read(d, i, 0)
                        }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { showChapters = false }) { Text("關閉") } },
    )

    if (showDownload) AlertDialog(
        onDismissRequest = { showDownload = false },
        title = { Text("下載哪幾話？") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(d.chapters) { _, c ->
                    Text(
                        c.title + if (Downloads.exists(d, c)) "（已下載）" else "",
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth().clickable {
                            showDownload = false
                            download(d, listOf(c))
                        }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                showDownload = false
                download(d, d.chapters)
            }) { Text("全部 ${d.chapters.size} 話") }
        },
        dismissButton = { TextButton(onClick = { showDownload = false }) { Text("取消") } },
    )

    if (showSearch) {
        val candidates = (listOf("書名" to Query.cleanTitle(d.title), "原文書名" to Query.cleanTitle(d.subtitle)) +
            d.tags.filter { it.label == "作者" || it.label == "社團" }.map { it.label to it.name } +
            Query.parseAuthors(d.title).map { "作者" to it })
            .filter { it.second.isNotBlank() }.distinctBy { it.second }
        SearchPanel(candidates) { showSearch = false }
    }
}

@Composable
private fun ChapterChip(c: Chapter, current: Boolean, onClick: () -> Unit) {
    Text(
        c.title,
        color = Color.White,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (current) Accent else Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private fun download(d: Detail, chapters: List<Chapter?>) {
    val list = chapters.filter { !Downloads.isQueued(d, it?.id) }
    if (list.isEmpty()) {
        toast("已經在下載佇列裡了")
        return
    }
    list.forEach { Downloads.enqueue(d, it) }
    toast(if (list.size > 1) "已加入下載：${list.size} 話" else "已加入下載")
}

/** 「用此漫畫搜尋」：可改搜尋字，或點候選（書名、原文書名、作者），一次搜所有勾選的站 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchPanel(candidates: List<Pair<String, String>>, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(candidates.firstOrNull()?.second.orEmpty()) }
    fun go(q: String) {
        if (q.isBlank()) return
        onDismiss()
        Nav.search(q.trim(), Prefs.searchSites, keyword = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用此漫畫搜尋") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, q) in candidates) {
                        Text(
                            "$label：$q",
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Surface2).clickable { go(q) }.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { go(text) }) { Text("搜尋所有站") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
