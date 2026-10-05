package com.lingansir.ooo

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.UUID

object LedgerJson {
    fun record(r: LedgerRecord) = JSONObject().put("id", r.id).put("kind", r.kind.name)
        .put("amountMinor", r.amountMinor).put("category", r.category.name)
        .put("paymentMethod", r.paymentMethod).put("date", r.date.toString())
        .put("note", r.note).put("imageNames", JSONArray(r.imageNames))
    fun readRecord(o: JSONObject) = LedgerRecord(
        UUID.fromString(o.getString("id")).toString(), Kind.valueOf(o.getString("kind")),
        o.getLong("amountMinor"), Category.valueOf(o.getString("category")),
        o.getString("paymentMethod"), LocalDate.parse(o.getString("date")),
        o.getString("note"), strings(o.getJSONArray("imageNames")))
    fun encode(s: LedgerSnapshot) = JSONObject().put("version", 1)
        .put("records", JSONArray().apply { s.records.forEach { put(record(it)) } })
        .put("paymentMethods", JSONArray(s.paymentMethods)).toString()
    fun decode(text: String): LedgerSnapshot {
        val o = JSONObject(text)
        require(o.getInt("version") == 1) { "账本版本不受支持。" }
        val records = o.getJSONArray("records")
        val result = LedgerSnapshot(List(records.length()) { readRecord(records.getJSONObject(it)) },
            strings(o.getJSONArray("paymentMethods")))
        require(result.paymentMethods.isNotEmpty() && result.paymentMethods.all { it.isNotBlank() && it.codePointCount(0,it.length) <= 20 })
        require(result.paymentMethods.map { it.lowercase() }.distinct().size == result.paymentMethods.size)
        require(result.records.map { it.id }.distinct().size == result.records.size)
        result.records.forEach { validateRecord(it); require(it.paymentMethod in result.paymentMethods) }
        return result
    }
    private fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }
    fun validateRecord(r: LedgerRecord) {
        require(r.amountMinor in 1..Money.MAX && r.category in Category.options(r.kind) &&
            r.imageNames.size <= 3 && r.imageNames.distinct().size == r.imageNames.size &&
            r.imageNames.all { Regex("[0-9a-fA-F-]{36}\\.jpg").matches(it) }) { "请检查金额、分类及图片数量。" }
        require(r.paymentMethod.isNotBlank() && r.paymentMethod.codePointCount(0,r.paymentMethod.length) <= 20) { "付款方式需为 1–20 个字。" }
        require(r.date.year in 1..9999) { "日期需在 0001–9999 年之间。" }
        require(UUID.fromString(r.id).toString().equals(r.id, true)) { "账单 ID 无效。" }
    }
}

/** Commit the ledger before publishing state or deleting any receipt files. */
class LedgerStore(private val directory: File) {
    private val ledger = File(directory, "ledger.json")
    @Volatile var snapshot = LedgerSnapshot(); private set
    val loadError: String?
    init {
        loadError = try {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建账本目录。" }
            if (ledger.exists()) snapshot = LedgerJson.decode(ledger.readText())
            null
        } catch (e: Exception) { "本地账本读取失败，原文件已保留，暂时禁止写入。\n${e.message}" }
    }
    fun imageFile(name: String): File {
        require(Regex("[0-9a-fA-F-]{36}\\.jpg").matches(name))
        return File(directory, name)
    }
    @Synchronized fun save(record: LedgerRecord, newImages: List<ByteArray> = emptyList()) {
        ensureWritable()
        LedgerJson.validateRecord(record)
        require(record.paymentMethod in snapshot.paymentMethods) { "请选择已有付款方式。" }
        require(record.imageNames.size + newImages.size <= 3) { "每笔最多添加 3 张图片。" }
        val previous = snapshot.records.find { it.id == record.id }
        require(record.imageNames.all { it in (previous?.imageNames ?: emptyList()) && imageFile(it).exists() }) { "附件不存在，请重新选择图片。" }
        val written = mutableListOf<String>()
        try {
            newImages.forEach { bytes ->
                require(bytes.isNotEmpty()) { "图片为空。" }
                val name = "${UUID.randomUUID()}.jpg"
                written.add(name)
                writeAtomic(imageFile(name), bytes)
            }
            val updated = record.copy(imageNames = record.imageNames + written)
            commit(snapshot.copy(records = snapshot.records.filterNot { it.id == record.id } + updated))
            previous?.imageNames?.filterNot { it in updated.imageNames }?.forEach { imageFile(it).delete() }
        } catch (e: Exception) { written.forEach { imageFile(it).delete() }; throw e }
    }
    @Synchronized fun delete(record: LedgerRecord) {
        val actual = snapshot.records.find { it.id == record.id } ?: return
        commit(snapshot.copy(records = snapshot.records.filterNot { it.id == record.id }))
        actual.imageNames.forEach { imageFile(it).delete() }
    }
    @Synchronized fun addMethod(text: String) {
        val name = text.trim()
        require(name.isNotEmpty() && name.codePointCount(0,name.length) <= 20) { "付款方式名称需为 1–20 个字。" }
        require(snapshot.paymentMethods.none { it.equals(name, true) }) { "这个付款方式已经存在。" }
        commit(snapshot.copy(paymentMethods = snapshot.paymentMethods + name))
    }
    @Synchronized fun deleteMethod(name: String) {
        require(snapshot.paymentMethods.size > 1) { "请至少保留一种付款方式。" }
        require(snapshot.records.none { it.paymentMethod == name }) { "已有账单使用此付款方式，请先修改相关账单。" }
        commit(snapshot.copy(paymentMethods = snapshot.paymentMethods - name))
    }
    @Synchronized fun importRecords(records: List<LedgerRecord>): ImportResult {
        ensureWritable()
        val ids = snapshot.records.map { it.id }.toMutableSet()
        val methods = snapshot.paymentMethods.toMutableList()
        val incoming = mutableListOf<LedgerRecord>()
        var skipped = 0
        records.forEach { r ->
            LedgerJson.validateRecord(r)
            require(r.imageNames.isEmpty()) { "导入账单不能包含图片文件。" }
            if (!ids.add(r.id)) skipped++
            else {
                val name = r.paymentMethod.trim()
                val method = methods.find { it.equals(name, true) } ?: name.also { methods.add(it) }
                incoming.add(r.copy(paymentMethod = method))
            }
        }
        if (incoming.isNotEmpty()) commit(snapshot.copy(records = snapshot.records + incoming, paymentMethods = methods))
        return ImportResult(incoming.size, skipped)
    }
    private fun ensureWritable() = check(loadError == null) { loadError ?: "账本不可写入。" }
    private fun commit(candidate: LedgerSnapshot) {
        ensureWritable()
        writeAtomic(ledger, LedgerJson.encode(candidate).toByteArray(Charsets.UTF_8))
        snapshot = candidate
    }
    private fun writeAtomic(target: File, bytes: ByteArray) {
        check(!target.isDirectory) { "保存位置不是文件，请检查本地存储。" }
        val temp = File(directory, ".${target.name}.tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            check(temp.renameTo(target)) { "账本保存失败，请检查存储空间。" }
        } finally { temp.delete() }
    }
}
