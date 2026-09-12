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
import com.grocerypos.v11.R
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.grocerypos.v11.ui.components.*

// NEW: every credit/partially-paid sale in one place, so a reminder date can be set
// (or changed) without needing a due-date field in the checkout flow itself — see
// SaleDao.dueSales()/setDueDate() in Database.kt. A sale with no date set is still
// shown (grouped last, gray) so nothing owed is ever hidden just because no reminder
// was set for it yet.
class DueRemindersActivity : AppCompatActivity() {

    // Pulled from ThemeManager so this screen respects dark mode. Header was a
    // primary→primaryDark gradient; now flat like the rest of the app.
    private var bg = "#F3F2FA"
    private var cardBg = "#FFFFFF"
    private var primary = "#4A3AFF"
    private var primaryDark = "#4A3AFF"
    private var amber = "#F5A524"
    private var red = "#E5484D"
    private var teal = "#0F9B8E"
    private var textDark = "#1A1A2E"
    private var textGray = "#8A8A9E"
    private var border = "#E7E5F3"
    private var purpleBg = "#E9E6FF"
    private var amberBg = "#FFF3E0"

    private fun loadThemeColors() {
        val p = com.grocerypos.v11.util.ThemeManager.palette(this)
        bg = p.bg
        cardBg = p.cardWhite
        primary = p.flatPurpleFg
        primaryDark = p.flatPurpleFg
        amber = p.flatAmberFg
        red = p.red
        teal = p.flatTealFg
        textDark = p.textDark
        textGray = p.textMuted
        border = p.border
        purpleBg = p.flatPurpleBg
        amberBg = p.flatAmberBg
    }

    private lateinit var resultsBox: LinearLayout
    private lateinit var summaryBox: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        loadThemeColors()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(premiumHeader(R.drawable.ic_alarm, Loc.t(this, "Due Date Reminders", "ادائیگی کی یاد دہانی"), Loc.t(this, "Credit sales still owed, by due date", "ادھار سیلز جو ابھی واجب الادا ہیں")))

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
        summaryBox.addView(summaryCard(R.drawable.ic_warning, Loc.t(this, "Overdue", "میعاد گزری"), "$overdue", red, "#FDE8E8"))
        summaryBox.addView(summaryCard(R.drawable.ic_wallet, Loc.t(this, "Total outstanding", "کل بقایا"), "Rs %.2f".format(totalDue), amber, amberBg))
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
                // ---- ADDED (Accounts/Party 10/10): a one-tap WhatsApp reminder next to
                // the existing call button — the call icon lets you talk, but calling to
                // ask for money is awkward for a lot of shopkeepers; a pre-written
                // WhatsApp message is the far more commonly used option in practice.
                // Uses the wa.me link (no image, no jid trick needed for plain text) so
                // it works even if the customer's number isn't saved as a contact.
                bottomRow.addView(ImageView(this).apply {
                    setImageDrawable(tintedDrawable(R.drawable.ic_send, "#25D366", 18))
                    setPadding(20, 10, 20, 10)
                    background = ovalBg("#DDF6E8")
                    setOnClickListener { sendWhatsAppReminder(s, due) }
                })
                bottomRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(10, 1) })
                bottomRow.addView(ImageView(this).apply {
                    setImageDrawable(tintedDrawable(R.drawable.ic_phone, primary, 18))
                    setPadding(20, 10, 20, 10)
                    background = ovalBg(purpleBg)
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

    // ---- ADDED (Accounts/Party 10/10): pre-fills a polite Urdu/English reminder with
    // the customer's name, invoice and amount still due, and opens WhatsApp straight to
    // that chat via the wa.me link. Same Pakistan-default digit cleanup as
    // BillPreviewActivity.cleanPhoneToJid(), just without the "@s.whatsapp.net" suffix
    // since wa.me wants plain digits.
    private fun sendWhatsAppReminder(sale: DueSale, due: Double) {
        var digits = sale.customerPhone.replace(Regex("[^0-9]"), "")
        if (digits.startsWith("0")) digits = "92" + digits.substring(1)
        else if (!digits.startsWith("92") && digits.length <= 10) digits = "92$digits"

        val message = "Assalam o Alaikum ${sale.customerName}, aapka bill (Invoice ${sale.invoice}) mein Rs %.0f abhi baaki hai. Barah-e-karam jald ada karein. Shukriya!".format(due)
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp nahi khul saka. Installed hai?", Toast.LENGTH_LONG).show()
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
            val px = (36 * resources.displayMetrics.density).toInt(); layoutParams = android.view.ViewGroup.LayoutParams(px, px)
            setOnClickListener { finish() }
        })
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(14, 1) })
        header.addView(TextView(this).apply {
            text = icon; textSize = 18f; gravity = Gravity.CENTER
            background = ovalBg("#5C4DFF")
            val px = (42 * resources.displayMetrics.density).toInt(); layoutParams = android.view.ViewGroup.LayoutParams(px, px)
        })
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        val headerCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        headerCol.addView(TextView(this).apply { text = title; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        headerCol.addView(TextView(this).apply { text = subtitle; textSize = 10.5f; setTextColor(Color.parseColor("#D8D3FF")); setPadding(0, 4, 0, 0) })
        header.addView(headerCol)
        return header
    }

    private fun summaryCard(iconRes: Int, label: String, value: String, accentHex: String, tintHex: String): LinearLayout {
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
                addView(ImageView(this@DueRemindersActivity).apply {
                    setImageDrawable(tintedDrawable(iconRes, accentHex, 18))
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
                })
            })
            val textCol = LinearLayout(this@DueRemindersActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 0, 0, 0) }
            textCol.addView(TextView(this@DueRemindersActivity).apply { text = label; setTextColor(Color.parseColor(textGray)); textSize = 12.5f; setTypeface(typeface, Typeface.BOLD) })
            textCol.addView(TextView(this@DueRemindersActivity).apply { text = value; setTextColor(Color.parseColor(accentHex)); textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 4, 0, 0) })
            addView(textCol)
        }
    }

    private fun tintedDrawable(iconRes: Int, tintHex: String, sizeDp: Int = 16): android.graphics.drawable.Drawable? {
        val d = androidx.core.content.ContextCompat.getDrawable(this, iconRes)?.mutate() ?: return null
        d.setTint(Color.parseColor(tintHex))
        val size = (sizeDp * resources.displayMetrics.density).toInt()
        d.setBounds(0, 0, size, size)
        return d
    }

    // spacer() now comes from the shared UiHelpers.kt (item #24 dedup) — was a
    // byte-identical private copy here before.
}
