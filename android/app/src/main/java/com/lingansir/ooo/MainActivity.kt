package com.lingansir.ooo

import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private object Palette {
    val background = Color.rgb(245,245,240)
    val ink = Color.rgb(31,51,56)
    val accent = Color.rgb(33,125,102)
    val mint = Color.rgb(205,235,214)
    val muted = Color.rgb(112,125,122)
    val expense = Color.rgb(189,97,64)
    val line = Color.rgb(235,238,231)
}

class MainActivity : AppCompatActivity() {
    private lateinit var model: AppModel
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var footer: LinearLayout
    private lateinit var progress: ProgressBar
    private var renderedDraftId: String? = null
    private val photoPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { handlePickerResult(PHOTOS, it.resultCode, it.data) }
    private val importPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { handlePickerResult(IMPORT, it.resultCode, it.data) }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { handlePickerResult(EXPORT, it.resultCode, it.data) }
    private var tab = 0
    private var month = YearMonth.now()
    private var reportMonth = YearMonth.now()
    private var exportMonth = YearMonth.now()
    private var reportKind = Kind.EXPENSE
    private var filter: Kind? = null
    private var search = ""
    private var monthlyExport = false
    private var recordLimit = 100
    private var rowsView: LinearLayout? = null
    private var countLabel: TextView? = null
    private val store get() = model.store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        model = ViewModelProvider(this)[AppModel::class.java]
        savedInstanceState?.let { state ->
            tab = state.getInt("tab")
            month = YearMonth.parse(state.getString("month") ?: month.toString())
            reportMonth = YearMonth.parse(state.getString("reportMonth") ?: reportMonth.toString())
            exportMonth = YearMonth.parse(state.getString("exportMonth") ?: exportMonth.toString())
            search = state.getString("search") ?: ""
            filter = state.getString("filter")?.let(Kind::valueOf)
            reportKind = Kind.valueOf(state.getString("reportKind") ?: Kind.EXPENSE.name)
            monthlyExport = state.getBoolean("monthlyExport")
            if (model.draft == null) state.getString("draft")?.let { text ->
                runCatching { EditorDraft.read(text) }.getOrNull()?.let { model.draft = it }
            }
        }
        root = column().apply { setBackgroundColor(Palette.background) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(Palette.accent); visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = column()
        root.addView(footer)
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    model.busy.value == true -> Unit
                    model.draft != null -> cancelEditor()
                    tab != 0 -> { tab = 0; render() }
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        model.busy.observe(this) { progress.visibility = if (it) View.VISIBLE else View.GONE }
        model.event.observe(this) { event ->
            if (event != null) {
                model.event.value = null
                handleEvent(event)
            }
        }
        render()
        if (savedInstanceState == null && store.loadError != null) message("无法读取账本", store.loadError!!)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab)
        outState.putString("month", month.toString()); outState.putString("reportMonth", reportMonth.toString())
        outState.putString("exportMonth", exportMonth.toString()); outState.putString("search", search)
        outState.putString("filter", filter?.name); outState.putString("reportKind", reportKind.name)
        outState.putBoolean("monthlyExport", monthlyExport); outState.putString("draft", model.draft?.json())
        super.onSaveInstanceState(outState)
    }
    private fun handleEvent(event: UiEvent) {
        when(event.type) {
            "error" -> message("操作失败", event.payload.toString())
            "saved" -> { model.clearDraft(); hideKeyboard(); render(); toast("账单已保存") }
            "deleted" -> { model.clearDraft(); hideKeyboard(); render(); toast("账单已删除") }
            "changed" -> { render(); toast(event.payload?.toString() ?: "已更新") }
            "photos" -> { render(); if (event.payload as Int > 0) message("图片导入提示", "有 ${event.payload} 张图片无法导入，请重新选择。") }
            "exportReady" -> {
                model.pendingExport = event.payload as ByteArray
                exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = XLSX
                    putExtra(Intent.EXTRA_TITLE, if (monthlyExport) "拾账账单-$exportMonth.xlsx" else "拾账全部账单.xlsx")
                })
            }
            "templateReady" -> {
                model.pendingExport = event.payload as ByteArray
                exportPicker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = XLSX; putExtra(Intent.EXTRA_TITLE, "拾账导入模板.xlsx")
                })
            }
            "exported" -> { model.pendingExport = null; toast("Excel 已保存") }
            "preview" -> { model.preview = event.payload as ImportBatch; showImportPreview() }
            "imported" -> {
                model.preview = null
                val result = event.payload as ImportResult
                render(); message("导入完成", "已导入 ${result.imported} 笔账单，跳过 ${result.skipped} 笔已有账单。")
            }
        }
    }
    private fun render() {
        val scrollY = if (renderedDraftId == model.draft?.id) (body.getChildAt(0) as? ScrollView)?.scrollY ?: 0 else 0
        renderedDraftId = model.draft?.id
        body.removeAllViews(); footer.removeAllViews(); rowsView = null; countLabel = null
        if (model.draft != null) {
            showEditor()
            (body.getChildAt(0) as? ScrollView)?.let { scroll -> scroll.post { scroll.scrollTo(0, scrollY) } }
            return
        }
        when(tab) { 0 -> showLedger(); 1 -> showReports(); 2 -> showMethods(); 3 -> showData() }
        if (tab == 0) footer.addView(button("记一笔", true, R.drawable.ic_plus) { openEditor(null) },
            LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(22), dp(8), dp(22), dp(12)) })
        footer.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(-1, dp(1)))
        val nav = row().apply { setPadding(dp(10), dp(4), dp(10), dp(4)); setBackgroundColor(Color.WHITE) }
        listOf("账本", "统计", "付款方式", "数据").forEachIndexed { index, label ->
            val item = column().apply {
                gravity = Gravity.CENTER; minimumHeight = dp(60)
                background = background(if (index == tab) Palette.mint else Color.TRANSPARENT, 18)
                contentDescription = label; isFocusable = true
                setOnClickListener { if (!busy()) { hideKeyboard(); tab = index; render() } }
            }
            val icon = ImageView(this).apply {
                setImageResource(listOf(R.drawable.ic_ledger, R.drawable.ic_reports, R.drawable.ic_payment, R.drawable.ic_data)[index])
                imageTintList = ColorStateList.valueOf(if (tab == index) Palette.accent else Palette.muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(23),dp(23)))
            item.addView(text(label, 11, tab == index, if (index == tab) Palette.accent else Palette.muted).apply { gravity = Gravity.CENTER })
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3),0,dp(3),0) })
        }
        footer.addView(nav)
    }
    private fun showLedger() {
        val page = page()
        val heading = row(Gravity.CENTER_VERTICAL)
        val title = column().apply { addView(text("拾账", 34, true)); addView(text("把每一笔生活，好好记下。", 13, false, Palette.muted)) }
        heading.addView(title, LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(ImageView(this).apply { setImageResource(R.drawable.ic_leaf); setPadding(dp(14),dp(14),dp(14),dp(14)); background = background(Palette.mint, 28) }, LinearLayout.LayoutParams(dp(54),dp(54)))
        page.addView(heading); gap(page, 22)
        page.addView(monthPicker(month) { month = it; recordLimit = 100; render() }); gap(page, 16)
        val records = store.snapshot.records.inMonth(month)
        val summary = column().apply {
            setPadding(dp(24),dp(24),dp(24),dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Palette.ink, Color.rgb(41,84,79))).apply { cornerRadius = dp(28).toFloat() }
        }
        val top = row(Gravity.CENTER_VERTICAL)
        top.addView(text("本月支出", 13, false, Palette.mint), LinearLayout.LayoutParams(0,-2,1f))
        top.addView(text("CNY / 人民币", 10, false, Palette.mint)); summary.addView(top); gap(summary, 18)
        summary.addView(text("¥ ${Money.formatted(records.total(Kind.EXPENSE))}", 36, true, Color.WHITE).apply { maxLines = 1; TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,18,36,1,2) })
        gap(summary,24)
        val bottom = row()
        listOf("↙ 收入" to records.total(Kind.INCOME), "结余" to (records.total(Kind.INCOME) - records.total(Kind.EXPENSE))).forEach { (label,total) ->
            bottom.addView(column().apply { addView(text(label,12,false,Palette.mint)); gap(this,7); addView(text("¥ ${Money.formatted(total)}",15,true,Color.WHITE).apply { maxLines=1; TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,10,15,1,2) }) }, LinearLayout.LayoutParams(0,-2,1f))
        }
        summary.addView(bottom); page.addView(summary); gap(page,24)
        val detailHeading = row(Gravity.CENTER_VERTICAL)
        detailHeading.addView(text("账单明细",20,true), LinearLayout.LayoutParams(0,-2,1f))
        countLabel = text("",13,false,Palette.muted); detailHeading.addView(countLabel); page.addView(detailHeading); gap(page,12)
        page.addView(segments(listOf("全部","支出","收入"), when(filter) { Kind.EXPENSE -> 1; Kind.INCOME -> 2; null -> 0 }) {
            filter = listOf(null,Kind.EXPENSE,Kind.INCOME)[it]; recordLimit = 100; render()
        }); gap(page,12)
        val searchBox = row(Gravity.CENTER_VERTICAL).apply { setPadding(dp(12),0,dp(10),0); background = background(Color.WHITE,16) }
        searchBox.addView(ImageView(this).apply { setImageResource(R.drawable.ic_search) }, LinearLayout.LayoutParams(dp(20),dp(20)))
        val input = input("搜索备注、分类或付款方式", search, single = true).apply { background = null; textSize = 13f }
        searchBox.addView(input, LinearLayout.LayoutParams(0,dp(50),1f))
        searchBox.addView(iconButton(R.drawable.ic_close,"清除搜索") { input.setText("") }, LinearLayout.LayoutParams(dp(40),dp(44)))
        input.onChange { search = it; recordLimit = 100; updateRecords() }
        page.addView(searchBox); gap(page,16)
        rowsView = column(); page.addView(rowsView); updateRecords()
    }
    private fun updateRecords() {
        val list = rowsView ?: return
        list.removeAllViews()
        val records = store.snapshot.records.inMonth(month).filter { (filter == null || it.kind == filter) &&
            (search.isBlank() || "${it.note} ${it.category.title} ${it.paymentMethod}".contains(search, true)) }
        countLabel?.text = "${records.size} 笔"
        if (records.isEmpty()) {
            empty(list, if (search.isNotBlank() || filter != null) "没有找到相关账单" else "这个月，还没有记录", "从一笔早餐、一次通勤开始。", R.drawable.ic_ledger)
            return
        }
        records.take(recordLimit).groupBy { it.date }.forEach { (date, dayRecords) ->
            val label = row(Gravity.CENTER_VERTICAL)
            label.addView(text(date.format(DateTimeFormatter.ofPattern("MM月dd日 EEEE", Locale.CHINA)),12,true,Palette.muted), LinearLayout.LayoutParams(0,-2,1f))
            label.addView(text("支出 ${Money.formatted(dayRecords.total(Kind.EXPENSE))}",11,false,Palette.muted))
            list.addView(label); gap(list,8)
            val card = card()
            dayRecords.forEachIndexed { i,r ->
                card.addView(recordRow(r).apply {
                    setOnClickListener { if (!busy()) openEditor(r) }
                    setOnLongClickListener { if (!busy()) deleteRecord(r); true }
                })
                if (i < dayRecords.lastIndex) divider(card)
            }
            list.addView(card); gap(list,18)
        }
        if (records.size > recordLimit) list.addView(button("加载更多（还剩 ${records.size-recordLimit} 笔）") { recordLimit += 100; updateRecords() })
    }
    private fun recordRow(r: LedgerRecord): View {
        val row = row(Gravity.CENTER_VERTICAL).apply { setPadding(0,dp(12),0,dp(12)); minimumHeight=dp(72) }
        row.addView(categoryBadge(r.category), LinearLayout.LayoutParams(dp(44),dp(44)))
        val info = column().apply { setPadding(dp(12),0,dp(8),0) }
        info.addView(text(r.note.ifEmpty { r.category.title },15,true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
        gap(info,5); info.addView(text(r.paymentMethod + if (r.imageNames.isEmpty()) "" else " · 图片 ${r.imageNames.size}",12,false,Palette.muted))
        row.addView(info, LinearLayout.LayoutParams(0,-2,1f))
        row.addView(text("${if(r.kind==Kind.INCOME) "+" else "−"}${Money.formatted(r.amountMinor)}",16,true,if(r.kind==Kind.INCOME) Palette.accent else Palette.ink).apply {
            gravity=Gravity.END; maxLines=1; TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,10,16,1,2)
        }, LinearLayout.LayoutParams(dp(112),dp(40)))
        return row
    }
    private fun showReports() {
        val page = page()
        title(page,"月度统计","看见生活的去向","每一笔记录，都是更了解自己的开始。",R.drawable.ic_reports)
        page.addView(monthPicker(reportMonth) { reportMonth=it; render() }); gap(page,14)
        page.addView(segments(listOf("支出","收入"),if(reportKind==Kind.EXPENSE)0 else 1) { reportKind=Kind.entries[it]; render() }); gap(page,20)
        val records=store.snapshot.records.inMonth(reportMonth).filter { it.kind==reportKind }
        val total=records.sumOf { it.amountMinor }
        val summary=card()
        summary.addView(text("本月总${reportKind.title}",13,false,Palette.muted)); gap(summary,12)
        summary.addView(text("¥ ${Money.formatted(total)}",32,true)); gap(summary,10)
        summary.addView(text("${records.size} 笔记录",12,false,Palette.muted)); page.addView(summary); gap(page,24)
        page.addView(text("分类分布",20,true)); gap(page,16)
        val totals=Category.options(reportKind).map { category -> category to records.filter { it.category==category }.sumOf { it.amountMinor } }
            .filter { it.second>0 }.sortedByDescending { it.second }
        if(totals.isEmpty()) empty(page,"还没有${reportKind.title}记录","记一笔，这里就能看见生活的去向。",R.drawable.ic_reports)
        else {
            val distribution=card()
            totals.forEachIndexed { i,(category,value) ->
                val row=row(Gravity.CENTER_VERTICAL)
                row.addView(categoryBadge(category),LinearLayout.LayoutParams(dp(42),dp(42)))
                row.addView(text(category.title,14,true).apply { setPadding(dp(12),0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
                row.addView(column().apply {
                    gravity=Gravity.END; addView(text("¥ ${Money.formatted(value)}",15,true))
                    addView(text(String.format(Locale.CHINA,"%.1f%%",value.toDouble()/total*100),12,false,Palette.muted))
                },LinearLayout.LayoutParams(-2,-2))
                distribution.addView(row); gap(distribution,12)
                distribution.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                    max=1000; progress=(value.toDouble()/total*1000).toInt(); progressTintList=ColorStateList.valueOf(Palette.accent)
                    progressBackgroundTintList=ColorStateList.valueOf(Palette.mint)
                    contentDescription="${category.title}占比 ${String.format(Locale.CHINA,"%.1f",value.toDouble()/total*100)}%"
                },LinearLayout.LayoutParams(-1,dp(7)))
                if(i<totals.lastIndex)gap(distribution,24)
            }
            page.addView(distribution)
        }
    }
    private fun showMethods() {
        val page=page()
        title(page,"付款方式","用你习惯的方式","微信、现金，或你自己的小钱包。\n付款方式可以自由添加，记账时直接选择。",R.drawable.ic_payment)
        val methods=card()
        store.snapshot.paymentMethods.forEachIndexed { i,method ->
            val row=row(Gravity.CENTER_VERTICAL)
            row.addView(ImageView(this).apply { setImageResource(R.drawable.ic_payment) },LinearLayout.LayoutParams(dp(24),dp(24)))
            row.addView(text(method,15,true).apply { setPadding(dp(12),0,0,0) },LinearLayout.LayoutParams(0,dp(56),1f))
            row.addView(iconButton(R.drawable.ic_delete,"删除$method") {
                AlertDialog.Builder(this).setTitle("删除“$method”？").setMessage("正在被账单使用的付款方式不能删除。")
                    .setNegativeButton("取消",null).setPositiveButton("删除") { _,_ -> model.work("changed") { store.deleteMethod(method); "付款方式已删除" } }.show()
            },LinearLayout.LayoutParams(dp(48),dp(48)))
            methods.addView(row); if(i<store.snapshot.paymentMethods.lastIndex)divider(methods)
        }
        page.addView(methods); gap(page,24)
        val add=card(); add.addView(text("添加付款方式",17,true)); gap(add,14)
        val name=input("例如：招商信用卡 / 零钱包",single=true)
        add.addView(name); gap(add,14)
        add.addView(button("添加",true,R.drawable.ic_plus) {
            hideKeyboard(); model.work("changed") { store.addMethod(name.text.toString()); "付款方式已添加" }
        }); page.addView(add); gap(page,20)
        page.addView(text("账单与图片保存在本机，卸载 App 会移除数据。",12,false,Palette.muted))
    }
    private fun showData() {
        val page=page()
        title(page,"导入与导出","账单，也能在 Excel 里整理","导出为 .xlsx，或把整理好的表格导回账本。",R.drawable.ic_data)
        val export=card(); export.addView(text("导出账单",18,true)); gap(export,16)
        export.addView(segments(listOf("全部账单","按月份"),if(monthlyExport)1 else 0) { monthlyExport=it==1; render() }); gap(export,14)
        if(monthlyExport) { export.addView(monthPicker(exportMonth) { exportMonth=it; render() }); gap(export,14) }
        val records=if(monthlyExport)store.snapshot.records.inMonth(exportMonth) else store.snapshot.records
        export.addView(text("当前范围 · ${records.size} 笔账单",13,false,Palette.muted)); gap(export,14)
        export.addView(button("导出 Excel",true) {
            if(records.isEmpty())message("还没有账单","记下第一笔账单后即可导出。")
            else model.work("exportReady") { ExcelWorkbook.export(records) }
        }); gap(export,12)
        export.addView(text("保存到系统文件，可在 Excel 或 WPS 中打开。包含账单 ID 和图片数量，不包含图片文件。",12,false,Palette.muted))
        page.addView(export); gap(page,20)
        val import=card(); import.addView(text("导入账单",18,true)); gap(import,12)
        import.addView(text("先下载模板填写，或使用从拾账导出的表格。新付款方式会自动加入账本。",14,false,Palette.muted)); gap(import,16)
        import.addView(button("下载 Excel 导入模板") { model.work("templateReady") { assets.open("ledger-template.xlsx").use { it.readBytes() } } }); gap(import,10)
        import.addView(button("选择 Excel 文件",true) {
            importPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="*/*"; putExtra(Intent.EXTRA_MIME_TYPES,arrayOf(XLSX,"application/octet-stream","application/zip")) })
        }); gap(import,12)
        import.addView(text("导入前先预览。存在错误行时整批不会写入；已有账单 ID 会跳过。没有 ID 的每一行会新增，重复导入会产生重复记录。",12,false,Palette.muted))
        page.addView(import); gap(page,20)
        page.addView(text("支持 .xlsx，每次最多 10,000 笔、文件最多 20 MB。\n不支持旧版 .xls、加密文件和公式单元格。",12,false,Palette.muted))
        if(model.preview!=null) { gap(page,12); page.addView(button("查看导入预览") { showImportPreview() }) }
    }
    private fun openEditor(record: LedgerRecord?) {
        model.draft=if(record==null)EditorDraft(method=store.snapshot.paymentMethods.first())
        else EditorDraft(record.id,true,record.kind,Money.input(record.amountMinor),record.category,record.paymentMethod,record.date,record.note,record.imageNames.toMutableList())
        render()
    }
    private fun showEditor() {
        val draft=model.draft ?: return
        val page=page()
        val header=row(Gravity.CENTER_VERTICAL)
        header.addView(text(if(draft.editing)"编辑账单" else "记下这一笔",25,true),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(iconButton(R.drawable.ic_close,"取消编辑") { cancelEditor() },LinearLayout.LayoutParams(dp(48),dp(48)))
        page.addView(header); gap(page,20)
        page.addView(segments(listOf("支出","收入"),if(draft.kind==Kind.EXPENSE)0 else 1) {
            draft.kind=Kind.entries[it]; if(draft.category !in Category.options(draft.kind))draft.category=Category.options(draft.kind).first(); hideKeyboard(); render()
        }); gap(page,20)
        val money=card().apply { background=background(Palette.mint,24) }
        money.addView(text("金额 · 人民币",13,false,Palette.accent)); gap(money,6)
        val moneyRow=row(Gravity.CENTER_VERTICAL)
        moneyRow.addView(text("¥",32,true,Palette.accent).apply { setPadding(0,0,dp(10),0) })
        val amount=input("0.00",draft.amount,single=true).apply {
            textSize=36f; setTextColor(Palette.ink); setTypeface(typeface,Typeface.BOLD); background=null
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; contentDescription="金额（元）"
        }
        amount.onChange { draft.amount=it }
        moneyRow.addView(amount,LinearLayout.LayoutParams(0,dp(68),1f)); money.addView(moneyRow)
        money.addView(text("手动输入，精确到分",11,false,Palette.accent)); page.addView(money); gap(page,20)
        val categories=card(); categories.addView(text("分类",16,true)); gap(categories,12)
        val options=Category.options(draft.kind)
        options.chunked(4).forEach { chunk ->
            val row=row()
            chunk.forEach { category ->
                val item=column().apply {
                    gravity=Gravity.CENTER; setPadding(dp(2),dp(10),dp(2),dp(10))
                    background=background(if(draft.category==category)Palette.mint else Color.TRANSPARENT,15)
                    contentDescription="分类：${category.title}"; isFocusable=true
                    setOnClickListener { if(!busy()) { draft.category=category; hideKeyboard(); render() } }
                }
                item.addView(categoryBadge(category),LinearLayout.LayoutParams(dp(38),dp(38))); gap(item,6)
                item.addView(text(category.title,12,draft.category==category))
                row.addView(item,LinearLayout.LayoutParams(0,-2,1f))
            }
            repeat(4-chunk.size) { row.addView(View(this),LinearLayout.LayoutParams(0,1,1f)) }
            categories.addView(row)
        }
        page.addView(categories); gap(page,20)
        val details=card()
        details.addView(text("付款方式",14,true)); gap(details,10)
        details.addView(button(draft.method) {
            val methods=store.snapshot.paymentMethods
            AlertDialog.Builder(this).setTitle("选择付款方式").setSingleChoiceItems(methods.toTypedArray(),methods.indexOf(draft.method)) { dialog,index -> draft.method=methods[index]; dialog.dismiss(); render() }
                .setNeutralButton("添加付款方式") { _,_ -> addMethodInEditor() }.setNegativeButton("取消",null).show()
        }); gap(details,18); details.addView(text("日期",14,true)); gap(details,8)
        details.addView(button(draft.date.toString()) {
            DatePickerDialog(this,{ _,y,m,d -> draft.date=LocalDate.of(y,m+1,d); render() },draft.date.year,draft.date.monthValue-1,draft.date.dayOfMonth).show()
        }); gap(details,18); details.addView(text("备注",14,true)); gap(details,10)
        details.addView(input("这笔钱，用在了什么地方？",draft.note).apply { minLines=2; maxLines=5; onChange { draft.note=it } })
        page.addView(details); gap(page,20)
        val photos=card(); photos.addView(text("图片附件 · ${draft.retained.size+draft.added.size}/3",16,true)); gap(photos,8)
        photos.addView(text("添加小票或生活照片，轻点查看大图。",12,false,Palette.muted)); gap(photos,14)
        val photoRow=row()
        val imageItems=draft.retained.map { it to false }+draft.added.map { it to true }
        imageItems.forEachIndexed { index,(name,temporary) ->
            val file=if(temporary)model.tempImage(name) else store.imageFile(name)
            val tile=column().apply { gravity=Gravity.CENTER; setPadding(dp(3),0,dp(3),0) }
            val image=ImageView(this).apply {
                scaleType=ImageView.ScaleType.CENTER_CROP; background=background(Palette.background,12); clipToOutline=true
                setImageBitmap(thumbnail(file)); contentDescription="查看第 ${index+1} 张图片"
                setOnClickListener { previewImage(file) }
            }
            tile.addView(image,LinearLayout.LayoutParams(-1,dp(95)))
            tile.addView(button("移除") {
                if(temporary) { draft.added.remove(name); model.tempImage(name).delete() } else draft.retained.remove(name)
                render()
            },LinearLayout.LayoutParams(-1,dp(44)))
            photoRow.addView(tile,LinearLayout.LayoutParams(0,-2,1f))
        }
        if(imageItems.isNotEmpty()) { photos.addView(photoRow); gap(photos,12) }
        photos.addView(button("从相册添加图片",false,R.drawable.ic_image) {
            if(imageItems.size>=3)message("图片已满","每笔最多添加 3 张图片，请先移除一张。")
            else photoPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type="image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
            })
        }); gap(photos,8); photos.addView(text("图片仅保存在本机，最长边压缩至 1800 像素。",11,false,Palette.muted)); page.addView(photos)
        if(draft.editing) { gap(page,20); page.addView(button("删除这笔账单") { store.snapshot.records.find { it.id==draft.id }?.let(::deleteRecord) }.apply { setTextColor(Palette.expense) }) }
        footer.addView(button("保存账单",true) {
            val value=Money.parse(draft.amount)
            if(value==null) { message("请检查金额","请输入大于 0、最多两位小数的金额，最大 999999999.99。") }
            else {
                hideKeyboard()
                val record=LedgerRecord(draft.id,draft.kind,value,draft.category,draft.method,draft.date,draft.note.trim(),draft.retained.toList())
                val files=draft.added.toList()
                model.work("saved") { store.save(record,files.map { model.tempImage(it).readBytes() }); null }
            }
        },LinearLayout.LayoutParams(-1,dp(56)).apply { setMargins(dp(22),dp(10),dp(22),dp(10)) })
    }
    private fun addMethodInEditor() {
        val input=input("例如：零钱包",single=true)
        val dialog=AlertDialog.Builder(this).setTitle("添加付款方式").setView(input).setNegativeButton("取消",null).setPositiveButton("添加",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name=input.text.toString().trim()
            dialog.dismiss(); model.work("changed") { store.addMethod(name); model.draft?.method=name; "付款方式已添加" }
        } }; dialog.show()
    }
    private fun cancelEditor() {
        if(busy())return
        AlertDialog.Builder(this).setTitle("放弃本次编辑？").setMessage("尚未保存的修改将丢弃。")
            .setNegativeButton("继续编辑",null).setPositiveButton("放弃") { _,_ -> model.clearDraft(); hideKeyboard(); render() }.show()
    }
    private fun deleteRecord(r: LedgerRecord) {
        AlertDialog.Builder(this).setTitle("删除这笔账单？").setMessage("${r.category.title} · ¥ ${Money.formatted(r.amountMinor)}\n账单及其图片将一起删除。")
            .setNegativeButton("取消",null).setPositiveButton("删除") { _,_ -> model.work("deleted") { store.delete(r); null } }.show()
    }
    private fun showImportPreview() {
        val batch=model.preview ?: return
        val ids=store.snapshot.records.map { it.id }.toSet()
        val incoming=batch.records.filterNot { it.id in ids }
        val methods=incoming.map { it.paymentMethod }.distinct().filter { name -> store.snapshot.paymentMethods.none { it.equals(name,true) } }
        val scroll=ScrollView(this)
        val content=column().apply { setPadding(dp(24),dp(12),dp(24),dp(20)) }
        content.addView(text(batch.filename,13,false,Palette.muted)); gap(content,14)
        content.addView(text("新增账单：${incoming.size} 笔\n已有账单，将跳过：${batch.records.size-incoming.size} 笔\n错误行：${batch.issues.size} 行",16,true))
        if(methods.isNotEmpty()) { gap(content,12); content.addView(text("新增付款方式：${methods.joinToString("、")}",13)) }
        if(batch.rowsWithImages>0) { gap(content,12); content.addView(text("${batch.rowsWithImages} 笔标记有图片。Excel 不含图片，新导入账单没有附件；已有账单的图片会保留。",12,false,Palette.muted)) }
        if(batch.issues.isNotEmpty()) {
            gap(content,20); content.addView(text("请在 Excel 修正后重新选择文件",15,true,Palette.expense))
            batch.issues.take(100).forEach { gap(content,10); content.addView(text("第 ${it.row} 行：${it.message}",13,false,Palette.expense)) }
            if(batch.issues.size>100)content.addView(text("还有 ${batch.issues.size-100} 行错误。",13))
        }
        if(incoming.isNotEmpty()) { gap(content,20); content.addView(text("账单示例（最多 10 笔）",15,true)) }
        incoming.take(10).forEach { gap(content,10); content.addView(text("${it.date} · ${it.category.title}\n${it.kind.title} ¥ ${Money.formatted(it.amountMinor)} · ${it.paymentMethod}",13)) }
        scroll.addView(content)
        val dialog=AlertDialog.Builder(this).setTitle("确认导入").setView(scroll).setNegativeButton("取消",null)
            .setPositiveButton("导入 ${incoming.size} 笔") { _,_ -> model.work("imported") { store.importRecords(batch.records) } }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=batch.issues.isEmpty() && incoming.isNotEmpty() && !busy() }
        dialog.show()
    }
    private fun handlePickerResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(resultCode!=RESULT_OK) { if(requestCode==EXPORT)model.pendingExport=null; return }
        when(requestCode) {
            PHOTOS -> {
                val uris=mutableListOf<Uri>()
                data?.clipData?.let { clips -> repeat(clips.itemCount) { uris.add(clips.getItemAt(it).uri) } }
                if(uris.isEmpty())data?.data?.let { uris.add(it) }
                val capacity=model.draft?.let { 3-it.retained.size-it.added.size } ?: 0
                if(uris.size>capacity)toast("仅添加前 $capacity 张图片，每笔最多 3 张")
                model.work("photos") { model.importPhotos(uris.distinct()) }
            }
            IMPORT -> data?.data?.let { uri -> model.work("preview") {
                val name=documentName(uri)
                require(name.endsWith(".xlsx",true)) { "请选择 .xlsx 文件，旧版 .xls 不受支持。" }
                contentResolver.openInputStream(uri)?.use { ExcelWorkbook.read(ExcelWorkbook.readLimited(it),name) } ?: error("无法读取文件。")
            } }
            EXPORT -> data?.data?.let { uri ->
                val bytes=model.pendingExport
                if(bytes==null)message("请重新导出","应用曾在后台关闭，请重新点击导出 Excel。")
                else model.work("exported") { contentResolver.openOutputStream(uri,"wt")?.use { it.write(bytes); it.flush() } ?: error("无法写入所选位置。"); null }
            }
        }
    }
    private fun documentName(uri: Uri): String = contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
        if(it.moveToFirst())it.getString(0) else null
    } ?: "所选文件.xlsx"
    private fun previewImage(file: File) {
        val dialog=Dialog(this,android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        val layout=column().apply { setBackgroundColor(Palette.ink) }
        layout.addView(button("关闭预览") { dialog.dismiss() },LinearLayout.LayoutParams(-1,dp(56)))
        layout.addView(ZoomImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(file.absolutePath)); contentDescription="图片预览，可双指缩放"
        },LinearLayout.LayoutParams(-1,0,1f))
        layout.addView(text("双指缩放 · 拖动查看",12,false,Color.WHITE).apply { gravity=Gravity.CENTER; setPadding(0,dp(12),0,dp(12)) })
        dialog.setContentView(layout); dialog.show()
        WindowCompat.setDecorFitsSystemWindows(dialog.window!!,true)
    }
    private fun thumbnail(file: File) = BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply { inSampleSize=4 })
    private fun page(): LinearLayout {
        val scroll=ScrollView(this).apply { isFillViewport=false; clipToPadding=false; overScrollMode=View.OVER_SCROLL_NEVER }
        val content=column().apply { setPadding(dp(22),dp(22),dp(22),dp(22)) }
        scroll.addView(content); body.addView(scroll,FrameLayout.LayoutParams(-1,-1)); return content
    }
    private fun title(page: LinearLayout, label: String, heading: String, subtitle: String, icon: Int) {
        val top=row(Gravity.CENTER_VERTICAL)
        top.addView(ImageView(this).apply { setImageResource(icon); background=background(Palette.mint,16); setPadding(dp(12),dp(12),dp(12),dp(12)) },LinearLayout.LayoutParams(dp(48),dp(48)))
        top.addView(text(label,13,true,Palette.accent).apply { setPadding(dp(12),0,0,0) }); page.addView(top); gap(page,20)
        page.addView(text(heading,25,true)); gap(page,8); page.addView(text(subtitle,14,false,Palette.muted)); gap(page,24)
    }
    private fun monthPicker(value: YearMonth, changed: (YearMonth) -> Unit): View {
        val row=row(Gravity.CENTER_VERTICAL)
        row.addView(iconButton(R.drawable.ic_chevron_left,"上个月") { if(value.year>1 || value.monthValue>1)changed(value.minusMonths(1)) },LinearLayout.LayoutParams(dp(48),dp(48)))
        row.addView(text("${value.year}年${String.format(Locale.CHINA,"%02d",value.monthValue)}月",15,true).apply {
            gravity=Gravity.CENTER; setOnClickListener { if(!busy())DatePickerDialog(this@MainActivity,{ _,y,m,_ -> changed(YearMonth.of(y,m+1)) },value.year,value.monthValue-1,1).show() }
            contentDescription="选择月份"
        },LinearLayout.LayoutParams(0,dp(48),1f))
        row.addView(iconButton(R.drawable.ic_chevron_right,"下个月") { if(value.year<9999 || value.monthValue<12)changed(value.plusMonths(1)) },LinearLayout.LayoutParams(dp(48),dp(48)))
        return row
    }
    private fun segments(labels: List<String>, selected: Int, changed: (Int) -> Unit): View {
        val row=row().apply { background=background(Palette.line,16); setPadding(dp(4),dp(4),dp(4),dp(4)) }
        labels.forEachIndexed { index,label ->
            row.addView(text(label,14,index==selected,if(index==selected)Color.WHITE else Palette.muted).apply {
                gravity=Gravity.CENTER; background=background(if(index==selected)Palette.ink else Color.TRANSPARENT,13)
                contentDescription=label; isFocusable=true; isSelected=index==selected
                setOnClickListener { if(!busy())changed(index) }
            },LinearLayout.LayoutParams(0,dp(40),1f))
        }
        return row
    }
    private fun empty(parent: LinearLayout, heading: String, subtitle: String, icon: Int) {
        val empty=column().apply { gravity=Gravity.CENTER; setPadding(dp(16),dp(38),dp(16),dp(38)) }
        empty.addView(ImageView(this).apply { setImageResource(icon); imageTintList=ColorStateList.valueOf(Palette.muted) },LinearLayout.LayoutParams(dp(44),dp(44)))
        gap(empty,16); empty.addView(text(heading,17,true).apply { gravity=Gravity.CENTER }); gap(empty,8)
        empty.addView(text(subtitle,13,false,Palette.muted).apply { gravity=Gravity.CENTER }); parent.addView(empty)
    }
    private fun categoryBadge(category: Category)=text(category.glyph,18,true,Palette.accent).apply { gravity=Gravity.CENTER; background=background(Color.rgb(229,242,229),14) }
    private fun card()=column().apply { setPadding(dp(20),dp(20),dp(20),dp(20)); background=background(Color.WHITE,24) }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(-1,-2) }
    private fun row(gravityValue: Int=Gravity.TOP)=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=gravityValue; layoutParams=LinearLayout.LayoutParams(-1,-2) }
    private fun text(value: String, size: Int, bold: Boolean=false, color: Int=Palette.ink)=AppCompatTextView(this).apply {
        text=value; textSize=size.toFloat(); setTextColor(color); if(bold)setTypeface(typeface,Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(),1f); gravity=Gravity.CENTER_VERTICAL; includeFontPadding=false
    }
    private fun input(hintValue: String, value: String="", single: Boolean=false)=EditText(this).apply {
        hint=hintValue; setText(value); textSize=15f; setTextColor(Palette.ink); setHintTextColor(Palette.muted)
        setPadding(dp(14),dp(12),dp(14),dp(12)); background=background(Palette.background,12)
        isSingleLine=single; inputType=if(single)InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minimumHeight=dp(52); contentDescription=hintValue
    }
    private fun EditText.onChange(changed: (String) -> Unit) { addTextChangedListener(object: TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s.toString()) }
        override fun afterTextChanged(s: Editable?) {}
    }) }
    private fun button(label: String, primary: Boolean=false, iconResource: Int?=null, action: () -> Unit)=MaterialButton(this).apply {
        text=label; textSize=15f; isAllCaps=false; setTypeface(typeface,Typeface.BOLD); cornerRadius=dp(16)
        insetTop=0; insetBottom=0; minimumHeight=dp(52); minHeight=dp(52); elevation=0f
        backgroundTintList=ColorStateList.valueOf(if(primary)Palette.accent else Palette.background)
        setTextColor(if(primary)Color.WHITE else Palette.accent); contentDescription=label
        if(iconResource!=null) { setIconResource(iconResource); iconTint=ColorStateList.valueOf(if(primary)Color.WHITE else Palette.accent); iconGravity=MaterialButton.ICON_GRAVITY_TEXT_START; iconSize=dp(20) }
        setOnClickListener { if(!busy())action() }
    }
    private fun iconButton(icon: Int, label: String, action: () -> Unit)=ImageButton(this).apply {
        setImageResource(icon); background=background(Color.TRANSPARENT,14); setPadding(dp(12),dp(12),dp(12),dp(12))
        contentDescription=label; setOnClickListener { if(!busy())action() }
    }
    private fun background(color: Int,radius: Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
    private fun gap(parent: LinearLayout,size: Int) { parent.addView(Space(this),LinearLayout.LayoutParams(1,dp(size))) }
    private fun divider(parent: LinearLayout) { parent.addView(View(this).apply { setBackgroundColor(Palette.line) },LinearLayout.LayoutParams(-1,dp(1))) }
    private fun message(title: String,body: String) { AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("知道了",null).show() }
    private fun toast(message: String) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show() }
    private fun busy()=model.busy.value==true
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken,0); currentFocus?.clearFocus() }
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    companion object {
        private const val XLSX="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val PHOTOS=101
        private const val IMPORT=102
        private const val EXPORT=103
    }
}
