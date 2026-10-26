package com.comichub.app.data

import androidx.compose.runtime.mutableStateListOf
import com.comichub.app.net.Item
import org.json.JSONArray

/**
 * 本機收藏：存在 App 裡（state.json 的 favorites），四個站都能收、不用登入。
 * 網站帳號的收藏另外在「收藏」頁的各站分頁讀。
 */
object Favorites {
    val items = mutableStateListOf<Item>()
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        val a = Store.arr("favorites")
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { items.add(Item.fromJson(it)) }
    }

    private fun save() {
        Store.put("favorites", JSONArray().apply { items.forEach { put(it.toJson()) } })
    }

    fun has(site: String, id: String) = items.any { it.site == site && it.id == id }

    /** 收藏 / 取消收藏，回傳收藏後的狀態；新收藏的排最前面 */
    fun toggle(item: Item): Boolean {
        load()
        val i = items.indexOfFirst { it.site == item.site && it.id == item.id }
        if (i >= 0) items.removeAt(i) else items.add(0, item)
        save()
        return i < 0
    }

    fun remove(item: Item) {
        items.removeAll { it.site == item.site && it.id == item.id }
        save()
    }
}
