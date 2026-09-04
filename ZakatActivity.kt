package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.util.IslamicCalendar
import android.icu.util.Calendar as IcuCalendar
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Expense
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.ZakatPayment
import com.grocerypos.v11.ZakatYear
import com.grocerypos.v11.smallestUnitFactor
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zakat tracker — Ramadan-to-Ramadan year (per the user's request), auto-calculated
 * from data this app already tracks (same asset formula as BalanceSheetActivity),
 * with support for paying the year's Zakat all at once or in installments, and an
 * optional month-by-month "how much have I covered so far" breakdown.
 *
 * ZAKATABLE ASSETS (standard/common view — see the in-app note; a mufti/scholar
 * should confirm anything business-specific):
 *   Cash in Hand + Bank Balance + Stock in Hand (at cost) + Accounts Receivable
 *   − Accounts Payable (short-term debts owed)
 *   × 2.5%
 *
 * A "Zakat year" starts on the most recent 1 Ramadan on/before today and runs to
 * the following 1 Ramadan. The asset snapshot + payable amount are calculated once
 * when the year is started (editable before saving) and stay fixed for that year;
 * payments are then recorded against it, all-at-once or split into parts. This is
 * local-only data for now — see the ZakatYear/ZakatPayment doc comment in Database.kt.
 */
class ZakatActivity : AppCompatActivity() {

    // ================= PREMIUM PALETTE (shared with Items/Categories/Reports) =================
    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val teal = "#0F9B8E"
    private val gold = "#C9A24B"
    private val red = "#E5484D"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private lateinit var resultsBox: LinearLayout
    private val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val myRole = getSharedPreferences("session", MODE_PRIVATE).getString("role", "cashier") ?: "cashier"
        if (myRole != "admin" && myRole != "manager") {
            Toast.makeText(this, "Sirf Admin/Manager is screen ko access kar sakte hain", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(premiumHeader("\u262A", Loc.t(this, "Zakat", "زکوٰۃ"), Loc.t(this, "Ramadan to Ramadan \u2022 auto-calculated", "رمضان تا رمضان \u2022 خودکار حساب")))

        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsBox)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        }
        setContentView(scroll)

        loadScreen()
    }

    override fun onResume() {
        super.onResume()
        if (::resultsBox.isInitialized) loadScreen()
    }

    // ---------------- Hijri (Ramadan) date helpers ----------------

    // Returns the Gregorian start-of-day timestamp for 1 Ramadan of the given Hijri year.
    private fun ramadanStart(hijriYear: Int): Long {
        val cal = IslamicCalendar()
        cal.clear()
        cal.set(hijriYear, 8 /* Ramadan = 9th month, 0-indexed */, 1)
        return cal.timeInMillis
    }

    // The most recent 1 Ramadan on/before "now", and the following 1 Ramadan —
    // i.e. the Zakat year that contains today.
    private fun currentRamadanBracket(): Pair<Long, Long> {
        val nowCal = IslamicCalendar()
        val hijriYearNow = nowCal.get(IcuCalendar.YEAR)
        var start = ramadanStart(hijriYearNow)
        if (start > System.currentTimeMillis()) start = ramadanStart(hijriYearNow - 1)
        val end = ramadanStart(hijriYearFor(start) + 1)
        return start to end
    }

    private fun hijriYearFor(timeMillis: Long): Int {
        val c = IslamicCalendar()
        c.timeInMillis = timeMillis
        return c.get(IcuCalendar.YEAR)
    }

    // ---------------- Load ----------------

    private fun loadScreen() {
        resultsBox.removeAllViews()
        lifecycleScope.launch {
            val db = PosDatabase.get(this@ZakatActivity)
            val latest = db.zakatDao().latestYear()
            val now = System.currentTimeMillis()

            resultsBox.removeAllViews()

            if (latest == null || now >= latest.endDate) {
                // No year started yet, or the last one's Ramadan-to-Ramadan window has
                // already closed — offer to start the current one.
                resultsBox.addView(startYearCard())
            } else {
                val paid = db.zakatDao().totalPaidForYear(latest.id)
                val payments = db.zakatDao().paymentsForYear(latest.id)
                resultsBox.addView(activeYearCard(latest, paid))
                resultsBox.addView(spacer(16))
                resultsBox.addView(monthlyBreakdownCard(latest, paid))
                resultsBox.addView(spacer(16))
                resultsBox.addView(historyCard(payments))
                resultsBox.addView(spacer(16))
                resultsBox.addView(startYearCard(isRestart = true))
            }

            resultsBox.addView(spacer(10))
            resultsBox.addView(TextView(this@ZakatActivity).apply {
                text = Loc.t(
                    this@ZakatActivity,
                    "Note: this uses a standard estimate (cash + bank + stock at cost + receivables \u2212 payables) \u00D7 2.5%. Confirm anything business-specific with your own mufti/scholar, especially Nisab and stock valuation.",
                    "نوٹ: یہ ایک عام تخمینہ استعمال کرتا ہے (نقدی + بینک + اسٹاک لاگت پر + قابل وصول \u2212 قابل ادائیگی) \u00D7 2.5%\u06D4 کاروبار سے متعلق تفصیلات، خاص طور پر نصاب اور اسٹاک کی قیمت، اپنے مفتی/عالم سے تصدیق کر لیں۔"
                )
                textSize = 11.5f
                setTextColor(Color.parseColor(textGray))
                setPadding(6, 4, 6, 20)
            })
        }
    }

    // ---------------- Start / restart a Zakat year ----------------

    private fun startYearCard(isRestart: Boolean = false): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 18)
            background = strokedBg(if (isRestart) border else gold, cardBg, 18)
            applyElevation(this, 2f)
        }
        val (start, end) = currentRamadanBracket()
        card.addView(TextView(this).apply {
            text = if (isRestart) Loc.t(this@ZakatActivity, "Start a new Zakat year", "نیا زکوٰۃ سال شروع کریں")
                else Loc.t(this@ZakatActivity, "No active Zakat year", "کوئی فعال زکوٰۃ سال نہیں")
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(textDark))
        })
        card.addView(TextView(this).apply {
            text = "${fmt.format(Date(start))} \u2014 ${fmt.format(Date(end))}"
            textSize = 12.5f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 4, 0, 14)
        })
        card.addView(pillButton(
            Loc.t(this@ZakatActivity, "Calculate & Start This Year", "حساب لگائیں اور سال شروع کریں"),
            primary
        ) { showStartYearDialog(start, end) })
        return card
    }

    private fun showStartYearDialog(start: Long, end: Long) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@ZakatActivity)

            // ---- Same asset formula as BalanceSheetActivity (see that file's doc
            // comment for why stock is cost-per-smallest-unit, not raw stock*cost). ----
            val cashInHand = db.cashTransactionDao().totalAll("IN", "cash") - db.cashTransactionDao().totalAll("OUT", "cash")
            val bankBalance = db.cashTransactionDao().totalAll("IN", "bank") - db.cashTransactionDao().totalAll("OUT", "bank")
            val allProducts = db.productDao().all().first()
            val stockValue = allProducts.sumOf { p ->
                val factor = p.smallestUnitFactor()
                val costPerSmallestUnit = if (factor > 0) p.cost / factor else p.cost
                p.stock * costPerSmallestUnit
            }
            val receivables = db.customerDao().receivablesTotal()
            val payables = db.supplierDao().payablesTotal()
            val autoAssets = cashInHand + bankBalance + stockValue + receivables - payables

            val padding = (24 * resources.displayMetrics.density).toInt()
            val col = LinearLayout(this@ZakatActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }
            col.addView(TextView(this@ZakatActivity).apply {
                text = Loc.t(this@ZakatActivity, "Auto-calculated from Cash + Bank + Stock + Receivables \u2212 Payables. Adjust if needed:", "نقدی + بینک + اسٹاک + قابل وصول \u2212 قابل ادائیگی سے خودکار حساب۔ ضرورت ہو تو تبدیل کریں:")
                textSize = 12f
                setTextColor(Color.parseColor(textGray))
                setPadding(0, 0, 0, 12)
            })
            val assetsInput = EditText(this@ZakatActivity).apply {
                hint = Loc.t(this@ZakatActivity, "Net Zakatable Assets", "خالص زکوٰۃ کے قابل اثاثے")
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(if (autoAssets > 0) "%.0f".format(autoAssets) else "0")
            }
            col.addView(assetsInput)

            AlertDialog.Builder(this@ZakatActivity)
                .setTitle(Loc.t(this@ZakatActivity, "Confirm Zakat Year", "زکوٰۃ سال کی تصدیق کریں"))
                .setView(col)
                .setPositiveButton(Loc.t(this@ZakatActivity, "Start", "شروع کریں")) { _, _ ->
                    val assets = assetsInput.text.toString().toDoubleOrNull() ?: 0.0
                    saveNewYear(start, end, assets)
                }
                .setNegativeButton(Loc.t(this@ZakatActivity, "Cancel", "منسوخ کریں"), null)
                .show()
        }
    }

    private fun saveNewYear(start: Long, end: Long, assets: Double) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@ZakatActivity)
            val payable = assets * 0.025
            db.zakatDao().insertYear(ZakatYear(startDate = start, endDate = end, assetsSnapshot = assets, totalPayable = payable))
            Toast.makeText(this@ZakatActivity, Loc.t(this@ZakatActivity, "Zakat year started", "زکوٰۃ سال شروع ہو گیا"), Toast.LENGTH_SHORT).show()
            loadScreen()
        }
    }

    // ---------------- Active year summary + payment ----------------

    private fun activeYearCard(year: ZakatYear, paid: Double): LinearLayout {
        val remaining = (year.totalPayable - paid).coerceAtLeast(0.0)
        val pct = if (year.totalPayable > 0) ((paid / year.totalPayable) * 100).coerceIn(0.0, 100.0) else 0.0

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 20)
            background = strokedBg(border, cardBg, 18)
            applyElevation(this, 2f)
        }
        card.addView(TextView(this).apply {
            text = "${fmt.format(Date(year.startDate))} \u2014 ${fmt.format(Date(year.endDate))}"
            textSize = 12f
            setTextColor(Color.parseColor(textGray))
        })
        card.addView(bigAmountRow(Loc.t(this, "Total Zakat Payable", "کل زکوٰۃ ادا کرنی ہے"), year.totalPayable, primary))
        card.addView(spacer(4))
        card.addView(bigAmountRow(Loc.t(this, "Paid So Far", "اب تک ادا شدہ"), paid, teal))
        card.addView(bigAmountRow(Loc.t(this, "Remaining", "باقی رقم"), remaining, if (remaining > 0) red else teal))

        card.addView(spacer(10))
        card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = pct.toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 20)
        })
        card.addView(TextView(this).apply {
            text = "%.0f%% ".format(pct) + Loc.t(this@ZakatActivity, "paid", "ادا شدہ")
            textSize = 11f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 4, 0, 14)
        })

        if (remaining > 0.0) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(pillButton(Loc.t(this@ZakatActivity, "Record Payment", "ادائیگی درج کریں"), primary) {
                showPaymentDialog(year, remaining)
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(10, 1) })
            row.addView(pillButton(Loc.t(this@ZakatActivity, "Pay Remaining in Full", "باقی مکمل ادا کریں"), teal) {
                savePayment(year, remaining, "cash", Loc.t(this@ZakatActivity, "Full remaining balance", "مکمل باقی رقم"))
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            card.addView(row)
        } else {
            card.addView(TextView(this).apply {
                text = "\u2705 " + Loc.t(this@ZakatActivity, "Fully paid for this year", "اس سال کی مکمل ادائیگی ہو چکی ہے")
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(teal))
            })
        }
        return card
    }

    private fun bigAmountRow(label: String, amount: Double, colorHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
            addView(TextView(this@ZakatActivity).apply {
                text = label
                textSize = 13.5f
                setTextColor(Color.parseColor(textGray))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@ZakatActivity).apply {
                text = "Rs %.0f".format(amount)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(colorHex))
            })
        }
    }

    private fun showPaymentDialog(year: ZakatYear, remaining: Double) {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }

        val amountInput = EditText(this).apply {
            hint = Loc.t(this@ZakatActivity, "Amount (remaining: Rs %.0f)".format(remaining), "رقم (باقی: Rs %.0f)".format(remaining))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        col.addView(amountInput)

        val methodSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ZakatActivity, android.R.layout.simple_spinner_dropdown_item, listOf("cash", "bank"))
        }
        col.addView(methodSpinner)

        val noteInput = EditText(this).apply {
            hint = Loc.t(this@ZakatActivity, "Note (optional)", "نوٹ (اختیاری)")
        }
        col.addView(noteInput)

        AlertDialog.Builder(this)
            .setTitle(Loc.t(this, "Record Zakat Payment", "زکوٰۃ ادائیگی درج کریں"))
            .setView(col)
            .setPositiveButton(Loc.t(this, "Save", "محفوظ کریں")) { _, _ ->
                val amt = amountInput.text.toString().toDoubleOrNull()
                if (amt == null || amt <= 0.0) {
                    Toast.makeText(this, Loc.t(this, "Enter a valid amount", "صحیح رقم لکھیں"), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                savePayment(year, amt, methodSpinner.selectedItem?.toString() ?: "cash", noteInput.text.toString().trim())
            }
            .setNegativeButton(Loc.t(this, "Cancel", "منسوخ کریں"), null)
            .show()
    }

    private fun savePayment(year: ZakatYear, amount: Double, method: String, note: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@ZakatActivity)
            db.zakatDao().insertPayment(ZakatPayment(zakatYearId = year.id, amount = amount, method = method, note = note))
            // Also logged as a normal expense (category "Zakat") so it shows up in the
            // existing Expense reports/P&L alongside everything else — same as every
            // other outgoing payment in this app.
            val desc = Loc.t(this@ZakatActivity, "Zakat payment", "زکوٰۃ کی ادائیگی") + " (${fmt.format(Date(year.startDate))} \u2014 ${fmt.format(Date(year.endDate))})" + if (note.isNotEmpty()) " | $note" else ""
            db.expenseDao().insert(Expense(category = "Zakat", description = desc, amount = amount))
            Toast.makeText(this@ZakatActivity, Loc.t(this@ZakatActivity, "Payment saved", "ادائیگی محفوظ ہو گئی"), Toast.LENGTH_SHORT).show()
            loadScreen()
        }
    }

    // ---------------- Monthly breakdown ----------------

    // Splits the year's total into 12 equal installments and shows, cumulatively,
    // how many of those "months' worth" the payments made so far cover — for anyone
    // who prefers to pay Zakat spread across the year rather than all at once.
    private fun monthlyBreakdownCard(year: ZakatYear, paid: Double): LinearLayout {
        val monthly = year.totalPayable / 12.0
        val monthsElapsed = monthsBetween(year.startDate, System.currentTimeMillis().coerceAtMost(year.endDate)).coerceIn(0, 12)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 18)
            background = strokedBg(border, cardBg, 18)
            applyElevation(this, 2f)
        }
        card.addView(TextView(this).apply {
            text = Loc.t(this@ZakatActivity, "Monthly Breakdown", "ماہانہ تفصیل")
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(textDark))
        })
        card.addView(TextView(this).apply {
            text = Loc.t(this@ZakatActivity, "If paying monthly: Rs %.0f / month".format(monthly), "ماہانہ ادائیگی کی صورت میں: Rs %.0f / ماہ".format(monthly))
            textSize = 12f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 4, 0, 14)
        })

        for (m in 1..12) {
            val dueSoFar = monthly * m
            val covered = paid >= dueSoFar - 0.5 // small tolerance for rounding
            val isCurrentMonth = m == monthsElapsed + 1
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
                addView(TextView(this@ZakatActivity).apply {
                    text = (if (covered) "\u2705" else if (isCurrentMonth) "\uD83D\uDD5A" else "\u2B1C") + "  " + Loc.t(this@ZakatActivity, "Month $m", "ماہ $m")
                    textSize = 13f
                    setTextColor(Color.parseColor(if (covered) textDark else textGray))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@ZakatActivity).apply {
                    text = "Rs %.0f".format(monthly)
                    textSize = 12.5f
                    setTextColor(Color.parseColor(if (covered) teal else textGray))
                })
            })
        }
        return card
    }

    private fun monthsBetween(startMillis: Long, endMillis: Long): Int {
        val days = ((endMillis - startMillis) / (1000L * 60 * 60 * 24)).toInt()
        // ~354-day lunar year / 12 \u2248 29.5 days per lunar month
        return (days / 29.5).toInt()
    }

    // ---------------- Payment history ----------------

    private fun historyCard(payments: List<ZakatPayment>): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 18)
            background = strokedBg(border, cardBg, 18)
            applyElevation(this, 2f)
        }
        card.addView(TextView(this).apply {
            text = Loc.t(this@ZakatActivity, "Payment History", "ادائیگی کی تاریخ")
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(textDark))
            setPadding(0, 0, 0, 10)
        })
        if (payments.isEmpty()) {
            card.addView(TextView(this).apply {
                text = Loc.t(this@ZakatActivity, "No payments recorded yet", "ابھی تک کوئی ادائیگی درج نہیں")
                textSize = 12.5f
                setTextColor(Color.parseColor(textGray))
            })
        } else {
            val histFmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            payments.forEach { p ->
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 8, 0, 8)
                    addView(LinearLayout(this@ZakatActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(TextView(this@ZakatActivity).apply {
                            text = histFmt.format(Date(p.createdAt))
                            textSize = 12f
                            setTextColor(Color.parseColor(textGray))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        addView(TextView(this@ZakatActivity).apply {
                            text = "Rs %.0f".format(p.amount)
                            textSize = 13.5f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Color.parseColor(teal))
                        })
                    })
                    if (p.note.isNotEmpty() || p.method.isNotEmpty()) {
                        addView(TextView(this@ZakatActivity).apply {
                            text = p.method.uppercase() + if (p.note.isNotEmpty()) "  \u2022  ${p.note}" else ""
                            textSize = 11.5f
                            setTextColor(Color.parseColor(textGray))
                        })
                    }
                })
            }
        }
        return card
    }

    // ---------------- Small UI helpers ----------------

    private fun pillButton(label: String, colorHex: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = roundedBg(colorHex, 30)
        setPadding(20, 16, 20, 16)
        setOnClickListener { onClick() }
    }

    // ================= PREMIUM HEADER (matches Items/Categories/Reports) =================
    private fun premiumHeader(icon: String, title: String, subtitle: String): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
            applyElevation(this, 10f)
        }
        header.addView(TextView(this).apply {
            text = "\u2039"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ovalBg("#33FFFFFF")
            val px = (36 * resources.displayMetrics.density).toInt()
            width = px; height = px
            setOnClickListener { finish() }
        })
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(14, 1) })
        header.addView(circleIcon(icon, "#5C4DFF", 42))
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
        val headerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerCol.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        headerCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Color.parseColor("#D8D3FF"))
            setPadding(0, 4, 0, 0)
        })
        header.addView(headerCol)
        return header
    }

    private fun circleIcon(label: String, colorHex: String, sizeDp: Int) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        background = ovalBg(colorHex)
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        width = px; height = px
    }

    private fun ovalBg(colorHex: String) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
    }

    private fun roundedBg(colorHex: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        cornerRadius = radius.toFloat()
    }

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
