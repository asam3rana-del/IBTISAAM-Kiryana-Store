package com.grocerypos.v11.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.ShellCustomer
import com.grocerypos.v11.ShellTransaction
import com.grocerypos.v11.ShopEmptyShellLog
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// NEW (Bottle Shell Ledger): two things tracked side by side —
// 1) which customers still owe an empty shell back (they were given a filled bottle
//    without handing one over in exchange), and
// 2) how many empty shells the shop itself currently has on hand.
// A customer "Return" automatically feeds into the shop's own count (see
// ShellDao/PosDatabase in Database.kt), since a returned shell is physically back at
// the shop; everything else about the shop's count is a manual +/- (collected extra,
// sent off for refill, recount correction).
class ShellLedgerActivity : AppCompatActivity() {

    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val teal = "#0F9B8E"
    private val amber = "#F5A524"
    private val red = "#E5484D"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private lateinit var summaryBox: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var resultsBox: LinearLayout
    private var allCustomers: List<ShellCustomer> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(premiumHeader("\uD83C\uDF7E",
            Loc.t(this, "Bottle Shell Ledger", "بوتل شیل کھاتہ"),
            Loc.t(this, "Customer dues & shop empty stock", "گاہکوں کا حساب اور دکان کا اسٹاک")))

        summaryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(summaryBox)
        root.addView(spacer(6))

        val addBtn = TextView(this).apply {
            text = "+  " + Loc.t(this@ShellLedgerActivity, "New Entry (Issue / Return)", "نئی اندراج (دیں / واپس لیں)")
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(20, 22, 20, 22)
            background = roundedBg(primary, 16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 4, 0, 16) }
            setOnClickListener { showAddEntryDialog(null) }
        }
        root.addView(addBtn)

        val sectionLabel = TextView(this).apply {
            text = Loc.t(this@ShellLedgerActivity, "Customers with shells owed", "گاہک جن پر شیل واجب ہیں")
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(textGray))
            setPadding(4, 0, 0, 8)
        }
        root.addView(sectionLabel)

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 4, 18, 4)
            background = strokedBg(border, "#FAFAFF", 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
        }
        searchBox.addView(TextView(this).apply { text = "\uD83D\uDD0D  "; textSize = 14f })
        searchField = EditText(this).apply {
            hint = Loc.t(this@ShellLedgerActivity, "Search customer name or phone…", "گاہک کا نام یا فون تلاش کریں…")
            setHintTextColor(Color.parseColor(textGray))
            setTextColor(Color.parseColor(textDark))
            background = null
            textSize = 14.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchBox.addView(searchField)
        root.addView(searchBox)

        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsBox)
        root.addView(spacer(30))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scroll)

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = renderList(s?.toString().orEmpty())
        })

        loadData()
    }

    override fun onResume() {
        super.onResume()
        if (::resultsBox.isInitialized) loadData()
    }

    private fun loadData() = lifecycleScope.launch {
        val db = PosDatabase.get(this@ShellLedgerActivity)
        val shopStock = db.shellDao().shopStockTotal()
        val totalOwed = db.shellDao().totalOwedByCustomers()
        allCustomers = db.shellDao().allCustomers()
        renderSummary(shopStock, totalOwed)
        renderList(searchField.text?.toString().orEmpty())
    }

    private fun renderSummary(shopStock: Int, totalOwed: Int) {
        summaryBox.removeAllViews()
        val shopCard = summaryCard("\uD83D\uDCE6", Loc.t(this, "Shop's empty shells", "دکان کی خالی شیلز"), "$shopStock", teal, "#E0F2F1")
        shopCard.setOnClickListener { showShopStockDialog(shopStock) }
        summaryBox.addView(shopCard)
        summaryBox.addView(summaryCard("\uD83E\uDDFE", Loc.t(this, "Owed by customers", "گاہکوں پر واجب"), "$totalOwed", amber, "#FFF3E0"))
    }

    private fun renderList(query: String) {
        resultsBox.removeAllViews()
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) allCustomers
            else allCustomers.filter { it.name.lowercase().contains(q) || it.phone.contains(q) }

        if (filtered.isEmpty()) {
            resultsBox.addView(TextView(this).apply {
                text = if (allCustomers.isEmpty())
                    Loc.t(this@ShellLedgerActivity, "No entries yet — tap \"New Entry\" to add one", "ابھی کوئی اندراج نہیں — \"نئی اندراج\" دبائیں")
                else Loc.t(this@ShellLedgerActivity, "No matching customer found", "کوئی گاہک نہیں ملا")
                setTextColor(Color.parseColor(textGray))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 40, 0, 0)
            })
            return
        }

        filtered.forEach { c ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 16, 20, 16)
                background = strokedBg(border, cardBg, 18)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
                applyElevation(this, 2f)
                isClickable = true
                isFocusable = true
                setOnClickListener { showCustomerDetail(c) }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            col.addView(TextView(this).apply { text = c.name; textSize = 14.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor(textDark)) })
            col.addView(TextView(this).apply {
                text = if (c.phone.isNotBlank()) c.phone else Loc.t(this@ShellLedgerActivity, "No phone on file", "فون نمبر درج نہیں")
                textSize = 12f; setTextColor(Color.parseColor(textGray)); setPadding(0, 3, 0, 0)
            })
            card.addView(col)

            card.addView(TextView(this).apply {
                text = "${c.shellsOwed}  " + Loc.t(this@ShellLedgerActivity, "owed", "واجب")
                textSize = 12.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = roundedBg(if (c.shellsOwed > 0) amber else teal, 12)
                setPadding(16, 8, 16, 8)
            })

            if (c.phone.isNotBlank()) {
                card.addView(spacer(10).apply { layoutParams = LinearLayout.LayoutParams(10, 1) })
                card.addView(TextView(this).apply {
                    text = "\uD83D\uDCDE"; textSize = 15f
                    setPadding(18, 9, 18, 9)
                    background = ovalBg("#E9E6FF")
                    setOnClickListener { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}"))) }
                })
            }
            resultsBox.addView(card)
        }
    }

    // ---------------- Add Entry dialog (Issue or Return) ----------------
    private fun showAddEntryDialog(prefill: ShellCustomer?) {
        var isIssue = true

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 20, 36, 8) }

        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) } }
        val issueChip = TextView(this).apply {
            text = "  \uD83C\uDF7E  " + Loc.t(this@ShellLedgerActivity, "Gave (shell owed)", "دی (شیل واجب)")
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
        }
        val returnChip = TextView(this).apply {
            text = "  \u2705  " + Loc.t(this@ShellLedgerActivity, "Returned shell", "شیل واپس ملی")
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 }
        }
        typeRow.addView(issueChip); typeRow.addView(returnChip)
        root.addView(typeRow)

        val nameInput = EditText(this).apply {
            hint = Loc.t(this@ShellLedgerActivity, "Customer name", "گاہک کا نام")
            setText(prefill?.name.orEmpty())
            setPadding(20, 18, 20, 18)
            background = strokedBg(border, "#FAFAFF", 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
            isEnabled = prefill == null
        }
        root.addView(nameInput)

        val phoneInput = EditText(this).apply {
            hint = Loc.t(this@ShellLedgerActivity, "Phone (optional)", "فون نمبر (اختیاری)")
            setText(prefill?.phone.orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(20, 18, 20, 18)
            background = strokedBg(border, "#FAFAFF", 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
        }
        root.addView(phoneInput)

        val qtyInput = EditText(this).apply {
            hint = Loc.t(this@ShellLedgerActivity, "How many shells?", "کتنی شیلیں؟")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(20, 18, 20, 18)
            background = strokedBg(border, "#FAFAFF", 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
        }
        root.addView(qtyInput)

        val noteInput = EditText(this).apply {
            hint = Loc.t(this@ShellLedgerActivity, "Note (optional)", "نوٹ (اختیاری)")
            setPadding(20, 18, 20, 18)
            background = strokedBg(border, "#FAFAFF", 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(noteInput)

        fun styleChips() {
            issueChip.background = if (isIssue) roundedBg(amber, 14) else strokedBg(border, cardBg, 14)
            issueChip.setTextColor(if (isIssue) Color.WHITE else Color.parseColor(textGray))
            returnChip.background = if (!isIssue) roundedBg(teal, 14) else strokedBg(border, cardBg, 14)
            returnChip.setTextColor(if (!isIssue) Color.WHITE else Color.parseColor(textGray))
        }
        issueChip.setOnClickListener { isIssue = true; styleChips() }
        returnChip.setOnClickListener { isIssue = false; styleChips() }
        styleChips()
        if (prefill != null) { isIssue = false; styleChips() }

        val scroll = ScrollView(this).apply { addView(root) }

        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Bottle Shell Entry", "بوتل شیل اندراج"))
            .setView(scroll)
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .setPositiveButton(Loc.t(this, "Save", "محفوظ کریں"), null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = nameInput.text.toString().trim()
                        val qty = qtyInput.text.toString().toIntOrNull()
                        if (name.isEmpty()) {
                            Toast.makeText(this, Loc.t(this, "Enter a customer name", "گاہک کا نام درج کریں"), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (qty == null || qty <= 0) {
                            Toast.makeText(this, Loc.t(this, "Enter a valid quantity", "درست تعداد درج کریں"), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val phone = phoneInput.text.toString().trim()
                        val note = noteInput.text.toString().trim()
                        if (isIssue) saveIssue(name, phone, qty, note) else saveReturnByName(name, qty, note)
                        dialog.dismiss()
                    }
                }
            }
            .show()
    }

    private fun saveIssue(name: String, phone: String, qty: Int, note: String) = lifecycleScope.launch {
        val db = PosDatabase.get(this@ShellLedgerActivity)
        val dao = db.shellDao()
        val existing = dao.findByName(name)
        val customer = if (existing != null) {
            val updated = existing.copy(
                phone = if (phone.isNotBlank()) phone else existing.phone,
                shellsOwed = existing.shellsOwed + qty,
                updatedAt = System.currentTimeMillis(),
                dirty = true
            )
            dao.updateCustomer(updated)
            updated
        } else {
            val newId = dao.insertCustomer(ShellCustomer(name = name, phone = phone, shellsOwed = qty))
            dao.getCustomer(newId)!!
        }
        dao.insertTransaction(ShellTransaction(customerId = customer.id, type = "ISSUE", qty = qty, note = note))
        Toast.makeText(this@ShellLedgerActivity, Loc.t(this@ShellLedgerActivity, "Saved", "محفوظ ہو گیا"), Toast.LENGTH_SHORT).show()
        loadData()
    }

    private fun saveReturnByName(name: String, qty: Int, note: String) = lifecycleScope.launch {
        val db = PosDatabase.get(this@ShellLedgerActivity)
        val customer = db.shellDao().findByName(name)
        if (customer == null) {
            Toast.makeText(this@ShellLedgerActivity,
                Loc.t(this@ShellLedgerActivity, "No such customer — use \"Gave\" first to add them", "ایسا کوئی گاہک نہیں — پہلے \"دی\" استعمال کریں"),
                Toast.LENGTH_LONG).show()
            return@launch
        }
        recordReturn(customer, qty, note)
    }

    private fun recordReturn(customer: ShellCustomer, qty: Int, note: String) = lifecycleScope.launch {
        val db = PosDatabase.get(this@ShellLedgerActivity)
        val dao = db.shellDao()
        val updated = customer.copy(
            shellsOwed = (customer.shellsOwed - qty).coerceAtLeast(0),
            updatedAt = System.currentTimeMillis(),
            dirty = true
        )
        dao.updateCustomer(updated)
        dao.insertTransaction(ShellTransaction(customerId = customer.id, type = "RETURN", qty = qty, note = note))
        // A returned shell is physically back at the shop, so the shop's own empty-stock
        // count goes up by the same amount — kept in sync automatically rather than
        // needing a second manual step.
        dao.insertShopLog(ShopEmptyShellLog(delta = qty, reason = "CUSTOMER_RETURN", note = "${Loc.t(this@ShellLedgerActivity, "From", "کی طرف سے")} ${customer.name}"))
        Toast.makeText(this@ShellLedgerActivity, Loc.t(this@ShellLedgerActivity, "Saved", "محفوظ ہو گیا"), Toast.LENGTH_SHORT).show()
        loadData()
    }

    // ---------------- Customer detail (history + quick return) ----------------
    private fun showCustomerDetail(customer: ShellCustomer) = lifecycleScope.launch {
        val db = PosDatabase.get(this@ShellLedgerActivity)
        val history = db.shellDao().historyForCustomer(customer.id)

        val root = LinearLayout(this@ShellLedgerActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 20, 36, 8) }
        root.addView(TextView(this@ShellLedgerActivity).apply {
            text = "${Loc.t(this@ShellLedgerActivity, "Currently owed", "فی الحال واجب")}: ${customer.shellsOwed}"
            textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor(amber))
            setPadding(0, 0, 0, 14)
        })

        if (history.isEmpty()) {
            root.addView(TextView(this@ShellLedgerActivity).apply {
                text = Loc.t(this@ShellLedgerActivity, "No history yet", "ابھی کوئی ریکارڈ نہیں")
                setTextColor(Color.parseColor(textGray)); textSize = 12.5f
            })
        } else {
            val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
            history.take(30).forEach { t ->
                val row = LinearLayout(this@ShellLedgerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 8)
                }
                val label = if (t.type == "ISSUE") Loc.t(this@ShellLedgerActivity, "Gave", "دی") else Loc.t(this@ShellLedgerActivity, "Returned", "واپس ملی")
                val color = if (t.type == "ISSUE") amber else teal
                row.addView(TextView(this@ShellLedgerActivity).apply {
                    text = "$label — ${t.qty}"
                    textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor(color))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this@ShellLedgerActivity).apply {
                    text = fmt.format(Date(t.createdAt))
                    textSize = 11.5f; setTextColor(Color.parseColor(textGray))
                })
                root.addView(row)
            }
        }

        val scroll = ScrollView(this@ShellLedgerActivity).apply { addView(root) }

        val builder = AlertDialog.Builder(this@ShellLedgerActivity)
            .setTitle(customer.name)
            .setView(scroll)
            .setNegativeButton(Loc.t(this@ShellLedgerActivity, "Close", "بند کریں"), null)

        if (customer.shellsOwed > 0) {
            builder.setPositiveButton(Loc.t(this@ShellLedgerActivity, "Record Return", "واپسی درج کریں")) { _, _ ->
                showQuickReturnDialog(customer)
            }
        }
        builder.show()
    }

    private fun showQuickReturnDialog(customer: ShellCustomer) {
        val qtyInput = EditText(this).apply {
            hint = Loc.t(this@ShellLedgerActivity, "How many shells returned?", "کتنی شیلیں واپس ملیں؟")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(20, 18, 20, 18)
            background = strokedBg(border, "#FAFAFF", 14)
        }
        val padded = FrameLayout(this).apply {
            setPadding(36, 12, 36, 0)
            addView(qtyInput)
        }
        AlertDialog.Builder(this)
            .setTitle(customer.name)
            .setView(padded)
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .setPositiveButton(Loc.t(this, "Save", "محفوظ کریں"), null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val qty = qtyInput.text.toString().toIntOrNull()
                        if (qty == null || qty <= 0) {
                            Toast.makeText(this, Loc.t(this, "Enter a valid quantity", "درست تعداد درج کریں"), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        recordReturn(customer, qty, "")
                        dialog.dismiss()
                    }
                }
            }
            .show()
    }

    // ---------------- Shop's own empty-stock adjustment ----------------
    private fun showShopStockDialog(currentStock: Int) {
        var isAdd = true

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 20, 36, 8) }
        root.addView(TextView(this).apply {
            text = "${Loc.t(this@ShellLedgerActivity, "Current shop stock", "دکان کا موجودہ اسٹاک")}: $currentStock"
            textSize = 13f; setTextColor(Color.parseColor(textGray)); setPadding(0, 0, 0, 16)
        })

        val signRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) } }
        val plusChip = TextView(this).apply {
            text = "+ " + Loc.t(this@ShellLedgerActivity, "Add", "شامل کریں")
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setPadding(20, 14, 20, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
        }
        val minusChip = TextView(this).apply {
            text = "\u2212 " + Loc.t(this@ShellLedgerActivity, "Remove", "کم کریں")
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setPadding(20, 14, 20, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 }
        }
        signRow.addView(plusChip); signRow.addView(minusChip)
        root.addView(signRow)

        val qtyInput = EditText(this).apply {
            hint = Loc.t(this@ShellLedgerActivity, "Quantity", "تعداد")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(20, 18, 20, 18)
            background = strokedBg(border, "#FAFAFF", 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 12) }
        }
        root.addView(qtyInput)

        val noteInput = EditText(this).apply {
            hint = Loc.t(this@ShellLedgerActivity, "Reason (e.g. sent for refill)", "وجہ (مثلاً ریفل کے لیے بھیجی)")
            setPadding(20, 18, 20, 18)
            background = strokedBg(border, "#FAFAFF", 14)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(noteInput)

        fun styleChips() {
            plusChip.background = if (isAdd) roundedBg(teal, 14) else strokedBg(border, cardBg, 14)
            plusChip.setTextColor(if (isAdd) Color.WHITE else Color.parseColor(textGray))
            minusChip.background = if (!isAdd) roundedBg(red, 14) else strokedBg(border, cardBg, 14)
            minusChip.setTextColor(if (!isAdd) Color.WHITE else Color.parseColor(textGray))
        }
        plusChip.setOnClickListener { isAdd = true; styleChips() }
        minusChip.setOnClickListener { isAdd = false; styleChips() }
        styleChips()

        val scroll = ScrollView(this).apply { addView(root) }

        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Shop Empty Stock", "دکان کی خالی شیلیں"))
            .setView(scroll)
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .setPositiveButton(Loc.t(this, "Save", "محفوظ کریں"), null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val qty = qtyInput.text.toString().toIntOrNull()
                        if (qty == null || qty <= 0) {
                            Toast.makeText(this, Loc.t(this, "Enter a valid quantity", "درست تعداد درج کریں"), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val note = noteInput.text.toString().trim()
                        saveShopAdjustment(isAdd, qty, note)
                        dialog.dismiss()
                    }
                }
            }
            .show()
    }

    private fun saveShopAdjustment(isAdd: Boolean, qty: Int, note: String) = lifecycleScope.launch {
        val db = PosDatabase.get(this@ShellLedgerActivity)
        val reason = if (isAdd) "MANUAL_ADD" else "MANUAL_REMOVE"
        db.shellDao().insertShopLog(ShopEmptyShellLog(delta = if (isAdd) qty else -qty, reason = reason, note = note))
        Toast.makeText(this@ShellLedgerActivity, Loc.t(this@ShellLedgerActivity, "Stock updated", "اسٹاک اپ ڈیٹ ہو گیا"), Toast.LENGTH_SHORT).show()
        loadData()
    }

    // ================= SHARED UI HELPERS (matches other Reports screens) =================
    private fun premiumHeader(icon: String, title: String, subtitle: String): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 20) }
            applyElevation(this, 10f)
        }
        header.addView(TextView(this).apply {
            text = "\u2039"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ovalBg("#33FFFFFF")
            val px = (36 * resources.displayMetrics.density).toInt(); width = px; height = px
            setOnClickListener { finish() }
        })
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(14, 1) })
        header.addView(TextView(this).apply {
            text = icon; textSize = 18f; gravity = Gravity.CENTER
            background = ovalBg("#5C4DFF")
            val px = (42 * resources.displayMetrics.density).toInt(); width = px; height = px
        })
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        val headerCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        headerCol.addView(TextView(this).apply { text = title; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        headerCol.addView(TextView(this).apply { text = subtitle; textSize = 10.5f; setTextColor(Color.parseColor("#D8D3FF")); setPadding(0, 4, 0, 0) })
        header.addView(headerCol)
        return header
    }

    private fun summaryCard(emoji: String, label: String, value: String, accentHex: String, tintHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 20, 22, 20)
            background = strokedBg(border, cardBg, 18)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
            applyElevation(this, 2f)
            isClickable = true
            isFocusable = true
            addView(FrameLayout(this@ShellLedgerActivity).apply {
                val size = (40 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(tintHex)) }
                addView(TextView(this@ShellLedgerActivity).apply {
                    text = emoji; textSize = 16f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            })
            val textCol = LinearLayout(this@ShellLedgerActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 0, 0, 0) }
            textCol.addView(TextView(this@ShellLedgerActivity).apply { text = label; setTextColor(Color.parseColor(textGray)); textSize = 12.5f; setTypeface(typeface, Typeface.BOLD) })
            textCol.addView(TextView(this@ShellLedgerActivity).apply { text = value; setTextColor(Color.parseColor(accentHex)); textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 4, 0, 0) })
            addView(textCol)
        }
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(colorHex)) }
    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = radius.toFloat() }
    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }
    private fun applyElevation(view: View, dp: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.elevation = dp * resources.displayMetrics.density
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }
    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }
}
