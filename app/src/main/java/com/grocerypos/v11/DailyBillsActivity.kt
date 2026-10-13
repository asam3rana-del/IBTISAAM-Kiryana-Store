package com.grocerypos.v11.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.DailyBillsRepository
import com.grocerypos.v11.DailyParty
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.RentConfig
import com.grocerypos.v11.RentLogic
import com.grocerypos.v11.RentRepository
import com.grocerypos.v11.dailyBillText
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * "Daily Bills" — ported from the Flutter app's DailyBillsScreen.
 * Each customer's sales for one day as ONE bill with previous / closing balance; tap a customer for
 * the full bill and Share. Also Daily Rent set-up and the holiday (remove rent) actions.
 * Admin/Manager only. Nothing here changes sales, stock or balances except the Daily Rent actions.
 */
class DailyBillsActivity : ThemedActivity() {

    private var bg = "#F3F2FA"
    private var cardBg = "#FFFFFF"
    private var primary = "#4A3AFF"
    private var textDark = "#1A1A2E"
    private var textGray = "#8A8A9E"
    private var border = "#E7E5F3"
    private var teal = "#0C7C71"
    private var blue = "#2F6BFF"
    private var red = "#D93025"

    private lateinit var repo: DailyBillsRepository
    private lateinit var rent: RentRepository
    private var dayMillis: Long = System.currentTimeMillis()
    private var rows: List<DailyParty> = emptyList()

    private lateinit var dateText: TextView
    private lateinit var nextBtn: TextView
    private lateinit var summaryBox: LinearLayout
    private lateinit var listBox: LinearLayout

    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rs(v: Double) = "Rs " + if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
    private fun balColor(v: Double) = if (v < 0) red else teal
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun loadThemeColors() {
        val p = com.grocerypos.v11.util.ThemeManager.palette(this)
        bg = p.bg; cardBg = p.cardWhite; primary = p.flatPurpleFg
        textDark = p.textDark; textGray = p.textMuted; border = p.border
        teal = p.flatTealFg; blue = p.flatBlueFg; red = p.red
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.parseColor(cardBg)); setStroke(1, Color.parseColor(border)); cornerRadius = 36f
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
    }

    private fun tv(text: String, size: Float = 13f, color: String = textDark, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    /** "label ........ value" row. */
    private fun kv(k: String, v: String, color: String = textDark, bold: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(3), 0, dp(3))
        addView(tv(k, 13f, textGray, bold), LinearLayout.LayoutParams(0, -2, 1f))
        addView(tv(v, 13.5f, color, true))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor(border))
        layoutParams = LinearLayout.LayoutParams(-1, 1).apply { setMargins(0, dp(6), 0, dp(6)) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val role = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (role != "admin" && role != "manager") {
            toast("Sirf Admin/Manager is screen ko access kar sakte hain")
            finish()
            return
        }
        loadThemeColors()
        repo = DailyBillsRepository(this)
        rent = RentRepository(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        // Header with Daily Rent + holiday menu
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(12), dp(16))
            background = GradientDrawable().apply { setColor(Color.parseColor(primary)); cornerRadius = 40f }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(tv(Loc.t(this, "Daily Bills", "روزانہ بلز"), 19f, "#FFFFFF", true))
        titleBox.addView(tv(Loc.t(this, "Each customer's day as one bill + balance", "ہر کسٹمر کا دن کا ایک بل اور بیلنس"), 12f, "#DAD5FF"))
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        val rentBtn = tv(Loc.t(this, "Daily Rent", "روزانہ کرایہ"), 12f, "#FFFFFF", true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")); cornerRadius = 40f }
            setOnClickListener { openRentDialog() }
        }
        header.addView(rentBtn)
        val menuBtn = tv("⋮", 22f, "#FFFFFF", true).apply {
            setPadding(dp(14), 0, dp(6), 0)
            setOnClickListener { v ->
                val m = PopupMenu(this@DailyBillsActivity, v)
                m.menu.add(0, 1, 0, Loc.t(this@DailyBillsActivity, "Remove this day's rent (holiday)", "اس دن کا کرایہ ہٹائیں (چھٹی)"))
                m.menu.add(0, 2, 1, Loc.t(this@DailyBillsActivity, "Put this day's rent back", "اس دن کا کرایہ واپس لگائیں"))
                m.setOnMenuItemClickListener { dayOff(it.itemId == 1); true }
                m.show()
            }
        }
        header.addView(menuBtn)
        root.addView(header)

        // Date bar
        val dateBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardBg)); setStroke(1, Color.parseColor(border)); cornerRadius = 36f
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
        }
        val prev = tv("‹", 26f, textDark, true).apply { setPadding(dp(18), dp(6), dp(18), dp(6)); setOnClickListener { shift(-1) } }
        dateText = tv("", 15f, textDark, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { pickDate() } }
        nextBtn = tv("›", 26f, textDark, true).apply { setPadding(dp(18), dp(6), dp(18), dp(6)); setOnClickListener { shift(1) } }
        dateBar.addView(prev)
        dateBar.addView(dateText, LinearLayout.LayoutParams(0, -2, 1f))
        dateBar.addView(nextBtn)
        root.addView(dateBar)

        summaryBox = card().apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        root.addView(summaryBox)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listBox) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        load()
    }

    private fun isToday(): Boolean = RentLogic.startOfDay(dayMillis) == RentLogic.startOfDay(System.currentTimeMillis())

    private fun shift(days: Int) {
        val c = Calendar.getInstance().apply { timeInMillis = dayMillis; add(Calendar.DAY_OF_MONTH, days) }
        if (c.timeInMillis > System.currentTimeMillis() + 24L * 3600_000) return
        dayMillis = c.timeInMillis
        load()
    }

    private fun pickDate() {
        val c = Calendar.getInstance().apply { timeInMillis = dayMillis }
        DatePickerDialog(this, { _, y, m, d ->
            dayMillis = Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }.timeInMillis
            load()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun load() {
        dateText.text = (if (isToday()) Loc.t(this, "Today", "آج") + " • " else "") + dateFmt.format(Date(dayMillis))
        nextBtn.alpha = if (isToday()) 0.3f else 1f
        lifecycleScope.launch {
            rows = try { repo.loadDay(dayMillis) } catch (e: Exception) { emptyList() }
            repo.lastRentError?.let { toast("Rent bills nahi ban sake: $it") }
            render()
        }
    }

    private fun render() {
        listBox.removeAllViews()
        summaryBox.removeAllViews()
        if (rows.isEmpty()) {
            summaryBox.visibility = View.GONE
            listBox.addView(tv(Loc.t(this, "No sales on this day", "اس دن کوئی سیل نہیں"), 14f, textGray, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(30), 0, 0)
            })
            return
        }
        summaryBox.visibility = View.VISIBLE
        fun stat(label: String, value: String, color: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(tv(label, 11.5f, textGray)); addView(tv(value, 14.5f, color, true))
        }
        summaryBox.addView(stat(Loc.t(this, "Customers", "کسٹمرز"), "${rows.size}", textDark), LinearLayout.LayoutParams(0, -2, 1f))
        summaryBox.addView(stat(Loc.t(this, "Total sale", "کل سیل"), rs(rows.sumOf { it.salesTotal }), blue), LinearLayout.LayoutParams(0, -2, 1f))
        summaryBox.addView(stat(Loc.t(this, "Total received", "کل وصول"), rs(rows.sumOf { it.receivedTotal }), teal), LinearLayout.LayoutParams(0, -2, 1f))
        rows.forEach { listBox.addView(partyCard(it)) }
    }

    private fun partyCard(r: DailyParty): LinearLayout = card().apply {
        val top = LinearLayout(this@DailyBillsActivity).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(tv(r.name, 15f, textDark, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(tv("${r.billCount} ${Loc.t(this@DailyBillsActivity, "bills", "بلز")}", 12f, textGray))
        addView(top)
        addView(kv(Loc.t(this@DailyBillsActivity, "Previous balance", "پچھلا بیلنس"), rs(r.previousBalance), balColor(r.previousBalance)))
        addView(kv(Loc.t(this@DailyBillsActivity, "Today's sale", "آج کی سیل"), "+ ${rs(r.salesTotal)}", blue))
        if (r.receivedTotal > 0) addView(kv(Loc.t(this@DailyBillsActivity, "Received today", "آج وصول"), "- ${rs(r.receivedTotal)}", teal))
        addView(divider())
        addView(kv(Loc.t(this@DailyBillsActivity, "Closing balance", "آج کا بیلنس"), rs(r.closingBalance), balColor(r.closingBalance), true))
        setOnClickListener { showDetail(r) }
    }

    private fun num(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

    private fun showDetail(r: DailyParty) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(6)) }
        box.addView(tv(r.name, 19f, textDark, true))
        box.addView(tv(dateFmt.format(Date(dayMillis)), 13f, textGray))
        box.addView(kv(Loc.t(this, "Previous balance", "پچھلا بیلنس"), rs(r.previousBalance), balColor(r.previousBalance), true))
        box.addView(divider())
        var dialog: AlertDialog? = null
        for (b in r.bills) {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(tv("${b.invoice}  •  ${timeFmt.format(Date(b.time))}", 13f, textDark, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(tv(rs(b.total), 13f, blue, true))
            box.addView(head)
            for (i in b.items) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(2), 0, 0) }
                line.addView(tv("${i.product}  ${num(i.qty)}${if (i.unit.isEmpty()) "" else " ${i.unit}"} × ${num(i.unitPrice)}", 12.5f, textGray), LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(tv(num(i.amount), 12.5f, textDark))
                box.addView(line)
            }
            if (b.paid > 0) box.addView(tv("${Loc.t(this, "Paid", "ادا")}: ${rs(b.paid)}", 12.5f, teal).apply { setPadding(dp(8), dp(2), 0, 0) })
            if (RentLogic.isRentInvoice(b.invoice)) {
                box.addView(Button(this).apply {
                    text = Loc.t(this@DailyBillsActivity, "Remove this customer's rent today (holiday)", "اس کسٹمر کا آج کا کرایہ ہٹائیں (چھٹی)")
                    isAllCaps = false
                    setOnClickListener { dialog?.dismiss(); billOff(b.invoice) }
                })
            }
            box.addView(divider())
        }
        for (p in r.payments) box.addView(kv("${Loc.t(this, "Payment received", "ادائیگی ملی")} • ${timeFmt.format(Date(p.time))}", "- ${rs(p.amount)}", teal))
        box.addView(kv(Loc.t(this, "Today's sale", "آج کی سیل"), "+ ${rs(r.salesTotal)}", blue))
        if (r.receivedTotal > 0) box.addView(kv(Loc.t(this, "Received today", "آج وصول"), "- ${rs(r.receivedTotal)}", teal))
        box.addView(divider())
        box.addView(kv(Loc.t(this, "Closing balance", "آج کا بیلنس"), rs(r.closingBalance), balColor(r.closingBalance), true))

        dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(Loc.t(this, "Share bill", "بل شیئر کریں")) { _, _ ->
                lifecycleScope.launch {
                    val text = dailyBillText(r, dayMillis, repo.shopName())
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                    }, Loc.t(this@DailyBillsActivity, "Share bill", "بل شیئر کریں")))
                }
            }
            .setNegativeButton(Loc.t(this, "Close", "بند کریں"), null)
            .show()
    }

    // ---- Daily Rent -----------------------------------------------------------------------

    private fun billOff(invoice: String) {
        lifecycleScope.launch {
            try {
                rent.setRentBillOff(invoice, true)
                toast(Loc.t(this@DailyBillsActivity, "Rent removed", "کرایہ ہٹا دیا گیا"))
            } catch (e: Exception) { toast(e.message ?: e.toString()) }
            load()
        }
    }

    private fun dayOff(off: Boolean) {
        lifecycleScope.launch {
            try {
                val n = rent.setDayOff(dayMillis, off)
                toast(
                    if (n == 0) Loc.t(this@DailyBillsActivity, "No rent bill found for this day", "اس دن کوئی کرایہ بل نہیں ملا")
                    else if (off) Loc.t(this@DailyBillsActivity, "Rent removed for $n customers (holiday)", "$n کسٹمرز کا کرایہ ہٹا دیا (چھٹی)")
                    else Loc.t(this@DailyBillsActivity, "Rent put back for $n customers", "$n کسٹمرز کا کرایہ واپس لگا دیا")
                )
            } catch (e: Exception) { toast(e.message ?: e.toString()) }
            load()
        }
    }

    private fun openRentDialog() {
        lifecycleScope.launch {
            val configs = try { rent.listConfigs() } catch (e: Exception) { toast(e.message ?: e.toString()); return@launch }
            val box = LinearLayout(this@DailyBillsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), 0) }
            box.addView(tv(Loc.t(this@DailyBillsActivity,
                "Daily rent is added to the customer's Daily Bill and balance every day. Remove a holiday's rent from the Daily Bills menu. Set rent on ONE device only.",
                "روز کا کرایہ ہر دن کسٹمر کے ڈیلی بل اور بیلنس میں خود جڑتا ہے۔ چھٹی والے دن کا کرایہ ڈیلی بلز کے مینو سے ہٹا دیں۔ کرایہ صرف ایک ڈیوائس پر سیٹ کریں۔"), 12.5f, textGray))
            if (configs.isEmpty()) {
                box.addView(tv(Loc.t(this@DailyBillsActivity, "No rent customers yet", "ابھی کوئی کرایہ کسٹمر نہیں"), 13f, textGray).apply { setPadding(0, dp(14), 0, dp(6)) })
            }
            var dlg: AlertDialog? = null
            for (c in configs) {
                val row = LinearLayout(this@DailyBillsActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(4)); gravity = Gravity.CENTER_VERTICAL }
                val info = LinearLayout(this@DailyBillsActivity).apply { orientation = LinearLayout.VERTICAL }
                info.addView(tv(c.name, 14.5f, textDark, true))
                info.addView(tv("${rs(c.dailyRent)} / ${Loc.t(this@DailyBillsActivity, "day", "دن")} • ${dateFmt.format(Date(c.startDay))}", 12.5f, textGray))
                info.setOnClickListener { dlg?.dismiss(); editRent(c) }
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(tv(Loc.t(this@DailyBillsActivity, "Stop", "بند"), 13f, red, true).apply {
                    setPadding(dp(12), dp(6), dp(4), dp(6))
                    setOnClickListener { dlg?.dismiss(); stopRent(c) }
                })
                box.addView(row)
            }
            dlg = AlertDialog.Builder(this@DailyBillsActivity)
                .setTitle(Loc.t(this@DailyBillsActivity, "Daily Rent", "روزانہ کرایہ"))
                .setView(ScrollView(this@DailyBillsActivity).apply { addView(box) })
                .setPositiveButton(Loc.t(this@DailyBillsActivity, "New rent customer", "نیا کرایہ کسٹمر")) { _, _ -> editRent(null) }
                .setNegativeButton(Loc.t(this@DailyBillsActivity, "Close", "بند کریں"), null)
                .show()
        }
    }

    private fun stopRent(c: RentConfig) {
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Stop rent?", "کرایہ بند کریں؟"))
            .setMessage(Loc.t(this, "${c.name}'s rent will not be added from now on. Old bills and balance stay as they are.",
                "${c.name} کا کرایہ آگے سے نہیں لگے گا۔ پرانے بل اور بیلنس جیسے ہیں ویسے رہیں گے۔"))
            .setPositiveButton(Loc.t(this, "Stop", "بند کریں")) { _, _ ->
                lifecycleScope.launch {
                    try { rent.removeConfig(c.customerId) } catch (e: Exception) { toast(e.message ?: e.toString()) }
                    load()
                }
            }
            .setNegativeButton(Loc.t(this, "No", "نہیں"), null)
            .show()
    }

    private fun editRent(existing: RentConfig?) {
        lifecycleScope.launch {
            val customers = PosDatabase.get(this@DailyBillsActivity).customerDao().allList()
            if (customers.isEmpty()) { toast(Loc.t(this@DailyBillsActivity, "Add a customer first", "پہلے کسٹمر شامل کریں")); return@launch }
            var start = existing?.startDay ?: System.currentTimeMillis()
            val box = LinearLayout(this@DailyBillsActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0) }
            val spinner = Spinner(this@DailyBillsActivity).apply {
                adapter = ArrayAdapter(this@DailyBillsActivity, android.R.layout.simple_spinner_dropdown_item, customers.map { it.name })
                val idx = customers.indexOfFirst { it.id == existing?.customerId }
                if (idx >= 0) setSelection(idx)
                isEnabled = existing == null // the customer of an existing rent can't be swapped
            }
            val amount = EditText(this@DailyBillsActivity).apply {
                hint = Loc.t(this@DailyBillsActivity, "Daily rent (Rs)", "روز کا کرایہ (روپے)")
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                if (existing != null) setText(num(existing.dailyRent))
            }
            val dateBtn = Button(this@DailyBillsActivity).apply { isAllCaps = false }
            fun refreshBtn() { dateBtn.text = "${Loc.t(this@DailyBillsActivity, "From", "کس دن سے")}: ${dateFmt.format(Date(start))}" }
            refreshBtn()
            dateBtn.setOnClickListener {
                val c = Calendar.getInstance().apply { timeInMillis = start }
                val dp = DatePickerDialog(this@DailyBillsActivity, { _, y, m, d ->
                    start = Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }.timeInMillis
                    refreshBtn()
                }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
                dp.datePicker.maxDate = System.currentTimeMillis()
                dp.show()
            }
            box.addView(spinner); box.addView(amount); box.addView(dateBtn)
            AlertDialog.Builder(this@DailyBillsActivity)
                .setTitle(Loc.t(this@DailyBillsActivity, "Daily Rent", "روزانہ کرایہ"))
                .setView(box)
                .setPositiveButton(Loc.t(this@DailyBillsActivity, "Save", "محفوظ کریں")) { _, _ ->
                    val rentAmt = amount.text.toString().trim().toDoubleOrNull()
                    if (rentAmt == null || rentAmt <= 0) {
                        toast(Loc.t(this@DailyBillsActivity, "Customer and rent (more than 0) are required", "کسٹمر اور کرایہ (0 سے زیادہ) ضروری ہے"))
                        return@setPositiveButton
                    }
                    val cust = customers[spinner.selectedItemPosition]
                    lifecycleScope.launch {
                        try {
                            rent.setConfig(cust.id, rentAmt, start)
                            val n = rent.ensureRentUpTo(System.currentTimeMillis())
                            toast(Loc.t(this@DailyBillsActivity, "Rent set. $n day bill(s) created", "کرایہ سیٹ ہو گیا۔ $n دن کے کرایہ بل بن گئے"))
                        } catch (e: Exception) { toast(e.message ?: e.toString()) }
                        load()
                    }
                }
                .setNegativeButton(Loc.t(this@DailyBillsActivity, "Cancel", "منسوخ کریں"), null)
                .show()
        }
    }
}
