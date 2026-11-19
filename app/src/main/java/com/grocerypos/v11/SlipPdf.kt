package com.grocerypos.v11

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================================
// NEW (Flutter parity, batch 4): PDF slips (Flutter's slip_pdf_service.dart) — a party
// statement / payment receipt as a real PDF instead of only plain text. Built with Android's
// own PdfDocument + StaticLayout, so Urdu names / labels are shaped and laid out right-to-left
// by the system (no extra font file needed). Shared through the existing FileProvider
// (cache-path "bill_images" = whole cacheDir).
// =====================================================================================

class SlipPdf(private val ctx: Context, private val pageWidth: Int = 420, private val pageHeight: Int = 595) {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = 0f
    private var pageNo = 0
    private val margin = 24f
    private val contentWidth get() = pageWidth - 2 * margin

    companion object {
        const val NAVY = 0xFF2E3242.toInt()
        const val TEAL = 0xFF0F9B8E.toInt()
        const val RED = 0xFFE5484D.toInt()
        const val GRAY = 0xFF8A90A6.toInt()
        const val LINE = 0xFFE3E6EF.toInt()
    }

    private fun paint(size: Float, bold: Boolean, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layout(text: String, width: Int, size: Float, bold: Boolean, color: Int, align: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint(size, bold, color), width.coerceAtLeast(10))
            .setAlignment(align).setIncludePad(false).build()

    private fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNo++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
        page = p; canvas = p.canvas; y = margin
    }

    private fun ensure(h: Float) {
        if (page == null || y + h > pageHeight - margin) newPage()
    }

    private fun draw(l: StaticLayout, x: Float) {
        val c = canvas ?: return
        c.save(); c.translate(x, y); l.draw(c); c.restore()
    }

    fun text(t: String, size: Float = 10f, bold: Boolean = false, color: Int = NAVY, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) {
        val l = layout(t, contentWidth.toInt(), size, bold, color, align)
        ensure(l.height.toFloat())
        draw(l, margin)
        y += l.height + 3
    }

    /** Left label + right value on one line. */
    fun kv(left: String, right: String, bold: Boolean = false, color: Int = NAVY) = row(listOf(left, right), listOf(0.6f, 0.4f), bold, color, lastRight = true)

    /** Table row; [weights] sum ≈ 1. The last column is right-aligned when [lastRight]. */
    fun row(cols: List<String>, weights: List<Float>, bold: Boolean = false, color: Int = NAVY, size: Float = 9.5f, lastRight: Boolean = true) {
        val widths = weights.map { (it * contentWidth).toInt() }
        val layouts = cols.mapIndexed { i, t ->
            val align = if (lastRight && i == cols.lastIndex && cols.size > 1) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            layout(t, widths[i] - 4, size, bold, color, align)
        }
        val h = (layouts.maxOfOrNull { it.height } ?: 0).toFloat()
        ensure(h)
        var x = margin
        layouts.forEachIndexed { i, l -> draw(l, x); x += widths[i] }
        y += h + 4
    }

    fun divider() {
        ensure(6f)
        canvas?.drawLine(margin, y + 2, pageWidth - margin, y + 2, Paint().apply { color = LINE; strokeWidth = 1f })
        y += 6
    }

    fun space(h: Float = 6f) { ensure(h); y += h }

    /** Writes cacheDir/slips/[name].pdf and returns it. */
    fun finish(name: String): File {
        if (page == null) newPage()
        page?.let { doc.finishPage(it) }
        page = null
        val dir = File(ctx.cacheDir, "slips").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(60).ifBlank { "slip" }
        val f = File(dir, "$safe.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }
}

object SlipShare {
    fun sharePdf(activity: Activity, file: File, subject: String) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(i, subject))
    }
}

/** Statement / receipt content (DB side). Same balance rule as PartyRepository.trueCustomerBalance(). */
object PartySlips {
    private fun rs(v: Double) = "Rs " + String.format(Locale.US, "%,.0f", v)
    private val dateFmt get() = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)

    private suspend fun header(db: PosDatabase, pdf: SlipPdf, title: String) {
        val shop = db.appSettingDao().get("shop_name")?.value?.takeIf { it.isNotBlank() } ?: "IBTISAAM Kiryana Store"
        val phone = db.appSettingDao().get("shop_phone")?.value ?: ""
        val address = db.appSettingDao().get("shop_address")?.value ?: ""
        pdf.text(shop, 15f, true, SlipPdf.NAVY, Layout.Alignment.ALIGN_CENTER)
        val sub = listOf(address, phone).filter { it.isNotBlank() }.joinToString("  •  ")
        if (sub.isNotBlank()) pdf.text(sub, 9f, false, SlipPdf.GRAY, Layout.Alignment.ALIGN_CENTER)
        pdf.space(4f)
        pdf.text(title, 12f, true, SlipPdf.TEAL, Layout.Alignment.ALIGN_CENTER)
        pdf.divider()
    }

    private data class Line(val at: Long, val text: String, val debit: Double, val credit: Double)

    suspend fun statement(ctx: Context, isCustomer: Boolean, partyId: Long, partyName: String): File {
        val db = PosDatabase.get(ctx)
        val opening: Double
        val stuck: Double
        val lines = mutableListOf<Line>()
        if (isCustomer) {
            val c = db.customerDao().find(partyId)
            opening = c?.openingBalance ?: 0.0
            stuck = c?.stuckBalance ?: 0.0
            val all = db.saleDao().salesByCustomer(partyId)
            val known = all.map { it.invoice }.toHashSet()
            for (s in all) if (s.status != "returned") lines.add(Line(s.createdAt, "Bill ${s.invoice}", s.total, s.paid))
            for (p in db.paymentDao().listByParty("customer", partyId)) {
                if (p.reference in known || (p.billReference.isNotBlank() && p.billReference in known)) continue
                lines.add(Line(p.createdAt, "Payment (${p.method})" + (if (p.note.isNotBlank()) " ${p.note}" else ""), 0.0, p.amount))
            }
        } else {
            val s = db.supplierDao().find(partyId)
            opening = s?.openingBalance ?: 0.0
            stuck = 0.0
            val all = db.purchaseDao().purchasesBySupplier(partyId)
            val known = all.map { it.billNo }.toHashSet()
            for (pu in all) if (pu.status != "returned") lines.add(Line(pu.createdAt, "Bill ${pu.billNo}", pu.total, pu.paid))
            for (p in db.paymentDao().listByParty("supplier", partyId)) {
                if (p.reference in known || (p.billReference.isNotBlank() && p.billReference in known)) continue
                lines.add(Line(p.createdAt, "Payment (${p.method})" + (if (p.note.isNotBlank()) " ${p.note}" else ""), 0.0, p.amount))
            }
        }
        lines.sortBy { it.at }
        val pdf = SlipPdf(ctx)
        header(db, pdf, if (isCustomer) "Customer Statement" else "Supplier Statement")
        pdf.kv("Party", partyName, true)
        pdf.kv("Date", dateFmt.format(Date()))
        pdf.divider()
        pdf.row(listOf("Date", "Detail", "Bill", "Paid", "Balance"), listOf(0.18f, 0.32f, 0.16f, 0.16f, 0.18f), bold = true, color = SlipPdf.GRAY, size = 8.5f)
        var bal = opening
        if (opening != 0.0) pdf.row(listOf("", "Opening balance", "", "", rs(bal)), listOf(0.18f, 0.32f, 0.16f, 0.16f, 0.18f), size = 8.5f)
        for (l in lines) {
            bal += l.debit - l.credit
            pdf.row(listOf(dateFmt.format(Date(l.at)), l.text, if (l.debit > 0) rs(l.debit) else "", if (l.credit > 0) rs(l.credit) else "", rs(bal)),
                listOf(0.18f, 0.32f, 0.16f, 0.16f, 0.18f), size = 8.5f)
        }
        pdf.divider()
        if (stuck != 0.0) { pdf.kv("Daily balance", rs(bal)); pdf.kv("Stuck (purana)", rs(stuck)) }
        val closing = bal + stuck
        pdf.kv(if (closing >= 0) (if (isCustomer) "Total due (aap ne dena hai)" else "Total payable") else "Advance", rs(kotlin.math.abs(closing)), true,
            if (closing > 0.009) SlipPdf.RED else SlipPdf.TEAL)
        pdf.space(10f)
        pdf.text("Shukriya!", 10f, true, SlipPdf.GRAY, Layout.Alignment.ALIGN_CENTER)
        return pdf.finish("statement_${partyName}_${System.currentTimeMillis()}")
    }

    suspend fun receipt(ctx: Context, isCustomer: Boolean, partyName: String, amount: Double, method: String, note: String, dateMillis: Long, billRef: String): File {
        val db = PosDatabase.get(ctx)
        val pdf = SlipPdf(ctx, pageWidth = 300, pageHeight = 420)
        header(db, pdf, if (isCustomer) "Payment Received" else "Payment Made")
        pdf.kv("Party", partyName, true)
        pdf.kv("Date", dateFmt.format(Date(dateMillis)))
        pdf.kv("Method", method.uppercase())
        if (billRef.isNotBlank()) pdf.kv("Against bill", billRef)
        if (note.isNotBlank()) pdf.kv("Note", note)
        pdf.divider()
        pdf.kv("Amount", rs(amount), true, SlipPdf.TEAL)
        pdf.space(10f)
        pdf.text("Shukriya!", 10f, true, SlipPdf.GRAY, Layout.Alignment.ALIGN_CENTER)
        return pdf.finish("receipt_${partyName}_$dateMillis")
    }
}
