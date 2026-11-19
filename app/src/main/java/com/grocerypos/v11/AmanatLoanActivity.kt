package com.grocerypos.v11.ui

import com.grocerypos.v11.RadioIds
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.CollectionRepository
import com.grocerypos.v11.Customer
import com.grocerypos.v11.DayUtil
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.R
import com.grocerypos.v11.ReturnEntries
import com.grocerypos.v11.ReturnEntry
import com.grocerypos.v11.ReturnEntryResult
import com.grocerypos.v11.ReturnLog
import com.grocerypos.v11.ReturnReminder
import com.grocerypos.v11.LOG_ADJUSTED
import com.grocerypos.v11.LOG_GIVEN
import com.grocerypos.v11.LOG_RECOVERED
import com.grocerypos.v11.ui.components.premiumHeader
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * NEW (Flutter parity, batch 1): Amanat / Loan screen — port of Flutter's
 * lib/screens/amanat_loan_screen.dart. Admin / manager only (Flutter RoleGuard).
 *
 *  * Amanat = customer ne paisa dukaan ke paas rakhwaya (dukaan ne wapas dena hai, balance minus).
 *  * Loan   = dukaan ne customer ko cash udhar diya (customer ne wapas dena hai, balance plus).
 * Har party ki kai entries ho sakti hain (customers.returnEntries JSON). Wapsi / wasooli FIFO se
 * entries kam karti hai aur Cash register mein OUT / IN row banati hai. Sab kuch customer doc ke
 * saath Flutter wali app se bhi sync hota hai.
 */
class AmanatLoanActivity : AppCompatActivity() {

    private lateinit var c: CuColors
    private lateinit var listBox: LinearLayout
    private lateinit var totalsBox: LinearLayout
    private lateinit var filterRow: LinearLayout
    private var all: List<ReturnReminder> = emptyList()
    private var filter = 0 // 0 all, 1 amanat, 2 loan

    private fun t(en: String, ur: String) = Loc.t(this, en, ur)
    private fun money(v: Double): String = "Rs " + (if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(v))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val role = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (role != "admin" && role != "manager") {
            cuToast(t("Only admin / manager can open Deposit / Loan", "امانت / قرض صرف ایڈمن / مینیجر کھول سکتے ہیں"))
            finish(); return
        }
        c = CuColors(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(c.bg))
        }
        root.addView(premiumHeader(iconRes = R.drawable.ic_wallet, title = t("Deposit / Loan", "امانت / قرض"),
            subtitle = t("All Deposit & Loan parties", "تمام امانت اور قرض پارٹیاں")))
        totalsBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(totalsBox)
        root.addView(cuGap(10))
        filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(filterRow)
        root.addView(cuButton("+  " + t("Add Deposit / Loan", "امانت / قرض شامل کریں"), c.purple) { openAddDialog(null) })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        root.addView(cuGap(30))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(c.bg)); addView(root) })
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::listBox.isInitialized) load()
    }

    private fun load(): kotlinx.coroutines.Job = lifecycleScope.launch {
        all = try { CollectionRepository.returnReminders(this@AmanatLoanActivity) } catch (e: Exception) {
            cuToast(t("Could not load list", "لسٹ لوڈ نہیں ہو سکی")); emptyList()
        }
        render()
    }

    private fun render() {
        val amanat = all.filter { it.isAmanat }
        val loans = all.filter { !it.isAmanat }
        totalsBox.removeAllViews()
        fun totalCard(label: String, count: Int, total: Double, accent: String): LinearLayout = cuCard(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, cuDp(5), 0) }
            addView(cuText("$label ($count)", 12.5f, c.muted, true))
            addView(cuText(money(abs(total)), 18f, accent, true))
        }
        totalsBox.addView(totalCard(t("Deposit", "امانت"), amanat.size, amanat.sumOf { it.closing }, c.teal))
        totalsBox.addView(totalCard(t("Loan", "قرض"), loans.size, loans.sumOf { it.closing }, c.amber))

        filterRow.removeAllViews()
        listOf(t("All", "سب") to 0, t("Deposit", "امانت") to 1, t("Loan", "قرض") to 2).forEach { (label, v) ->
            filterRow.addView(cuChip(label, filter == v, c) { filter = v; render() })
        }

        listBox.removeAllViews()
        val shown = when (filter) { 1 -> amanat; 2 -> loans; else -> all }
        if (shown.isEmpty()) {
            listBox.addView(cuText(t("No Deposit or Loan parties yet.\nTap \"Add\" to create one.",
                "ابھی کوئی امانت یا قرض پارٹی نہیں۔\n\"شامل کریں\" دبائیں۔"), 13f, c.muted).apply {
                gravity = Gravity.CENTER; setPadding(0, cuDp(40), 0, cuDp(40))
            })
            return
        }
        for (r in shown) listBox.addView(card(r))
    }

    private fun card(r: ReturnReminder): LinearLayout {
        val today = DayUtil.today()
        val overdue = r.returnDate in 1L until today
        val card = cuCard(c)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(cuText(r.partyName, 14.5f, c.text, true))
        col.addView(cuText(if (r.isAmanat) t("Deposit — return to customer", "امانت — کسٹمر کو واپس کرنی ہے")
            else t("Loan — collect from customer", "قرض — کسٹمر سے وصول کرنا ہے"), 11.5f, c.muted))
        col.addView(cuText("${t("Balance", "بیلنس")}: ${money(abs(r.closing))}", 13.5f, c.purple, true).apply { setPadding(0, cuDp(6), 0, 0) })
        if (r.entries.isNotEmpty()) {
            col.addView(cuText("${r.entries.size} ${t(if (r.entries.size == 1) "entry" else "entries", "انٹری")} • ${t("Total", "کل")}: ${money(r.entriesTotal)}", 11.5f, c.muted))
        }
        col.addView(cuText(if (r.returnDate > 0) t("Return: ", "واپسی: ") + DayUtil.fmt(r.returnDate)
            else t("Tap to see entries", "انٹریاں دیکھنے کے لیے دبائیں"), 11.5f, if (overdue) c.red else c.muted, overdue))
        top.addView(col)
        if (r.partyPhone.isNotBlank()) {
            top.addView(cuRound(R.drawable.ic_send, c.whatsapp, c.whatsappBg) {
                val msg = if (r.isAmanat) "Assalam o Alaikum ${r.partyName}, it is time to return your deposit. Please contact us. Thank you!"
                else "Assalam o Alaikum ${r.partyName}, your loan repayment is due. Please pay as soon as possible. Thank you!"
                cuOpenWhatsApp(r.partyPhone, msg)
            })
            top.addView(cuRound(R.drawable.ic_phone, c.purple, c.purpleBg) { cuDial(r.partyPhone) })
        }
        top.addView(cuRound(R.drawable.ic_more_vert, c.muted, c.bg) { openMenu(r) })
        card.addView(top)
        card.setOnClickListener { openEntries(r) }
        return card
    }

    private fun openMenu(r: ReturnReminder) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (r.entries.isNotEmpty()) items.add((if (r.isAmanat) t("Return Deposit (pay back)", "امانت واپس کریں") else t("Collect Loan (receive)", "قرض وصول کریں")) to { openSettle(r) })
        items.add(t("Entries / edit amount", "انٹریاں / رقم ایڈٹ") to { openEntries(r) })
        items.add((if (r.isAmanat) t("Add another Deposit", "ایک اور امانت شامل کریں") else t("Add another Loan", "ایک اور قرض شامل کریں")) to { openAddDialog(r) })
        if (r.entries.isEmpty()) items.add(t("Set return date", "واپسی کی تاریخ") to {
            cuPickDate(r.returnDate) { d -> lifecycleScope.launch { CollectionRepository.setReturnDate(this@AmanatLoanActivity, r.partyId, d); load() } }
        })
        items.add(t("Edit party", "پارٹی ایڈٹ") to { openEditParty(r) })
        items.add(t("Remove from Deposit / Loan", "امانت / قرض سے ہٹائیں") to {
            confirm(t("Remove from Deposit / Loan?", "امانت / قرض سے ہٹائیں؟"),
                t("${r.partyName} will become a normal customer. Ledger and bills stay as they are.", "${r.partyName} عام کسٹمر بن جائے گا۔ کھاتہ اور بل جیسے ہیں ویسے رہیں گے۔"),
                t("Remove", "ہٹائیں")) {
                lifecycleScope.launch { CollectionRepository.setKind(this@AmanatLoanActivity, r.partyId, "", 0L); load() }
            }
        })
        items.add(t("Delete party", "پارٹی ڈیلیٹ کریں") to {
            confirm(t("Delete this party?", "یہ پارٹی ڈیلیٹ کریں؟"),
                t("${r.partyName} will be deleted from this device and from synced devices. Old bills stay in history by name.", "${r.partyName} اس ڈیوائس اور سنک شدہ ڈیوائسز سے ڈیلیٹ ہو جائے گا۔ پرانے بل نام کے ساتھ ہسٹری میں رہیں گے۔"),
                t("Delete", "ڈیلیٹ")) {
                lifecycleScope.launch { CollectionRepository.deleteReturnParty(this@AmanatLoanActivity, r.partyId); load() }
            }
        })
        AlertDialog.Builder(this).setTitle(r.partyName)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun confirm(title: String, body: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .show()
    }

    private fun resultToast(res: ReturnEntryResult) {
        when (res) {
            ReturnEntryResult.OK -> cuToast(t("Saved", "محفوظ ہو گیا"))
            ReturnEntryResult.KIND_MISMATCH -> cuToast(t("This party is already the other type (Deposit / Loan)", "یہ پارٹی پہلے سے دوسری قسم (امانت / قرض) کی ہے"))
            ReturnEntryResult.INVALID_AMOUNT -> cuToast(t("Enter a valid amount", "درست رقم لکھیں"))
            ReturnEntryResult.NOTHING_TO_SETTLE -> cuToast(t("Nothing left to settle", "کچھ باقی نہیں"))
            ReturnEntryResult.NOT_FOUND -> cuToast(t("This party no longer exists", "یہ پارٹی اب موجود نہیں"))
        }
    }

    // ------------------------------------------------------------------ settle (wapsi / wasooli)

    private fun openSettle(r: ReturnReminder) {
        val (scroll, body) = cuDialogBody()
        body.addView(cuText("${r.partyName} — ${t("pending", "باقی")}: ${money(r.entriesTotal)}", 13f, c.muted))
        body.addView(cuGap(8))
        val amount = cuField(t("Amount", "رقم"), c, numeric = true, initial = r.entriesTotal.roundToLong().toString())
        body.addView(amount)
        val methods = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val cash = RadioButton(this).apply { text = t("Cash", "کیش"); id = RadioIds.of(1001); isChecked = true }
        val bank = RadioButton(this).apply { text = t("Bank", "بینک"); id = RadioIds.of(1002) }
        methods.addView(cash); methods.addView(bank)
        body.addView(methods)
        val note = cuField(t("Note (optional)", "نوٹ (اختیاری)"), c)
        body.addView(note)
        body.addView(cuText(if (r.isAmanat) t("Cash will go OUT of the register.", "یہ رقم کیش سے باہر جائے گی۔")
            else t("Cash will come IN to the register.", "یہ رقم کیش میں آئے گی۔"), 11.5f, c.muted))
        AlertDialog.Builder(this)
            .setTitle(if (r.isAmanat) t("Return Deposit", "امانت واپس") else t("Collect Loan", "قرض وصول"))
            .setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(if (r.isAmanat) t("Return", "واپس") else t("Collect", "وصول")) { _, _ ->
                val amt = cuParseAmount(amount.text.toString())
                if (amt == null) { resultToast(ReturnEntryResult.INVALID_AMOUNT); return@setPositiveButton }
                val m = if (methods.checkedRadioButtonId == RadioIds.of(1002)) "bank" else "cash"
                lifecycleScope.launch {
                    resultToast(CollectionRepository.settleReturn(this@AmanatLoanActivity, r.partyId, amt, m, note.text.toString()))
                    load()
                }
            }.show()
    }

    // ------------------------------------------------------------------ entries

    private fun openEntries(r: ReturnReminder) {
        val (scroll, body) = cuDialogBody()
        body.addView(cuText("${t("Balance", "بیلنس")}: ${money(abs(r.closing))}", 13.5f, c.purple, true))
        if (r.baseAmount > 0) body.addView(cuText("${t("Old / opening amount", "پرانی / اوپننگ رقم")}: ${money(r.baseAmount)}", 11.5f, c.muted))
        body.addView(cuGap(8))
        var dialog: AlertDialog? = null
        if (r.entries.isEmpty()) {
            body.addView(cuText(t("No entries yet", "ابھی کوئی انٹری نہیں"), 13f, c.muted).apply { setPadding(0, cuDp(16), 0, cuDp(16)) })
        }
        for (e in r.entries) {
            val row = cuCard(c)
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(cuText(money(e.amount), 15f, c.text, true))
            col.addView(cuText((if (e.date > 0) t("Date: ", "تاریخ: ") + DayUtil.fmt(e.date) else t("Date: —", "تاریخ: —")), 11.5f, c.muted))
            col.addView(cuText((if (e.returnDate > 0) t("Return: ", "واپسی: ") + DayUtil.fmt(e.returnDate) else t("Return: not set", "واپسی: مقرر نہیں")),
                11.5f, if (e.returnDate in 1L until DayUtil.today()) c.red else c.muted))
            if (e.note.isNotEmpty()) col.addView(cuText(e.note, 11.5f, c.muted))
            line.addView(col)
            line.addView(cuRound(R.drawable.ic_edit, c.purple, c.purpleBg) { dialog?.dismiss(); openEntryEditor(r, e) })
            line.addView(cuRound(R.drawable.ic_delete, c.red, c.bg) {
                confirm(t("Delete this entry?", "یہ انٹری ڈیلیٹ کریں؟"),
                    t("${money(e.amount)} will be taken out of the balance.", "${money(e.amount)} بیلنس سے نکل جائے گی۔"),
                    t("Delete", "ڈیلیٹ")) {
                    dialog?.dismiss()
                    lifecycleScope.launch { resultToast(CollectionRepository.deleteReturnEntry(this@AmanatLoanActivity, r.partyId, e.id)); load() }
                }
            })
            row.addView(line)
            body.addView(row)
        }
        // History (log) — sirf dikhane ke liye.
        val log = ReturnLog.decode(r.customer.returnLog)
        if (log.isNotEmpty()) {
            body.addView(cuText(t("HISTORY", "ہسٹری"), 11.5f, c.muted, true).apply { setPadding(0, cuDp(8), 0, cuDp(4)) })
            for (row in log.sortedByDescending { it.date }) {
                val label = when (row.type) {
                    LOG_GIVEN -> if (row.kind == "amanat") t("Deposit taken", "امانت لی") else t("Loan given", "قرض دیا")
                    LOG_RECOVERED -> if (row.kind == "amanat") t("Deposit returned", "امانت واپس کی") else t("Loan collected", "قرض وصول ہوا")
                    LOG_ADJUSTED -> t("Amount changed", "رقم تبدیل")
                    else -> t("Entry removed", "انٹری ہٹائی")
                }
                val amt = if (row.type == LOG_ADJUSTED) (if (row.amount >= 0) "+" else "-") + money(abs(row.amount)) else money(row.amount)
                body.addView(cuText("${if (row.date > 0) DayUtil.fmt(row.date) else "—"}  •  $label  •  $amt" +
                    (if (row.note.isNotEmpty()) "  •  ${row.note}" else ""), 11.5f, c.text).apply { setPadding(0, cuDp(2), 0, cuDp(2)) })
            }
        }
        val builder = AlertDialog.Builder(this).setTitle(r.partyName).setView(scroll)
            .setNegativeButton(t("Close", "بند"), null)
            .setNeutralButton(if (r.isAmanat) t("Add Deposit", "امانت شامل") else t("Add Loan", "قرض شامل")) { _, _ -> openAddDialog(r) }
        if (r.entries.isNotEmpty()) {
            builder.setPositiveButton(if (r.isAmanat) t("Return", "واپس") else t("Collect", "وصول")) { _, _ -> openSettle(r) }
        }
        dialog = builder.show()
    }

    private fun openEntryEditor(r: ReturnReminder, e: ReturnEntry) {
        val (scroll, body) = cuDialogBody()
        val amount = cuField(t("Amount", "رقم"), c, numeric = true, initial = if (e.amount == Math.floor(e.amount)) e.amount.toLong().toString() else e.amount.toString())
        body.addView(amount)
        val date = CuDateField(this, c, t("Date", "تاریخ"), e.date)
        body.addView(date.view)
        val ret = CuDateField(this, c, t("Return date", "واپسی کی تاریخ"), e.returnDate)
        body.addView(ret.view)
        body.addView(cuText(t("Long-press a date to clear it.", "تاریخ ہٹانے کے لیے دیر تک دبائیں۔"), 11f, c.muted))
        val note = cuField(t("Note (optional)", "نوٹ (اختیاری)"), c, initial = e.note)
        body.addView(note)
        AlertDialog.Builder(this).setTitle(t("Edit entry", "انٹری ایڈٹ")).setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Save", "محفوظ کریں")) { _, _ ->
                val amt = cuParseAmount(amount.text.toString())
                if (amt == null) { resultToast(ReturnEntryResult.INVALID_AMOUNT); return@setPositiveButton }
                lifecycleScope.launch {
                    resultToast(CollectionRepository.editReturnEntry(this@AmanatLoanActivity, r.partyId, e.id, amt, date.value, ret.value, note.text.toString()))
                    load()
                }
            }.show()
    }

    // ------------------------------------------------------------------ add (new party / existing / another entry)

    /** [forParty] != null => usi party ki ek aur entry (qism fixed). */
    private fun openAddDialog(forParty: ReturnReminder?) {
        lifecycleScope.launch {
            val customers = if (forParty == null) PosDatabase.get(this@AmanatLoanActivity).customerDao().allList() else emptyList()
            showAddDialog(forParty, customers)
        }
    }

    private fun showAddDialog(forParty: ReturnReminder?, customers: List<Customer>) {
        val (scroll, body) = cuDialogBody()
        var picked: Customer? = null
        var mode = 0 // 0 new party, 1 existing customer
        val kindGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rAmanat = RadioButton(this).apply { text = t("Deposit (Amanat)", "امانت"); id = RadioIds.of(2001) }
        val rLoan = RadioButton(this).apply { text = t("Loan (Qarz)", "قرض"); id = RadioIds.of(2002) }
        kindGroup.addView(rAmanat); kindGroup.addView(rLoan)
        val name = cuField(t("Name *", "نام *"), c)
        val phone = cuField(t("Phone", "فون"), c).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
        val pickBtn = cuButton(t("Select customer", "کسٹمر چنیں"), c.purpleBg, c.purple) {}
        val newBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(name); addView(phone) }
        if (forParty == null) {
            rAmanat.isChecked = true
            body.addView(kindGroup)
            val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            val mNew = RadioButton(this).apply { text = t("New party", "نئی پارٹی"); id = RadioIds.of(3001); isChecked = true }
            val mOld = RadioButton(this).apply { text = t("Existing customer", "موجودہ کسٹمر"); id = RadioIds.of(3002) }
            modeGroup.addView(mNew); modeGroup.addView(mOld)
            body.addView(modeGroup)
            body.addView(newBox)
            pickBtn.visibility = android.view.View.GONE
            body.addView(pickBtn)
            modeGroup.setOnCheckedChangeListener { _, id ->
                mode = if (id == RadioIds.of(3002)) 1 else 0
                newBox.visibility = if (mode == 0) android.view.View.VISIBLE else android.view.View.GONE
                pickBtn.visibility = if (mode == 1) android.view.View.VISIBLE else android.view.View.GONE
            }
            pickBtn.setOnClickListener {
                val labels = customers.map { cu ->
                    val tag = when (cu.kind) { "amanat" -> t("Deposit", "امانت"); "loan" -> t("Loan", "قرض"); else -> "" }
                    listOf(cu.name, cu.phone, tag).filter { it.isNotBlank() }.joinToString("  •  ")
                }.toTypedArray()
                if (labels.isEmpty()) { cuToast(t("No customers", "کوئی کسٹمر نہیں")); return@setOnClickListener }
                AlertDialog.Builder(this).setTitle(t("Select customer", "کسٹمر چنیں"))
                    .setItems(labels) { _, i -> picked = customers[i]; pickBtn.text = customers[i].name }
                    .show()
            }
        } else {
            body.addView(cuText(forParty.partyName, 13f, c.muted, true))
            body.addView(cuGap(6))
        }
        val amount = cuField(t("Amount", "رقم"), c, numeric = true)
        body.addView(amount)
        val date = CuDateField(this, c, t("Date", "تاریخ"), DayUtil.today())
        body.addView(date.view)
        val ret = CuDateField(this, c, t("Return date", "واپسی کی تاریخ"), 0L)
        body.addView(ret.view)
        val note = cuField(t("Note (optional)", "نوٹ (اختیاری)"), c)
        body.addView(note)
        body.addView(cuText(t("Deposit is saved as minus (shop owes), Loan as plus (customer owes).",
            "امانت مائنس میں (دکان نے دینی ہے)، قرض پلس میں (کسٹمر نے دینا ہے) جمع ہوتا ہے۔"), 11f, c.muted))

        AlertDialog.Builder(this)
            .setTitle(if (forParty == null) t("Add Deposit / Loan", "امانت / قرض شامل کریں")
                else if (forParty.isAmanat) t("Add another Deposit", "ایک اور امانت") else t("Add another Loan", "ایک اور قرض"))
            .setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Save", "محفوظ کریں")) { _, _ ->
                val amt = cuParseAmount(amount.text.toString())
                val kind = forParty?.kind ?: (if (kindGroup.checkedRadioButtonId == RadioIds.of(2002)) "loan" else "amanat")
                lifecycleScope.launch {
                    val ctx = this@AmanatLoanActivity
                    when {
                        forParty != null -> {
                            if (amt == null) { resultToast(ReturnEntryResult.INVALID_AMOUNT); return@launch }
                            resultToast(CollectionRepository.addReturnEntry(ctx, forParty.partyId, kind, amt, date.value, ret.value, note.text.toString()))
                        }
                        mode == 1 -> {
                            val p = picked
                            if (p == null) { cuToast(t("Select a customer", "کسٹمر چنیں")); return@launch }
                            if (amt == null) {
                                // Sirf tag (amount ke baghair).
                                if (p.kind.isNotEmpty() && p.kind != kind) { resultToast(ReturnEntryResult.KIND_MISMATCH); return@launch }
                                CollectionRepository.setKind(ctx, p.id, kind, ret.value)
                                cuToast(t("Saved", "محفوظ ہو گیا"))
                            } else {
                                resultToast(CollectionRepository.addReturnEntry(ctx, p.id, kind, amt, date.value, ret.value, note.text.toString()))
                            }
                        }
                        else -> {
                            val id = CollectionRepository.addNewReturnParty(ctx, name.text.toString(), phone.text.toString(), kind,
                                ret.value, amt ?: 0.0, date.value, note.text.toString())
                            if (id == null) cuToast(t("Name is required", "نام ضروری ہے")) else cuToast(t("Saved", "محفوظ ہو گیا"))
                        }
                    }
                    load()
                }
            }.show()
    }

    // ------------------------------------------------------------------ edit party

    private fun openEditParty(r: ReturnReminder) {
        val (scroll, body) = cuDialogBody()
        val name = cuField(t("Name *", "نام *"), c, initial = r.partyName)
        val phone = cuField(t("Phone", "فون"), c, initial = r.partyPhone).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
        body.addView(name); body.addView(phone)
        val kindGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rAmanat = RadioButton(this).apply { text = t("Deposit (Amanat)", "امانت"); id = RadioIds.of(4001); isChecked = r.isAmanat }
        val rLoan = RadioButton(this).apply { text = t("Loan (Qarz)", "قرض"); id = RadioIds.of(4002); isChecked = !r.isAmanat }
        kindGroup.addView(rAmanat); kindGroup.addView(rLoan)
        val ret = CuDateField(this, c, t("Return date", "واپسی کی تاریخ"), r.returnDate)
        if (r.entries.isEmpty()) {
            body.addView(kindGroup)
            body.addView(ret.view)
        }
        val base = cuField(t("Old / opening amount", "پرانی / اوپننگ رقم"), c, numeric = true,
            initial = if (r.baseAmount > 0) r.baseAmount.roundToLong().toString() else "")
        body.addView(base)
        AlertDialog.Builder(this).setTitle(t("Edit party", "پارٹی ایڈٹ")).setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Save", "محفوظ کریں")) { _, _ ->
                val kind = if (kindGroup.checkedRadioButtonId == RadioIds.of(4002)) "loan" else "amanat"
                val baseTxt = base.text.toString().trim()
                val baseAmt: Double? = if (baseTxt.isEmpty()) 0.0 else baseTxt.replace(",", "").toDoubleOrNull()
                lifecycleScope.launch {
                    val ok = CollectionRepository.editReturnParty(this@AmanatLoanActivity, r.partyId, name.text.toString(), phone.text.toString(),
                        kind, ret.value, baseAmt)
                    cuToast(if (ok) t("Saved", "محفوظ ہو گیا") else t("Name is required", "نام ضروری ہے"))
                    load()
                }
            }.show()
    }
}
