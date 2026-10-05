package com.lingansir.ooo

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class AppJourneyTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun resetTestLedger() { File(context.filesDir,"ledger").deleteRecursively() }
    private fun waitForWork(scenario: ActivityScenario<MainActivity>) {
        val done=AtomicBoolean(false)
        val deadline=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<deadline) {
            scenario.onActivity { done.set(ViewModelProvider(it)[AppModel::class.java].busy.value!=true) }
            if(done.get()) { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); return }
            Thread.sleep(50)
        }
        fail("后台操作未完成")
    }
    @Test fun addEditRotateReloadAndViewReports() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("记一笔")).perform(click())
            onView(withContentDescription("金额（元）")).perform(replaceText("123.45"),closeSoftKeyboard())
            scenario.recreate()
            onView(withContentDescription("金额（元）")).check(matches(withText("123.45")))
            onView(withText("保存账单")).perform(click()); waitForWork(scenario)
            assertEquals(12345L,LedgerStore(File(context.filesDir,"ledger")).snapshot.records.single().amountMinor)
            onView(withText("−123.45")).perform(scrollTo(),click())
            onView(withContentDescription("金额（元）")).perform(replaceText("0.29"),closeSoftKeyboard())
            onView(withText("保存账单")).perform(click()); waitForWork(scenario)
            onView(withContentDescription("统计")).perform(click())
            onView(allOf(withText("¥ 0.29"),hasSibling(withText("本月总支出")))).check(matches(isDisplayed()))
            assertEquals(29L,LedgerStore(File(context.filesDir,"ledger")).snapshot.records.single().amountMinor)
            scenario.recreate(); onView(allOf(withText("¥ 0.29"),hasSibling(withText("本月总支出")))).check(matches(isDisplayed()))
            onView(withContentDescription("付款方式")).perform(click())
            onView(withContentDescription("例如：招商信用卡 / 零钱包")).perform(scrollTo(),replaceText("My Wallet"),closeSoftKeyboard())
            onView(withText("添加")).perform(scrollTo(),click()); waitForWork(scenario)
            assertTrue("My Wallet" in LedgerStore(File(context.filesDir,"ledger")).snapshot.paymentMethods)
        }
    }
    @Test fun realPhotoCompressionStorageAndAttachmentCleanup() {
        val photo=File(context.cacheDir,"test-large.jpg")
        val bitmap=Bitmap.createBitmap(2400,1200,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it) }; bitmap.recycle()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("记一笔")).perform(click())
            onView(withContentDescription("金额（元）")).perform(replaceText("25.80"),closeSoftKeyboard())
            scenario.onActivity { activity ->
                val model=ViewModelProvider(activity)[AppModel::class.java]
                model.work("photos") { model.importPhotos(listOf(Uri.fromFile(photo))) }
            }
            waitForWork(scenario)
            scenario.recreate()
            onView(withText("保存账单")).perform(click()); waitForWork(scenario)
            val store=LedgerStore(File(context.filesDir,"ledger")); val saved=store.snapshot.records.single()
            assertEquals(1,saved.imageNames.size)
            val image=store.imageFile(saved.imageNames.single())
            val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeFile(image.path,options)
            assertEquals(1800,options.outWidth); assertEquals(900,options.outHeight)
            onView(withText("−25.80")).perform(scrollTo(),click())
            onView(withText("移除")).perform(scrollTo(),click())
            onView(withText("保存账单")).perform(click()); waitForWork(scenario)
            assertFalse(image.exists()); assertTrue(LedgerStore(File(context.filesDir,"ledger")).snapshot.records.single().imageNames.isEmpty())
        }
        photo.delete()
    }
    @Test fun androidXmlCodecReadsSharedStringsAndMatchesExcelTemplate() {
        val template=context.assets.open("ledger-template.xlsx").use { it.readBytes() }
        assertTrue(ExcelWorkbook.read(template,"模板.xlsx").issues.isEmpty())
        val r=LedgerRecord(kind=Kind.INCOME,amountMinor=888899,category=Category.SALARY,paymentMethod="银行卡",date=LocalDate.of(2026,10,5),note="工资 & 奖励")
        val batch=ExcelWorkbook.read(ExcelWorkbook.export(listOf(r)),"安卓账单.xlsx")
        assertEquals(listOf(r),batch.records); assertTrue(batch.issues.isEmpty())
    }
}
