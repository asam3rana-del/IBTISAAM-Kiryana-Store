package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.CashTransaction
import com.grocerypos.v11.PosDatabase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CashActivity : AppCompatActivity() {

    private val bg = "#F3F4F9"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#1A1D2E"
    private val textMuted = "#8A8FA3"
    private val green = "#2E7D32"
    private val red = "#C62828"

    private lateinit var amount: EditText
    private lateinit var reason: EditText
    private lateinit var methodSpinner: Spinner
    private lateinit var inTotalText: TextView
    private lateinit var outTotalText: TextView
    private lateinit var listContainer: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 32)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(TextView(this).apply {
            text = "Cash In / Cash Out"
            textSize = 21f
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(4, 0, 0, 22)
        })

        // ---- Today's totals: premium white cards ----
        val totalsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val inCard = statCard("💰", "Today Cash In", green, "#E8F5E9")
        val outCard = statCard("💸", "Today Cash Out", red, "#FFEBEE")
        inTotalText = inCard.second
        outTotalText = outCard.second

        inCard.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,9,0) }
        outCard.first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(9,0,0,0) }

        totalsRow.addView(inCard.first)
        totalsRow.addView(outCard.first)
        root.addView(totalsRow)
        root.addView(spacer(26))

        // ---- Entry form card ----
        val formCard = cardContainer()
        formCard.addView(sectionLabel("New Entry"))

        val amountBox = outlinedBox()
        amount = EditText(this).apply {
            hint = "Amount"
            background = null
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        amountBox.addView(amount)
        formCard.addView(amountBox)

        val methodBox = outlinedBox()
        methodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@CashActivity, android.R.layout.simple_spinner_dropdown_item, listOf("cash", "bank"))
        }
        methodBox.addView(methodSpinner)
        formCard.addView(methodBox)

        val reasonBox = outlinedBox()
        reason = EditText(this).apply { hint = "Reason / Note (optional)"; background = null }
        reasonBox.addView(reason)
        formCard.addView(reasonBox)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "CASH IN"
            setTextColor(Color.WHITE)
            background = roundedBg(green, 16)
            setPadding(0, 22, 0, 22)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,8,0) }
            setOnClickListener { saveEntry("IN") }
        })
        btnRow.addView(Button(this).apply {
            text = "CASH OUT"
            setTextColor(Color.WHITE)
            background = roundedBg(red, 16)
            setPadding(0, 22, 0, 22)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8,0,0,0) }
            setOnClickListener { saveEntry("OUT") }
        })
        formCard.addView(btnRow)
        root.addView(formCard)
        root.addView(spacer(26))

        // ---- Recent transactions ----
        root.addView(sectionLabelPlain("RECENT TRANSACTIONS"))
        root.addView(spacer(10))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        root.addView(spacer(30))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadTodayTotals()
        loadTransactions()
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
            addView(TextView(this@CashActivity).apply {
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

    // ---- logic unchanged ----
    private fun saveEntry(type: String) {
        val amt = amount.text.toString().toDoubleOrNull()
        if (amt == null || amt <= 0.0) {
            Toast.makeText(this, "Sahi amount likhein", Toast.LENGTH_SHORT).show()
            return
        }
        val method = methodSpinner.selectedItem?.toString() ?: "cash"
        val note = reason.text.toString().trim()

        lifecycleScope.launch {
            PosDatabase.get(this@CashActivity).cashTransactionDao().insert(
                CashTransaction(type = type, method = method, amount = amt, reason = note)
            )
            Toast.makeText(this@CashActivity, "Saved", Toast.LENGTH_SHORT).show()
            amount.text.clear()
            reason.text.clear()
            loadTodayTotals()
        }
    }

    private fun loadTodayTotals() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@CashActivity)
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            val end = start + 24 * 60 * 60 * 1000L

            val cashIn = db.cashTransactionDao().totalBetween("IN", "cash", start, end)
            val bankIn = db.cashTransactionDao().totalBetween("IN", "bank", start, end)
            val cashOut = db.cashTransactionDao().totalBetween("OUT", "cash", start, end)
            val bankOut = db.cashTransactionDao().totalBetween("OUT", "bank", start, end)

            inTotalText.text = "Rs %.2f".format(cashIn + bankIn)
            outTotalText.text = "Rs %.2f".format(cashOut + bankOut)
        }
    }

    private fun loadTransactions() {
        lifecycleScope.launch {
            PosDatabase.get(this@CashActivity).cashTransactionDao().all().collectLatest { list ->
                listContainer.removeAllViews()
                if (list.isEmpty()) {
                    listContainer.addView(TextView(this@CashActivity).apply {
                        text = "Koi entry nahi hai"
                        setTextColor(Color.parseColor(textMuted))
                        setPadding(8,8,8,8)
                    })
                }
                val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                for (t in list.take(50)) {
                    val color = Color.parseColor(if (t.type == "IN") green else red)
                    val sign = if (t.type == "IN") "+" else "-"
                    listContainer.addView(LinearLayout(this@CashActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(20, 16, 20, 16)
                        background = roundedBg(cardWhite, 16)
                        elevation = 2f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, 10) }

                        val row = LinearLayout(this@CashActivity).apply { orientation = LinearLayout.HORIZONTAL }
                        row.addView(TextView(this@CashActivity).apply {
                            text = t.method.uppercase() + if (t.reason.isNotEmpty()) " - ${t.reason}" else ""
                            textSize = 14f
                            setTextColor(Color.parseColor(textDark))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        row.addView(TextView(this@CashActivity).apply {
                            text = "$sign Rs %.2f".format(t.amount)
                            setTextColor(color)
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            textSize = 15f
                        })
                        addView(row)
                        addView(TextView(this@CashActivity).apply {
                            text = fmt.format(Date(t.createdAt))
                            setTextColor(Color.parseColor(textMuted))
                            textSize = 11.5f
                            setPadding(0, 6, 0, 0)
                        })
                    })
                }
            }
        }
    }
}
