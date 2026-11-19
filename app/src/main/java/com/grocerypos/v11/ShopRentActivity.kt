package com.grocerypos.v11.ui

import com.grocerypos.v11.RadioIds
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.BooksRuleException
import com.grocerypos.v11.DayUtil
import com.grocerypos.v11.R
import com.grocerypos.v11.RentMath
import com.grocerypos.v11.ShopRentRepository
import com.grocerypos.v11.ShopRentSummary
import com.grocerypos.v11.Ym
import com.grocerypos.v11.money0
import com.grocerypos.v11.ui.components.premiumHeader
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch

/**
 * NEW (Flutter parity, batch 2): Shop Rents (kiraye ki dukaneen) — port of Flutter's
 * shop_rent_screen.dart. Admin / manager only.
 *  * Har mahine ka rent khud "due" banta hai (screen kholne par).
 *  * Pay Rent: sab se purane baqi mahine se; Expense (Rent) + Cash OUT. Zyada diya to advance.
 *  * Give Advance: Cash OUT, Expense NAHI. Advance se katna: Expense + barabar Cash IN/OUT.
 *  * Change Rent (% ya naya rent, is / agle mahine se), Waive month, Close / Reopen, Delete, History, Monthly report.
 */
class ShopRentActivity : AppCompatActivity() {

    private lateinit var c: CuColors
    private lateinit var content: LinearLayout
    private var list: List<ShopRentSummary> = emptyList()

    private fun t(en: String, ur: String) = Loc.t(this, en, ur)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val role = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (role != "admin" && role != "manager") {
            cuToast(t("Only admin / manager can open Shop Rents", "دکانوں کا کرایہ صرف ایڈمن / مینیجر کھول سکتے ہیں")); finish(); return
        }
        c = CuColors(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(c.bg))
        }
        root.addView(premiumHeader(iconRes = R.drawable.ic_store, title = t("Shop Rents", "دکانوں کا کرایہ"),
            subtitle = t("Monthly rent, advance & landlords", "ماہانہ کرایہ، ایڈوانس اور مالکان")))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        root.addView(cuGap(30))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(c.bg)); addView(root) })
        load()
    }

    private fun doAction(block: suspend () -> Unit) = lifecycleScope.launch {
        try { block() } catch (e: BooksRuleException) { cuToast(e.message ?: "") } catch (e: Exception) { cuToast(t("Could not save", "محفوظ نہیں ہو سکا")) }
    }

    private fun load(): kotlinx.coroutines.Job = lifecycleScope.launch {
        val ctx = this@ShopRentActivity
        try { ShopRentRepository.ensureCharges(ctx) } catch (_: Exception) { }
        list = ShopRentRepository.summaries(ctx)
        render()
    }

    private fun render() {
        content.removeAllViews()
        val active = list.filter { it.shop.active }
        val totals = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(label: String, v: Double, color: String) = cuCard(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, cuDp(4), cuDp(8)) }
            addView(cuText(label, 11f, c.muted, true)); addView(cuText(money0(v), 15f, color, true))
        }
        val due = active.sumOf { it.outstanding }
        totals.addView(tile(t("Monthly Rent", "ماہانہ کرایہ"), active.sumOf { it.currentRent }, c.navy))
        totals.addView(tile(t("Due", "باقی"), due, if (due > 0.009) c.red else c.teal))
        totals.addView(tile(t("Advance", "ایڈوانس"), list.sumOf { it.advanceBalance }, c.teal))
        content.addView(totals)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(cuButton("+  " + t("Add Shop", "دکان شامل کریں"), c.purple) { editShop(null) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, cuDp(4), cuDp(10)) }
        })
        btns.addView(cuButton(t("Monthly report", "ماہانہ رپورٹ"), c.purpleBg, c.purple) { pickReportMonth() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(cuDp(4), 0, 0, cuDp(10)) }
        })
        content.addView(btns)
        if (list.isEmpty()) {
            content.addView(cuText(t("No shops yet. Tap \"Add Shop\" to add a rented shop.", "ابھی کوئی دکان نہیں۔ کرائے کی دکان کے لیے \"دکان شامل کریں\" دبائیں۔"), 13f, c.muted).apply {
                gravity = Gravity.CENTER; setPadding(0, cuDp(40), 0, cuDp(40))
            })
            return
        }
        for (s in list) content.addView(card(s))
    }

    private fun card(s: ShopRentSummary): LinearLayout {
        val shop = s.shop
        val card = cuCard(c)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(cuText(shop.shopName + (if (shop.landlord.isBlank()) "" else " (${shop.landlord})"), 14.5f, if (shop.active) c.text else c.muted, true))
        val (badge, color) = when {
            !shop.active -> t("Closed", "بند") to c.muted
            s.isClear -> t("Paid", "ادا") to c.teal
            s.overdue -> t("Overdue", "تاخیر") + " " + money0(s.outstanding) to c.red
            else -> t("Due", "باقی") + " " + money0(s.outstanding) to c.amber
        }
        col.addView(cuText(badge, 12f, color, true))
        col.addView(cuText("${money0(s.currentRent)} / ${t("month", "ماہ")}  •  ${t("Due on", "تاریخ")} ${shop.dueDay}" +
            (if (s.advanceBalance > 0.009) "  •  ${t("Advance", "ایڈوانس")} ${money0(s.advanceBalance)}" else ""), 11.5f, c.muted))
        top.addView(col)
        top.addView(cuRound(R.drawable.ic_more_vert, c.muted, c.bg) { shopMenu(s) })
        card.addView(top)
        for (d in s.dues.take(6)) {
            card.addView(cuText("${Ym.label(d.ym)}: ${money0(d.due)}  •  ${t("due", "تاریخ")} ${DayUtil.fmt(Ym.dateOf(d.ym, shop.dueDay))}", 11.5f,
                if (Ym.dateOf(d.ym, shop.dueDay) < DayUtil.today()) c.red else c.text))
        }
        if (s.dues.size > 6) card.addView(cuText("+ ${s.dues.size - 6} " + t("more months", "مزید مہینے"), 11f, c.muted))
        if (shop.active && s.reviewDue) card.addView(cuText(t("Rent has not changed for a year — time to review it. Tap ⋮ → Change Rent.",
            "ایک سال سے کرایہ نہیں بدلا — جائزے کا وقت ہے۔ ⋮ ← کرایہ بدلیں۔"), 11f, c.amber, true))
        if (shop.active) {
            val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, cuDp(8), 0, 0) }
            fun act(label: String, bg: String, fg: String, onClick: () -> Unit) = cuChip(label, false, c, onClick).apply {
                background = cuFill(bg, 14); setTextColor(Color.parseColor(fg))
            }
            acts.addView(act(t("Pay Rent", "کرایہ دیں"), c.tealBg, c.teal) { payDialog(s) })
            acts.addView(act(t("Give Advance", "ایڈوانس دیں"), c.purpleBg, c.purple) { advanceDialog(s) })
            if (s.advanceBalance > 0.009 && !s.isClear) acts.addView(act(t("Deduct from Advance", "ایڈوانس سے کاٹیں"), c.amberBg, c.amber) {
                doAction {
                    val done = ShopRentRepository.applyAdvance(this, shop.id)
                    cuToast(if (done > 0) t("Deducted from advance", "ایڈوانس سے کٹا") + ": " + money0(done) else t("Nothing to deduct", "کاٹنے کے لیے کچھ نہیں"))
                    load()
                }
            })
            card.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(acts) })
        }
        return card
    }

    private fun shopMenu(s: ShopRentSummary) {
        val shop = s.shop
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items.add(t("History", "ہسٹری") to { history(s) })
        items.add(t("Change Rent", "کرایہ بدلیں") to { changeRent(s) })
        items.add(t("Edit Shop", "دکان میں ترمیم") to { editShop(s) })
        if (shop.phone.isNotBlank()) items.add(t("Call Landlord", "مالک کو کال کریں") to { cuDial(shop.phone) })
        if (s.dues.isNotEmpty()) items.add(t("Waive a Month", "ایک مہینہ معاف") to { waive(s) })
        items.add((if (shop.active) t("Close Shop", "دکان بند کریں") else t("Reopen Shop", "دکان دوبارہ شروع")) to {
            doAction { ShopRentRepository.setActive(this, shop.id, !shop.active); load() }
        })
        items.add(t("Delete Shop", "دکان حذف کریں") to {
            AlertDialog.Builder(this).setTitle(t("Delete Shop", "دکان حذف کریں")).setMessage(shop.shopName)
                .setNegativeButton(t("Cancel", "منسوخ"), null)
                .setPositiveButton(t("Delete", "حذف کریں")) { _, _ -> doAction { ShopRentRepository.deleteShop(this, shop.id); load() } }.show()
        })
        AlertDialog.Builder(this).setTitle(shop.shopName).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun methodGroup(): RadioGroup = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        addView(RadioButton(this@ShopRentActivity).apply { text = t("Cash", "کیش"); id = RadioIds.of(8101) })
        addView(RadioButton(this@ShopRentActivity).apply { text = t("Bank", "بینک"); id = RadioIds.of(8102) })
        check(RadioIds.of(8101))
    }

    private fun payDialog(s: ShopRentSummary) {
        val (scroll, body) = cuDialogBody()
        val amount = cuField(t("Amount", "رقم"), c, numeric = true, initial = if (s.outstanding > 0) Math.round(s.dues.first().due).toString() else "")
        val m = methodGroup()
        val date = CuDateField(this, c, t("Payment date", "ادائیگی کی تاریخ"), DayUtil.today(), clearable = false)
        val note = cuField(t("Note (optional)", "نوٹ (اختیاری)"), c)
        body.addView(amount); body.addView(m); body.addView(date.view); body.addView(note)
        body.addView(cuText(if (s.outstanding > 0) t("Anything extra becomes advance.", "زیادہ دیا تو باقی ایڈوانس بن جائے گا۔")
            else t("Nothing is due. This will be kept as advance.", "کچھ باقی نہیں۔ یہ ایڈوانس رہے گا۔"), 11f, c.muted))
        AlertDialog.Builder(this).setTitle(t("Pay Rent", "کرایہ دیں") + " • " + s.shop.shopName).setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Pay", "ادا کریں")) { _, _ ->
                val a = cuParseAmount(amount.text.toString())
                if (a == null) { cuToast(t("Enter a valid amount", "صحیح رقم لکھیں")); return@setPositiveButton }
                // Same day as today => keep the real time; a past date => noon of that day.
                val at = if (date.value == DayUtil.today()) System.currentTimeMillis() else date.value + 12 * 3600_000L
                doAction {
                    val (toRent, toAdv) = ShopRentRepository.pay(this, s.shop.id, a, if (m.checkedRadioButtonId == RadioIds.of(8102)) "bank" else "cash", note.text.toString(), at)
                    cuToast(t("Rent paid", "کرایہ ادا") + " " + money0(toRent) + (if (toAdv > 0) " • " + t("advance", "ایڈوانس") + " " + money0(toAdv) else ""))
                    load()
                }
            }.show()
    }

    private fun advanceDialog(s: ShopRentSummary) {
        val (scroll, body) = cuDialogBody()
        val amount = cuField(t("Amount", "رقم"), c, numeric = true)
        val m = methodGroup()
        val note = cuField(t("Note (optional)", "نوٹ (اختیاری)"), c)
        body.addView(amount); body.addView(m); body.addView(note)
        body.addView(cuText(t("Advance is money kept with the landlord. It is a Cash/Bank out but NOT an expense.",
            "ایڈوانس مالک کے پاس رکھا پیسہ ہے۔ یہ کیش/بینک سے باہر جاتا ہے لیکن خرچ نہیں۔"), 11f, c.muted))
        AlertDialog.Builder(this).setTitle(t("Give Advance", "ایڈوانس دیں") + " • " + s.shop.shopName).setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Give", "دیں")) { _, _ ->
                val a = cuParseAmount(amount.text.toString())
                if (a == null) { cuToast(t("Enter a valid amount", "صحیح رقم لکھیں")); return@setPositiveButton }
                doAction {
                    ShopRentRepository.giveAdvance(this, s.shop.id, a, if (m.checkedRadioButtonId == RadioIds.of(8102)) "bank" else "cash", note.text.toString())
                    cuToast(t("Advance saved", "ایڈوانس محفوظ")); load()
                }
            }.show()
    }

    private fun changeRent(s: ShopRentSummary) {
        val (scroll, body) = cuDialogBody()
        body.addView(cuText("${t("Current", "موجودہ")}: ${money0(s.currentRent)} (${t("since", "سے")} ${Ym.label(s.lastTermYm)})", 12.5f, c.muted, true))
        val pct = cuField(t("Increase %", "اضافہ %"), c, numeric = true)
        val rent = cuField(t("New monthly rent", "نیا ماہانہ کرایہ"), c, numeric = true)
        body.addView(pct)
        body.addView(cuButton(t("Apply %", "% لگائیں"), c.purpleBg, c.purple) {
            val p = pct.text.toString().trim().toDoubleOrNull()
            if (p != null) rent.setText(RentMath.increaseByPercent(s.currentRent, p).toLong().toString())
        })
        body.addView(rent)
        val when_ = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        when_.addView(RadioButton(this).apply { text = t("From this month", "اس مہینے سے"); id = RadioIds.of(8201) })
        when_.addView(RadioButton(this).apply { text = t("From next month", "اگلے مہینے سے"); id = RadioIds.of(8202) })
        when_.check(RadioIds.of(8201))
        body.addView(when_)
        body.addView(cuText(t("Past months stay as they were.", "پچھلے مہینے ویسے ہی رہیں گے۔"), 11f, c.muted))
        AlertDialog.Builder(this).setTitle(t("Change Rent", "کرایہ بدلیں") + " • " + s.shop.shopName).setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Save", "محفوظ کریں")) { _, _ ->
                val r = cuParseAmount(rent.text.toString())
                if (r == null) { cuToast(t("Enter a valid amount", "صحیح رقم لکھیں")); return@setPositiveButton }
                val nowYm = Ym.of(System.currentTimeMillis())
                val from = if (when_.checkedRadioButtonId == RadioIds.of(8202)) Ym.add(nowYm, 1) else nowYm
                doAction { ShopRentRepository.changeRent(this, s.shop.id, from, r); cuToast(t("Saved", "محفوظ ہو گیا")); load() }
            }.show()
    }

    private fun waive(s: ShopRentSummary) {
        val months = s.dues.map { it.ym }
        AlertDialog.Builder(this).setTitle(t("Waive a Month", "ایک مہینہ معاف"))
            .setItems(months.map { "${Ym.label(it)}  •  ${money0(s.dues.first { d -> d.ym == it }.due)}" }.toTypedArray()) { _, i ->
                AlertDialog.Builder(this).setTitle(t("Waive ${Ym.label(months[i])}?", "${Ym.label(months[i])} معاف کریں؟"))
                    .setMessage(t("This month's rent becomes 0 (e.g. the shop was closed).", "اس مہینے کا کرایہ 0 ہو جائے گا (مثلاً دکان بند تھی)۔"))
                    .setNegativeButton(t("Cancel", "منسوخ"), null)
                    .setPositiveButton(t("Yes", "ہاں")) { _, _ -> doAction { ShopRentRepository.waiveMonth(this, s.shop.id, months[i]); load() } }.show()
            }.show()
    }

    private fun history(s: ShopRentSummary): kotlinx.coroutines.Job = lifecycleScope.launch {
        val rows = ShopRentRepository.history(this@ShopRentActivity, s.shop.id)
        val (scroll, body) = cuDialogBody()
        var dialog: AlertDialog? = null
        if (rows.isEmpty()) body.addView(cuText(t("No entries yet", "ابھی کوئی انٹری نہیں"), 12.5f, c.muted))
        for (e in rows) {
            val label = when (e.kind) {
                "charge" -> if (e.amount <= RentMath.EPS) t("Rent waived", "کرایہ معاف") else t("Rent due", "کرایہ واجب")
                "payment" -> t("Paid", "ادا کیا")
                "advance" -> t("Advance given", "ایڈوانس دیا")
                else -> t("Deducted from advance", "ایڈوانس سے کٹا")
            }
            val row = LinearLayout(this@ShopRentActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, cuDp(4), 0, cuDp(4)) }
            val col = LinearLayout(this@ShopRentActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            col.addView(cuText("$label  •  ${money0(e.amount)}", 13f, if (e.kind == "charge") c.text else c.teal, true))
            col.addView(cuText((if (e.ym.isNotBlank()) Ym.label(e.ym) + "  •  " else "") + DayUtil.fmt(e.createdAt) +
                (if (e.kind != "charge") "  •  " + (if (e.method == "bank") t("Bank", "بینک") else t("Cash", "کیش")) else "") +
                (if (e.note.isNotBlank()) "  •  ${e.note}" else ""), 11f, c.muted))
            row.addView(col)
            if (e.kind != "charge") row.addView(cuRound(R.drawable.ic_delete, c.red, c.bg) {
                AlertDialog.Builder(this@ShopRentActivity).setTitle(t("Delete this entry?", "یہ انٹری حذف کریں؟"))
                    .setMessage(t("Its expense and cash entry are removed too.", "اس کا خرچ اور کیش انٹری بھی ہٹ جائے گی۔"))
                    .setNegativeButton(t("Cancel", "منسوخ"), null)
                    .setPositiveButton(t("Delete", "حذف کریں")) { _, _ -> dialog?.dismiss(); doAction { ShopRentRepository.deleteEntry(this@ShopRentActivity, e.id); load() } }.show()
            })
            body.addView(row)
        }
        dialog = AlertDialog.Builder(this@ShopRentActivity).setTitle(t("History", "ہسٹری") + " • " + s.shop.shopName).setView(scroll)
            .setNegativeButton(t("Close", "بند"), null).show()
    }

    private fun editShop(s: ShopRentSummary?) {
        val shop = s?.shop
        val (scroll, body) = cuDialogBody()
        val name = cuField(t("Shop name", "دکان کا نام"), c, initial = shop?.shopName ?: "")
        val landlord = cuField(t("Landlord name", "مالک کا نام"), c, initial = shop?.landlord ?: "")
        val phone = cuField(t("Landlord phone", "مالک کا فون"), c, initial = shop?.phone ?: "").apply { inputType = android.text.InputType.TYPE_CLASS_PHONE }
        body.addView(name); body.addView(landlord); body.addView(phone)
        val rent = cuField(t("Monthly rent", "ماہانہ کرایہ"), c, numeric = true)
        val day = cuField(t("Pay on day of month (1-31)", "مہینے کی کس تاریخ کو دینا ہے (1-31)"), c, numeric = true, initial = (shop?.dueDay ?: 1).toString())
        val start = CuDateField(this, c, t("Rent starts from (any day of that month)", "کرایہ کس مہینے سے (اس مہینے کا کوئی بھی دن)"), DayUtil.today(), clearable = false)
        val adv = cuField(t("Advance already given (optional)", "پہلے سے دیا ہوا ایڈوانس (اختیاری)"), c, numeric = true)
        if (shop == null) { body.addView(rent) }
        body.addView(day)
        if (shop == null) { body.addView(start.view); body.addView(adv) }
        val auto = CheckBox(this).apply { text = t("Deduct new rent from advance automatically", "نیا کرایہ ایڈوانس سے خود کاٹیں"); isChecked = shop?.autoApply ?: true }
        body.addView(auto)
        val note = cuField(t("Note (optional)", "نوٹ (اختیاری)"), c, initial = shop?.note ?: "")
        body.addView(note)
        AlertDialog.Builder(this).setTitle(if (shop == null) t("Add Shop", "دکان شامل کریں") else t("Edit Shop", "دکان میں ترمیم")).setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Save", "محفوظ کریں")) { _, _ ->
                val d = day.text.toString().trim().toIntOrNull() ?: 0
                doAction {
                    if (shop == null) {
                        val r = cuParseAmount(rent.text.toString()) ?: throw BooksRuleException(t("Enter a valid amount", "صحیح رقم لکھیں"))
                        val a = adv.text.toString().trim().replace(",", "").toDoubleOrNull() ?: 0.0
                        ShopRentRepository.addShop(this, name.text.toString(), landlord.text.toString(), phone.text.toString(), r, d,
                            Ym.of(start.value), a, auto.isChecked, note.text.toString())
                    } else {
                        ShopRentRepository.updateShop(this, shop.id, name.text.toString(), landlord.text.toString(), phone.text.toString(), d, auto.isChecked, note.text.toString())
                    }
                    cuToast(t("Saved", "محفوظ ہو گیا")); load()
                }
            }.show()
    }

    private fun pickReportMonth() {
        val nowYm = Ym.of(System.currentTimeMillis())
        val months = (0 until 12).map { Ym.add(nowYm, -it) }
        AlertDialog.Builder(this).setTitle(t("Monthly report", "ماہانہ رپورٹ"))
            .setItems(months.map { Ym.label(it) }.toTypedArray()) { _, i -> showReport(months[i]) }.show()
    }

    private fun showReport(ym: String): kotlinx.coroutines.Job = lifecycleScope.launch {
        val rows = ShopRentRepository.monthReport(this@ShopRentActivity, ym)
        val (scroll, body) = cuDialogBody()
        if (rows.isEmpty()) body.addView(cuText(t("No rent for this month", "اس مہینے کا کوئی کرایہ نہیں"), 12.5f, c.muted))
        for (r in rows) {
            body.addView(cuText(r.shop.shopName, 13.5f, c.text, true))
            body.addView(cuText("${t("Rent", "کرایہ")} ${money0(r.rent)}  •  ${t("Paid", "ادا")} ${money0(r.paid)}  •  ${t("Due", "باقی")} ${money0(r.due)}",
                11.5f, if (r.due > 0.009) c.red else c.teal).apply { setPadding(0, 0, 0, cuDp(8)) })
        }
        if (rows.isNotEmpty()) body.addView(cuText("${t("Total", "کل")}: ${money0(rows.sumOf { it.rent })}  •  ${t("Paid", "ادا")} ${money0(rows.sumOf { it.paid })}  •  ${t("Due", "باقی")} ${money0(rows.sumOf { it.due })}", 12.5f, c.navy, true))
        AlertDialog.Builder(this@ShopRentActivity).setTitle(Ym.label(ym)).setView(scroll).setNegativeButton(t("Close", "بند"), null).show()
    }
}
