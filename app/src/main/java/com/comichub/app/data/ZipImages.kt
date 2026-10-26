package com.comichub.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import coil.size.Size
import coil.transform.Transformation
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/** CBZ 裡的一張圖（給 Coil 當圖片來源） */
data class ZipPage(val file: File, val entry: String)

/** 開過的 CBZ 留著幾個，翻頁時不用每張都重新讀目錄 */
object ZipCache {
    private val open = object : LinkedHashMap<String, ZipFile>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ZipFile>): Boolean {
            if (size > 4) {
                runCatching { eldest.value.close() }
                return true
            }
            return false
        }
    }

    @Synchronized
    fun get(f: File): ZipFile = open.getOrPut(f.absolutePath) { ZipFile(f) }

    @Synchronized
    fun drop(f: File) {
        open.remove(f.absolutePath)?.let { runCatching { it.close() } }
    }

    fun bytes(p: ZipPage): ByteArray {
        val z = get(p.file)
        val e = z.getEntry(p.entry) ?: throw java.io.FileNotFoundException(p.entry)
        return z.getInputStream(e).use { it.readBytes() }
    }
}

/** 記憶體快取的鍵（沒有它 Coil 不會快取，書櫃捲動時封面會一直重新解碼） */
class ZipPageKeyer : Keyer<ZipPage> {
    override fun key(data: ZipPage, options: Options) = "${data.file.absolutePath}#${data.entry}#${data.file.lastModified()}"
}

class ZipPageFetcher(private val page: ZipPage, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val data = ZipCache.bytes(page)
        return SourceResult(
            source = ImageSource(Buffer().write(data), options.context),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<ZipPage> {
        override fun create(data: ZipPage, options: Options, imageLoader: ImageLoader): Fetcher = ZipPageFetcher(data, options)
    }
}

/**
 * JM 站的圖被橫切成 num 條上下倒置，照網站的演算法拼回來（跟桌面版 descramble 一樣）。
 */
object Descramble {
    fun bitmap(src: Bitmap, num: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val over = h % num
        val base = h / num
        for (i in 0 until num) {
            var move = base
            val ySrc = h - base * (i + 1) - over
            var yDst = base * i
            if (i == 0) move += over else yDst += over
            c.drawBitmap(src, Rect(0, ySrc, w, ySrc + move), Rect(0, yDst, w, yDst + move), null)
        }
        return out
    }

    /** 下載用：解碼 → 拼回 → 存成 JPEG */
    fun jpeg(data: ByteArray, num: Int): ByteArray {
        val src = BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw java.io.IOException("圖片解碼失敗")
        val out = bitmap(src, num)
        src.recycle()
        return ByteArrayOutputStream().use {
            out.compress(Bitmap.CompressFormat.JPEG, 92, it)
            out.recycle()
            it.toByteArray()
        }
    }
}

class DescrambleTransformation(private val num: Int) : Transformation {
    override val cacheKey = "descramble-$num"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap = Descramble.bitmap(input, num)
}
