package com.comichub.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.comichub.app.net.Chapter
import com.comichub.app.net.Detail
import com.comichub.app.net.Net
import com.comichub.app.net.ReaderPage
import com.comichub.app.net.SiteConfig
import com.comichub.app.net.site
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 下載成 CBZ（跟桌面版一樣：一次一本、每本同時抓 4 張，寫進 zip 一律照頁碼順序，附 ComicInfo.xml）。
 * 先寫成 .part，完成才改名，書櫃不會看到下載到一半的檔案。
 */
object Downloads {
    enum class Status(val text: String) { Queued("排隊中"), Running("下載中"), Paused("已暫停"), Failed("失敗"), Done("完成") }

    class Task(
        val site: String,
        val id: String,
        val chapterId: String?,
        val title: String,
        val chapterTitle: String,
        val cover: String,
        val name: String,
        status: Status,
        done: Int = 0,
        total: Int = 0,
        error: String? = null,
        val time: Long = System.currentTimeMillis(),
    ) {
        var status by mutableStateOf(status)
        var done by mutableIntStateOf(done)
        var total by mutableIntStateOf(total)
        var error by mutableStateOf(error)
        val key get() = "${site}_${id}_${chapterId.orEmpty()}"
        val file get() = File(Library.dir, "$name.cbz")

        fun toJson(): JSONObject = JSONObject()
            .put("site", site).put("id", id).put("chapterId", chapterId ?: "").put("title", title)
            .put("chapterTitle", chapterTitle).put("cover", cover).put("name", name).put("status", status.name)
            .put("done", done).put("total", total).put("error", error ?: "").put("time", time)

        companion object {
            fun fromJson(o: JSONObject): Task {
                // 上次關 App 時還在下載 / 排隊的，改成暫停，使用者按「繼續」再接著下
                val st = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.Failed)
                    .let { if (it == Status.Running || it == Status.Queued) Status.Paused else it }
                return Task(
                    o.optString("site"), o.optString("id"), o.optString("chapterId").ifBlank { null }, o.optString("title"),
                    o.optString("chapterTitle"), o.optString("cover"), o.optString("name"), st,
                    o.optInt("done"), o.optInt("total"), o.optString("error").ifBlank { null }, o.optLong("time"),
                )
            }
        }
    }

    /** 新加入的在前面 */
    val tasks = mutableStateListOf<Task>()
    private val details = HashMap<String, Detail>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null
    private var current: Pair<Task, Job>? = null
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        val a = Store.arr("downloads")
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { tasks.add(Task.fromJson(it)) }
    }

    private fun save() {
        Store.put("downloads", JSONArray().apply { tasks.take(200).forEach { put(it.toJson()) } })
    }

    val activeCount get() = tasks.count { it.status == Status.Queued || it.status == Status.Running }

    /** 檔名：[站名] 書名 - 話數 (編號)，跟桌面版一樣，兩邊的檔案可以互相搬 */
    fun fileName(d: Detail, chapter: Chapter?): String {
        val multi = chapter != null && d.chapters.size > 1
        val head = "[${SiteConfig.label(d.site)}] "
        val idTag = site(d.site).idTag(chapter?.id ?: d.id)
        val tail = "${if (multi && chapter!!.title.isNotBlank()) " - ${chapter.title.take(40)}" else ""} ($idTag)"
        val room = maxOf(20, 150 - head.length - tail.length)
        val title = if (d.title.length > room) d.title.take(room).trim() + "…" else d.title
        return Library.safeName(head + title + tail)
    }

    fun isQueued(d: Detail, chapterId: String?) =
        tasks.any { it.site == d.site && it.id == d.id && it.chapterId == chapterId && (it.status == Status.Queued || it.status == Status.Running) }

    fun exists(d: Detail, chapter: Chapter?) = File(Library.dir, fileName(d, chapter) + ".cbz").exists()

    fun enqueue(d: Detail, chapter: Chapter?) {
        load()
        if (isQueued(d, chapter?.id)) return
        details["${d.site}_${d.id}"] = d
        val name = fileName(d, chapter)
        // 重新下載同一本時，舊的那筆紀錄拿掉
        tasks.removeAll { it.name == name }
        tasks.add(
            0,
            Task(
                d.site, d.id, chapter?.id, d.title, if (chapter != null && d.chapters.size > 1) chapter.title else "",
                d.cover, name, Status.Queued,
            ),
        )
        save()
        kick()
    }

    fun resume(t: Task) {
        if (t.status == Status.Paused || t.status == Status.Failed) {
            t.status = Status.Queued
            t.error = null
            save()
            kick()
        }
    }

    fun pause(t: Task) {
        if (current?.first === t) current?.second?.cancel()
        if (t.status == Status.Queued || t.status == Status.Running) t.status = Status.Paused
        save()
    }

    fun remove(t: Task) {
        pause(t)
        tasks.remove(t)
        File(Library.dir, t.name + ".cbz.part").delete()
        save()
    }

    fun clearFinished() {
        tasks.removeAll { it.status == Status.Done }
        save()
    }

    private fun kick() {
        if (runner?.isActive == true) return
        DownloadService.start()
        runner = scope.launch {
            while (true) {
                val next = tasks.lastOrNull { it.status == Status.Queued } ?: break
                val job = launch { run(next) }
                current = next to job
                job.join()
                current = null
                // 被暫停時 job 會被取消，狀態已經在 pause() 設好
                if (next.status == Status.Running) next.status = Status.Paused
                save()
            }
        }
    }

    private suspend fun run(t: Task) {
        t.status = Status.Running
        t.error = null
        t.done = 0
        DownloadService.update(t)
        val part = File(Library.dir, t.name + ".cbz.part")
        try {
            val s = site(t.site)
            val d = details["${t.site}_${t.id}"] ?: s.detail(t.id).also { details["${t.site}_${t.id}"] = it }
            val pages = s.read(d, t.chapterId)
            if (pages.isEmpty()) throw Exception("沒有圖片")
            t.total = pages.size
            val width = maxOf(3, pages.size.toString().length)
            withContext(Dispatchers.IO) {
                ZipOutputStream(part.outputStream().buffered()).use { zip ->
                    val gate = Semaphore(4)
                    // 每 8 張一批：同時抓 4 張，抓完照順序寫進去（記憶體裡最多放一批）
                    for (batch in pages.indices.chunked(8)) {
                        val got = coroutineScope {
                            batch.map { i -> async { gate.withPermit { fetchPage(t.site, pages[i], i) } } }.awaitAll()
                        }
                        for ((i, r) in batch.zip(got)) {
                            ensureActive()
                            store(zip, "${(i + 1).toString().padStart(width, '0')}.${r.second}", r.first)
                        }
                        withContext(Dispatchers.Main) {
                            t.done += batch.size
                            DownloadService.update(t)
                        }
                    }
                    store(zip, "ComicInfo.xml", comicInfo(d, t, pages.size).toByteArray())
                }
                val dest = t.file
                dest.delete()
                if (!part.renameTo(dest)) throw Exception("無法寫入檔案")
            }
            t.status = Status.Done
            Library.refresh()
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            t.status = Status.Failed
            t.error = e.message ?: e.toString()
        }
    }

    /** 抓一張圖；EH 要先解析圖片網址，圖片伺服器掛掉時換一台；JM 打亂的圖先拼回來 */
    private suspend fun fetchPage(siteKey: String, p: ReaderPage, i: Int): Pair<ByteArray, String> {
        try {
            var url = p.url.ifBlank { p.resolve?.invoke(false) ?: "" }
            val r = try {
                Net.bytes(siteKey, url)
            } catch (e: Exception) {
                if (e is CancellationException || p.resolve == null) throw e
                url = p.resolve.invoke(true)
                Net.bytes(siteKey, url)
            }
            if (p.segs > 0) return Descramble.jpeg(r.first, p.segs) to "jpg"
            return r.first to extOf(r.second, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception("第 ${i + 1} 頁：${e.message}")
        }
    }

    private fun extOf(type: String, url: String): String {
        val t = type.lowercase()
        if ("webp" in t) return "webp"
        if ("png" in t) return "png"
        if ("gif" in t) return "gif"
        if ("avif" in t) return "avif"
        if ("jpeg" in t || "jpg" in t) return "jpg"
        val m = Regex("\\.(jpe?g|png|webp|gif|avif)$", RegexOption.IGNORE_CASE).find(url.substringBefore('?'))
        return m?.groupValues?.get(1)?.lowercase()?.replace("jpeg", "jpg") ?: "jpg"
    }

    /** 圖片本身已壓縮，用 STORE 不再壓（跟桌面版一樣） */
    private fun store(zip: ZipOutputStream, name: String, data: ByteArray) {
        val e = ZipEntry(name)
        e.method = ZipEntry.STORED
        e.size = data.size.toLong()
        e.compressedSize = data.size.toLong()
        e.crc = CRC32().apply { update(data) }.value
        zip.putNextEntry(e)
        zip.write(data)
        zip.closeEntry()
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    /** 漫畫閱讀器（Komga、Kavita、CDisplayEx…）認得的 metadata */
    private fun comicInfo(d: Detail, t: Task, pageCount: Int): String {
        val author = d.tags.filter { it.label == "作者" }.map { it.name }
        val tags = d.tags.filter { it.label != "作者" }.map { it.name }
        val chapter = t.chapterTitle.isNotBlank()
        return listOfNotNull(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<ComicInfo xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">",
            "  <Title>${xml(if (chapter) t.chapterTitle else d.title)}</Title>",
            if (chapter) "  <Series>${xml(d.title)}</Series>" else null,
            if (chapter) "  <Number>${d.chapters.indexOfFirst { it.id == t.chapterId } + 1}</Number>" else null,
            if (author.isNotEmpty()) "  <Writer>${xml(author.joinToString(", "))}</Writer>" else null,
            if (tags.isNotEmpty()) "  <Tags>${xml(tags.joinToString(", "))}</Tags>" else null,
            "  <Web>${xml(d.url)}</Web>",
            "  <PageCount>$pageCount</PageCount>",
            "  <Notes>${xml("${SiteConfig.label(d.site)} ${d.id}")}</Notes>",
            "</ComicInfo>",
        ).joinToString("\n")
    }
}
