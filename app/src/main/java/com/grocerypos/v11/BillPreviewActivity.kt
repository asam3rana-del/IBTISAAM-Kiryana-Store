package com.grocerypos.v11.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable as DrawableGradient
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
    private lateinit var shopNameLine: TextView
    private lateinit var shopSubLine: TextView
    private lateinit var footerLine: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val type = intent.getStringExtra(EXTRA_TYPE)?: "sale"
        val reference = intent.getStringExtra(EXTRA_REFERENCE)?: ""
        val partyName = intent.getStringExtra(EXTRA_PARTY_NAME)?: ""
        val partyLabel = intent.getStringExtra(EXTRA_PARTY_LABEL)?: "Customer"
        val dateMillis = intent.getLongExtra(EXTRA_DATE_MILLIS, System.currentTimeMillis())
        val subtotal = intent.getDoubleExtra(EXTRA_SUBTOTAL, 0.0)
        val discount = intent.getDoubleExtra(EXTRA_DISCOUNT, 0.0)
        val total = intent.getDoubleExtra(EXTRA_TOTAL, 0.0)
        val paid = intent.getDoubleExtra(EXTRA_PAID, 0.0)
        val paymentMethod = intent.getStringExtra(EXTRA_PAYMENT_METHOD)?: ""
        val itemsEncoded = intent.getStringExtra(EXTRA_ITEMS_ENCODED)?: ""

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
            background = DrawableGradient(DrawableGradient.Orientation.TL_BR, intArrayOf(Color.parseColor(accent), Color.parseColor(accentDark))).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 18) }
            applyElevation(this, 10f)
        }
        header.addView(TextView(this).apply { text = "✅"; textSize = 32f; gravity = Gravity.CENTER })
        header.addView(TextView(this).apply { text = if (isSale) "Sale Saved" else "Purchase Saved"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); setPadding(0, 8, 0, 0) })
        header.addView(TextView(this).apply { text = reference; textSize = 12.5f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#E4F5EC")); setPadding(0, 4, 0, 0) })
        root.addView(header)

        val receiptCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 24, 26, 20)
            background = strokedBg(border, cardBg, 18)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            applyElevation(this, 3f)
        }

        receiptCard.addView(TextView(this).apply { textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }.also { shopNameLine = it })
        receiptCard.addView(TextView(this).apply { textSize = 11.5f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(textGray)); setPadding(0, 4, 0, 0) }.also { shopSubLine = it })
        receiptCard.addView(dashedDivider())
        val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        receiptCard.addView(kv("Invoice/Bill No", reference))
        receiptCard.addView(kv("Date", fmt.format(Date(dateMillis))))
        if (partyName.isNotBlank()) receiptCard.addView(kv(partyLabel, partyName))
        if (paymentMethod.isNotBlank()) receiptCard.addView(kv("Payment Method", paymentMethod.replaceFirstChar { it.uppercase() }))
        receiptCard.addView(dashedDivider())

        receiptCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@BillPreviewActivity).apply { text = "ITEM"; textSize = 11f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f) })
            addView(TextView(this@BillPreviewActivity).apply { text = "QTY"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(TextView(this@BillPreviewActivity).apply { text = "AMOUNT"; textSize = 11f; gravity = Gravity.END; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        })
        receiptCard.addView(spacer(6))

        for (line in lines) {
            receiptCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
                addView(LinearLayout(this@BillPreviewActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                    addView(TextView(this@BillPreviewActivity).apply { text = line.name; textSize = 13.5f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
                    addView(TextView(this@BillPreviewActivity).apply { text = "@ %.2f".format(line.rate); textSize = 11f; setTextColor(Color.parseColor(textGray)) })
                })
                addView(TextView(this@BillPreviewActivity).apply { text = "${line.qty} ${line.unit}"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(textDark)); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                addView(TextView(this@BillPreviewActivity).apply { text = "%.2f".format(line.amount); textSize = 13.5f; gravity = Gravity.END; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            })
        }

        receiptCard.addView(dashedDivider())
        receiptCard.addView(kv("Subtotal", "Rs %.2f".format(subtotal)))
        if (discount > 0) receiptCard.addView(kv("Discount", "- Rs %.2f".format(discount)))
        receiptCard.addView(kv("Total", "Rs %.2f".format(total), bold = true))
        receiptCard.addView(kv("Paid", "Rs %.2f".format(paid)))
        val balance = total - paid
        if (balance > 0.009) receiptCard.addView(kv("Balance Due", "Rs %.2f".format(balance), valueColor = "#C62828"))
        footerLine = TextView(this).apply { textSize = 11.5f; gravity = Gravity.CENTER; setTextColor(Color.parseColor(textGray)); setPadding(0, 14, 0, 0) }
        receiptCard.addView(footerLine)
        root.addView(receiptCard)
        root.addView(spacer(22))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "🖨️ PRINT BIG 58mm"; setTextColor(Color.WHITE); textSize = 14.5f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            background = DrawableGradient(DrawableGradient.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor(blue), Color.parseColor("#1E4FBE"))).apply { cornerRadius = 16f }
            setPadding(0, 26, 0, 26); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
            applyElevation(this, 4f)
            setOnClickListener { printReceipt(type, reference, partyName, partyLabel, dateMillis, lines, subtotal, discount, total, paid, paymentMethod) }
        })
        btnRow.addView(Button(this).apply {
            text = "✓ DONE"; setTextColor(Color.WHITE); textSize = 14.5f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            background = DrawableGradient(DrawableGradient.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))).apply { cornerRadius = 16f }
            setPadding(0, 26, 0, 26); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 0, 0) }
            applyElevation(this, 4f)
            setOnClickListener { finish() }
        })
        root.addView(btnRow)
        root.addView(spacer(30))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(bg)); addView(root) })
        loadShopInfo()
    }

    private fun loadShopInfo() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@BillPreviewActivity)
            shopName = db.appSettingDao().get("shop_name")?.value?.ifBlank { shopName }?: shopName
            shopPhone = db.appSettingDao().get("shop_phone")?.value?: ""
            shopAddress = db.appSettingDao().get("shop_address")?.value?: ""
            receiptFooter = db.appSettingDao().get("receipt_footer")?.value?: ""
            shopNameLine.text = shopName
            val subParts = listOfNotNull(shopAddress.takeIf { it.isNotBlank() }, shopPhone.takeIf { it.isNotBlank() }?.let { "📞 $it" })
            shopSubLine.text = subParts.joinToString(" • ")
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
            val dotWidth = 384
            val bitmap = buildReceiptBitmapBIG58mm(type, reference, partyName, partyLabel, dateMillis, lines, subtotal, discount, total, paid, paymentMethod, dotWidth)
            val ok = PrinterHelper.printBitmap(this@BillPreviewActivity, PrinterHelper.PrinterType.BLUETOOTH, mac, bitmap)
            Toast.makeText(this@BillPreviewActivity, if (ok) "BIG Print bheja" else "Print fail", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun makeStaticLayout(text: String, paint: TextPaint, width: Int, align: Layout.Alignment): StaticLayout {
        val safeWidth = width.coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
               .setAlignment(align).setIncludePad(false).build()
        } else {
            StaticLayout(text, paint, safeWidth, align, 1f, 0f, false)
        }
    }

    private fun buildReceiptBitmapBIG58mm(
        type: String, reference: String, partyName: String, partyLabel: String,
        dateMillis: Long, lines: List<PreviewLine>, subtotal: Double, discount: Double,
        total: Double, paid: Double, paymentMethod: String, dotWidth: Int
    ): Bitmap {
        val width = 384
        val padding = 2
        val contentLeft = padding
        val contentRight = width - padding
        val contentWidth = contentRight - contentLeft

        fun makePaint(sizeFactor: Float, bold: Boolean): TextPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = width * sizeFactor
                typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            }

        val titlePaint = makePaint(0.115f, true)
        val subPaint = makePaint(0.055f, false)
        val sectionPaint = makePaint(0.070f, true)
        val labelPaint = makePaint(0.060f, false)
        val valuePaint = makePaint(0.060f, true)
        val boldLabelPaint = makePaint(0.075f, true)
        val boldValuePaint = makePaint(0.075f, true)
        val itemNamePaint = makePaint(0.065f, true)
        val itemSubPaint = makePaint(0.048f, false)
        val itemQtyPaint = makePaint(0.058f, true)
        val itemAmountPaint = makePaint(0.065f, true)

        val maxHeight = 4000
        val scratch = Bitmap.createBitmap(width, maxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scratch)
        canvas.drawColor(Color.WHITE)
        var y = padding.toFloat()

        fun lineWidthOf(layout: StaticLayout) = if (layout.lineCount > 0) layout.getLineWidth(0) else 0f

        fun drawAt(text: String, p: TextPaint, boxLeft: Float, boxWidth: Float, y0: Float): StaticLayout? {
            if (text.isBlank()) return null
            val layout = makeStaticLayout(text, p, boxWidth.toInt().coerceAtLeast(1), Layout.Alignment.ALIGN_NORMAL)
            canvas.save(); canvas.translate(boxLeft, y0); layout.draw(canvas); canvas.restore()
            return layout
        }

        fun drawCentered(text: String, p: TextPaint, gapAfter: Float) {
            if (text.isBlank()) return
            val layout = makeStaticLayout(text, p, contentWidth, Layout.Alignment.ALIGN_NORMAL)
            val lineW = lineWidthOf(layout)
            val startX = contentLeft + (contentWidth - lineW) / 2f
            canvas.save(); canvas.translate(startX, y); layout.draw(canvas); canvas.restore()
            y += layout.height + gapAfter
        }

        fun drawRightAligned(text: String, p: TextPaint, rightX: Float, maxWidth: Float, y0: Float): StaticLayout? {
            if (text.isBlank()) return null
            val layout = makeStaticLayout(text, p, maxWidth.toInt().coerceAtLeast(1), Layout.Alignment.ALIGN_NORMAL)
            val lineW = lineWidthOf(layout)
            canvas.save(); canvas.translate(rightX - lineW, y0); layout.draw(canvas); canvas.restore()
            return layout
        }

        fun fullLine(char: String = "-") {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = width * 0.055f
                typeface = Typeface.MONOSPACE
            }
            drawAt(char.repeat(32), paint, contentLeft.toFloat(), contentWidth.toFloat(), y)
            y += paint.textSize * 1.2f
        }

        fun kvRow(label: String, value: String, bold: Boolean = false) {
            val lp = if (bold) boldLabelPaint else labelPaint
            val vp = if (bold) boldValuePaint else valuePaint
            val labelLayout = drawAt(label, lp, contentLeft.toFloat(), contentWidth * 0.48f, y)
            val valueLayout = drawRightAligned(value, vp, contentRight.toFloat(), contentWidth * 0.52f, y)
            val h = maxOf(labelLayout?.height?: 0, valueLayout?.height?: 0, 25)
            y += h + 6
        }

        drawCentered(shopName.uppercase(Locale.getDefault()), titlePaint, 4f)
        val subParts = listOfNotNull(shopAddress.takeIf { it.isNotBlank() }, shopPhone.takeIf { it.isNotBlank() })
        if (subParts.isNotEmpty()) drawCentered(subParts.joinToString(" | "), subPaint, 6f)
        fullLine("-")

        drawCentered(if (type == "sale") "SALE RECEIPT" else "PURCHASE", sectionPaint, 8f)
        val fmt = SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.getDefault())
        kvRow("Bill:", reference)
        kvRow("Date:", fmt.format(Date(dateMillis)))
        if (partyName.isNotBlank()) kvRow("$partyLabel:", partyName)
        fullLine("-")

        val col1 = contentLeft.toFloat()
        val col1W = contentWidth * 0.50f
        val col2X = contentLeft + contentWidth * 0.50f
        val col2W = contentWidth * 0.20f
        val col3X = contentRight.toFloat()
        val col3W = contentWidth * 0.30f

        drawAt("ITEM", boldLabelPaint, col1, col1W, y)
        drawAt("QTY", boldLabelPaint, col2X, col2W, y)
        drawRightAligned("AMT", boldLabelPaint, col3X, col3W, y)
        y += 28
        fullLine("-")

        for (line in lines) {
            val rowY = y
            val nameLayout = drawAt(line.name, itemNamePaint, col1, col1W, y)
            drawAt("${line.qty}", itemQtyPaint, col2X, col2W, y)
            drawRightAligned("${line.amount.toInt()}", itemAmountPaint, col3X, col3W, y)
            y = rowY + (nameLayout?.height?: 22) + 2
            drawAt("${line.unit} @${line.rate.toInt()}", itemSubPaint, col1, col1W, y)
            y += 20
        }

        fullLine("-")
        kvRow("Subtotal:", "${subtotal.toInt()}")
        if (discount > 0) kvRow("Disc:", "${discount.toInt()}")
        kvRow("TOTAL:", "Rs ${total.toInt()}", bold = true)
        kvRow("Paid:", "${paid.toInt()}")
        val balance = total - paid
        if (balance > 0.009) kvRow("Due:", "Rs ${balance.toInt()}", bold = true)
        fullLine("=")
        drawCentered(receiptFooter.ifBlank { "Shukriya!" }, subPaint, 6f)
        drawCentered("IBTISAAM POS", subPaint, 6f)
        y += 30f
        val finalHeight = y.toInt().coerceIn(1, maxHeight)
        return Bitmap.createBitmap(scratch, 0, 0, width, finalHeight)
    }

    private fun decodeItems(encoded: String): List<PreviewLine> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split("\u0002").mapNotNull { row ->
            val f = row.split("\u0003")
            if (f.size >= 5) PreviewLine(f[0], f[1], f[2], f[3].toDoubleOrNull()?: 0.0, f[4].toDoubleOrNull()?: 0.0) else null
        }
    }

    private fun kv(label: String, value: String, bold: Boolean = false, valueColor: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, 5, 0, 5)
        addView(TextView(this@BillPreviewActivity).apply { text = label; textSize = if (bold) 14.5f else 13f; setTextColor(Color.parseColor(if (bold) textDark else textGray)); if (bold) setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        addView(TextView(this@BillPreviewActivity).apply { text = value; textSize = if (bold) 14.5f else 13f; setTextColor(Color.parseColor(valueColor?: textDark)); setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL); gravity = Gravity.END })
    }
    private fun dashedDivider() = View(this).apply { setBackgroundColor(Color.parseColor(border)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { setMargins(0, 12, 0, 12) } }
    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = DrawableGradient().apply { setColor(Color.parseColor(fillHex)); setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex)); cornerRadius = radius.toFloat() }
    private fun applyElevation(view: View, dp: Float) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { view.elevation = dp * resources.displayMetrics.density; view.outlineProvider = ViewOutlineProvider.BACKGROUND } }
    private fun spacer(heightDp: Int) = View(this).apply { val px = (heightDp * resources.displayMetrics.density).toInt(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px) }
}
