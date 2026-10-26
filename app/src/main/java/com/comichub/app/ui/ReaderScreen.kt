package com.comichub.app.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import coil.size.Size
import com.comichub.app.Prefs
import com.comichub.app.data.DescrambleTransformation
import com.comichub.app.data.History
import com.comichub.app.data.Progress
import com.comichub.app.net.site
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(route: Route.Reader) {
    val d = route.detail
    var pages by remember { mutableStateOf(route.pages) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    // 第一次開用指定的頁；換話以後從第 1 頁開始
    var start by remember { mutableIntStateOf(route.startPage) }
    val chapter = d?.chapters?.getOrNull(route.chapter)

    ImmersiveMode()

    if (d != null) {
        LaunchedEffect(route.chapter, attempt) {
            pages = null
            error = null
            try {
                pages = site(d.site).read(d, chapter?.id).map { PageSrc.Web(d.site, it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }
        LaunchedEffect(Unit) { History.add(d.toItem(), chapter?.title) }
    }

    val list = pages
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            list != null && list.isEmpty() -> CenterText("沒有圖片")
            list != null -> key(route.chapter) {
                Pages(route, list, start.coerceIn(0, list.size - 1), chapter?.title) { i ->
                    start = 0
                    route.chapter = i
                }
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorBox(error!!) { attempt++ } }
            else -> CenterLoading()
        }
        if (list == null) IconButton(onClick = { Nav.pop() }, modifier = Modifier.statusBarsPadding()) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
        }
    }
}

@Composable
private fun Pages(route: Route.Reader, pages: List<PageSrc>, startPage: Int, chapterTitle: String?, onChapter: (Int) -> Unit) {
    val d = route.detail
    val chapters = d?.chapters.orEmpty()
    var vertical by rememberSaveable { mutableStateOf(Prefs.vertical) }
    var rtl by remember { mutableStateOf(Prefs.rightToLeft) }
    var overlay by rememberSaveable { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(startPage) }
    var showList by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val list = rememberLazyListState(initialFirstVisibleItemIndex = current)
    val pager = rememberPagerState(initialPage = current) { pages.size }
    LaunchedEffect(vertical) {
        if (vertical) {
            list.scrollToItem(current)
            snapshotFlow { list.firstVisibleItemIndex }.collect { if (it < pages.size) current = it }
        } else {
            pager.scrollToPage(current)
            snapshotFlow { pager.currentPage }.collect { current = it }
        }
    }

    /** 合集：目前這頁是哪一本 */
    val tocIndex = route.toc.indexOfLast { it.second <= current }
    val subTitle = chapterTitle ?: route.toc.getOrNull(tocIndex)?.first

    // 記住看到哪
    LaunchedEffect(current) {
        val p = Progress(
            chapterId = route.detail?.chapters?.getOrNull(route.chapter)?.id,
            chapterTitle = subTitle.orEmpty(),
            page = current,
            total = pages.size,
            time = System.currentTimeMillis(),
        )
        History.saveProgress(route.pkey, p, d?.site, d?.id)
    }

    fun jump(i: Int) {
        current = i.coerceIn(0, pages.size - 1)
        scope.launch { if (vertical) list.scrollToItem(current) else pager.scrollToPage(current) }
    }

    val nextChapter = if (route.chapter >= 0 && route.chapter + 1 < chapters.size) route.chapter + 1 else -1
    val prevChapter = if (route.chapter > 0) route.chapter - 1 else -1

    Box(Modifier.fillMaxSize()) {
        if (vertical) {
            Zoomable(onTap = { overlay = !overlay }, panOnlyWhenZoomed = false) { zoomMod ->
                LazyColumn(state = list, modifier = zoomMod.fillMaxSize()) {
                    itemsIndexed(pages) { i, p -> PageImage(p, i, fit = false) }
                    item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (nextChapter >= 0) Button(onClick = { onChapter(nextChapter) }) {
                                Text("下一話：${chapters[nextChapter].title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else Text("已經是最後一頁", color = Muted)
                        }
                    }
                }
            }
        } else {
            HorizontalPager(
                state = pager,
                reverseLayout = rtl,
                beyondViewportPageCount = 2,
                modifier = Modifier.fillMaxSize(),
            ) { i ->
                Zoomable(onTap = { overlay = !overlay }, panOnlyWhenZoomed = true) { zoomMod ->
                    Box(zoomMod.fillMaxSize(), contentAlignment = Alignment.Center) { PageImage(pages[i], i, fit = true) }
                }
            }
        }

        // 不顯示選單時，右下角留個小小的頁碼
        if (!overlay) {
            Text(
                "${current + 1}/${pages.size}",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(6.dp)
                    .background(Color(0x88000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        AnimatedVisibility(overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xCC000000)).statusBarsPadding().height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { Nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(route.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    if (!subTitle.isNullOrBlank()) Text(subTitle, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                }
                TextButton(onClick = {
                    vertical = !vertical
                    Prefs.vertical = vertical
                }) { Text(if (vertical) "改左右翻頁" else "改上下捲動", color = Color.White) }
            }
        }
        AnimatedVisibility(overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(Color(0xCC000000)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                var drag by remember { mutableFloatStateOf(-1f) }
                val shown = if (drag >= 0) drag.roundToInt() else current
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("第 ${shown + 1} / ${pages.size} 頁", color = Color.White)
                    if (!vertical) TextButton(onClick = {
                        rtl = !rtl
                        Prefs.rightToLeft = rtl
                        jump(current)
                    }) { Text(if (rtl) "由右往左" else "由左往右", color = Color.White) }
                }
                if (pages.size > 1) {
                    Slider(
                        value = shown.toFloat(),
                        onValueChange = { drag = it },
                        onValueChangeFinished = {
                            if (drag >= 0) jump(drag.roundToInt())
                            drag = -1f
                        },
                        valueRange = 0f..(pages.size - 1).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                    )
                }
                if (chapters.size > 1 || route.toc.size > 1) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        if (route.toc.size > 1) {
                            TextButton(enabled = tocIndex > 0, onClick = { jump(route.toc[tocIndex - 1].second) }) { Text("上一本") }
                            TextButton(onClick = { showList = true }) { Text("目錄（${route.toc.size} 本）", color = Color.White) }
                            TextButton(enabled = tocIndex + 1 < route.toc.size, onClick = { jump(route.toc[tocIndex + 1].second) }) { Text("下一本") }
                        } else {
                            TextButton(enabled = prevChapter >= 0, onClick = { onChapter(prevChapter) }) { Text("上一話") }
                            TextButton(onClick = { showList = true }) { Text("章節（${chapters.size} 話）", color = Color.White) }
                            TextButton(enabled = nextChapter >= 0, onClick = { onChapter(nextChapter) }) { Text("下一話") }
                        }
                    }
                }
            }
        }
    }

    if (showList) {
        val entries: List<Pair<String, () -> Unit>> =
            if (route.toc.size > 1) route.toc.map { (t, p) -> t to { jump(p) } }
            else chapters.mapIndexed { i, c -> c.title to { onChapter(i) } }
        val selected = if (route.toc.size > 1) tocIndex else route.chapter
        AlertDialog(
            onDismissRequest = { showList = false },
            title = { Text(if (route.toc.size > 1) "目錄" else "章節") },
            text = {
                val state = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 3).coerceAtLeast(0))
                LazyColumn(Modifier.heightIn(max = 460.dp), state = state) {
                    itemsIndexed(entries) { i, (t, go) ->
                        Text(
                            t,
                            color = if (i == selected) Accent else Color.White,
                            modifier = Modifier.fillMaxWidth().clickable {
                                showList = false
                                go()
                            }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showList = false }) { Text("關閉") } },
        )
    }
}

@Composable
private fun PageImage(src: PageSrc, index: Int, fit: Boolean) {
    val context = LocalContext.current
    var retry by remember(src) { mutableIntStateOf(0) }
    val web = src as? PageSrc.Web
    var url by remember(src) { mutableStateOf(web?.page?.url.orEmpty()) }
    var resolveError by remember(src) { mutableStateOf<String?>(null) }
    // EH：圖片網址要再開一頁才拿得到；重試時換一台圖片伺服器
    val resolver = web?.page?.resolve
    if (resolver != null) LaunchedEffect(src, retry) {
        if (url.isNotBlank() && retry == 0) return@LaunchedEffect
        resolveError = null
        try {
            url = resolver(retry > 0)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            resolveError = e.message ?: e.toString()
        }
    }
    val ratio = web?.page?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: 0.7f
    val box = if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(ratio)
    val model: Any? = when (src) {
        is PageSrc.Zip -> src.page
        is PageSrc.Web -> url.ifBlank { null }
    }
    if (model == null) {
        Box(box.clickable { retry++ }, contentAlignment = Alignment.Center) {
            Text(resolveError?.let { "第 ${index + 1} 頁：$it\n點這裡重試" } ?: "${index + 1}", color = if (resolveError != null) Muted else Color.DarkGray, fontSize = if (resolveError != null) 14.sp else 32.sp, textAlign = TextAlign.Center)
        }
        return
    }
    val segs = web?.page?.segs ?: 0
    val request = remember(model, retry) {
        ImageRequest.Builder(context).data(model)
            .apply {
                // JM 打亂的圖要用原尺寸拼，縮過再拼會錯位
                if (segs > 0) {
                    transformations(DescrambleTransformation(segs))
                    size(Size.ORIGINAL)
                }
            }
            .setParameter("retry", retry, memoryCacheKey = null)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = if (fit) ContentScale.Fit else ContentScale.FillWidth,
        modifier = if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
            is AsyncImagePainter.State.Error -> Box(box.clickable { retry++ }, contentAlignment = Alignment.Center) {
                Text("第 ${index + 1} 頁載入失敗，點這裡重試", color = Muted)
            }
            else -> Box(box, contentAlignment = Alignment.Center) {
                Text("${index + 1}", color = Color.DarkGray, fontSize = 32.sp)
            }
        }
    }
}

/**
 * 雙指縮放、雙擊放大。
 * panOnlyWhenZoomed＝true（左右翻頁）：沒放大時不吃手勢，讓 Pager 能滑。
 * 上下捲動模式：單指拖曳交給 LazyColumn，只處理雙指縮放與放大後的左右平移。
 */
@Composable
private fun Zoomable(onTap: () -> Unit, panOnlyWhenZoomed: Boolean, content: @Composable (Modifier) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pos ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            // 以點擊位置為中心放大
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = clampOffset((center - pos) * (scale - 1f), scale, size.width, size.height, panOnlyWhenZoomed)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            val p = if (panOnlyWhenZoomed) pan else Offset(pan.x, 0f)
                            offset = clampOffset(offset + p, scale, size.width, size.height, panOnlyWhenZoomed)
                            // 上下捲動模式的單指拖曳不吃掉，讓列表照常捲
                            if (multi || panOnlyWhenZoomed) event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.05f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            }
    ) {
        content(
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        )
    }
}

private fun clampOffset(o: Offset, scale: Float, w: Int, h: Int, allowY: Boolean): Offset {
    val maxX = w * (scale - 1f) / 2f
    val maxY = h * (scale - 1f) / 2f
    return Offset(o.x.coerceIn(-maxX, maxX), if (allowY) o.y.coerceIn(-maxY, maxY) else 0f)
}

/** 閱讀時隱藏狀態列和導覽列，並保持螢幕常亮 */
@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
}
