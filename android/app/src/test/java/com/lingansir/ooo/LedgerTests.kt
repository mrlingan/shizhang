package com.lingansir.ooo

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

class LedgerTests {
    @get:Rule val folder=TemporaryFolder()
    private fun record(amount: Long=12345)=LedgerRecord(kind=Kind.EXPENSE,amountMinor=amount,category=Category.FOOD,paymentMethod="微信支付",date=LocalDate.of(2026,10,5))
    @Test fun decimalMoneyRemainsExactAndRejectsInvalidInput() {
        assertEquals(29L,Money.parse("0.29")); assertEquals(12340L,Money.parse(" 123.4 "))
        assertEquals(Money.MAX,Money.parse("999999999.99")); assertEquals(12300L,Money.parse("123."))
        listOf("0","-1","1.001","1e2","1000000000",".1","１２","NaN").forEach { assertNull(it,Money.parse(it)) }
        assertEquals(123L,Money.parseExcel("1.2300")); assertEquals(12300L,Money.parseExcel("1.23e2"))
        assertNull(Money.parseExcel("1.231")); assertNull(Money.parseExcel("1e999999"))
    }
    @Test fun savesReloadsEditsAndCleansOnlyRemovedImages() {
        val dir=folder.newFolder(); val store=LedgerStore(dir); val original=record()
        store.save(original,listOf(byteArrayOf(1,2,3),byteArrayOf(4,5)))
        val saved=store.snapshot.records.single(); assertEquals(2,saved.imageNames.size)
        assertTrue(store.imageFile(saved.imageNames[0]).exists())
        val reloaded=LedgerStore(dir); assertEquals(store.snapshot,reloaded.snapshot)
        reloaded.save(saved.copy(amountMinor=999,imageNames=listOf(saved.imageNames[1])))
        assertFalse(store.imageFile(saved.imageNames[0]).exists()); assertTrue(store.imageFile(saved.imageNames[1]).exists())
        reloaded.delete(reloaded.snapshot.records.single())
        assertTrue(LedgerStore(dir).snapshot.records.isEmpty()); assertFalse(store.imageFile(saved.imageNames[1]).exists())
    }
    @Test fun corruptLedgerIsPreservedAndCannotBeOverwritten() {
        val dir=folder.newFolder(); val file=File(dir,"ledger.json"); file.writeText("broken-original")
        val store=LedgerStore(dir); assertNotNull(store.loadError)
        assertThrows(IllegalStateException::class.java) { store.save(record(),listOf(byteArrayOf(1))) }
        assertThrows(IllegalStateException::class.java) { store.addMethod("钱包") }
        assertEquals("broken-original",file.readText()); assertEquals(listOf("ledger.json"),dir.list()!!.toList())
    }
    @Test fun failedCommitRollsBackStateAndNewImages() {
        val dir=folder.newFolder(); val store=LedgerStore(dir)
        File(dir,"ledger.json").mkdir()
        assertThrows(IllegalStateException::class.java) { store.save(record(),listOf(byteArrayOf(1,2))) }
        assertTrue(store.snapshot.records.isEmpty()); assertFalse(dir.list()!!.any { it.endsWith(".jpg") })
    }
    @Test fun paymentMethodsCannotBeDuplicatedDeletedWhileUsedOrRemoveLast() {
        val store=LedgerStore(folder.newFolder()); store.addMethod(" 零钱包 ")
        assertEquals("零钱包",store.snapshot.paymentMethods.last())
        assertThrows(IllegalArgumentException::class.java) { store.addMethod("零钱包") }
        store.save(record()); assertThrows(IllegalArgumentException::class.java) { store.deleteMethod("微信支付") }
        listOf("支付宝","银行卡","现金","零钱包").forEach(store::deleteMethod)
        assertThrows(IllegalArgumentException::class.java) { store.deleteMethod("微信支付") }
    }
    @Test fun importIsAtomicAndSkipsExistingIdsWithoutChangingImagesOrAmount() {
        val store=LedgerStore(folder.newFolder()); val first=record()
        store.save(first,listOf(byteArrayOf(1)))
        val original=store.snapshot.records.single()
        val fresh=record().copy(paymentMethod="新银行卡",kind=Kind.INCOME,category=Category.SALARY)
        val result=store.importRecords(listOf(first.copy(amountMinor=1),fresh))
        assertEquals(ImportResult(1,1),result); assertEquals(original,store.snapshot.records.find { it.id==first.id })
        assertTrue("新银行卡" in store.snapshot.paymentMethods)
        val before=store.snapshot
        assertThrows(IllegalArgumentException::class.java) { store.importRecords(listOf(record(),record().copy(amountMinor=0))) }
        assertEquals(before,store.snapshot)
    }
    @Test fun monthTotalsRespectKindAndMonth() {
        val records=listOf(record(),record(55).copy(kind=Kind.INCOME,category=Category.SALARY),record(77).copy(date=LocalDate.of(2026,9,30)))
        val month=records.inMonth(YearMonth.of(2026,10))
        assertEquals(12345L,month.total(Kind.EXPENSE)); assertEquals(55L,month.total(Kind.INCOME)); assertEquals(2,month.size)
    }
}
