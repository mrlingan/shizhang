package com.lingansir.ooo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors

data class EditorDraft(
    val id: String = UUID.randomUUID().toString(), val editing: Boolean = false,
    var kind: Kind = Kind.EXPENSE, var amount: String = "", var category: Category = Category.FOOD,
    var method: String, var date: LocalDate = LocalDate.now(), var note: String = "",
    val retained: MutableList<String> = mutableListOf(), val added: MutableList<String> = mutableListOf()
) {
    fun json(): String = JSONObject().put("id", id).put("editing", editing).put("kind", kind.name)
        .put("amount", amount).put("category", category.name).put("method", method).put("date", date.toString())
        .put("note", note).put("retained", JSONArray(retained)).put("added", JSONArray(added)).toString()
    companion object {
        fun read(text: String): EditorDraft {
            val o = JSONObject(text)
            fun list(key: String): MutableList<String> = o.getJSONArray(key).let { a -> MutableList(a.length()) { a.getString(it) } }
            return EditorDraft(o.getString("id"), o.getBoolean("editing"), Kind.valueOf(o.getString("kind")),
                o.getString("amount"), Category.valueOf(o.getString("category")), o.getString("method"),
                LocalDate.parse(o.getString("date")), o.getString("note"), list("retained"), list("added"))
        }
    }
}
data class UiEvent(val type: String, val payload: Any? = null)
class AppModel(application: Application) : AndroidViewModel(application) {
    val store = LedgerStore(File(application.filesDir, "ledger"))
    val busy = MutableLiveData(false)
    val event = MutableLiveData<UiEvent?>(null)
    private val executor = Executors.newSingleThreadExecutor()
    var draft: EditorDraft? = null
    var pendingExport: ByteArray? = null
    var preview: ImportBatch? = null
    fun work(type: String, action: () -> Any?) {
        if (busy.value == true) return
        busy.value = true
        executor.execute {
            val result = try { UiEvent(type, action()) } catch(e: Exception) { UiEvent("error", e.message ?: "操作失败，请重试。") }
            event.postValue(result)
            busy.postValue(false)
        }
    }
    fun tempImage(name: String): File {
        require(Regex("[0-9a-fA-F-]{36}\\.jpg").matches(name))
        return File(getApplication<Application>().cacheDir, name)
    }
    fun clearDraft() { draft?.added?.forEach { tempImage(it).delete() }; draft = null }
    fun importPhotos(uris: List<Uri>): Int {
        val draft = draft ?: return 0
        val resolver = getApplication<Application>().contentResolver
        var failures = 0
        uris.take(3 - draft.retained.size - draft.added.size).forEach { uri ->
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong()*bounds.outHeight <= 150_000_000) { "图片尺寸无效。" }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3600) sample *= 2
                val bitmap = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("无法读取图片。")
                val orientation = runCatching { resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
                val matrix = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f)
                        3 -> setRotate(180f)
                        4 -> setScale(1f, -1f)
                        5 -> { setRotate(90f); postScale(-1f, 1f) }
                        6 -> setRotate(90f)
                        7 -> { setRotate(-90f); postScale(-1f, 1f) }
                        8 -> setRotate(-90f)
                    }
                }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                val scale = minOf(1.0, 1800.0 / maxOf(rotated.width, rotated.height))
                val resized = Bitmap.createScaledBitmap(rotated, maxOf(1, (rotated.width*scale).toInt()), maxOf(1, (rotated.height*scale).toInt()), true)
                val name = "${UUID.randomUUID()}.jpg"
                try {
                    tempImage(name).outputStream().use { check(resized.compress(Bitmap.CompressFormat.JPEG, 82, it)) }
                    draft.added.add(name)
                } catch(e: Exception) { tempImage(name).delete(); throw e }
                finally { if (resized !== rotated) resized.recycle(); rotated.recycle() }
            } catch(e: Exception) { failures++ }
        }
        return failures
    }
    override fun onCleared() { executor.shutdown() }
}
