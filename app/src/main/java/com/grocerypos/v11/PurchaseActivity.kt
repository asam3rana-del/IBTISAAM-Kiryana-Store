package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.grocerypos.v11.util.Loc

/**
 * "Purchase" entry screen — numbered line-item cards ("#N  Name  Rs price" +
 * "Item Subtotal   qty unit x rate = Rs total"), a totals strip
 * (Total Disc / Total Tax Amt / Total Qty / Subtotal), an "+ Add Items" button,
 * a Charges section (Shipping + one more charge field), and a bottom bar with
 * Delete / Save / overflow-menu — matching the reference screenshot.
 *
 * Wire up wherever PurchaseActivity is currently started from (PartyDashboardActivity's
 * bottom bar and quick-add dialog already point at PurchaseActivity::class.java).
 *
 * NOTE: Add to AndroidManifest.xml under <application> if not already present:
 *   <activity android:name=".ui.PurchaseActivity" />
 */
class PurchaseActivity : AppCompatActivity() {

    // ---- palette (kept consistent with PartyActivity.kt / PartyDashboardActivity.kt) ----
    private val bg = "#F3F4F9"
    private val blue = "#5B6EE8"
    private val red = "#E57373"
    private val cardWhite = "#FFFFFF"
    private val cardBg = "#F5F6FA"
    private val cardBorder = "#EEF0F7"
    private val labelGray = "#9AA0B4"
    private val textDark = "#2E3242"
    private val divider = "#E7E9F2"

    private lateinit var itemsContainer: LinearLayout
    private lateinit var totalDiscValue: TextView
    private lateinit var totalTaxValue: TextView
    private lateinit var totalQtyValue: TextView
    private lateinit var subtotalValue: TextView
    private lateinit var shippingField: EditText
    private lateinit var otherChargeField: EditText

    /** Placeholder in-memory model — swap for the real PurchaseItem/PurchaseDao entity. */
    private data class PurchaseLineItem(
        val number: Int,
        val name: String,
        val qty: Double,
        val unit: String,
        val rate: Double,
        val discount: Double = 0.0,
        val tax: Double = 0.0
    ) {
        val subtotal: Double get() = qty * rate
    }

    private val lineItems = mutableListOf(
        PurchaseLineItem(3, "Capstan", 5.0, "otr", 2410.0),
        PurchaseLineItem(4, "Capstan Select", 5.0, "otr", 1600.0),
        PurchaseLineItem(5, "Capstan", 5.0, "otr", 2410.0),
        PurchaseLineItem(6, "Capstan international", 3.0, "otr", 1850.0)
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        outer.addView(buildHeader())

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)

        root.addView(buildTotalsStrip())
        root.addView(buildAddItemsButton())
        root.addView(sectionDivider())
        root.addView(buildChargesSection())
        root.addView(spacer(90)) // keep content clear of the floating bottom bar

        scroll.addView(root)
        outer.addView(scroll)
        outer.addView(buildBottomBar())

        setContentView(outer)

        renderLineItems()
    }

    // ================= HEADER =================
    private fun buildHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 46, 20, 22)
            setBackgroundColor(Color.parseColor(cardWhite))

            addView(TextView(this@PurchaseActivity).apply {
                text = "\u2190"
                textSize = 22f
                setTextColor(Color.parseColor(textDark))
                setPadding(4, 0, 24, 0)
                setOnClickListener { finish() }
            })

            addView(TextView(this@PurchaseActivity).apply {
                text = Loc.t(this@PurchaseActivity, "Purchase", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC")
                textSize = 20f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(this@PurchaseActivity).apply {
                text = "\u2699"
                textSize = 20f
                setTextColor(Color.parseColor(textDark))
                setPadding(0, 0, 4, 0)
                setOnClickListener { showSettingsDialog() }
            })
        }
    }

    private fun showSettingsDialog() {
        Toast.makeText(this, Loc.t(this, "Purchase settings", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC \u0633\u06CC\u0679\u0646\u06AF\u0632"), Toast.LENGTH_SHORT).show()
    }

    // ================= LINE ITEMS =================
    private fun renderLineItems() {
        itemsContainer.removeAllViews()
        for (item in lineItems) {
            itemsContainer.addView(lineItemRow(item))
        }
        updateTotals()
    }

    private fun lineItemRow(item: PurchaseLineItem): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(Color.parseColor(cardBg))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            setOnClickListener { editLineItem(item) }

            val topRow = LinearLayout(this@PurchaseActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(this@PurchaseActivity).apply {
                text = "#${item.number}"
                textSize = 11f
                setTextColor(Color.parseColor(labelGray))
                setPadding(10, 4, 10, 4)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(cardWhite))
                    cornerRadius = 8f
                    setStroke(1, Color.parseColor(cardBorder))
                }
            })
            topRow.addView(TextView(this@PurchaseActivity).apply {
                text = item.name
                textSize = 15.5f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(16, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            topRow.addView(TextView(this@PurchaseActivity).apply {
                text = "Rs %,.0f".format(item.subtotal)
                textSize = 16f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(topRow)

            val subRow = LinearLayout(this@PurchaseActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 0)
            }
            subRow.addView(TextView(this@PurchaseActivity).apply {
                text = Loc.t(this@PurchaseActivity, "Item Subtotal", "\u0622\u0626\u0679\u0645 \u0633\u0628 \u0679\u0648\u0679\u0644")
                textSize = 12.5f
                setTextColor(Color.parseColor(labelGray))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            subRow.addView(TextView(this@PurchaseActivity).apply {
                val qtyStr = if (item.qty == item.qty.toLong().toDouble()) item.qty.toLong().toString() else item.qty.toString()
                text = "$qtyStr ${item.unit} x %,.0f = Rs %,.0f".format(item.rate, item.subtotal)
                textSize = 12.5f
                setTextColor(Color.parseColor(labelGray))
            })
            addView(subRow)
        }
    }

    private fun editLineItem(item: PurchaseLineItem) {
        // Hook this up to the real item-edit dialog / ItemDao when wiring in actual data.
        Toast.makeText(this, item.name, Toast.LENGTH_SHORT).show()
    }

    // ================= TOTALS STRIP =================
    private fun buildTotalsStrip(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.parseColor(cardWhite))
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val discPair = totalLabelValue(Loc.t(this, "Total Disc", "\u0679\u0648\u0679\u0644 \u0688\u0633\u06A9\u0627\u0624\u0646\u0679"))
        val taxPair = totalLabelValue(Loc.t(this, "Total Tax Amt", "\u0679\u0648\u0679\u0644 \u0679\u06CC\u06A9\u0633"))
        totalDiscValue = discPair.second
        totalTaxValue = taxPair.second
        discPair.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        taxPair.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row1.addView(discPair.first)
        row1.addView(taxPair.first)
        wrap.addView(row1)
        wrap.addView(spacer(10))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qtyPair = totalLabelValue(Loc.t(this, "Total Qty", "\u0679\u0648\u0679\u0644 \u0645\u0642\u062F\u0627\u0631"))
        val subPair = totalLabelValue(Loc.t(this, "Subtotal", "\u0633\u0628 \u0679\u0648\u0679\u0644"))
        totalQtyValue = qtyPair.second
        subtotalValue = subPair.second
        qtyPair.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        subPair.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row2.addView(qtyPair.first)
        row2.addView(subPair.first)
        wrap.addView(row2)

        return wrap
    }

    private fun totalLabelValue(label: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = "$label: "
            textSize = 13f
            setTextColor(Color.parseColor(labelGray))
        })
        val value = TextView(this).apply {
            text = "0.00"
            textSize = 13f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(value)
        return Pair(row, value)
    }

    private fun updateTotals() {
        val totalDisc = lineItems.sumOf { it.discount }
        val totalTax = lineItems.sumOf { it.tax }
        val totalQty = lineItems.sumOf { it.qty }
        val subtotal = lineItems.sumOf { it.subtotal }

        totalDiscValue.text = "%.2f".format(totalDisc)
        totalTaxValue.text = "%.2f".format(totalTax)
        totalQtyValue.text = if (totalQty == totalQty.toLong().toDouble()) "${totalQty.toLong()}.0" else "%.1f".format(totalQty)
        subtotalValue.text = "%.2f".format(subtotal)
    }

    private fun grandTotal(): Double {
        val shipping = shippingField.text?.toString()?.toDoubleOrNull() ?: 0.0
        val other = otherChargeField.text?.toString()?.toDoubleOrNull() ?: 0.0
        return lineItems.sumOf { it.subtotal } + shipping + other
    }

    // ================= ADD ITEMS BUTTON =================
    private fun buildAddItemsButton(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 20, 20)
            setBackgroundColor(Color.parseColor(cardWhite))
        }

        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 10f
                setStroke(2, Color.parseColor(cardBorder))
            }
            setOnClickListener { onAddItems() }
        }
        btn.addView(TextView(this).apply {
            text = "\u2295"
            textSize = 17f
            setTextColor(Color.parseColor(blue))
            setPadding(0, 0, 10, 0)
        })
        btn.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Add Items", "\u0622\u0626\u0679\u0645\u0632 \u0634\u0627\u0645\u0644 \u06A9\u0631\u06CC\u06BA")
            textSize = 14.5f
            setTextColor(Color.parseColor(blue))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        wrap.addView(btn)
        return wrap
    }

    private fun onAddItems() {
        // Hook this up to the item picker / ItemDao when wiring in actual data.
        Toast.makeText(this, Loc.t(this, "Add Items", "\u0622\u0626\u0679\u0645\u0632 \u0634\u0627\u0645\u0644 \u06A9\u0631\u06CC\u06BA"), Toast.LENGTH_SHORT).show()
    }

    private fun sectionDivider() = View(this).apply {
        setBackgroundColor(Color.parseColor(divider))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (10 * resources.displayMetrics.density).toInt()
        )
    }

    // ================= CHARGES =================
    private fun buildChargesSection(): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor(cardWhite))
        }

        wrap.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Charges", "\u0686\u0627\u0631\u062C\u0632")
            textSize = 15f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        wrap.addView(spacer(10))
        wrap.addView(View(this).apply {
            setBackgroundColor(Color.parseColor(divider))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        })
        wrap.addView(spacer(18))

        val shippingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shippingRow.addView(TextView(this).apply {
            text = Loc.t(this@PurchaseActivity, "Shipping", "\u0634\u067E\u0646\u06AF")
            textSize = 14.5f
            setTextColor(Color.parseColor(textDark))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val shippingBox = currencyInputBox()
        shippingField = shippingBox.second
        shippingRow.addView(shippingBox.first)
        wrap.addView(shippingRow)
        wrap.addView(spacer(14))

        val otherRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; gravity = Gravity.END }
        val otherBox = currencyInputBox(enabled = true)
        otherChargeField = otherBox.second
        otherRow.addView(otherBox.first)
        wrap.addView(otherRow)

        return wrap
    }

    private fun currencyInputBox(enabled: Boolean = true): Pair<LinearLayout, EditText> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardWhite))
                cornerRadius = 8f
                setStroke(2, Color.parseColor(cardBorder))
            }
            layoutParams = LinearLayout.LayoutParams(
                (170 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        box.addView(TextView(this).apply {
            text = "Rs"
            textSize = 13.5f
            setTextColor(Color.parseColor(labelGray))
            setPadding(16, 16, 12, 16)
            setBackgroundColor(Color.parseColor(cardBg))
        })
        val field = EditText(this).apply {
            hint = "0.00"
            setText(if (enabled) "" else "")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = null
            textSize = 13.5f
            setPadding(14, 14, 14, 14)
            isEnabled = enabled
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        box.addView(field)
        return Pair(box, field)
    }

    // ================= BOTTOM ACTION BAR =================
    private fun buildBottomBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 4, 20, 4)
            setBackgroundColor(Color.parseColor(cardWhite))
            elevation = 10f

            addView(TextView(this@PurchaseActivity).apply {
                text = Loc.t(this@PurchaseActivity, "Delete", "\u0688\u06CC\u0644\u06CC\u0679")
                textSize = 15f
                setTextColor(Color.parseColor(textDark))
                gravity = Gravity.CENTER
                setPadding(0, 22, 0, 22)
                setOnClickListener { confirmDelete() }
            })

            addView(TextView(this@PurchaseActivity).apply {
                text = Loc.t(this@PurchaseActivity, "Save", "\u0645\u062D\u0641\u0648\u0638 \u06A9\u0631\u06CC\u06BA")
                textSize = 15.5f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 22, 0, 22)
                background = GradientDrawable().apply { setColor(Color.parseColor(blue)) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(20, 10, 20, 10)
                }
                setOnClickListener { savePurchase() }
            })

            addView(TextView(this@PurchaseActivity).apply {
                text = "\u22EE"
                textSize = 18f
                setTextColor(Color.parseColor(textDark))
                gravity = Gravity.CENTER
                setPadding(16, 22, 4, 22)
                setOnClickListener { showOverflowMenu(this) }
            })
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Delete purchase?", "\u062E\u0631\u06CC\u062F\u0627\u0631\u06CC \u062E\u062A\u0645 \u06A9\u0631\u06CC\u06BA\u061F"))
            .setMessage(Loc.t(this, "This cannot be undone.", "\u0627\u0633\u06D2 \u0648\u0627\u067E\u0633 \u0646\u06C1\u06CC\u06BA \u06A9\u06CC\u0627 \u062C\u0627 \u0633\u06A9\u062A\u0627."))
            .setPositiveButton(Loc.t(this, "Delete", "\u0688\u06CC\u0644\u06CC\u0679")) { _, _ ->
                // Hook this up to PurchaseDao.delete() when wiring in actual data.
                finish()
            }
            .setNegativeButton(Loc.t(this, "Cancel", "\u0645\u0646\u0633\u0648\u062E \u06A9\u0631\u06CC\u06BA"), null)
            .show()
    }

    private fun savePurchase() {
        // Hook this up to PurchaseDao.insert()/update() when wiring in actual data.
        Toast.makeText(this, Loc.t(this, "Saved: Rs %.2f".format(grandTotal()), "\u0645\u062D\u0641\u0648\u0638: \u0631\u0648\u067E\u06D2 %.2f".format(grandTotal())), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun showOverflowMenu(anchor: View) {
        val options = arrayOf(
            Loc.t(this, "Print", "\u067E\u0631\u0646\u0679"),
            Loc.t(this, "Share", "\u0634\u06CC\u0626\u0631 \u06A9\u0631\u06CC\u06BA"),
            Loc.t(this, "Duplicate", "\u0688\u067E\u0644\u06CC\u06A9\u06CC\u0679")
        )
        AlertDialog.Builder(this)
            .setItems(options) { _, _ -> /* hook up per-action handling */ }
            .show()
    }

    // ================= UI helpers =================
    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
}
