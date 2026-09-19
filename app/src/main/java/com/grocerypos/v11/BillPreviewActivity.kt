package com.grocerypos.v11.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Customer
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.R
import com.grocerypos.v11.util.PrinterHelper
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.grocerypos.v11.ui.components.*

class BillPreviewActivity : ThemedActivity() {

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_REFERENCE = "reference"
        const val EXTRA_PARTY_NAME = "party_name"
        const val EXTRA_PARTY_LABEL = "party_label"
        const val EXTRA_PARTY_ID = "party_id"
        const val EXTRA_DATE_MILLIS = "date_millis"
        const val EXTRA_SUBTOTAL = "subtotal"
        const val EXTRA_DISCOUNT = "discount"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_PAID = "paid"
        const val EXTRA_PAYMENT_METHOD = "payment_method"
        const val EXTRA_ITEMS_ENCODED = "items_encoded"
    }

    // NOTE: cardBg/textDark/textGray/border/primary stay fixed — they're the printed
    // receipt paper's own colors (must always look like white paper with dark ink,
    // same as what actually prints/shares via WhatsApp, regardless of app theme).
    // Only the screen's own background (around the paper) follows dark mode.
    private var bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val green = "#1FA971"
    private val greenDark = "#158A5A"
    private val blue = "#2F6FED"
    private val whatsapp = "#25D366"
    private val whatsappDark = "#1DA851"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    private fun loadThemeColors() {
        bg = com.grocerypos.v11.util.ThemeManager.palette(this).bg
    }

    private fun tintedDrawable(iconRes: Int, tintHex: String, sizeDp: Int = 16): android.graphics.drawable.Drawable? {
        val d = androidx.core.content.ContextCompat.getDrawable(this, iconRes)?.mutate() ?: return null
        d.setTint(Color.parseColor(tintHex))
        val size = (sizeDp * resources.displayMetrics.density).toInt()
        d.setBounds(0, 0, size, size)
        return d
    }

    private fun TextView.setLeadingIcon(iconRes: Int, tintHex: String, sizeDp: Int = 16, paddingDp: Int = 8) {
        setCompoundDrawablesRelative(tintedDrawable(iconRes, tintHex, sizeDp), null, null, null)
        compoundDrawablePadding = (paddingDp * resources.displayMetrics.density).toInt()
    }

    private data class PreviewLine(val name: String, val qty: String, val unit: String, val rate: Double, val amount: Double)

    // Whole-rupee, comma-grouped amount formatting for the "Gate Pass" print
    // layout (e.g. 266030.0 -> "266,030"), matching the reference wholesaler
    // slip's amount columns, which show no decimal/paisa places.
    private fun formatAmt(v: Double): String =
        java.text.DecimalFormat("#,##0").format(Math.round(v))

    private var shopName = "IBTISAAM Kiryana Store"
    private var shopPhone = ""
    private var shopAddress = ""
    private var receiptFooter = ""

    // ---- state needed for WhatsApp share ----
    private var partyId: Long? = null
    private var referenceNo = ""
    private var totalAmount = 0.0
    private lateinit var receiptCardRef: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        loadThemeColors()

        val type = intent.getStringExtra(EXTRA_TYPE) ?: "sale"
        val reference = intent.getStringExtra(EXTRA_REFERENCE) ?: ""
        val partyName = intent.getStringExtra(EXTRA_PARTY_NAME) ?: ""
        val partyLabel = intent.getStringExtra(EXTRA_PARTY_LABEL) ?: "Customer"
        val incomingPartyId = intent.getLongExtra(EXTRA_PARTY_ID, -1L)
        partyId = if (incomingPartyId > 0) incomingPartyId else null
        val dateMillis = intent.getLongExtra(EXTRA_DATE_MILLIS, System.currentTimeMillis())
        val subtotal = intent.getDoubleExtra(EXTRA_SUBTOTAL, 0.0)
        val discount = intent.getDoubleExtra(EXTRA_DISCOUNT, 0.0)
        val total = intent.getDoubleExtra(EXTRA_TOTAL, 0.0)
        val paid = intent.getDoubleExtra(EXTRA_PAID, 0.0)
        val paymentMethod = intent.getStringExtra(EXTRA_PAYMENT_METHOD) ?: ""
        val itemsEncoded = intent.getStringExtra(EXTRA_ITEMS_ENCODED) ?: ""

        referenceNo = reference
        totalAmount = total

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
        header.addView(ImageView(this).apply {
            setImageDrawable(tintedDrawable(R.drawable.ic_check, "#FFFFFF", 32))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER }
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
        receiptCardRef = receiptCard

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
        // CHANGE (Gate Pass style header): reordered to Customer / Bill No / Cashier /
        // Date / Payment, matching the wholesaler slip's field order and labels
        // ("Bill No" instead of "Ref", added "Cashier" — the logged-in staff name).
        if (partyName.isNotBlank()) receiptCard.addView(kv(partyLabel, partyName))
        receiptCard.addView(kv("Bill No", reference))
        val cashierName = getSharedPreferences("session", MODE_PRIVATE).getString("username", null)
        if (!cashierName.isNullOrBlank()) receiptCard.addView(kv("Cashier", cashierName))
        receiptCard.addView(kv("Date", fmt.format(Date(dateMillis))))
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
                text = "AMOUNT"; textSize = 11f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor(textGray))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@BillPreviewActivity).apply {
                text = "QTY"; textSize = 11f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor(textGray))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@BillPreviewActivity).apply {
                text = "RATE"; textSize = 11f; gravity = Gravity.END
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
                // Item name sits directly under the ITEM header. TEXT_ALIGNMENT_VIEW_START
                // keeps Urdu names on the left edge too (default alignment flips RTL text
                // to the right side of the cell, away from the ITEM header).
                addView(TextView(this@BillPreviewActivity).apply {
                    text = line.name; textSize = 13.5f
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.START
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                })
                addView(TextView(this@BillPreviewActivity).apply {
                    text = "%.2f".format(line.amount); textSize = 13.5f; gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(textDark))
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@BillPreviewActivity).apply {
                    text = "${line.qty} ${line.unit}"; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(textDark))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@BillPreviewActivity).apply {
                    text = "%.2f".format(line.rate); textSize = 13f; gravity = Gravity.END
                    setTextColor(Color.parseColor(textDark))
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
        root.addView(spacer(16))

        // ---- WhatsApp share button (full width, above Print/Done) ----
        root.addView(Button(this).apply {
            text = "WhatsApp par bhejein"
            setLeadingIcon(R.drawable.ic_send, "#FFFFFF", 18, 8)
            setTextColor(Color.WHITE)
            textSize = 14.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor(whatsapp), Color.parseColor(whatsappDark))
            ).apply { cornerRadius = 16f }
            setPadding(0, 26, 0, 26)
            applyElevation(this, 4f)
            setOnClickListener { handleWhatsAppShare() }
        })
        root.addView(spacer(12))

        // ---- "Add Another Bill" — lets the user jump straight back into a fresh
        // Purchase/Sale screen instead of finishing all the way to the Dashboard,
        // so entering several bills back-to-back doesn't need a Dashboard round-trip. ----
        if (type == "purchase" || type == "sale") {
            root.addView(Button(this).apply {
                text = if (type == "purchase") "+ NAYA PURCHASE BILL" else "+ NAYI SALE BILL"
                setLeadingIcon(R.drawable.ic_check, "#FFFFFF", 18, 8)
                setTextColor(Color.WHITE)
                textSize = 14.5f
                isAllCaps = false
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(greenDark))
                    cornerRadius = 16f
                }
                setPadding(0, 26, 0, 26)
                applyElevation(this, 4f)
                setOnClickListener {
                    val next = if (type == "purchase") com.grocerypos.v11.ui.PurchaseActivity::class.java else com.grocerypos.v11.ui.SaleActivity::class.java
                    startActivity(Intent(this@BillPreviewActivity, next))
                    finish()
                }
            })
            root.addView(spacer(12))
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "PRINT"
            setLeadingIcon(R.drawable.ic_printer, "#FFFFFF", 17, 6)
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
            text = "DONE"
            setLeadingIcon(R.drawable.ic_check, "#FFFFFF", 17, 6)
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

    // ================= WhatsApp share =================

    private fun handleWhatsAppShare() {
        val pid = partyId
        if (pid == null) {
            // Walk-in / no linked customer — just ask for a number, nothing to save to.
            promptForPhoneAndShare(saveToCustomerId = null)
            return
        }
        lifecycleScope.launch {
            val db = PosDatabase.get(this@BillPreviewActivity)
            val customer = db.customerDao().find(pid)
            val phone = customer?.phone?.trim() ?: ""
            if (phone.isNotBlank()) {
                shareBitmapToWhatsApp(phone)
            } else {
                promptForPhoneAndShare(saveToCustomerId = pid)
            }
        }
    }

    private fun promptForPhoneAndShare(saveToCustomerId: Long?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        val phoneInput = EditText(this).apply {
            hint = "Customer ka number (e.g. 03001234567)"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        container.addView(phoneInput)

        var saveCheckbox: CheckBox? = null
        if (saveToCustomerId != null) {
            container.addView(spacer(10))
            saveCheckbox = CheckBox(this).apply {
                text = "Number save karein future ke liye"
                isChecked = true
            }
            container.addView(saveCheckbox)
        }

        AlertDialog.Builder(this)
            .setTitle("WhatsApp Number")
            .setView(container)
            .setPositiveButton("Bhejein") { d, _ ->
                val phone = phoneInput.text.toString().trim()
                if (phone.length < 7) {
                    Toast.makeText(this, "Sahi number likhein", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (saveToCustomerId != null && saveCheckbox?.isChecked == true) {
                    lifecycleScope.launch {
                        val db = PosDatabase.get(this@BillPreviewActivity)
                        val customer = db.customerDao().find(saveToCustomerId)
                        if (customer != null) {
                            db.customerDao().update(customer.copy(phone = phone))
                        }
                        shareBitmapToWhatsApp(phone)
                    }
                } else {
                    shareBitmapToWhatsApp(phone)
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareBitmapToWhatsApp(rawPhone: String) {
        val bitmap = viewToBitmap(receiptCardRef)
        val uri = try {
            bitmapToShareUri(bitmap)
        } catch (e: Exception) {
            Toast.makeText(this, "Image banane mein masla hua", Toast.LENGTH_SHORT).show()
            return
        }

        val caption = "Invoice: $referenceNo\nTotal: Rs %.2f\nShukriya!".format(totalAmount)
        val jid = cleanPhoneToJid(rawPhone)

        // FIX (share reliability): a content:// URI passed only via EXTRA_STREAM,
        // without a matching ClipData, does not reliably get its read permission
        // grant honoured by the receiving app on many Android versions/OEMs — WhatsApp
        // would silently fail to load the image (blank/broken attachment) even though
        // startActivity() itself never threw. Setting ClipData explicitly fixes that.
        val clip = ClipData.newUri(contentResolver, "receipt", uri)

        // Try direct chat with that number first (undocumented but widely working on
        // regular WhatsApp; some OEM/WhatsApp builds ignore the "jid" extra and just
        // open the normal contact/share picker instead — that's an acceptable fallback,
        // not a crash, so we still consider this the "success" path).
        val directIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            putExtra("jid", jid)
            setPackage("com.whatsapp")
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(directIntent)
        } catch (e: Exception) {
            // Fallback: generic share sheet (WhatsApp not installed, or direct-jid trick failed)
            try {
                val fallback = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, caption)
                    clipData = clip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(fallback, "Bill share karein"))
            } catch (e2: Exception) {
                Toast.makeText(this, "Share nahi ho saka. WhatsApp installed hai?", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Cleans an entered number into a WhatsApp jid ("<countrycode><number>@s.whatsapp.net").
     *  Assumes Pakistan (92) if no country code was entered — adjust the default
     *  country code below if this shop is in a different country. */
    private fun cleanPhoneToJid(raw: String): String {
        var digits = raw.replace(Regex("[^0-9]"), "")
        if (digits.startsWith("0")) digits = "92" + digits.substring(1)
        else if (!digits.startsWith("92") && digits.length <= 10) digits = "92$digits"
        return "$digits@s.whatsapp.net"
    }

    private fun viewToBitmap(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        view.draw(canvas)
        return bitmap
    }

    private fun bitmapToShareUri(bitmap: Bitmap): Uri {
        val dir = File(cacheDir, "receipts").apply { mkdirs() }
        val file = File(dir, "bill_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    // ================= Thermal print =================

    // FIX (item table matches on-screen preview — "print view ki tarah print ana
    // chahiye"): the preview card's header grew a dedicated RATE column
    // (ITEM / RATE / QTY / AMOUNT) but the printed receipt was still using the
    // older 3-column header ("Item / Qty / Amount", no Rate at all) with rate
    // tucked away as a small "@ rate" note in a different order underneath —
    // so the two no longer matched. The printed header now uses
    // PrinterHelper.ReceiptLine.Row4 with all 4 labels, and each item's detail
    // line under the name now reads rate / qty / amount, left-to-right, the
    // same order as the header and the preview card — instead of the old
    // qty / "@ rate" / amount order. Still borderless, no box/grid lines
    // anywhere, matching the preview exactly.
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
            val printerType = when (db.appSettingDao().get("printer_type")?.value?.lowercase()) {
                "usb" -> PrinterHelper.PrinterType.USB
                else -> PrinterHelper.PrinterType.BLUETOOTH
            }

            val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            val receiptLines = mutableListOf<PrinterHelper.ReceiptLine>()

            // ================= "Gate Pass" wholesaler-slip layout =================
            // FIX ("print format apply kro shuru se akhir tak same same"): rebuilt
            // to match the reference wholesaler slip section-by-section instead of
            // the older generic receipt layout. Two things are intentionally NOT a
            // literal copy of the reference photo, since copying them verbatim
            // would be wrong for OUR shop's own receipt rather than a faithful port
            // of the format:
            //  - the reference's title is "Gate Pass" (that supplier's own document
            //    name for goods leaving their warehouse) — ours prints "SALE
            //    RECEIPT"/"PURCHASE RECEIPT" in that same spot instead, since that's
            //    what this document actually is.
            //  - the reference's footer is that OTHER company's POS-software vendor
            //    contact number — replaced with our own receiptFooter setting so we
            //    don't print a stranger's phone number on every bill.
            // Everything else (section order, the Amount/Qty/Rate/Barcode column
            // layout, the Payable/Amount Paid/Prev Balance/Net Balance block) is
            // ported as-is.
            // FIX ("Sale Receipt us k sath hi date" — date moved onto the title's
            // own line, right-aligned, instead of sharing a row with Bill No further
            // down): title left, date right, one TwoCol row.
            receiptLines.add(
                PrinterHelper.ReceiptLine.TwoCol(
                    if (type == "sale") "SALE RECEIPT" else "PURCHASE RECEIPT",
                    fmt.format(Date(dateMillis)),
                    bold = true
                )
            )
            receiptLines.add(PrinterHelper.ReceiptLine.Center(shopName))
            // FIX ("Bhikhi Urdu ma adrees a raha ha wo b khatam nhi howa" — the
            // shop address line was never actually asked for, only Shop name +
            // Phone number were in the original list; removed).
            if (shopPhone.isNotBlank()) receiptLines.add(PrinterHelper.ReceiptLine.Center(shopPhone))

            // ---- Customer / Bill block ----
            // FIX ("Cash credit customer b hata do" — the old "Cash Customer .....
            // Customer" row printed the payment-method label against the bare party
            // type and added nothing useful; removed). Shop name/phone above and
            // Customer/Bill No below now sit right after each other with no
            // Divider between them ("ye sab sath sath ... extra space k bagair") —
            // only one Divider now, right before the item table.
            if (partyName.isNotBlank()) {
                val codePrefix = partyId?.let { "$it " } ?: ""
                receiptLines.add(PrinterHelper.ReceiptLine.TwoCol("$partyLabel:", "$codePrefix$partyName"))
            } else {
                receiptLines.add(PrinterHelper.ReceiptLine.TwoCol("$partyLabel:", "Walk-in"))
            }
            receiptLines.add(PrinterHelper.ReceiptLine.TwoCol("Bill No:", reference))
            // FIX ("Till no aur cashier name b khatam nhi howa" — also never asked
            // for in the original list, removed same as the address line above).
            receiptLines.add(PrinterHelper.ReceiptLine.Divider)

            // ---- Item table: Amount / Qty / Rate / Barcode header + one plain row
            // per item (amount, qty, rate, name). ----
            // FIX ("Amount. Qty Rate Barcode" — 3rd column relabeled from CTN to
            // Rate, showing each item's per-unit rate instead of qty-as-whole-number).
            val gateWeights = listOf(1.15f, 0.85f, 0.7f, 2.3f)
            receiptLines.add(
                PrinterHelper.ReceiptLine.Row4("Amount", "Qty", "Rate", "Barcode", gateWeights, bold = true)
            )
            for (line in lines) {
                val qtyVal = line.qty.toDoubleOrNull() ?: 0.0
                receiptLines.add(
                    PrinterHelper.ReceiptLine.GateRow(
                        amount = formatAmt(line.amount),
                        qty = "%.3f".format(qtyVal),
                        rate = "%.2f".format(line.rate),
                        name = line.name,
                        weights = gateWeights
                    )
                )
            }
            receiptLines.add(PrinterHelper.ReceiptLine.Divider)

            // ---- Totals block: Gross Amount, Payable, Amount Paid, Prev Balance,
            // Net Balance.
            // FIX (per user's own hand-drawn sample receipt): dropped the
            // "= X.XXX Total units" suffix off Gross Amount (plain label now, no
            // qty text) and dropped the separate "Total Count" row entirely — it
            // always printed the exact same number as Payable right below it, so
            // the sample keeps only one of the two.
            receiptLines.add(PrinterHelper.ReceiptLine.TwoCol(formatAmt(total), "Gross Amount"))
            receiptLines.add(PrinterHelper.ReceiptLine.TwoCol(formatAmt(total), "Payable", bold = true))
            receiptLines.add(PrinterHelper.ReceiptLine.TwoCol(formatAmt(paid), "Amount Paid"))

            // Prev/Net balance come from the party's running ledger balance (already
            // updated to post-transaction by the time this prints), so Prev Balance
            // is backed out as netBalance - (total - paid). Walk-in/cash sales with
            // no linked party have no ledger, so both are omitted for them.
            val pid = partyId
            if (pid != null) {
                val netBalance = if (type == "sale") {
                    db.customerDao().find(pid)?.balance ?: 0.0
                } else {
                    db.supplierDao().find(pid)?.balance ?: 0.0
                }
                val prevBalance = netBalance - (total - paid)
                receiptLines.add(PrinterHelper.ReceiptLine.TwoCol(formatAmt(prevBalance), "Prev Balance"))
                receiptLines.add(PrinterHelper.ReceiptLine.TwoCol(formatAmt(netBalance), "Net Balance", bold = true))
            }
            receiptLines.add(PrinterHelper.ReceiptLine.Divider)
            receiptLines.add(PrinterHelper.ReceiptLine.Center(receiptFooter.ifBlank { "Shukriya! Dobara tashreef layein." }))

            val ok = PrinterHelper.printReceiptLines(
                this@BillPreviewActivity,
                printerType,
                mac,
                receiptLines
            )
            Toast.makeText(
                this@BillPreviewActivity,
                if (ok) "Print bhej diya" else "Print fail ho gaya. Printer check karein.",
                Toast.LENGTH_LONG
            ).show()
        }
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

    // spacer() now comes from the shared UiHelpers.kt (item #24 dedup) — was a
    // byte-identical private copy here before.
}
