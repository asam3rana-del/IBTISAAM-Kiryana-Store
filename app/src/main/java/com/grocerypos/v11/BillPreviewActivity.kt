package com.grocerypos.v11.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.util.PrinterHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BillPreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_REFERENCE = "reference"
        const val EXTRA_PARTY_NAME = "party_name"
        const val EXTRA_PARTY_LABEL = "party_label"
        const val EXTRA_DATE_MILLIS = "date_millis"
        const val EXTRA_SUBTOTAL = "subtotal"
        const val EXTRA_DISCOUNT = "discount"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_PAID = "paid"
        const val EXTRA_PAYMENT_METHOD = "payment_method"
        const val EXTRA_ITEMS_ENCODED = "items_encoded"
    }

    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val green = "#1FA971"
    private val greenDark = "#158A5A"
    private val blue = "#2F6FED"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private data class PreviewLine(val name: String, val qty: String, val unit: String, val rate: Double, val amount: Double)

    private var shopName = "IBTISAAM Kiryana Store"
    private var shopPhone = ""
    private var shopAddress = ""
    private var receiptFooter = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val type = intent.getStringExtra(EXTRA_TYPE) ?: "sale"
        val reference = intent.getStringExtra(EXTRA_REFERENCE) ?: ""
        val partyName = intent.getStringExtra(EXTRA_PARTY_NAME) ?: ""
        val partyLabel = intent.getStringExtra(EXTRA_PARTY_LABEL) ?: "Customer"
        val dateMillis = intent.getLongExtra(EXTRA_DATE_MILLIS, System.currentTimeMillis())
        val subtotal = intent.getDoubleExtra(EXTRA_SUBTOTAL, 0.0)
        val discount = intent.getDoubleExtra(EXTRA_DISCOUNT, 0.0)
        val total = intent.getDoubleExtra(EXTRA_TOTAL, 0.0)
        val paid = intent.getDoubleExtra(EXTRA_PAID, 0.0)
        val paymentMethod = intent.getStringExtra(EXTRA_PAYMENT_METHOD) ?: ""
        val itemsEncoded = intent.getStringExtra(EXTRA_ITEMS_ENCODED) ?: ""

        val lines = decodeItems(itemsEncoded)
        val isSale = type == "sale"
        val accent = if (isSale) green else "#EF6C00"
        val accentDark = if (isSale) greenDark else "#C4560A"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 56, 24, 24)
            setBackgroundColor(Color.parseColor(bg))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(26, 26, 26, 26)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(accent), Color.parseColor(accentDark))
            ).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 18) }
            applyElevation(this, 10f)
        }
        header.addView(TextView(this).apply {
            text = "✅"
            textSize = 32f
            gravity = Gravity.CENTER
        })
        header.addView(TextView(this).apply {
            text = if (isSale) "Sale Saved" else "Purchase Saved"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 8, 0, 0)
        })
        header.addView(TextView(this).apply {
            text = reference
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E4F5EC"))
            setPadding(0, 4, 0, 0)
        })
        root.addView(header)

        val receiptCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 24, 26, 20)
            background = strokedBg(border, cardBg, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            applyElevation(this, 3f)
        }

        receiptCard.addView(TextView(this).apply {
            text = shopName
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(textDark))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }.also { shopNameLine = it })

        receiptCard.addView(TextView(this).apply {
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 4, 0, 0)
        }.also { shopSubLine = it })

        receiptCard.addView(dashedDivider())

        val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        receiptCard.addView(kv("Invoice/Bill No", reference))
        receiptCard.addView(kv("Date", fmt.format(Date(dateMillis))))
        if (partyName.isNotBlank()) receiptCard.addView(kv(partyLabel, partyName))
        if (paymentMethod.isNotBlank()) receiptCard.addView(kv("Payment Method", paymentMethod.replaceFirstChar { it.uppercase() }))

        receiptCard.addView(dashedDivider())

        receiptCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@BillPreviewActivity).apply {
                text = "ITEM"; textSize = 11f
                setTextColor(Color.parseColor(textGray))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            })
            addView(TextView(this@BillPreviewActivity).apply {
                text = "QTY"; textSize = 11f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor(textGray))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@BillPreviewActivity).apply {
                text = "AMOUNT"; textSize = 11f; gravity = Gravity.END
                setTextColor(Color.parseColor(textGray))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        receiptCard.addView(spacer(6))

        for (line in lines) {
            receiptCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
                addView(LinearLayout(this@BillPreviewActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                    addView(TextView(this@BillPreviewActivity).apply {
                        text = line.name; textSize = 13.5f
                        setTextColor(Color.parseColor(textDark))
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(this@BillPreviewActivity).apply {
                        text = "@ %.2f".format(line.rate); textSize = 11f
                        setTextColor(Color.parseColor(textGray))
                    })
                })
                addView(TextView(this@BillPreviewActivity).apply {
                    text = "${line.qty} ${line.unit}"; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(textDark))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@BillPreviewActivity).apply {
                    text = "%.2f".format(line.amount); textSize = 13.5f; gravity = Gravity.END
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
        }

        receiptCard.addView(dashedDivider())
        receiptCard.addView(kv("Subtotal", "Rs %.2f".format(subtotal)))
        if (discount > 0) receiptCard.addView(kv("Discount", "- Rs %.2f".format(discount)))
        receiptCard.addView(kv("Total", "Rs %.2f".format(total), bold = true))
        receiptCard.addView(kv("Paid", "Rs %.2f".format(paid)))
        val balance = total - paid
        if (balance > 0.009) receiptCard.addView(kv("Balance Due", "Rs %.2f".format(balance), valueColor = "#C62828"))

        footerLine = TextView(this).apply {
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 14, 0, 0)
        }
        receiptCard.addView(footerLine)

        root.addView(receiptCard)
        root.addView(spacer(22))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "🖨️  PRINT"
            setTextColor(Color.WHITE)
            textSize = 14.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor(blue), Color.parseColor("#1E4FBE"))
            ).apply { cornerRadius = 16f }
            setPadding(0, 26, 0, 26)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
            applyElevation(this, 4f)
            setOnClickListener { printReceipt(type, reference, partyName, partyLabel, dateMillis, lines, subtotal, discount, total, paid, paymentMethod) }
        })
        btnRow.addView(Button(this).apply {
            text = "✓  DONE"
            setTextColor(Color.WHITE)
            textSize = 14.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 16f }
            setPadding(0, 26, 0, 26)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) }
            applyElevation(this, 4f)
            setOnClickListener { finish() }
        })
        root.addView(btnRow)
        root.addView(spacer(30))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadShopInfo()
    }

    private lateinit var shopNameLine: TextView
    private lateinit var shopSubLine: TextView
    private lateinit var footerLine: TextView

    private fun loadShopInfo() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@BillPreviewActivity)
            shopName = db.appSettingDao().get("shop_name")?.value?.ifBlank { shopName } ?: shopName
            shopPhone = db.appSettingDao().get("shop_phone")?.value ?: ""
            shopAddress = db.appSettingDao().get("shop_address")?.value ?: ""
            receiptFooter = db.appSettingDao().get("receipt_footer")?.value ?: ""

            shopNameLine.text = shopName
            val subParts = listOfNotNull(
                shopAddress.takeIf { it.isNotBlank() },
                shopPhone.takeIf { it.isNotBlank() }?.let { "📞 $it" }
            )
            shopSubLine.text = subParts.joinToString("  •  ")
            footerLine.text = receiptFooter.ifBlank { "Shukriya! Dobara tashreef layein." }
        }
    }

    private fun printReceipt(
        type: String, reference: String, partyName: String, partyLabel: String,
        dateMillis: Long, lines: List<PreviewLine>, subtotal: Double, discount: Double,
        total: Double, paid: Double, paymentMethod: String
    ) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@BillPreviewActivity)
            val mac = db.appSettingDao().get("printer_mac")?.value
            if (mac.isNullOrEmpty()) {
                Toast.makeText(this@BillPreviewActivity, "Pehle Settings mein printer select karein", Toast.LENGTH_LONG).show()
                return@launch
            }

            val width = 32
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            val sb = StringBuilder()

            sb.append(center(shopName, width)).append("\n")
            if (shopAddress.isNotBlank()) sb.append(center(shopAddress, width)).append("\n")
            if (shopPhone.isNotBlank()) sb.append(center(shopPhone, width)).append("\n")
            sb.append("-".repeat(width)).append("\n")
            sb.append(center(if (type == "sale") "SALE RECEIPT" else "PURCHASE RECEIPT", width)).append("\n")
            sb.append("-".repeat(width)).append("\n")
            sb.append("Ref: $reference\n")
            sb.append("Date: ${fmt.format(Date(dateMillis))}\n")
            if (partyName.isNotBlank()) sb.append("$partyLabel: $partyName\n")
            if (paymentMethod.isNotBlank()) sb.append("Payment: ${paymentMethod.replaceFirstChar { it.uppercase() }}\n")
            sb.append("-".repeat(width)).append("\n")

            for (line in lines) {
                sb.append(line.name).append("\n")
                val left = "${line.qty} ${line.unit} x %.2f".format(line.rate)
                sb.append(row(left, "%.2f".format(line.amount), width)).append("\n")
            }

            sb.append("-".repeat(width)).append("\n")
            sb.append(row("Subtotal", "Rs %.2f".format(subtotal), width)).append("\n")
            if (discount > 0) sb.append(row("Discount", "-Rs %.2f".format(discount), width)).append("\n")
            sb.append(row("TOTAL", "Rs %.2f".format(total), width)).append("\n")
            sb.append(row("Paid", "Rs %.2f".format(paid), width)).append("\n")
            val balance = total - paid
            if (balance > 0.009) sb.append(row("Balance Due", "Rs %.2f".format(balance), width)).append("\n")
            sb.append("-".repeat(width)).append("\n")
            sb.append(center(receiptFooter.ifBlank { "Shukriya! Dobara tashreef layein." }, width)).append("\n")

            val ok = PrinterHelper.printUrduText(
                this@BillPreviewActivity,
                PrinterHelper.PrinterType.BLUETOOTH,
                mac,
                sb.toString()
            )
            Toast.makeText(
                this@BillPreviewActivity,
                if (ok) "Print bhej diya" else "Print fail ho gaya. Printer check karein.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun center(text: String, width: Int): String {
        if (text.length >= width) return text
        val left = (width - text.length) / 2
        return " ".repeat(left) + text
    }

    private fun row(left: String, right: String, width: Int): String {
        val space = (width - left.length - right.length).coerceAtLeast(1)
        return left + " ".repeat(space) + right
    }

    private fun decodeItems(encoded: String): List<PreviewLine> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split("\u0002").mapNotNull { row ->
            val f = row.split("\u0003")
            if (f.size >= 5) {
                PreviewLine(
                    name = f[0],
                    qty = f[1],
                    unit = f[2],
                    rate = f[3].toDoubleOrNull() ?: 0.0,
                    amount = f[4].toDoubleOrNull() ?: 0.0
                )
            } else null
        }
    }

    private fun kv(label: String, value: String, bold: Boolean = false, valueColor: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 5, 0, 5)
        addView(TextView(this@BillPreviewActivity).apply {
            text = label; textSize = if (bold) 14.5f else 13f
            setTextColor(Color.parseColor(if (bold) textDark else textGray))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@BillPreviewActivity).apply {
            text = value; textSize = if (bold) 14.5f else 13f
            setTextColor(Color.parseColor(valueColor ?: textDark))
            setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.END
        })
    }

    private fun dashedDivider() = View(this).apply {
        setBackgroundColor(Color.parseColor(border))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
            setMargins(0, 12, 0, 12)
        }
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
