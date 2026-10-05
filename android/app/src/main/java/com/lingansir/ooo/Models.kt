package com.lingansir.ooo

import java.math.BigDecimal
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

enum class Kind(val title: String) { EXPENSE("支出"), INCOME("收入") }
enum class Category(val title: String, val glyph: String) {
    FOOD("餐饮", "餐"), SHOPPING("购物", "购"), TRANSPORT("交通", "行"), HOME("居家", "家"),
    ENTERTAINMENT("娱乐", "乐"), HEALTH("医疗", "医"), OTHER("其他", "·"),
    SALARY("工资", "薪"), BONUS("奖金", "奖"), GIFT("礼金", "礼"), REFUND("退款", "退");
    companion object {
        fun options(kind: Kind): List<Category> = if (kind == Kind.EXPENSE)
            listOf(FOOD, SHOPPING, TRANSPORT, HOME, ENTERTAINMENT, HEALTH, OTHER)
        else listOf(SALARY, BONUS, GIFT, REFUND, OTHER)
    }
}
data class LedgerRecord(
    val id: String = UUID.randomUUID().toString(), val kind: Kind, val amountMinor: Long,
    val category: Category, val paymentMethod: String, val date: LocalDate,
    val note: String = "", val imageNames: List<String> = emptyList()
)
data class LedgerSnapshot(val records: List<LedgerRecord> = emptyList(),
    val paymentMethods: List<String> = listOf("微信支付", "支付宝", "银行卡", "现金"))
data class ImportResult(val imported: Int, val skipped: Int)
object Money {
    const val MAX = 99_999_999_999L
    fun parse(text: String): Long? {
        val value = text.trim()
        if (!Regex("[0-9]{1,9}(\\.[0-9]{0,2})?").matches(value)) return null
        return runCatching { BigDecimal(value).movePointRight(2).longValueExact() }
            .getOrNull()?.takeIf { it in 1..MAX }
    }
    fun parseExcel(text: String): Long? {
        if (text.length > 40 || !Regex("[+]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matches(text)) return null
        return runCatching { BigDecimal(text).movePointRight(2).longValueExact() }
            .getOrNull()?.takeIf { it in 1..MAX }
    }
    fun input(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()
    fun formatted(minor: Long): String = DecimalFormat("#,##0.00").format(BigDecimal.valueOf(minor, 2))
}
fun List<LedgerRecord>.inMonth(month: YearMonth) = filter { YearMonth.from(it.date) == month }
    .sortedWith(compareByDescending<LedgerRecord> { it.date }.thenBy { it.id })
fun List<LedgerRecord>.total(kind: Kind): Long = filter { it.kind == kind }.sumOf { it.amountMinor }
