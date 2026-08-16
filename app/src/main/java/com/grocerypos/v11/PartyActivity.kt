package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Customer
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Supplier
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PartyActivity : AppCompatActivity() {

    // ---- Palette matched to the Dashboard ----
    private val bg = "#F4F3FB"
    private val gradientStart = "#3949AB"
    private val gradientEnd = "#5C6BC0"
    private val blue = "#1565C0"
    private val orange = "#EF6C00"
    private val green = "#2E7D32"
    private val red = "#C62828"
    private val cardWhite = "#FFFFFF"
    private val labelGray = "#9E9E9E"

    private lateinit var tabRow: LinearLayout
    private lateinit var formCard: LinearLayout
    private lateinit var nameField: EditText
    private lateinit var phoneField: EditText
    private lateinit var creditLimitField: EditText
    private lateinit var creditLimitBox: LinearLayout
    private lateinit var openingBalanceField: EditText
    private lateinit var listContainer: LinearLayout
    private lateinit var saveButton: Button
    private lateinit var sectionAccentText: TextView

    private var showingCustomers = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= GRADIENT HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 40, 24, 32)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(gradientStart), Color.parseColor(gradientEnd))
            )
        }
        header.addView(TextView(this).apply {
            text = "\uD83D\uDC65"
            textSize = 22f
            gravity = Gravity.CENTER
            background = ovalBg(cardWhite)
            width = (48 * resources.displayMetrics.density).toInt()
            height = (48 * resources.displayMetrics.density).toInt()
        })
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerText.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Customers & Suppliers", "کسٹمرز اور سپلائرز")
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerText.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Manage parties & view ledgers", "پارٹیز کا انتظام اور کھاتے دیکھیں")
            textSize = 12f
            setTextColor(Color.parseColor("#D5D8F5"))
        })
        header.addView(headerText)
        outer.addView(header)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 28)
        }

        // ================= TABS (pill style) =================
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabRow)
        root.addView(spacer(16))

        // ================= ADD PARTY FORM CARD =================
        formCard = premiumCard().apply { setPadding(22, 20, 22, 20) }

        sectionAccentText = TextView(this).apply {
            text = "\uD83D\uDC64  " + Loc.t(this@PartyActivity, "Add Customer", "کسٹمر شامل کریں")
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(blue))
            setPadding(0, 0, 0, 12)
        }
        formCard.addView(sectionAccentText)

        val nameBox = innerField()
        nameField = EditText(this).apply { hint = Loc.t(this@PartyActivity, "Name *", "نام *"); background = null; textSize = 15f }
        nameBox.addView(nameField)
        formCard.addView(nameBox)
        formCard.addView(spacer(10))

        val phoneBox = innerField()
        phoneField = EditText(this).apply {
            hint = Loc.t(this@PartyActivity, "Phone (optional)", "فون (اختیاری)")
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_PHONE
        }
        phoneBox.addView(phoneField)
        formCard.addView(phoneBox)
        formCard.addView(spacer(10))

        creditLimitBox = innerField()
        creditLimitField = EditText(this).apply {
            hint = Loc.t(this@PartyActivity, "Credit Limit (optional)", "کریڈٹ لیمٹ (اختیاری)")
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        creditLimitBox.addView(creditLimitField)
        formCard.addView(creditLimitBox)
        formCard.addView(spacer(10))

        val openingBox = innerField()
        openingBalanceField = EditText(this).apply {
            hint = Loc.t(this@PartyActivity, "Opening Balance (Rs, if any previous due)", "ابتدائی بیلنس (روپے، اگر کوئی پرانا واجب الادا ہو)")
            background = null
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        openingBox.addView(openingBalanceField)
        formCard.addView(openingBox)
        formCard.addView(spacer(14))

        saveButton = Button(this).apply {
            text = Loc.t(this@PartyActivity, "SAVE", "محفوظ کریں")
            setTextColor(Color.WHITE)
            textSize = 14f
            background = roundedBackground(blue, 14)
            setPadding(0, 20, 0, 20)
            setOnClickListener { saveParty() }
        }
        formCard.addView(saveButton)
        root.addView(formCard)
        root.addView(spacer(18))

        // ================= LIST HEADER =================
        val listHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 10)
        }
        listHeaderRow.addView(TextView(this).apply {
            text = "\uD83D\uDCCB  "
            textSize = 15f
        })
        listHeaderRow.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Party List", "پارٹی لسٹ")
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        listHeaderRow.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Tap a party for full history", "مکمل تاریخ کے لیے پارٹی پر ٹیپ کریں")
            textSize = 11f
            setTextColor(Color.parseColor(labelGray))
        })
        root.addView(listHeaderRow)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        val scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(root)
        }
        outer.addView(scrollArea)

        setContentView(outer)

        buildTabs()
        showCustomers()
    }

    override fun onResume() {
        super.onResume()
        if (showingCustomers) loadCustomers() else loadSuppliers()
    }

    // ================= Tabs =================
    private fun buildTabs() {
        tabRow.removeAllViews()
        tabRow.addView(Button(this).apply {
            text = "\uD83D\uDC64  " + Loc.t(this@PartyActivity, "CUSTOMERS", "کسٹمرز")
            setTextColor(Color.WHITE)
            textSize = 12f
            background = roundedBackground(if (showingCustomers) blue else "#B0B7C3", 24)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
            setOnClickListener { showCustomers() }
        })
        tabRow.addView(Button(this).apply {
            text = "\uD83D\uDCE6  " + Loc.t(this@PartyActivity, "SUPPLIERS", "سپلائرز")
            setTextColor(Color.WHITE)
            textSize = 12f
            background = roundedBackground(if (!showingCustomers) orange else "#B0B7C3", 24)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) }
            setOnClickListener { showSuppliers() }
        })
    }

    private fun showCustomers() {
        showingCustomers = true
        buildTabs()
        creditLimitBox.visibility = View.VISIBLE
        sectionAccentText.text = "\uD83D\uDC64  " + Loc.t(this@PartyActivity, "Add Customer", "کسٹمر شامل کریں")
        sectionAccentText.setTextColor(Color.parseColor(blue))
        saveButton.background = roundedBackground(blue, 14)
        loadCustomers()
    }

    private fun showSuppliers() {
        showingCustomers = false
        buildTabs()
        creditLimitBox.visibility = View.GONE
        sectionAccentText.text = "\uD83D\uDCE6  " + Loc.t(this@PartyActivity, "Add Supplier", "سپلائر شامل کریں")
        sectionAccentText.setTextColor(Color.parseColor(orange))
        saveButton.background = roundedBackground(orange, 14)
        loadSuppliers()
    }

    private fun saveParty() {
        val n = nameField.text.toString().trim()
        if (n.isEmpty()) {
            Toast.makeText(this, Loc.t(this, "Name is required", "نام ضروری ہے"), Toast.LENGTH_SHORT).show()
            return
        }
        val phone = phoneField.text.toString().trim()
        val opening = openingBalanceField.text.toString().toDoubleOrNull() ?: 0.0

        lifecycleScope.launch {
            if (showingCustomers) {
                val limit = creditLimitField.text.toString().toDoubleOrNull() ?: 0.0
                PosDatabase.get(this@PartyActivity).customerDao().insert(
                    Customer(name = n, phone = phone, creditLimit = limit, openingBalance = opening, balance = 0.0)
                )
            } else {
                PosDatabase.get(this@PartyActivity).supplierDao().insert(
                    Supplier(name = n, phone = phone, openingBalance = opening, balance = 0.0)
                )
            }
            Toast.makeText(this@PartyActivity, Loc.t(this@PartyActivity, "Saved", "محفوظ ہو گیا"), Toast.LENGTH_SHORT).show()
            nameField.text.clear()
            phoneField.text.clear()
            creditLimitField.text.clear()
            openingBalanceField.text.clear()
        }
    }

    private fun loadCustomers() {
        lifecycleScope.launch {
            PosDatabase.get(this@PartyActivity).customerDao().all().collectLatest { list ->
                listContainer.removeAllViews()
                if (list.isEmpty()) {
                    listContainer.addView(emptyCard(Loc.t(this@PartyActivity, "No customers yet", "کوئی کسٹمر نہیں ہے")))
                }
                for (c in list) {
                    listContainer.addView(partyRow(c.name, c.phone, c.openingBalance, c.balance, blue, "\uD83D\uDC64") { openCustomerHistory(c) })
                }
            }
        }
    }

    private fun loadSuppliers() {
        lifecycleScope.launch {
            PosDatabase.get(this@PartyActivity).supplierDao().all().collectLatest { list ->
                listContainer.removeAllViews()
                if (list.isEmpty()) {
                    listContainer.addView(emptyCard(Loc.t(this@PartyActivity, "No suppliers yet", "کوئی سپلائر نہیں ہے")))
                }
                for (s in list) {
                    listContainer.addView(partyRow(s.name, s.phone, s.openingBalance, s.balance, orange, "\uD83D\uDCE6") { openSupplierHistory(s) })
                }
            }
        }
    }

    // ================= Premium party row card =================
    private fun partyRow(
        name: String,
        phone: String,
        opening: Double,
        running: Double,
        accentHex: String,
        icon: String,
        onClick: () -> Unit
    ): LinearLayout {
        val closing = opening + running
        val outerRow = premiumCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 16, 18, 16)
            setOnClickListener { onClick() }
        }

        outerRow.addView(TextView(this).apply {
            text = icon
            textSize = 18f
            gravity = Gravity.CENTER
            background = ovalBg(accentHex)
            width = (42 * resources.displayMetrics.density).toInt()
            height = (42 * resources.displayMetrics.density).toInt()
        })

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 0, 12, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoCol.addView(TextView(this).apply {
            text = name
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (phone.isNotEmpty()) {
            infoCol.addView(TextView(this).apply {
                text = phone
                textSize = 12f
                setTextColor(Color.parseColor(labelGray))
            })
        }
        val balRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 0) }
        balRow.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Opening", "ابتدائی") + ": Rs %.2f".format(opening)
            textSize = 11f
            setTextColor(Color.parseColor(labelGray))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        infoCol.addView(balRow)
        infoCol.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Tap for full history  ›", "مکمل تاریخ کے لیے ٹیپ کریں  ›")
            textSize = 11f
            setTextColor(Color.parseColor(accentHex))
            setPadding(0, 4, 0, 0)
        })
        outerRow.addView(infoCol)

        outerRow.addView(TextView(this).apply {
            text = "Rs %.2f".format(closing)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(if (closing > 0) red else green))
        })

        return outerRow
    }

    // ================= Customer full history =================
    private fun openCustomerHistory(c: Customer) {
        lifecycleScope.launch {
            val sales = PosDatabase.get(this@PartyActivity).saleDao().salesByCustomer(c.id)
            val content = historyDialogContainer(c.name, blue, "\uD83D\uDC64", c.openingBalance, c.balance)
            val body = content.getChildAt(1) as LinearLayout

            if (sales.isEmpty()) {
                body.addView(emptyCard(Loc.t(this@PartyActivity, "No sales yet", "ابھی تک کوئی سیل نہیں ہوئی")))
            } else {
                val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                for (s in sales) {
                    body.addView(historyRow(s.invoice, fmt.format(Date(s.createdAt)), s.total, s.paid, blue))
                }
            }

            val dialog = AlertDialog.Builder(this@PartyActivity).setView(content).create()
            (content.getChildAt(2) as LinearLayout).addView(Button(this@PartyActivity).apply {
                text = Loc.t(this@PartyActivity, "Close", "بند کریں")
                setTextColor(Color.WHITE)
                background = roundedBackground(blue, 14)
                setOnClickListener { dialog.dismiss() }
            })
            dialog.show()
        }
    }

    // ================= Supplier full history =================
    private fun openSupplierHistory(s: Supplier) {
        lifecycleScope.launch {
            val purchases = PosDatabase.get(this@PartyActivity).purchaseDao().purchasesBySupplier(s.id)
            val content = historyDialogContainer(s.name, orange, "\uD83D\uDCE6", s.openingBalance, s.balance)
            val body = content.getChildAt(1) as LinearLayout

            if (purchases.isEmpty()) {
                body.addView(emptyCard(Loc.t(this@PartyActivity, "No purchases yet", "ابھی تک کوئی خریداری نہیں ہوئی")))
            } else {
                val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                for (p in purchases) {
                    body.addView(historyRow(p.billNo, fmt.format(Date(p.createdAt)), p.total, p.paid, orange))
                }
            }

            val dialog = AlertDialog.Builder(this@PartyActivity).setView(content).create()
            (content.getChildAt(2) as LinearLayout).addView(Button(this@PartyActivity).apply {
                text = Loc.t(this@PartyActivity, "Close", "بند کریں")
                setTextColor(Color.WHITE)
                background = roundedBackground(orange, 14)
                setOnClickListener { dialog.dismiss() }
            })
            dialog.show()
        }
    }

    // ================= shared dialog helpers (premium gradient header dialog) =================
    private fun historyDialogContainer(name: String, colorHex: String, icon: String, opening: Double, running: Double): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bg))
                cornerRadius = 20f
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 24, 28, 24)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(colorHex), darken(colorHex))
            ).apply {
                cornerRadii = floatArrayOf(20f, 20f, 20f, 20f, 0f, 0f, 0f, 0f)
            }
        }
        header.addView(TextView(this).apply {
            text = icon
            textSize = 18f
            gravity = Gravity.CENTER
            background = ovalBg(cardWhite)
            width = (40 * resources.displayMetrics.density).toInt()
            height = (40 * resources.displayMetrics.density).toInt()
        })
        val headerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 0, 0)
        }
        headerTextCol.addView(TextView(this).apply {
            text = name; textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val balRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 0) }
        balRow.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Opening", "ابتدائی") + ": Rs %.2f".format(opening)
            setTextColor(Color.parseColor("#EAEAFF")); textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        balRow.addView(TextView(this).apply {
            text = Loc.t(this@PartyActivity, "Closing", "اختتامی") + ": Rs %.2f".format(opening + running)
            setTextColor(Color.WHITE); textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        headerTextCol.addView(balRow)
        header.addView(headerTextCol)
        outer.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 8)
        }
        outer.addView(body)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 8, 20, 20)
        }
        outer.addView(footer)
        return outer
    }

    private fun historyRow(ref: String, date: String, total: Double, paid: Double, colorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            background = elevatedCardBg()
            elevation = 3f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }

            val top = LinearLayout(this@PartyActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this@PartyActivity).apply {
                text = ref; textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(TextView(this@PartyActivity).apply {
                text = "Rs %.2f".format(total)
                setTextColor(Color.parseColor(colorHex))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 14f
            })
            addView(top)
            addView(TextView(this@PartyActivity).apply {
                text = "$date  •  " + Loc.t(this@PartyActivity, "Paid", "ادا شدہ") + ": Rs %.2f".format(paid)
                textSize = 11f
                setTextColor(Color.parseColor(labelGray))
                setPadding(0, 4, 0, 0)
            })
        }
    }

    private fun emptyCard(text: String) = premiumCard().apply {
        gravity = Gravity.CENTER
        setPadding(20, 24, 20, 24)
        addView(TextView(this@PartyActivity).apply {
            this.text = text
            setTextColor(Color.parseColor(labelGray))
            textSize = 13f
        })
    }

    // ---- UI helpers ----

    /** Elevated white card matching the dashboard's stat cards. */
    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 16, 20, 16)
        background = elevatedCardBg()
        elevation = 4f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) }
    }

    /** Lighter inner wrapper used for text fields inside a card. */
    private fun innerField() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 10, 18, 10)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F7F7FB"))
            cornerRadius = 10f
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun elevatedCardBg() = GradientDrawable().apply {
        setColor(Color.parseColor(cardWhite))
        cornerRadius = 16f
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
    }

    private fun roundedBackground(colorHex: String, cornerRadius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        this.cornerRadius = cornerRadius.toFloat()
    }

    private fun darken(colorHex: String): Int {
        val c = Color.parseColor(colorHex)
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] *= 0.75f
        return Color.HSVToColor(hsv)
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(0xFFEEEEEE.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        }
    }
}
