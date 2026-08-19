package com.grocerypos.v11.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.GradientDrawable
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
            text = "🖨️ PRINT"; setTextColor(Color.WHITE); textSize = 14.5f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
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
            shopName = db.appSettingDao().get("shop_name")?.value?.ifBlank { shopName } ?: shopName
            shopPhone = db.appSettingDao().get("shop_phone")?.value ?: ""
            shopAddress = db.appSettingDao().get("shop_address")?.value ?: ""
            receiptFooter = db.appSettingDao().get("receipt_footer")?.value ?: ""
            shopNameLine.text = shopName
            val subParts = listOfNotNull(shopAddress.takeIf { it.isNotBlank() }, shopPhone.takeIf { it.isNotBlank() }?.let { "📞 $it" })
            shopSubLine.text = subParts.joinToString(" • ")
            footerLine.text = receiptFooter.ifBlank { "Shukriya! Dobara tashreef layein." }
        }
    }

    // ----------------------------------------------------------------------
    // PRINTING
    //
    // The receipt is rendered as a bitmap (using Android's own text layout
    // engine) and sent to the printer as a raster image, instead of sending
    // raw text bytes. Raw text bytes only work reliably for plain ASCII -
    // thermal printers use their own single-byte codepage/font, so any
    // Urdu/Unicode characters in the shop name, item names, address, or
    // footer would come out as boxes or "????". Printing as an image avoids
    // that completely: whatever is typed (English, Urdu, or mixed) prints
    // exactly as it would render on screen.
    // ----------------------------------------------------------------------

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

            // Printer paper width in mm, configurable via app settings.
            // 58mm printers print at 384 dots wide, 80mm at 576 dots wide
            // (both at the common 203dpi print head resolution).
            val paperWidthMm = db.appSettingDao().get("printer_paper_width")?.value?.toIntOrNull() ?: 58
            val dotWidth = if (paperWidthMm >= 80) 576 else 384

            val bitmap = buildReceiptBitmap(
                type, reference, partyName, partyLabel, dateMillis, lines,
                subtotal, discount, total, paid, paymentMethod, dotWidth
            )

            val ok = PrinterHelper.printBitmap(this@BillPreviewActivity, PrinterHelper.PrinterType.BLUETOOTH, mac, bitmap)
            Toast.makeText(this@BillPreviewActivity, if (ok) "Print bhej diya" else "Print fail", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun makeStaticLayout(text: String, paint: TextPaint, width: Int, align: Layout.Alignment): StaticLayout {
        val safeWidth = width.coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(align)
                .setIncludePad(false)
                .build()
        } else {
            StaticLayout(text, paint, safeWidth, align, 1f, 0f, false)
        }
    }

    private fun buildReceiptBitmap(
        type: String, reference: String, partyName: String, partyLabel: String,
        dateMillis: Long, lines: List<PreviewLine>, subtotal: Double, discount: Double,
        total: Double, paid: Double, paymentMethod: String, dotWidth: Int
    ): Bitmap {
        val width = dotWidth
        val padding = (width * 0.035f).toInt().coerceAtLeast(6)
        val contentLeft = padding
        val contentRight = width - padding
        val contentWidth = (contentRight - contentLeft).coerceAtLeast(1)

        fun makePaint(sizeFactor: Float, bold: Boolean, align: Paint.Align = Paint.Align.LEFT): TextPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = width * sizeFactor
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textAlign = align
            }

        val titlePaint = makePaint(0.072f, true, Paint.Align.CENTER)
        val subPaint = makePaint(0.042f, false, Paint.Align.CENTER)
        val sectionPaint = makePaint(0.05f, true, Paint.Align.CENTER)
        val labelPaint = makePaint(0.045f, false)
        val valuePaint = makePaint(0.045f, false, Paint.Align.RIGHT)
        val boldLabelPaint = makePaint(0.05f, true)
        val boldValuePaint = makePaint(0.05f, true, Paint.Align.RIGHT)
        val itemNamePaint = makePaint(0.047f, true)
        val itemSubPaint = makePaint(0.04f, false)
        val itemQtyPaint = makePaint(0.045f, false, Paint.Align.CENTER)
        val itemAmountPaint = makePaint(0.047f, true, Paint.Align.RIGHT)
        val headerPaint = makePaint(0.038f, true)
        val headerCenterPaint = makePaint(0.038f, true, Paint.Align.CENTER)
        val headerRightPaint = makePaint(0.038f, true, Paint.Align.RIGHT)

        // Draw onto an oversized scratch canvas first, then crop to the
        // actual content height once everything has been laid out.
        val maxHeight = 4000
        val scratch = Bitmap.createBitmap(width, maxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scratch)
        canvas.drawColor(Color.WHITE)

        var y = padding.toFloat()

        fun drawCentered(text: String, p: TextPaint, gapAfter: Float) {
            if (text.isBlank()) return
            val layout = makeStaticLayout(text, p, contentWidth, Layout.Alignment.ALIGN_CENTER)
            canvas.save(); canvas.translate(contentLeft.toFloat(), y); layout.draw(canvas); canvas.restore()
            y += layout.height + gapAfter
        }

        fun dashedLine(gapBefore: Float, gapAfter: Float) {
            y += gapBefore
            val dashPaint = Paint().apply { color = Color.BLACK; strokeWidth = (width * 0.004f).coerceAtLeast(1.5f) }
            val dash = width * 0.012f
            val gapPx = width * 0.008f
            var x = contentLeft.toFloat()
            while (x < contentRight) {
                canvas.drawLine(x, y, minOf(x + dash, contentRight.toFloat()), y, dashPaint)
                x += dash + gapPx
            }
            y += gapAfter
        }

        fun kvRow(label: String, value: String, bold: Boolean = false) {
            val lp = if (bold) boldLabelPaint else labelPaint
            val vp = if (bold) boldValuePaint else valuePaint
            val labelLayout = makeStaticLayout(label, lp, (contentWidth * 0.55f).toInt(), Layout.Alignment.ALIGN_NORMAL)
            canvas.save(); canvas.translate(contentLeft.toFloat(), y); labelLayout.draw(canvas); canvas.restore()
            canvas.drawText(value, contentRight.toFloat(), y + vp.textSize, vp)
            y += maxOf(labelLayout.height.toFloat(), vp.textSize * 1.35f)
        }

        // Header
        drawCentered(shopName.uppercase(Locale.getDefault()), titlePaint, width * 0.015f)
        val subParts = listOfNotNull(shopAddress.takeIf { it.isNotBlank() }, shopPhone.takeIf { it.isNotBlank() }?.let { "Tel: $it" })
        if (subParts.isNotEmpty()) drawCentered(subParts.joinToString("   •   "), subPaint, width * 0.02f)
        dashedLine(width * 0.01f, width * 0.02f)

        drawCentered(if (type == "sale") "SALE RECEIPT" else "PURCHASE RECEIPT", sectionPaint, width * 0.02f)

        val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        kvRow("No.", reference)
        kvRow("Date", fmt.format(Date(dateMillis)))
        if (partyName.isNotBlank()) kvRow(partyLabel, partyName)
        if (paymentMethod.isNotBlank()) kvRow("Payment", paymentMethod.replaceFirstChar { it.uppercase() })
        dashedLine(width * 0.015f, width * 0.02f)

        // Table header
        val col1 = contentLeft.toFloat()
        val qtyX = contentLeft + contentWidth * 0.78f
        val col3 = contentRight.toFloat()
        canvas.drawText("ITEM", col1, y + headerPaint.textSize, headerPaint)
        canvas.drawText("QTY", qtyX, y + headerPaint.textSize, headerCenterPaint)
        canvas.drawText("AMOUNT", col3, y + headerPaint.textSize, headerRightPaint)
        y += headerPaint.textSize * 1.7f

        for (line in lines) {
            val nameLayout = makeStaticLayout(line.name, itemNamePaint, (contentWidth * 0.58f).toInt(), Layout.Alignment.ALIGN_NORMAL)
            canvas.save(); canvas.translate(col1, y); nameLayout.draw(canvas); canvas.restore()
            canvas.drawText("${line.qty} ${line.unit}", qtyX, y + itemNamePaint.textSize, itemQtyPaint)
            canvas.drawText("%.2f".format(line.amount), col3, y + itemNamePaint.textSize, itemAmountPaint)
            y += nameLayout.height + width * 0.004f
            canvas.drawText("@ %.2f".format(line.rate), col1, y + itemSubPaint.textSize, itemSubPaint)
            y += itemSubPaint.textSize * 1.5f + width * 0.014f
        }

        dashedLine(width * 0.008f, width * 0.02f)
        kvRow("Subtotal", "Rs %.2f".format(subtotal))
        if (discount > 0) kvRow("Discount", "-Rs %.2f".format(discount))
        kvRow("TOTAL", "Rs %.2f".format(total), bold = true)
        kvRow("Paid", "Rs %.2f".format(paid))
        val balance = total - paid
        if (balance > 0.009) kvRow("Balance Due", "Rs %.2f".format(balance), bold = true)
        dashedLine(width * 0.015f, width * 0.02f)

        drawCentered(receiptFooter.ifBlank { "Shukriya! Dobara tashreef layein." }, subPaint, width * 0.03f)

        y += width * 0.02f

        val finalHeight = y.toInt().coerceIn(1, maxHeight)
        return Bitmap.createBitmap(scratch, 0, 0, width, finalHeight)
    }

    private fun decodeItems(encoded: String): List<PreviewLine> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split("\u0002").mapNotNull { row ->
            val f = row.split("\u0003")
            if (f.size >= 5) PreviewLine(f[0], f[1], f[2], f[3].toDoubleOrNull() ?: 0.0, f[4].toDoubleOrNull() ?: 0.0) else null
        }
    }

    private fun kv(label: String, value: String, bold: Boolean = false, valueColor: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, 5, 0, 5)
        addView(TextView(this@BillPreviewActivity).apply { text = label; textSize = if (bold) 14.5f else 13f; setTextColor(Color.parseColor(if (bold) textDark else textGray)); if (bold) setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        addView(TextView(this@BillPreviewActivity).apply { text = value; textSize = if (bold) 14.5f else 13f; setTextColor(Color.parseColor(valueColor ?: textDark)); setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL); gravity = Gravity.END })
    }
    private fun dashedDivider() = View(this).apply { setBackgroundColor(Color.parseColor(border)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { setMargins(0, 12, 0, 12) } }
    private fun strokedBg(strokeHex: String, fillHex: String, radius: Int) = DrawableGradient().apply { setColor(Color.parseColor(fillHex)); setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeHex)); cornerRadius = radius.toFloat() }
    private fun applyElevation(view: View, dp: Float) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { view.elevation = dp * resources.displayMetrics.density; view.outlineProvider = ViewOutlineProvider.BACKGROUND } }
    private fun spacer(heightDp: Int) = View(this).apply { val px = (heightDp * resources.displayMetrics.density).toInt(); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px) }
}
