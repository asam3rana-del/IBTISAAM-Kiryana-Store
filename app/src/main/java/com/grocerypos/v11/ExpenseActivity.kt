package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Expense
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ExpenseActivity : AppCompatActivity() {

    private val bg = "#F3F4F9"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#1A1D2E"
    private val textMuted = "#8A8FA3"
    private val red = "#C62828"
    private val teal = "#0F9B8E"

    private val expenseCategories = listOf(
        "Food Authority License Fees",
        "Utility Bills",
        "Wages",
        "Fuel Expense",
        "Pick up Maintenance",
        "Fines",
        "Rent",
        "Income Tax Fees",
        "Miscellaneous"
    )

    private lateinit var amount: EditText
    private lateinit var reason: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var miscToggle: TextView
    private lateinit var miscDescBox: LinearLayout
    private lateinit var miscDesc: EditText
    private lateinit var todayTotalText: TextView
    private lateinit var monthTotalText: TextView
    private lateinit var listContainer: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 32)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(TextView(this).apply {
            text = Loc.t(this@ExpenseActivity, "Expenses", "اخراجات")
            textSize = 21f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(4, 0, 0, 22)
        })

        // ---- Totals: premium white cards ----
        val totalsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val todayCard = statCard("💸", Loc.t(this, "Today's Expense", "آج کا خرچہ"), red, "#FFEBEE")
        val monthCard = statCard("📅", Loc.t(this, "This Month", "اس مہینے"), red, "#FFEBEE")
        todayTotalText = todayCard.second
        monthTotalText = monthCard.second

        todayCard.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,9,0) }
        monthCard.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(9,0,0,0) }

        totalsRow.addView(todayCard.first)
        totalsRow.addView(monthCard.first)
        root.addView(totalsRow)
        root.addView(spacer(26))

        // ---- Entry form card ----
        val formCard = cardContainer()
        formCard.addView(sectionLabel(Loc.t(this, "New Expense", "نیا خرچہ")))

        val amountBox = outlinedBox()
        amount = EditText(this).apply {
            hint = Loc.t(this@ExpenseActivity, "Amount", "رقم")
            background = null
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        amountBox.addView(amount)
        formCard.addView(amountBox)

        formCard.addView(sectionLabel(Loc.t(this, "Expense Category", "خرچہ کیٹیگری")))
        val categoryBox = outlinedBox()
        categorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ExpenseActivity, android.R.layout.simple_spinner_dropdown_item, expenseCategories)
        }
        categoryBox.addView(categorySpinner)
        formCard.addView(categoryBox)

        // ---- Miscellaneous description: collapsed by default, expands on tap ----
        miscToggle = TextView(this).apply {
            text = "📝  " + Loc.t(this@ExpenseActivity, "Add description", "تفصیل شامل کریں")
            textSize = 12f
            setTextColor(Color.parseColor(teal))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(4, 0, 0, 10)
            visibility = View.GONE
            setOnClickListener {
                miscDescBox.visibility = View.VISIBLE
                miscToggle.visibility = View.GONE
                miscDesc.requestFocus()
            }
        }
        formCard.addView(miscToggle)

        miscDescBox = outlinedBox().apply { visibility = View.GONE }
        miscDesc = EditText(this).apply {
            hint = Loc.t(this@ExpenseActivity, "Describe this expense", "اس خرچے کی تفصیل لکھیں")
            background = null
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
        }
        miscDescBox.addView(miscDesc)
        formCard.addView(miscDescBox)

        categorySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isMisc = expenseCategories.getOrNull(position) == "Miscellaneous"
                if (isMisc) {
                    if (miscDesc.text.isNullOrBlank()) {
                        miscToggle.visibility = View.VISIBLE
                        miscDescBox.visibility = View.GONE
                    } else {
                        miscToggle.visibility = View.GONE
                        miscDescBox.visibility = View.VISIBLE
                    }
                } else {
                    miscToggle.visibility = View.GONE
                    miscDescBox.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val reasonBox = outlinedBox()
        reason = EditText(this).apply { hint = Loc.t(this@ExpenseActivity, "Note (optional)", "نوٹ (اختیاری)"); background = null }
        reasonBox.addView(reason)
        formCard.addView(reasonBox)

        formCard.addView(Button(this).apply {
            text = Loc.t(this@ExpenseActivity, "SAVE EXPENSE", "خرچہ محفوظ کریں")
            setTextColor(Color.WHITE)
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBg(red, 16)
            setPadding(0, 24, 0, 24)
            setOnClickListener { saveExpense() }
        })
        root.addView(formCard)
        root.addView(spacer(26))

        // ---- Recent expenses ----
        root.addView(sectionLabelPlain(Loc.t(this, "RECENT EXPENSES", "حالیہ اخراجات")))
        root.addView(spacer(10))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(spacer(30))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadTotals()
        loadExpenses()
    }

    // ---- UI helpers ----
    private fun cardContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 22, 24, 22)
        background = roundedBg(cardWhite, 24)
        elevation = 4f
    }

    private fun outlinedBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 14, 20, 14)
        background = strokedBg("#E6E8F0", "#FFFFFF", 14)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 14) }
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.parseColor(textDark))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(2, 0, 0, 14)
    }

    private fun sectionLabelPlain(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(Color.parseColor(textMuted))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(4, 0, 0, 0)
    }

    private fun statCard(emoji: String, label: String, accentHex: String, tintHex: String): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 20)
            background = roundedBg(cardWhite, 22)
            elevation = 4f
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(FrameLayout(this).apply {
            val size = (36 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(lighten(accentHex, 0.85f), Color.parseColor(tintHex))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            addView(TextView(this@ExpenseActivity).apply {
                text = emoji; textSize = 15f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        })
        topRow.addView(TextView(this).apply {
            text = "  $label"; setTextColor(Color.parseColor(textMuted)); textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(topRow)
        val valueText = TextView(this).apply {
            text = "Rs 0.00"
            setTextColor(Color.parseColor(accentHex))
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 10, 0, 0)
        }
        card.addView(valueText)
        return Pair(card, valueText)
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(fillHex))
        setStroke((1.2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex))
        cornerRadius = radius.toFloat()
    }

    private fun lighten(hex: String, factor: Float): Int {
        val base = Color.parseColor(hex)
        val r = (Color.red(base) + (255 - Color.red(base)) * factor).toInt()
        val g = (Color.green(base) + (255 - Color.green(base)) * factor).toInt()
        val bl = (Color.blue(base) + (255 - Color.blue(base)) * factor).toInt()
        return Color.rgb(r.coerceIn(0,255), g.coerceIn(0,255), bl.coerceIn(0,255))
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        val px = (heightDp * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px)
    }

    // ---- logic ----
    private fun saveExpense() {
        val amt = amount.text.toString().toDoubleOrNull()
        if (amt == null || amt <= 0.0) {
            Toast.makeText(this, Loc.t(this, "Enter a valid amount", "صحیح رقم لکھیں"), Toast.LENGTH_SHORT).show()
            return
        }
        val category = categorySpinner.selectedItem?.toString() ?: ""
        val misc = miscDesc.text.toString().trim()
        val note = reason.text.toString().trim()

        val fullDescription = buildString {
            if (category == "Miscellaneous" && misc.isNotEmpty()) {
                append(misc)
            }
            if (note.isNotEmpty()) {
                if (isNotEmpty()) append(" | ")
                append(note)
            }
        }

        lifecycleScope.launch {
            val db = PosDatabase.get(this@ExpenseActivity)
            val expense = Expense(category = category, description = fullDescription, amount = amt)
            val newId = db.expenseDao().insert(expense)
            val savedExpense = expense.copy(id = newId)
            SyncQueueHelper.enqueue(db, "expense", SyncQueueHelper.expenseEntityId(savedExpense), "create", SyncQueueHelper.expenseJson(savedExpense))
            SyncQueueHelper.trigger(this@ExpenseActivity)
            Toast.makeText(this@ExpenseActivity, Loc.t(this@ExpenseActivity, "Saved", "محفوظ ہو گیا"), Toast.LENGTH_SHORT).show()
            amount.text.clear()
            reason.text.clear()
            miscDesc.text.clear()
            miscDescBox.visibility = View.GONE
            miscToggle.visibility = View.GONE
            categorySpinner.setSelection(0)
            loadTotals()
        }
    }

    private fun loadTotals() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@ExpenseActivity)
            val dayCal = Calendar.getInstance()
            dayCal.set(Calendar.HOUR_OF_DAY, 0); dayCal.set(Calendar.MINUTE, 0)
            dayCal.set(Calendar.SECOND, 0); dayCal.set(Calendar.MILLISECOND, 0)
            val dayStart = dayCal.timeInMillis
            val dayEnd = dayStart + 24 * 60 * 60 * 1000L

            val monthCal = Calendar.getInstance()
            monthCal.set(Calendar.DAY_OF_MONTH, 1)
            monthCal.set(Calendar.HOUR_OF_DAY, 0); monthCal.set(Calendar.MINUTE, 0)
            monthCal.set(Calendar.SECOND, 0); monthCal.set(Calendar.MILLISECOND, 0)
            val monthStart = monthCal.timeInMillis
            val monthEnd = monthStart + 32L * 24 * 60 * 60 * 1000L

            todayTotalText.text = "Rs %.2f".format(db.expenseDao().totalBetween(dayStart, dayEnd))
            monthTotalText.text = "Rs %.2f".format(db.expenseDao().totalBetween(monthStart, monthEnd))
        }
    }

    private fun loadExpenses() {
        lifecycleScope.launch {
            PosDatabase.get(this@ExpenseActivity).expenseDao().all().collectLatest { list ->
                listContainer.removeAllViews()
                if (list.isEmpty()) {
                    listContainer.addView(TextView(this@ExpenseActivity).apply {
                        text = Loc.t(this@ExpenseActivity, "No expenses yet", "ابھی تک کوئی خرچہ نہیں")
                        setTextColor(Color.parseColor(textMuted))
                        setPadding(8,8,8,8)
                    })
                }
                val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                for (e in list.take(50)) {
                    listContainer.addView(LinearLayout(this@ExpenseActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(20, 16, 20, 16)
                        background = roundedBg(cardWhite, 16)
                        elevation = 2f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, 10) }

                        val row = LinearLayout(this@ExpenseActivity).apply { orientation = LinearLayout.HORIZONTAL }
                        row.addView(TextView(this@ExpenseActivity).apply {
                            text = e.category + if (e.description.isNotEmpty()) " - ${e.description}" else ""
                            textSize = 14f
                            setTextColor(Color.parseColor(textDark))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        row.addView(TextView(this@ExpenseActivity).apply {
                            text = "- Rs %.2f".format(e.amount)
                            setTextColor(Color.parseColor(red))
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            textSize = 15f
                        })
                        addView(row)

                        val bottomRow = LinearLayout(this@ExpenseActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        bottomRow.addView(TextView(this@ExpenseActivity).apply {
                            text = fmt.format(Date(e.createdAt))
                            setTextColor(Color.parseColor(textMuted))
                            textSize = 11.5f
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        bottomRow.addView(TextView(this@ExpenseActivity).apply {
                            text = "\u2715"
                            textSize = 11f
                            setTextColor(Color.parseColor(red))
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setPadding(16, 4, 4, 4)
                            setOnClickListener { confirmDeleteExpense(e) }
                        })
                        addView(bottomRow.apply { setPadding(0, 6, 0, 0) })
                    })
                }
            }
        }
    }

    private fun confirmDeleteExpense(e: Expense) {
        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Delete Expense", "خرچہ حذف کریں"))
            .setMessage(Loc.t(this, "Remove this expense entry?", "کیا یہ خرچہ حذف کر دیں؟"))
            .setPositiveButton(Loc.t(this, "Delete", "حذف کریں")) { _, _ ->
                lifecycleScope.launch {
                    PosDatabase.get(this@ExpenseActivity).expenseDao().delete(e)
                    loadTotals()
                }
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ"), null)
            .show()
    }
}
