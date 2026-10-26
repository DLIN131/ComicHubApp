package com.comichub.app.data

import com.comichub.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 程式資料（紀錄、閱讀進度、合集、書櫃分組、下載紀錄）存成 filesDir/state.json。
 * 跟桌面版一樣：先寫暫存檔再改名（寫到一半當掉也不會壞），舊的那份留成 .bak，讀不到時用 .bak。
 */
object Store {
    private val file by lazy { File(App.instance.filesDir, "state.json") }
    private val bak by lazy { File(App.instance.filesDir, "state.json.bak") }
    private var data = JSONObject()
    private var loaded = false
    // 資料都在主執行緒改，所以也在主執行緒轉成字串（避免背景轉字串時剛好被改到），只有寫檔在背景
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pending: Job? = null

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        data = read(file) ?: read(bak) ?: JSONObject()
    }

    private fun read(f: File): JSONObject? = runCatching { JSONObject(f.readText()) }.getOrNull()

    @Synchronized
    fun obj(key: String): JSONObject {
        load()
        return data.optJSONObject(key) ?: JSONObject().also { data.put(key, it) }
    }

    @Synchronized
    fun arr(key: String): JSONArray {
        load()
        return data.optJSONArray(key) ?: JSONArray().also { data.put(key, it) }
    }

    @Synchronized
    fun put(key: String, value: Any) {
        load()
        data.put(key, value)
        save()
    }

    /** 改了 obj() / arr() 拿到的物件後呼叫；300ms 內的多次修改合併成一次寫檔 */
    fun save() {
        pending?.cancel()
        pending = scope.launch {
            delay(300)
            val text = snapshot() ?: return@launch
            withContext(Dispatchers.IO) { write(text) }
        }
    }

    @Synchronized
    private fun snapshot(): String? = if (loaded) runCatching { data.toString() }.getOrNull() else null

    /** 關 App 前呼叫：馬上寫檔 */
    fun flush() {
        pending?.cancel()
        snapshot()?.let { write(it) }
    }

    @Synchronized
    private fun write(text: String) {
        runCatching {
            val tmp = File(file.parentFile, "state.json.tmp")
            tmp.writeText(text)
            if (file.exists()) file.copyTo(bak, overwrite = true)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }
}
