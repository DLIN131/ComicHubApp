package com.comichub.app.data

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.comichub.app.App
import com.comichub.app.net.Lang
import com.comichub.app.net.Query
import com.comichub.app.net.SiteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** 書櫃裡的一本 CBZ */
class Book(
    val file: File,
    /** 檔名（不含副檔名），分組、進度都用它記 */
    val name: String,
    val title: String,
    /** 多話作品下載單話時的話數標題 */
    val chapter: String,
    /** 檔名開頭 [站名] 對回的站別代號，不是本程式下載的就是空字串 */
    val site: String,
    val pages: List<String>,
    val authors: List<String>,
    val tags: List<String>,
    val lang: String,
    val mtime: Long,
    val size: Long,
    val error: String? = null,
) {
    val key get() = "local_$name"
    fun page(i: Int) = ZipPage(file, pages[i])
    val cover get() = if (pages.isNotEmpty()) page(0) else null
}

/** 合集：幾本串成一本連續看（不另外產生檔案，只記檔名） */
data class Collection(val id: String, val name: String, val files: List<String>, val cover: String) {
    val key get() = "col_$id"

    fun toJson() = JSONObject().put("id", id).put("name", name).put("files", JSONArray(files)).put("cover", cover)

    companion object {
        fun fromJson(o: JSONObject) = Collection(
            o.optString("id"), o.optString("name"),
            o.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            o.optString("cover"),
        )
    }
}

/**
 * 書櫃：下載資料夾（App 自己的外部儲存空間 Android/data/…/files/downloads）裡的 CBZ / ZIP。
 * 圖片頁直接從 zip 讀（ZipFile 只讀目錄，不用解壓）。
 */
object Library {
    val dir: File by lazy {
        (App.instance.getExternalFilesDir(null) ?: App.instance.filesDir).resolve("downloads").apply { mkdirs() }
    }

    val books = mutableStateListOf<Book>()
    var scanning by mutableStateOf(false)
        private set
    /** 分組、合集改了就 +1，畫面跟著重算 */
    var version by mutableStateOf(0)
        private set

    private val cache = HashMap<String, Book>()
    private val IMAGE = Regex("\\.(jpe?g|png|webp|gif|avif|bmp)$", RegexOption.IGNORE_CASE)

    suspend fun refresh() {
        scanning = true
        try {
            val list = withContext(Dispatchers.IO) {
                dir.listFiles { f -> f.isFile && Regex("\\.(cbz|zip)$", RegexOption.IGNORE_CASE).containsMatchIn(f.name) }.orEmpty()
                    .map { f ->
                        val k = "${f.name}|${f.lastModified()}|${f.length()}"
                        cache.getOrPut(k) { read(f) }
                    }
                    .sortedByDescending { it.mtime }
            }
            books.clear()
            books.addAll(list)
        } finally {
            scanning = false
        }
    }

    /** 自然排序：2.jpg 排在 10.jpg 前面 */
    private val naturalOrder = Comparator<String> { a, b ->
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size - rb.size
    }

    private fun read(f: File): Book {
        val name = f.name.replace(Regex("\\.(cbz|zip)$", RegexOption.IGNORE_CASE), "")
        val m = Regex("^\\[([^\\]]+)]\\s*(.+)$").find(name)
        val source = m?.groupValues?.get(1).orEmpty()
        val site = SiteConfig.KEYS.firstOrNull { SiteConfig.label(it) == source }.orEmpty()
        return try {
            ZipFile(f).use { z ->
                val pages = z.entries().asSequence()
                    .filter { !it.isDirectory && IMAGE.containsMatchIn(it.name) && !it.name.startsWith("__MACOSX") }
                    .map { it.name }.toList().sortedWith(naturalOrder)
                val xml = z.getEntry("ComicInfo.xml")?.let { e -> z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }.orEmpty()
                fun tag(t: String) = Regex("<$t>([\\s\\S]*?)</$t>").find(xml)?.groupValues?.get(1)?.let { unxml(it) }.orEmpty()
                val series = tag("Series")
                val title = series.ifBlank { tag("Title") }.ifBlank { m?.groupValues?.get(2)?.replace(Regex("\\s*\\([^)]*\\)$"), "") ?: name }
                val writers = tag("Writer").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val tags = tag("Tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                Book(
                    f, name, title, if (series.isNotBlank()) tag("Title") else "", site, pages,
                    writers.ifEmpty { Query.parseAuthors(title) }, tags,
                    Lang.detect("$title $name ${tags.joinToString(" ")}"), f.lastModified(), f.length(),
                    if (pages.isEmpty()) "裡面沒有圖片" else null,
                )
            }
        } catch (e: Exception) {
            Book(f, name, name, "", site, emptyList(), emptyList(), emptyList(), "", f.lastModified(), f.length(), "無法讀取：${e.message}")
        }
    }

    private fun unxml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun byName(name: String) = books.firstOrNull { it.name == name }

    suspend fun delete(b: Book) {
        withContext(Dispatchers.IO) {
            ZipCache.drop(b.file)
            b.file.delete()
        }
        books.remove(b)
        setGroup(b.name, null)
        History.clearProgress(b.key)
    }

    /** 把使用者選的 CBZ / ZIP 複製進書櫃 */
    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val cr = App.instance.contentResolver
        val display = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "漫畫-${System.currentTimeMillis()}.cbz"
        val stem = safeName(display.replace(Regex("\\.(cbz|zip)$", RegexOption.IGNORE_CASE), ""))
        val ext = if (display.endsWith(".zip", true)) "zip" else "cbz"
        var dest = File(dir, "$stem.$ext")
        var n = 2
        while (dest.exists()) dest = File(dir, "$stem (${n++}).$ext")
        val tmp = File(dir, dest.name + ".part")
        cr.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: throw java.io.IOException("讀不到檔案")
        // 先確認是 zip 再放進書櫃
        runCatching { ZipFile(tmp).close() }.onFailure {
            tmp.delete()
            throw java.io.IOException("不是 CBZ / ZIP 檔")
        }
        tmp.renameTo(dest)
        val b = read(dest)
        withContext(Dispatchers.Main) { refresh() }
        b
    }

    fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimEnd('.').ifBlank { "untitled" }

    fun totalSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    // ---------- 分組（以檔名記） ----------

    fun groupOf(name: String): String? = Store.obj("libGroups").optString(name).ifBlank { null }

    fun groups(): List<String> {
        val of = Store.obj("libGroups")
        val list = Store.arr("libGroupList")
        return ((0 until list.length()).map { list.optString(it) } + of.keys().asSequence().map { of.optString(it) }).filter { it.isNotBlank() }.distinct()
    }

    fun setGroup(name: String, group: String?) {
        val of = Store.obj("libGroups")
        if (group.isNullOrBlank()) of.remove(name) else of.put(name, group)
        if (!group.isNullOrBlank() && group !in groups()) Store.arr("libGroupList").put(group)
        Store.save()
        version++
    }

    fun deleteGroup(group: String) {
        val of = Store.obj("libGroups")
        of.keys().asSequence().toList().filter { of.optString(it) == group }.forEach { of.remove(it) }
        val list = Store.arr("libGroupList")
        Store.put("libGroupList", JSONArray((0 until list.length()).map { list.optString(it) }.filter { it != group }))
        version++
    }

    // ---------- 合集 ----------

    fun collections(): List<Collection> {
        val a = Store.arr("collections")
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Collection::fromJson) }
    }

    private fun saveCollections(list: List<Collection>) {
        Store.put("collections", JSONArray().apply { list.forEach { put(it.toJson()) } })
        version++
    }

    fun collection(id: String) = collections().firstOrNull { it.id == id }

    fun createCollection(name: String, files: List<String>): Collection {
        val c = Collection(System.currentTimeMillis().toString(36), name.ifBlank { "未命名合集" }, files.distinct(), files.firstOrNull().orEmpty())
        saveCollections(collections() + c)
        return c
    }

    fun updateCollection(c: Collection) {
        saveCollections(collections().map { if (it.id == c.id) c else it })
    }

    fun deleteCollection(id: String) {
        saveCollections(collections().filter { it.id != id })
        History.clearProgress("col_$id")
    }

    /** 合集裡還找得到檔案的書，照合集順序 */
    fun booksOf(c: Collection): List<Book> = c.files.mapNotNull { byName(it) }.filter { it.pages.isNotEmpty() }
}
