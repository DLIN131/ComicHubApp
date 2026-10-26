package com.comichub.app.data

import androidx.compose.runtime.mutableStateListOf
import com.comichub.app.net.Item
import org.json.JSONArray
import org.json.JSONObject

/** 看到哪：第幾話第幾頁（page 從 0 起算） */
data class Progress(val chapterId: String?, val chapterTitle: String, val page: Int, val total: Int, val time: Long) {
    val text get() = "${if (chapterTitle.isNotBlank()) "$chapterTitle " else ""}第 ${page + 1} / $total 頁"
}

/**
 * 閱讀紀錄與進度。進度的 key：線上作品 `${site}_${id}`、書櫃 `local_${檔名}`、合集 `col_${id}`（跟桌面版一樣）。
 */
object History {
    val items = mutableStateListOf<Item>()
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        val a = Store.arr("history")
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { items.add(Item.fromJson(it)) }
    }

    private fun save() {
        val a = JSONArray()
        items.forEach { a.put(it.toJson()) }
        Store.put("history", a)
    }

    fun add(item: Item, chapter: String? = null) {
        load()
        val old = items.indexOfFirst { it.site == item.site && it.id == item.id }
        val sub = progress(item.key)?.let { "看到：${it.text}" } ?: chapter?.let { "看到：$it" } ?: item.sub
        if (old >= 0) items.removeAt(old)
        items.add(0, item.copy(sub = sub))
        while (items.size > 300) items.removeAt(items.lastIndex)
        save()
    }

    fun remove(item: Item) {
        items.removeAll { it.site == item.site && it.id == item.id }
        save()
    }

    fun clear() {
        items.clear()
        save()
    }

    fun progress(key: String): Progress? = Store.obj("progress").optJSONObject(key)?.let {
        Progress(it.optString("chapterId").ifBlank { null }, it.optString("chapterTitle"), it.optInt("page"), it.optInt("total"), it.optLong("time"))
    }

    fun saveProgress(key: String, p: Progress, histSite: String? = null, histId: String? = null) {
        val all = Store.obj("progress")
        all.put(
            key,
            JSONObject().put("chapterId", p.chapterId ?: "").put("chapterTitle", p.chapterTitle)
                .put("page", p.page).put("total", p.total).put("time", p.time),
        )
        // 只留最近 1000 本，免得存檔越長越大
        if (all.length() > 1000) {
            val keys = all.keys().asSequence().toList().sortedBy { all.optJSONObject(it)?.optLong("time") ?: 0 }
            keys.take(all.length() - 1000).forEach { all.remove(it) }
        }
        Store.save()
        // 「紀錄」頁的那本也一起更新看到哪
        if (histSite != null && histId != null) {
            val i = items.indexOfFirst { it.site == histSite && it.id == histId }
            if (i >= 0) {
                items[i] = items[i].copy(sub = "看到：${p.text}")
                save()
            }
        }
    }

    fun clearProgress(key: String) {
        Store.obj("progress").remove(key)
        Store.save()
    }
}
