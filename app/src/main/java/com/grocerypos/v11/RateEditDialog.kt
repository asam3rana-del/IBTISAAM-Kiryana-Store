package com.grocerypos.v11.ui

import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.fromPrimaryUnitRate
import com.grocerypos.v11.toPrimaryUnitRate
import com.grocerypos.v11.unitLadder
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Quick rate edit for ADMIN: Retail / Wholesale / Shopkeeper in one place — ported from the Flutter
 * app's showRateEditSheet. A unit picker lets you type the rates in whichever unit is easiest (Ctn /
 * Dzn / Pcs …); on Save every rate is converted back to the product's PRIMARY unit (how salePrice /
 * wholesalePrice / shopkeeperPrice are stored). A rate below the product's cost asks "Save anyway?".
 * An empty Shopkeeper rate = Shopkeeper bills use the Wholesale rate. Cost is not touched here.
 *
 * The caller must already have checked the role; [onSaved] gets the updated product so the screen can refresh.
 */
object RateEditDialog {

    fun show(activity: AppCompatActivity, product: Product, focusShopkeeper: Boolean = false, onSaved: (Product) -> Unit = {}) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun fmt(v: Double): String {
            if (v <= 0.0) return ""
            val r = Math.round(v * 100) / 100.0
            return if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.US, "%.2f", r)
        }

        val units = product.unitLadder().asReversed().map { it.unit }.distinct().ifEmpty { listOf(product.unit) }
        var unit = if (product.unit in units) product.unit else units.first()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(4))
        }
        val labels = mutableListOf<Pair<TextView, String>>()
        fun labelText(base: String) = "$base (${Loc.t(activity, "per", "فی")} $unit)"

        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, units)
            setSelection(units.indexOf(unit).coerceAtLeast(0))
        }
        if (units.size > 1) box.addView(spinner)

        fun field(base: String, initialPrimary: Double, hint: String? = null): EditText {
            val tv = TextView(activity).apply {
                text = labelText(base); textSize = 12f
                setPadding(0, px(10), 0, px(2))
            }
            labels.add(tv to base)
            box.addView(tv)
            val et = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                this.hint = "0.00"
                setText(fmt(product.fromPrimaryUnitRate(initialPrimary, unit).takeIf { initialPrimary > 0 } ?: 0.0))
            }
            box.addView(et)
            if (hint != null) box.addView(TextView(activity).apply { text = hint; textSize = 11f; setTextColor(Color.GRAY) })
            return et
        }

        val retail = field(Loc.t(activity, "RETAIL RATE", "خوردہ ریٹ"), product.salePrice)
        val wholesale = field(Loc.t(activity, "WHOLESALE RATE", "ہول سیل ریٹ"), product.wholesalePrice)
        val shopkeeper = field(
            Loc.t(activity, "SHOPKEEPER RATE (optional)", "دکاندار ریٹ (اختیاری)"), product.shopkeeperPrice,
            Loc.t(activity, "Empty = Wholesale rate is used.", "خالی = ہول سیل ریٹ لگے گا۔")
        )

        // Switching the unit re-expresses what is typed in the new unit (nothing is lost).
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val to = units[position]
                if (to == unit) return
                for (et in listOf(retail, wholesale, shopkeeper)) {
                    val typed = et.text.toString().trim().toDoubleOrNull() ?: 0.0
                    if (typed > 0) et.setText(fmt(product.fromPrimaryUnitRate(product.toPrimaryUnitRate(typed, unit), to)))
                }
                unit = to
                labels.forEach { (tv, base) -> tv.text = labelText(base) }
            }
        }

        val dlg = AlertDialog.Builder(activity)
            .setTitle("${product.name} — ${Loc.t(activity, "Edit rates", "ریٹ تبدیل کریں")}")
            .setView(box)
            .setPositiveButton(Loc.t(activity, "Save", "محفوظ کریں"), null)
            .setNegativeButton(Loc.t(activity, "Cancel", "منسوخ کریں"), null)
            .create()
        dlg.show()
        if (focusShopkeeper) shopkeeper.requestFocus()

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            fun parse(et: EditText): Double? {
                val t = et.text.toString().trim()
                if (t.isEmpty()) return 0.0
                val v = t.toDoubleOrNull()
                return if (v == null || v < 0 || !v.isFinite()) null else v
            }
            val r = parse(retail); val w = parse(wholesale); val s = parse(shopkeeper)
            if (r == null || w == null || s == null) {
                Toast.makeText(activity, Loc.t(activity, "Enter valid rates", "صحیح ریٹ لکھیں"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val newRetail = if (r > 0) product.toPrimaryUnitRate(r, unit) else 0.0
            val newWholesale = if (w > 0) product.toPrimaryUnitRate(w, unit) else 0.0
            val newShop = if (s > 0) product.toPrimaryUnitRate(s, unit) else 0.0

            fun persist() {
                activity.lifecycleScope.launch {
                    val db = PosDatabase.get(activity)
                    val base = db.productDao().find(product.barcode) ?: product
                    val updated = base.copy(
                        salePrice = newRetail, wholesalePrice = newWholesale, shopkeeperPrice = newShop,
                        dirty = true, updatedAt = System.currentTimeMillis()
                    )
                    db.productDao().upsert(updated)
                    SyncQueueHelper.enqueueProduct(db, updated)
                    Toast.makeText(activity, Loc.t(activity, "Rates updated", "ریٹ اپڈیٹ ہو گئی"), Toast.LENGTH_SHORT).show()
                    dlg.dismiss()
                    onSaved(updated)
                }
            }

            val cost = product.cost
            val below = cost > 0 && listOf(newRetail, newWholesale, newShop).any { it > 0 && it < cost }
            if (below) {
                AlertDialog.Builder(activity)
                    .setTitle(Loc.t(activity, "Rate below cost", "ریٹ لاگت سے کم ہے"))
                    .setMessage(Loc.t(activity, "A rate is lower than the purchase cost (Rs ${fmt(product.fromPrimaryUnitRate(cost, unit))} per $unit). Save anyway?",
                        "ایک ریٹ خرید کی لاگت سے کم ہے۔ پھر بھی محفوظ کریں؟"))
                    .setPositiveButton(Loc.t(activity, "Save anyway", "پھر بھی محفوظ کریں")) { _, _ -> persist() }
                    .setNegativeButton(Loc.t(activity, "Cancel", "منسوخ کریں"), null)
                    .show()
            } else persist()
        }
    }
}
