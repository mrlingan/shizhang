package com.lingansir.ooo

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ExcelTests {
    private fun record()=LedgerRecord(kind=Kind.EXPENSE,amountMinor=1299,category=Category.FOOD,paymentMethod="微信支付",date=LocalDate.of(2026,10,5),note="午餐 <家常> & 茶\n=不是公式 😀")
    private fun fixture(name: String)=javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
    private fun mutate(bytes: ByteArray, change: (String,String)->String): ByteArray {
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { out -> ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while(true) { val entry=input.nextEntry ?: break; val data=input.readBytes()
                out.putNextEntry(ZipEntry(entry.name)); out.write(change(entry.name,String(data,Charsets.UTF_8)).toByteArray()); out.closeEntry() }
        } }; return output.toByteArray()
    }
    @Test fun roundTripPreservesDatesCentsIdsUnicodeAndLiteralFormulaText() {
        val r=record().copy(imageNames=listOf("00000000-0000-0000-0000-000000000000.jpg"))
        val batch=ExcelWorkbook.read(ExcelWorkbook.export(listOf(r)),"账单.xlsx")
        assertTrue(batch.issues.toString(),batch.issues.isEmpty()); assertEquals(listOf(r.copy(imageNames=emptyList())),batch.records); assertEquals(1,batch.rowsWithImages)
    }
    @Test fun readsIPhoneTemplateAndExternalSharedStringsFixture() {
        assertTrue(ExcelWorkbook.read(fixture("template.xlsx"),"模板.xlsx").records.isEmpty())
        val batch=ExcelWorkbook.read(fixture("shared-strings.xlsx"),"外部.xlsx")
        assertTrue(batch.issues.toString(),batch.issues.isEmpty()); assertFalse(batch.records.isEmpty())
        assertEquals(Kind.EXPENSE,batch.records.first().kind)
    }
    @Test fun supports1900And1904DateSystemsAndRejectsFakeLeapDay() {
        val data=ExcelWorkbook.export(listOf(record()))
        fun serial(n: String, use1904: Boolean=false)=mutate(data) { path,xml ->
            when(path) {
                "xl/workbook.xml" -> if(use1904)xml.replace("date1904=\"0\"","date1904=\"1\"") else xml
                "xl/worksheets/sheet1.xml" -> xml.replace(Regex("<c r=\"A2\".*?</c>"),"<c r=\"A2\"><v>$n</v></c>")
                else -> xml
            }
        }
        assertEquals(LocalDate.of(1900,1,1),ExcelWorkbook.read(serial("1"),"x").records.single().date)
        assertEquals(LocalDate.of(1900,3,1),ExcelWorkbook.read(serial("61.5"),"x").records.single().date)
        assertEquals(LocalDate.of(1904,1,1),ExcelWorkbook.read(serial("0",true),"x").records.single().date)
        assertEquals(1,ExcelWorkbook.read(serial("60"),"x").issues.size)
    }
    @Test fun invalidAmountFormulaCategoryAndIdProduceRowIssues() {
        val data=ExcelWorkbook.export(listOf(record()))
        val invalid=mutate(data) { path,xml -> if(path.endsWith("sheet1.xml"))xml.replace("<v>12.99</v>","<f>1+1</f><v>12.999</v>") else xml }
        val batch=ExcelWorkbook.read(invalid,"bad.xlsx")
        assertTrue(batch.records.isEmpty()); assertEquals(2,batch.issues.single().row)
        val invalidCategory=mutate(data) { path,xml -> if(path.endsWith("sheet1.xml"))xml.replace("餐饮","工资") else xml }
        assertEquals(1,ExcelWorkbook.read(invalidCategory,"bad.xlsx").issues.size)
    }
    @Test fun duplicatedIdsAndTooManyRowsAreRejected() {
        val record=record()
        val batch=ExcelWorkbook.read(ExcelWorkbook.export(listOf(record,record)),"repeat.xlsx")
        assertEquals(1,batch.issues.size)
        assertThrows(IllegalArgumentException::class.java) { ExcelWorkbook.export(List(10001) { record }) }
    }
    @Test fun rejectsNonXlsxOversizedAndEntityFiles() {
        assertThrows(IllegalStateException::class.java) { ExcelWorkbook.read("not excel".toByteArray(),"a.xls") }
        assertThrows(IllegalArgumentException::class.java) { ExcelWorkbook.read(ByteArray(ExcelWorkbook.MAX_FILE+1),"a.xlsx") }
        val unsafe=mutate(ExcelWorkbook.export(listOf(record()))) { path,xml -> if(path=="xl/workbook.xml")"<!DOCTYPE workbook [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>$xml" else xml }
        assertThrows(IllegalArgumentException::class.java) { ExcelWorkbook.read(unsafe,"unsafe.xlsx") }
    }
}
