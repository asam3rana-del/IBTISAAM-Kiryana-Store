package com.grocerypos.v11.ui

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
import com.grocerypos.v11.RecycleBin
import com.grocerypos.v11.RecycleBinEntry
import com.grocerypos.v11.money0
import com.grocerypos.v11.ui.components.premiumHeader
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * NEW (Flutter parity, batch 3): Recycle Bin — port of Flutter's recycle_bin_screen.dart. Admin only.
 * Deleted Expenses, Customers, Suppliers, Workers and Shop Rents wait here; Restore brings the record
 * back (with its cash rows) on every device, Delete removes the snapshot for good.
 */
class RecycleBinActivity : AppCompatActivity() {

    private lateinit var c: CuColors
    private lateinit var content: LinearLayout
    private var filter = ""

    private fun t(en: String, ur: String) = Loc.t(this, en, ur)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val role = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (role != "admin") { cuToast(t("Only admin can open the Recycle Bin", "ری سائیکل بن صرف ایڈمن کھول سکتا ہے")); finish(); return }
        c = CuColors(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(c.bg))
        }
        root.addView(premiumHeader(iconRes = R.drawable.ic_delete, title = t("Recycle Bin", "ری سائیکل بن"),
            subtitle = t("Restore deleted records", "حذف شدہ ریکارڈ واپس لائیں")))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        root.addView(cuGap(30))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(c.bg)); addView(root) })
        load()
    }

    private fun kindLabel(k: String) = when (k) {
        RecycleBin.EXPENSE -> t("Expense", "خرچ")
        RecycleBin.CUSTOMER -> t("Customer", "کسٹمر")
        RecycleBin.SUPPLIER -> t("Supplier", "سپلائر")
        RecycleBin.WORKER -> t("Worker", "ورکر")
        RecycleBin.SHOP_RENT -> t("Shop rent", "دکان کرایہ")
        RecycleBin.SALE -> t("Sale bill", "سیل بل")
        RecycleBin.PURCHASE -> t("Purchase bill", "خریداری بل")
        else -> k
    }

    private fun load(): Job = lifecycleScope.launch {
        val all = RecycleBin.list(this@RecycleBinActivity)
        content.removeAllViews()
        val (scroll, row) = cuChipRow()
        row.addView(cuChip(t("All", "سب") + " (${all.size})", filter == "", c) { filter = ""; load() })
        for (k in listOf(RecycleBin.SALE, RecycleBin.PURCHASE, RecycleBin.EXPENSE, RecycleBin.CUSTOMER, RecycleBin.SUPPLIER, RecycleBin.WORKER, RecycleBin.SHOP_RENT)) {
            val n = all.count { it.kind == k }
            if (n > 0) row.addView(cuChip(kindLabel(k) + " ($n)", filter == k, c) { filter = k; load() })
        }
        content.addView(scroll)
        val shown = if (filter.isEmpty()) all else all.filter { it.kind == filter }
        if (shown.isEmpty()) {
            content.addView(cuText(t("The bin is empty.", "بن خالی ہے۔"), 13f, c.muted).apply { gravity = Gravity.CENTER; setPadding(0, cuDp(40), 0, cuDp(40)) })
            return@launch
        }
        for (e in shown) content.addView(card(e))
        content.addView(cuButton(t("Empty the bin", "بن خالی کریں"), c.bg, c.red) {
            AlertDialog.Builder(this@RecycleBinActivity).setTitle(t("Empty the bin?", "بن خالی کریں؟"))
                .setMessage(t("All ${all.size} entries are removed for good. Nothing else changes.", "تمام ${all.size} انٹریاں ہمیشہ کے لیے ہٹ جائیں گی۔ اور کچھ نہیں بدلے گا۔"))
                .setNegativeButton(t("Cancel", "منسوخ"), null)
                .setPositiveButton(t("Empty", "خالی کریں")) { _, _ -> lifecycleScope.launch { RecycleBin.purgeAll(this@RecycleBinActivity); load() } }.show()
        })
    }

    private fun card(e: RecycleBinEntry): LinearLayout {
        val card = cuCard(c)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(cuText(kindLabel(e.kind).uppercase(), 10.5f, c.purple, true))
        col.addView(cuText(e.title.ifBlank { "—" }, 14f, c.text, true))
        if (e.subtitle.isNotBlank()) col.addView(cuText(e.subtitle, 11.5f, c.muted))
        col.addView(cuText((if (e.amount != 0.0) money0(e.amount) + "  •  " else "") + DayUtil.fmt(e.deletedAt) + "  •  " + e.deletedBy, 11f, c.muted))
        top.addView(col)
        top.addView(cuRound(R.drawable.ic_undo, c.teal, c.tealBg) {
            AlertDialog.Builder(this).setTitle(t("Restore?", "واپس لائیں؟")).setMessage(e.title)
                .setNegativeButton(t("Cancel", "منسوخ"), null)
                .setPositiveButton(t("Restore", "واپس لائیں")) { _, _ ->
                    lifecycleScope.launch {
                        try { RecycleBin.restore(this@RecycleBinActivity, e.id); cuToast(t("Restored", "واپس آ گیا")) }
                        catch (x: BooksRuleException) { cuToast(x.message ?: "") }
                        load()
                    }
                }.show()
        })
        top.addView(cuRound(R.drawable.ic_delete, c.red, c.bg) {
            AlertDialog.Builder(this).setTitle(t("Delete forever?", "ہمیشہ کے لیے حذف؟")).setMessage(e.title)
                .setNegativeButton(t("Cancel", "منسوخ"), null)
                .setPositiveButton(t("Delete", "حذف کریں")) { _, _ -> lifecycleScope.launch { RecycleBin.purge(this@RecycleBinActivity, e.id); load() } }.show()
        })
        card.addView(top)
        return card
    }
}
