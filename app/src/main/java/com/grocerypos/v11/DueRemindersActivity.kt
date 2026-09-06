package com.grocerypos.v11.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.DueSale
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// NEW: every credit/partially-paid sale in one place, so a reminder date can be set
// (or changed) without needing a due-date field in the checkout flow itself — see
// SaleDao.dueSales()/setDueDate() in Database.kt. A sale with no date set is still
// shown (grouped last, gray) so nothing owed is ever hidden just because no reminder
// was set for it yet.
class DueRemindersActivity : AppCompatActivity() {

    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val amber = "#F5A524"
    private val red = "#E5484D"
    private val teal = "#0F9B8E"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private lateinit var resultsBox: LinearLayout
    private lateinit var summaryBox: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(premiumHeader("\u23F0", Loc.t(this, "Due Date Reminders", "ادائیگی کی یاد دہانی"), Loc.t(this, "Credit sales still owed, by due date", "ادھار سیلز جو ابھی واجب الادا ہیں")))

        summaryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(summaryBox)
        root.addView(spacer(10))

        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsBox)
        root.addView(spacer(30))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scroll)

        loadData()
    }

    override fun onResume() {
        super.onResume()
        // Cheap enough to just reload — covers a payment made elsewhere (e.g. Party
        // Transaction screen) clearing a sale out of this list while we were away.
        if (::resultsBox.isInitialized) loadData()
    }

    private fun loadData() = lifecycleScope.launch {
        val db = PosDatabase.get(this@DueRemindersActivity)
        val sales = db.saleDao().dueSales()
        renderSummary(sales)
        renderList(sales)
    }

    private fun renderSummary(sales: List<DueSale>) {
        summaryBox.removeAllViews()
        val today = startOfToday()
        val overdue = sales.count { it.dueDate in 1 until today }
        val totalDue = sales.sumOf { it.total - it.paid }
        summaryBox.addView(summaryCard("\u26A0\uFE0F", Loc.t(this, "Overdue", "میعاد گزری"), "$overdue", red, "#FDE8E8"))
        summaryBox.addView(summaryCard("\uD83D\uDCB0", Loc.t(this, "Total outstanding", "کل بقایا"), "Rs %.2f".format(totalDue), amber, "#FFF3E0"))
    }

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun renderList(sales: List<DueSale>) {
        resultsBox.removeAllViews()
        if (sales.isEmpty()) {
            resultsBox.addView(TextView(this).apply {
                text = Loc.t(this@DueRemindersActivity, "Nothing outstanding — all credit sales are paid off", "کوئی بقایا نہیں — تمام ادھار سیلز ادا ہو چکی ہیں")
                setTextColor(Color.parseColor(textGray))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 40, 0, 0)
            })
            return
        }
        val today = startOfToday()
        val tomorrow = today + 24 * 60 * 60 * 1000L
        val in3Days = today + 3 * 24 * 60 * 60 * 1000L

        sales.forEach { s ->
            val due = s.total - s.paid
            val (badgeText, badgeColor) = when {
                s.dueDate <= 0L -> Loc.t(this, "No date set", "تاریخ طے نہیں") to textGray
                s.dueDate < today -> Loc.t(this, "OVERDUE", "میعاد گزر گئی") to red
                s.dueDate < tomorrow -> Loc.t(this, "DUE TODAY", "آج واجب الادا") to red
                s.dueDate < in3Days -> Loc.t(this, "DUE SOON", "جلد واجب الادا") to amber
                else -> Loc.t(this, "UPCOMING", "آنے والا") to teal
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                background = strokedBg(border, cardBg, 18)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 10) }
                applyElevation(this, 2f)
            }
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            nameCol.addView(TextView(this).apply { text = s.customerName; textSize = 14.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor(textDark)) })
            nameCol.addView(TextView(this).apply { text = s.invoice; textSize = 11.5f; setTextColor(Color.parseColor(textGray)); setPadding(0, 2, 0, 0) })
            topRow.addView(nameCol)
            topRow.addView(TextView(this).apply {
                text = badgeText; textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = roundedBg(badgeColor, 8); setPadding(14, 5, 14, 5)
            })
            card.addView(topRow)
            card.addView(spacer(8))

            val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val leftInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            leftInfo.addView(TextView(this).apply { text = "Rs %.2f".format(due) + "  " + Loc.t(this@DueRemindersActivity, "due", "واجب الادا"); textSize = 13.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor(primary)) })
            leftInfo.addView(TextView(this).apply {
                text = if (s.dueDate > 0L) Loc.t(this@DueRemindersActivity, "Due: ", "تاریخ: ") + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(s.dueDate))
                       else Loc.t(this@DueRemindersActivity, "Tap to set a due date", "تاریخ طے کرنے کے لیے دبائیں")
                textSize = 11.5f; setTextColor(Color.parseColor(textGray)); setPadding(0, 2, 0, 0)
            })
            bottomRow.addView(leftInfo)

            if (s.customerPhone.isNotBlank()) {
                bottomRow.addView(TextView(this).apply {
                    text = "\uD83D\uDCDE"
                    textSize = 16f
                    setPadding(20, 10, 20, 10)
                    background = ovalBg("#E9E6FF")
                    setOnClickListener {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${s.customerPhone}")))
                    }
                })
            }
            card.addView(bottomRow)

            card.setOnClickListener { showDatePicker(s) }
            resultsBox.addView(card)
        }
    }

    private fun showDatePicker(sale: DueSale) {
        val cal = Calendar.getInstance()
        if (sale.dueDate > 0L) cal.timeInMillis = sale.dueDate
        DatePickerDialog(this, { _, y, m, d ->
            val picked = Calendar.getInstance().apply {
                set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            lifecycleScope.launch {
                PosDatabase.get(this@DueRemindersActivity).saleDao().setDueDate(sale.invoice, picked)
                loadData()
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ================= SHARED UI HELPERS =================
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
            addView(FrameLayout(this@DueRemindersActivity).apply {
                val size = (40 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(tintHex)) }
                addView(TextView(this@DueRemindersActivity).apply {
                    text = emoji; textSize = 16f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            })
            val textCol = LinearLayout(this@DueRemindersActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 0, 0, 0) }
            textCol.addView(TextView(this@DueRemindersActivity).apply { text = label; setTextColor(Color.parseColor(textGray)); textSize = 12.5f; setTypeface(typeface, Typeface.BOLD) })
            textCol.addView(TextView(this@DueRemindersActivity).apply { text = value; setTextColor(Color.parseColor(accentHex)); textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 4, 0, 0) })
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
