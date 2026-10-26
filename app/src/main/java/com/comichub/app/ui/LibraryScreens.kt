package com.comichub.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
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
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.comichub.app.Prefs
import com.comichub.app.data.Book
import com.comichub.app.data.Collection
import com.comichub.app.data.Downloads
import com.comichub.app.data.Favorites
import com.comichub.app.net.Item
import com.comichub.app.data.History
import com.comichub.app.data.Library
import com.comichub.app.net.Auth
import com.comichub.app.net.AuthState
import com.comichub.app.net.Lang
import com.comichub.app.net.Query
import com.comichub.app.net.SiteConfig
import com.comichub.app.net.site
import kotlinx.coroutines.launch

// ================= 收藏（網站帳號的收藏） =================

object FavState {
    var site by mutableStateOf(Prefs.favSite)
    val auth = HashMap<String, AuthState>()
}

/** 「收藏」分頁的第一個：本機收藏 */
const val LOCAL_FAV = "local"

@Composable
fun FavoritesScreen() {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("收藏", Modifier.weight(1f))
            LangButton()
        }
        // 本機收藏＋各站的網站帳號收藏
        val tabs = listOf(LOCAL_FAV to "本機收藏") + SiteConfig.KEYS.map { it to SiteConfig.label(it) }
        SiteTabs(tabs, FavState.site, colorOf = { if (it == LOCAL_FAV) FavColor else siteColor(it) }) {
            FavState.site = it
            Prefs.favSite = it
        }
        if (FavState.site == LOCAL_FAV) LocalFavorites() else SiteFavorites(FavState.site)
    }
}

@Composable
private fun LocalFavorites() {
    val lang = Nav.lang
    val list = Favorites.items.filter { lang.isEmpty() || it.lang == lang }
    var removing by remember { mutableStateOf<Item?>(null) }
    if (list.isEmpty()) {
        CenterText(
            if (Favorites.items.isEmpty()) "還沒有收藏。\n在作品頁按 ♡ 收藏，四個站都可以收、不用登入。\n（登入網站帳號的收藏在右邊各站的分頁）"
            else "沒有${Lang.NAMES[lang] ?: ""}的收藏",
        )
    } else LazyVerticalGrid(
        columns = GridColumns,
        modifier = Modifier.fillMaxSize(),
        contentPadding = GridPadding,
        horizontalArrangement = GridSpacing,
        verticalArrangement = GridSpacing,
    ) {
        fullWidth("hint") { Text("共 ${list.size} 本・長按可以取消收藏", color = Muted, fontSize = 12.sp) }
        items(list, key = { it.key }) { item ->
            // 看過的顯示看到哪
            val p = History.progress(item.key)
            ItemCard(item.copy(sub = p?.let { "看到：${it.text}" } ?: item.sub), onLongClick = { removing = item })
        }
    }
    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("取消收藏？") },
            text = { Text(item.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    Favorites.remove(item)
                    removing = null
                    toast("已取消收藏")
                }) { Text("取消收藏") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("保留") } },
        )
    }
}

@Composable
private fun SiteFavorites(key: String) {
    Column(Modifier.fillMaxSize()) {
        var auth by remember(key) { mutableStateOf(FavState.auth[key]) }
        var attempt by remember(key) { mutableStateOf(0) }
        LaunchedEffect(key, attempt) {
            if (auth == null) auth = Auth.status(key).also { FavState.auth[key] = it }
        }
        val a = auth
        val lang = Nav.lang
        when {
            a == null -> CenterLoading()
            !a.loggedIn -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    when {
                        a.error != null -> "無法確認登入狀態：${a.error}"
                        a.expired -> Auth.expiredText(key)
                        else -> "還沒登入${SiteConfig.label(key)}，登入後就能看到網站上的收藏"
                    },
                    color = Muted,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        FavState.auth.remove(key)
                        auth = null
                        Nav.push(Route.Login(key))
                    }) { Text("登入 ${SiteConfig.label(key)}") }
                    OutlinedButton(onClick = {
                        FavState.auth.remove(key)
                        auth = null
                        attempt++
                    }) { Text("重新檢查") }
                }
            }
            else -> {
                val feed = remember(key, lang) { Feeds.get("fav_$key") { site(key).favorites(it) } }
                FeedGrid(feed, empty = "網站上的收藏是空的")
            }
        }
    }
}

// ================= 紀錄 =================

@Composable
fun HistoryScreen() {
    var confirm by remember { mutableStateOf(false) }
    val lang = Nav.lang
    val list = History.items.filter { lang.isEmpty() || it.lang == lang }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("最近看過", Modifier.weight(1f))
            if (History.items.isNotEmpty()) TextButton(onClick = { confirm = true }) { Text("清除") }
            LangButton()
        }
        if (list.isEmpty()) CenterText("還沒有看過的作品")
        else LazyVerticalGrid(
            columns = GridColumns,
            state = rememberLazyGridState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = GridPadding,
            horizontalArrangement = GridSpacing,
            verticalArrangement = GridSpacing,
        ) {
            items(list, key = { it.key }) { item ->
                ItemCard(item, onLongClick = {
                    History.remove(item)
                    toast("已從紀錄移除")
                })
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("清除所有紀錄？") },
        text = { Text("只清除「最近看過」清單，每本看到哪的進度會保留。") },
        confirmButton = {
            TextButton(onClick = {
                History.clear()
                confirm = false
            }) { Text("清除") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}

// ================= 書櫃 =================

private const val ALL = "__all"
private const val COLLECTIONS = "__collections"
private const val NO_GROUP = "__none"

object ShelfState {
    var filter by mutableStateOf(ALL)
}

@Composable
fun ShelfScreen() {
    val scope = rememberCoroutineScope()
    var book by remember { mutableStateOf<Book?>(null) }
    var col by remember { mutableStateOf<Collection?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var ok = 0
            for (u in uris) runCatching { Library.import(u) }.onSuccess { ok++ }.onFailure { toast("無法加入：${it.message}") }
            if (ok > 0) toast("已加入書櫃：$ok 本")
        }
    }
    LaunchedEffect(Unit) { Library.refresh() }
    @Suppress("UNUSED_VARIABLE") val v = Library.version
    val groups = Library.groups()
    val collections = Library.collections()
    val lang = Nav.lang
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("書櫃", Modifier.weight(1f))
            LangButton()
            val active = Downloads.activeCount
            TextButton(onClick = { Nav.push(Route.Downloads) }) { Text(if (active > 0) "下載中（$active）" else "下載") }
            IconButton(onClick = { picker.launch(arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream")) }) {
                Icon(Icons.Filled.Add, "加入漫畫檔", tint = Color.White)
            }
            IconButton(onClick = { scope.launch { Library.refresh() } }) { Icon(Icons.Filled.Refresh, "重新整理", tint = Color.White) }
        }
        val opts = listOf(ALL to "全部", COLLECTIONS to "合集") + groups.map { it to it } + listOf(NO_GROUP to "未分組")
        ChipRow(opts, ShelfState.filter) { ShelfState.filter = it }
        val f = ShelfState.filter
        val books = Library.books.filter { b ->
            (lang.isEmpty() || b.lang == lang) && when (f) {
                ALL -> true
                COLLECTIONS -> false
                NO_GROUP -> Library.groupOf(b.name) == null
                else -> Library.groupOf(b.name) == f
            }
        }
        val cols = if (f == ALL || f == COLLECTIONS) collections else emptyList()
        if (books.isEmpty() && cols.isEmpty()) {
            CenterText(
                if (Library.scanning) "讀取中…"
                else if (Library.books.isEmpty()) "書櫃是空的。\n在作品頁按「下載 CBZ」，或按右上角 ＋ 加入手機裡的 CBZ / ZIP 漫畫檔，就能離線閱讀。"
                else "這裡沒有漫畫",
            )
        } else LazyVerticalGrid(
            columns = GridColumns,
            modifier = Modifier.fillMaxSize(),
            contentPadding = GridPadding,
            horizontalArrangement = GridSpacing,
            verticalArrangement = GridSpacing,
        ) {
            items(cols, key = { "col_" + it.id }) { c ->
                val cover = Library.byName(c.cover)?.cover ?: Library.booksOf(c).firstOrNull()?.cover
                val p = History.progress(c.key)
                Cover(cover, c.name, p?.let { "看到：${it.text}" } ?: "${c.files.size} 本", overlay = {
                    Text(
                        "合集", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Accent).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }) { col = c }
            }
            items(books, key = { it.name }) { b ->
                val p = History.progress(b.key)
                val sub = b.error ?: p?.let { "看到：${it.text}" } ?: (if (b.chapter.isNotBlank()) "${b.chapter}・" else "") + "${b.pages.size} 頁"
                Cover(b.cover, b.title, sub, site = b.site.ifBlank { null }, lang = b.lang) { book = b }
            }
        }
    }
    book?.let { b -> BookSheet(b) { book = null } }
    col?.let { c -> CollectionSheet(c) { col = null } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BookSheet(b: Book, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groupDialog by remember { mutableStateOf(false) }
    var colDialog by remember { mutableStateOf(false) }
    var searchDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    val p = History.progress(b.key)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row {
                AsyncImage(b.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.width(90.dp).aspectRatio(0.7f).clip(RoundedCornerShape(6.dp)).background(Surface2))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (b.chapter.isNotBlank()) Text(b.chapter, color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (b.site.isNotBlank()) SiteBadge(b.site)
                        LangBadge(b.lang)
                    }
                    Text("${b.pages.size} 頁・${formatSize(b.size)}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Text("分組：${Library.groupOf(b.name) ?: "未分組"}", color = Muted, fontSize = 12.sp)
                    b.error?.let { Text(it, color = ErrorColor, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (b.pages.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (p != null && p.page > 0) {
                    Button(onClick = { onDismiss(); Nav.readBook(b, p.page) }, modifier = Modifier.weight(1f)) { Text("繼續：第 ${p.page + 1} / ${p.total} 頁") }
                    OutlinedButton(onClick = { onDismiss(); Nav.readBook(b, 0) }) { Text("從頭看") }
                } else {
                    Button(onClick = { onDismiss(); Nav.readBook(b, 0) }, modifier = Modifier.fillMaxWidth()) { Text("閱讀") }
                }
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { groupDialog = true }) { Text("分組") }
                OutlinedButton(onClick = { colDialog = true }) { Text("加入合集") }
                OutlinedButton(onClick = { searchDialog = true }) { Text("用此漫畫搜尋") }
                OutlinedButton(onClick = {
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", b.file)
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                "分享漫畫檔",
                            )
                        )
                    }.onFailure { toast("無法分享：${it.message}") }
                }) { Text("分享") }
                OutlinedButton(onClick = { deleteDialog = true }) { Text("刪除", color = ErrorColor) }
            }
            if (b.authors.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("作者（點了搜尋相關作品）", color = Muted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    for (a in b.authors) {
                        Text(a, color = Color.White, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Surface2).clickable {
                            onDismiss()
                            Nav.search(a, Prefs.searchSites, keyword = true)
                        }.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }
        }
    }
    if (groupDialog) GroupDialog(b) { groupDialog = false }
    if (colDialog) AddToCollectionDialog(listOf(b)) { colDialog = false }
    if (searchDialog) {
        val candidates = (listOf("書名" to Query.cleanTitle(b.title)) + b.authors.map { "作者" to it }).filter { it.second.isNotBlank() }.distinctBy { it.second }
        SearchPanel(candidates) {
            searchDialog = false
            onDismiss()
        }
    }
    if (deleteDialog) AlertDialog(
        onDismissRequest = { deleteDialog = false },
        title = { Text("刪除這本？") },
        text = { Text("「${b.title}」的漫畫檔會從手機刪除，無法復原。") },
        confirmButton = {
            TextButton(onClick = {
                deleteDialog = false
                scope.launch {
                    Library.delete(b)
                    toast("已刪除")
                    onDismiss()
                }
            }) { Text("刪除", color = ErrorColor) }
        },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } },
    )
}

@Composable
private fun GroupDialog(b: Book, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val current = Library.groupOf(b.name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分組") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(Library.groups()) { g ->
                        Row(Modifier.fillMaxWidth().clickable {
                            Library.setGroup(b.name, g)
                            onDismiss()
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(g, color = if (g == current) Accent else Color.White, modifier = Modifier.weight(1f))
                            IconButton(onClick = { Library.deleteGroup(g) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Delete, "刪除分組", tint = Muted) }
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("新分組名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) Library.setGroup(b.name, name.trim())
                onDismiss()
            }) { Text(if (name.isNotBlank()) "建立並加入" else "關閉") }
        },
        dismissButton = {
            if (current != null) TextButton(onClick = {
                Library.setGroup(b.name, null)
                onDismiss()
            }) { Text("移出分組") }
        },
    )
}

@Composable
private fun AddToCollectionDialog(books: List<Book>, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(books.firstOrNull()?.let { Query.cleanTitle(it.title) }.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入合集") },
        text = {
            Column {
                val list = Library.collections()
                if (list.isNotEmpty()) Text("加到現有的合集：", color = Muted, fontSize = 13.sp)
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(list) { c ->
                        Text(c.name + "（${c.files.size} 本）", color = Color.White, modifier = Modifier.fillMaxWidth().clickable {
                            Library.updateCollection(c.copy(files = (c.files + books.map { it.name }).distinct()))
                            toast("已加入「${c.name}」")
                            onDismiss()
                        }.padding(vertical = 10.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("或建立新合集") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    Library.createCollection(name.trim(), books.map { it.name })
                    toast("已建立合集「${name.trim()}」")
                }
                onDismiss()
            }) { Text("建立新合集") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionSheet(start: Collection, onDismiss: () -> Unit) {
    var c by remember { mutableStateOf(start) }
    var rename by remember { mutableStateOf(false) }
    var dissolve by remember { mutableStateOf(false) }
    var addBooks by remember { mutableStateOf(false) }
    fun save(n: Collection) {
        c = n
        Library.updateCollection(n)
    }
    val p = History.progress(c.key)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(c.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("${c.files.size} 本・連續閱讀，上方選單可以跳到每本的第一頁", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (p != null && p.page > 0) {
                    Button(onClick = { onDismiss(); Nav.readCollection(c, p.page) }, modifier = Modifier.weight(1f)) { Text("繼續：${p.text}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    OutlinedButton(onClick = { onDismiss(); Nav.readCollection(c, 0) }) { Text("從頭看") }
                } else Button(onClick = { onDismiss(); Nav.readCollection(c, 0) }, modifier = Modifier.fillMaxWidth()) { Text("閱讀") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { rename = true }) { Text("改名") }
                OutlinedButton(onClick = { addBooks = true }) { Text("加入漫畫") }
                OutlinedButton(onClick = { dissolve = true }) { Text("解散", color = ErrorColor) }
            }
            Spacer(Modifier.height(10.dp))
            Text("順序（★ = 封面）", color = Muted, fontSize = 12.sp)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(c.files, key = { _, n -> n }) { i, n ->
                    val b = Library.byName(n)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(b?.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.width(36.dp).aspectRatio(0.7f).clip(RoundedCornerShape(3.dp)).background(Surface2))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            (b?.let { it.title + if (it.chapter.isNotBlank()) " ${it.chapter}" else "" } ?: "$n（找不到檔案）"),
                            color = if (b == null) Muted else Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { save(c.copy(cover = n)) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Star, "設為封面", tint = if (c.cover == n) Color(0xFFFFC107) else Muted)
                        }
                        IconButton(enabled = i > 0, onClick = {
                            val l = c.files.toMutableList()
                            l.add(i - 1, l.removeAt(i))
                            save(c.copy(files = l))
                        }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.KeyboardArrowUp, "上移", tint = Color.White) }
                        IconButton(enabled = i < c.files.size - 1, onClick = {
                            val l = c.files.toMutableList()
                            l.add(i + 1, l.removeAt(i))
                            save(c.copy(files = l))
                        }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.KeyboardArrowDown, "下移", tint = Color.White) }
                        IconButton(onClick = {
                            val l = c.files - n
                            save(c.copy(files = l, cover = if (c.cover == n) l.firstOrNull().orEmpty() else c.cover))
                        }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Delete, "移出合集", tint = Muted) }
                    }
                }
            }
        }
    }
    if (rename) {
        var name by remember { mutableStateOf(c.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("合集名稱") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) save(c.copy(name = name.trim()))
                    rename = false
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("取消") } },
        )
    }
    if (addBooks) {
        val candidates = Library.books.filter { it.name !in c.files && it.pages.isNotEmpty() }
        AlertDialog(
            onDismissRequest = { addBooks = false },
            title = { Text("加入哪一本？") },
            text = {
                if (candidates.isEmpty()) Text("書櫃裡沒有其他漫畫了", color = Muted)
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.name }) { b ->
                        Text(b.title + if (b.chapter.isNotBlank()) " ${b.chapter}" else "", color = Color.White, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable {
                            save(c.copy(files = c.files + b.name, cover = c.cover.ifBlank { b.name }))
                        }.padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addBooks = false }) { Text("完成") } },
        )
    }
    if (dissolve) AlertDialog(
        onDismissRequest = { dissolve = false },
        title = { Text("解散合集？") },
        text = { Text("只會刪除「${c.name}」這個合集，裡面的漫畫檔都會留在書櫃。") },
        confirmButton = {
            TextButton(onClick = {
                Library.deleteCollection(c.id)
                dissolve = false
                onDismiss()
            }) { Text("解散") }
        },
        dismissButton = { TextButton(onClick = { dissolve = false }) { Text("取消") } },
    )
}

// ================= 下載 =================

@Composable
fun DownloadsScreen() {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
            SectionTitle("下載", Modifier.weight(1f))
            if (Downloads.tasks.any { it.status == Downloads.Status.Done }) TextButton(onClick = { Downloads.clearFinished() }) { Text("清除已完成") }
        }
        Text(
            "存在：${Library.dir.absolutePath}",
            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (Downloads.tasks.isEmpty()) CenterText("沒有下載。在作品頁按「下載 CBZ」就會出現在這裡")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Downloads.tasks, key = { it.key + it.time }) { t -> TaskRow(t) }
        }
    }
}

@Composable
private fun TaskRow(t: Downloads.Task) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(t.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.width(48.dp).aspectRatio(0.7f).clip(RoundedCornerShape(4.dp)).background(Surface2))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SiteBadge(t.site)
                Spacer(Modifier.width(6.dp))
                Text(t.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t.chapterTitle.isNotBlank()) Text(t.chapterTitle, color = Muted, fontSize = 12.sp, maxLines = 1)
            val status = t.status.text + if (t.total > 0 && t.status != Downloads.Status.Done) " ${t.done}/${t.total}" else ""
            Text(t.error?.let { "$status：$it" } ?: status, color = if (t.status == Downloads.Status.Failed) ErrorColor else Muted, fontSize = 12.sp, maxLines = 2)
            if (t.status == Downloads.Status.Running && t.total > 0) {
                LinearProgressIndicator(progress = { t.done / t.total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = Accent)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            when (t.status) {
                Downloads.Status.Running, Downloads.Status.Queued -> TextButton(onClick = { Downloads.pause(t) }) { Text("暫停") }
                Downloads.Status.Paused, Downloads.Status.Failed -> TextButton(onClick = { Downloads.resume(t) }) { Text(if (t.status == Downloads.Status.Failed) "重試" else "繼續") }
                Downloads.Status.Done -> TextButton(onClick = {
                    val b = Library.byName(t.name)
                    if (b != null) Nav.readBook(b) else toast("書櫃裡找不到這個檔案")
                }) { Text("閱讀") }
            }
            TextButton(onClick = { Downloads.remove(t) }) { Text("移除", color = Muted) }
        }
    }
}
