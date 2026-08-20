
package com.grocerypos.v11.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.Product
import com.grocerypos.v11.Purchase
import com.grocerypos.v11.PurchaseItem
import com.grocerypos.v11.Supplier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

class PurchaseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BILL_NO = "billNo"
        const val VOICE_REQUEST_CODE = 1001
        const val PERMISSION_REQUEST_CODE = 1002
    }

    private val teal = "#0FA89A"
    private val tealGradientStart = "#14B8A6"
    private val tealGradientEnd = "#0D9488"
    private val darkBlue = "#0B2D4D"
    private val darkBlueStart = "#123A62"
    private val darkBlueEnd = "#081F36"
    private val bg = "#F4F5F7"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#111827"
    private val textGray = "#6B7280"
    private val border = "#E5E7EB"
    private val lightTealBg = "#E6F7F5"
    private val voiceRed = "#EF4444"

    private var selectedSupplier: Supplier? = null
    private var billItems = mutableListOf<BillItem>()
    private var editingBillNo: String? = null
    private var isListening = false
    private var allProductsCache: List<Product> = emptyList()

    data class BillItem(
        val product: Product,
        val displayQty: Double,
        val displayUnit: String,
        val factorToSmallest: Double,
        val ratePerDisplayUnit: Double,
        val totalAmount: Double,
        val smallestQty: Double
    )

    private lateinit var dateChip: TextView
    private lateinit var firmNameText: TextView
    private lateinit var partyBalanceText: TextView
    private lateinit var partyNameField: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var totalQtyText: TextView
    private lateinit var subtotalText: TextView
    private lateinit var paidAmountField: EditText
    private lateinit var dueAmountText: TextView
    private lateinit var saveButton: TextView
    private lateinit var voiceButton: TextView
    private lateinit var voiceStatusText: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        editingBillNo = intent.getStringExtra(EXTRA_BILL_NO)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        // PREMIUM HEADER WITH VOICE MIC - NO SCAN
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 24, 18, 22)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(tealGradientStart), Color.parseColor(tealGradientEnd))).apply { cornerRadii = floatArrayOf(0f,0f,0f,0f,28f,28f,0f,0f) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 8f; outlineProvider = ViewOutlineProvider.BACKGROUND }
        }
        val headerLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        headerLeft.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@PurchaseActivity).apply { text = "🛒"; textSize = 20f; setPadding(0,0,8,0) })
            addView(TextView(this@PurchaseActivity).apply { text = "Purchase"; textSize = 20f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        })
        headerLeft.addView(TextView(this).apply { text = "🎙️ Voice Billing"; textSize = 11.5f; setTextColor(Color.parseColor("#C6FFF7")); setPadding(0,3,0,0) })
        header.addView(headerLeft)

        // VOICE MIC BUTTON - PREMIUM
        voiceButton = TextView(this).apply {
            text = "🎙️"; textSize = 20f; gravity = Gravity.CENTER
            setPadding(14,14,14,14)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 6f; outlineProvider = ViewOutlineProvider.BACKGROUND }
            setOnClickListener { toggleVoice() }
        }
        header.addView(voiceButton)
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(8,1) })
        header.addView(TextView(this).apply {
            text = "🕘"; textSize = 16f; setTextColor(Color.WHITE); setPadding(10,10,10,10)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#20FFFFFF")) }
            setOnClickListener { startActivity(Intent(this@PurchaseActivity, HistoryActivity::class.java)) }
        })
        outer.addView(header)

        // VOICE STATUS BAR
        val voiceStatusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16,8,16,8)
            setBackgroundColor(Color.parseColor("#FEF2F2"))
            visibility = View.GONE
        }
        voiceStatusBar.addView(TextView(this).apply { text = "🔴"; textSize = 12f; setPadding(0,0,8,0) })
        voiceStatusText = TextView(this).apply { text = "Sun raha hun... Bolo"; textSize = 12f; setTextColor(Color.parseColor(voiceRed)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        voiceStatusBar.addView(voiceStatusText)
        voiceStatusBar.addView(TextView(this).apply { text = "❌"; textSize = 12f; setPadding(10,4,10,4); setOnClickListener { stopVoiceUI() } })
        outer.addView(voiceStatusBar)
        // Keep reference for toggle
        voiceStatusBar.tag = "voiceBar"

        val scrollRoot = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 24) }

        // DATE CHIP
        dateChip = TextView(this).apply {
            text = "📅 " + java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())
            textSize = 13f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD)
            setPadding(18,9,18,9)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 24f; setStroke(1, Color.parseColor(border)) }
        }
        root.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(dateChip) })
        root.addView(spacer(12))

        // FIRM CARD
        val firmCard = premiumCard()
        firmCard.addView(TextView(this).apply { text = "🏢 Firm Name"; textSize = 10.5f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD) })
        firmNameText = TextView(this).apply { text = "✨ ابـتسام ٹریڈرز"; textSize = 16f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END; setPadding(0,8,0,12) }
        firmCard.addView(firmNameText)
        firmCard.addView(View(this).apply { setBackgroundColor(Color.parseColor("#F3F4F6")); layoutParams = LinearLayout.LayoutParams(-1,1) })
        firmCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0,12,0,0)
            addView(TextView(this@PurchaseActivity).apply { text = "💰 Party Balance"; textSize = 11f; setTextColor(Color.parseColor(textGray)); layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
            partyBalanceText = TextView(this@PurchaseActivity).apply { text = "Rs 0.00"; textSize = 15f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) }
            addView(partyBalanceText)
        })
        root.addView(firmCard)
        root.addView(spacer(12))

        // PARTY CARD
        val partyCard = premiumCard()
        partyCard.addView(TextView(this).apply { text = "👤 PARTY / SUPPLIER"; textSize = 10.5f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); setPadding(0,0,0,10) })
        val partyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        partyNameField = TextView(this).apply {
            text = "🏭 Party Name (Supplier) * - Bolo 'Supplier Ahmed'"; textSize = 13.5f; setTextColor(Color.parseColor("#9AA6B8")); layoutParams = LinearLayout.LayoutParams(0,-2,1f)
            setPadding(14,14,14,14)
            background = GradientDrawable().apply { setColor(Color.parseColor("#F9FAFB")); cornerRadius = 10f; setStroke(1, Color.parseColor(border)) }
            setOnClickListener { openSupplierPicker() }
        }
        partyRow.addView(partyNameField)
        partyRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(8,1) })
        partyRow.addView(TextView(this).apply {
            text = "＋"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(darkBlueStart), Color.parseColor(darkBlueEnd))).apply { shape = GradientDrawable.OVAL }
            val px = (42*resources.displayMetrics.density).toInt(); width = px; height = px
            setOnClickListener { startActivity(Intent(this@PurchaseActivity, PartyActivity::class.java)) }
        })
        partyCard.addView(partyRow)
        root.addView(partyCard)
        root.addView(spacer(12))

        // ADD ITEMS CARD - VOICE ONLY, NO SCAN
        val addItemsCard = premiumCard().apply { setPadding(16,14,16,14) }
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addRow.addView(LinearLayout(this@PurchaseActivity).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-2,1f)
            addView(TextView(this@PurchaseActivity).apply { text = "🎙️ Voice Add"; textSize = 15f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(this@PurchaseActivity).apply { text = "Bolo 'Add 2 carton sugar'"; textSize = 11f; setTextColor(Color.parseColor(textGray)); setPadding(0,2,0,0) })
        })
        addRow.addView(TextView(this).apply {
            text = "➕ Add"; textSize = 13f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setPadding(18,12,18,12)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(darkBlueStart), Color.parseColor(darkBlueEnd))).apply { cornerRadius = 24f }
            setOnClickListener { showAddItemDialog() }
        })
        addRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(8,1) })
        addRow.addView(TextView(this).apply {
            text = "🎙️"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(14,12,14,12)
            background = GradientDrawable().apply { setColor(Color.parseColor(voiceRed)); cornerRadius = 24f }
            setOnClickListener { toggleVoice() }
        })
        addItemsCard.addView(addRow)
        // Voice hints
        addItemsCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1,8) })
        addItemsCard.addView(TextView(this).apply {
            text = "💡 Bolo: 'Sugar 2 carton', 'Supplier Ali', 'Paid 5000', 'Save purchase'"
            textSize = 10.5f; setTextColor(Color.parseColor(textGray)); setPadding(4,0,0,0)
        })
        root.addView(addItemsCard)
        root.addView(spacer(10))

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)
        root.addView(spacer(10))

        val totalsCard = premiumCard().apply { setPadding(16,12,16,12) }
        totalQtyText = TextView(this).apply { text = "📦 Total Qty:0.0"; textSize = 11.5f; setTextColor(Color.parseColor(textGray)); layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        subtotalText = TextView(this).apply { text = "💵 Subtotal: 0.00"; textSize = 11.5f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        val totalsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(totalQtyText); addView(subtotalText) }
        totalsCard.addView(totalsRow)
        root.addView(totalsCard)
        root.addView(spacer(12))

        val paidCard = premiumCard()
        val paidRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        paidRow.addView(TextView(this).apply { text = "💳 PAID - Bolo 'Paid 5000'"; textSize = 11f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        paidRow.addView(TextView(this).apply { text = "Rs"; textSize = 14f; setTextColor(Color.parseColor(teal)); setTypeface(typeface, Typeface.BOLD); setPadding(0,0,8,0) })
        paidAmountField = EditText(this).apply {
            hint = "0"; textSize = 18f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = null; gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0,-2,1f)
            addTextChangedListener(object: TextWatcher{ override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){} override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){} override fun afterTextChanged(s:Editable?){ updateDue() } })
        }
        paidRow.addView(paidAmountField)
        paidCard.addView(paidRow)
        root.addView(paidCard)
        root.addView(spacer(12))

        val dueCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16,14,16,14)
            background = GradientDrawable().apply { setColor(Color.parseColor(lightTealBg)); cornerRadius = 12f }
        }
        dueCard.addView(TextView(this).apply { text = "⏳ DUE AMOUNT"; textSize = 11f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        dueAmountText = TextView(this).apply { text = "Rs 0"; textSize = 18f; setTextColor(Color.parseColor(teal)); setTypeface(typeface, Typeface.BOLD) }
        dueCard.addView(dueAmountText)
        root.addView(dueCard)
        root.addView(spacer(18))

        saveButton = TextView(this).apply {
            text = "🎙️ Bolo 'Save Purchase' | ✅ SAVE PURCHASE"; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0,18,0,18)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor(tealGradientStart), Color.parseColor(tealGradientEnd))).apply { cornerRadius = 14f }
            setOnClickListener { savePurchase() }
        }
        root.addView(saveButton)

        scrollRoot.addView(root)
        outer.addView(scrollRoot)
        setContentView(outer)

        // Load products cache for voice
        lifecycleScope.launch {
            allProductsCache = PosDatabase.get(this@PurchaseActivity).productDao().all().first()
        }

        if (editingBillNo != null) loadBillForEdit(editingBillNo!!)
    }

    private fun premiumCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16,14,16,14)
        background = GradientDrawable().apply { setColor(Color.parseColor(cardWhite)); cornerRadius = 12f; setStroke(1, Color.parseColor(border)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 2f; outlineProvider = ViewOutlineProvider.BACKGROUND }
    }

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, (h*resources.displayMetrics.density).toInt()) }

    // ===== VOICE CONTROL - COMPLETE PACKAGE =====
    private fun toggleVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
            return
        }
        startVoiceRecognition()
    }

    private fun startVoiceRecognition() {
        isListening = true
        updateVoiceUI(true)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo: Add item, Supplier, Paid amount, Save purchase")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            startActivityForResult(intent, VOICE_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "🎙️ Voice not supported", Toast.LENGTH_SHORT).show()
            updateVoiceUI(false)
        }
    }

    private fun updateVoiceUI(listening: Boolean) {
        val outer = findViewById<LinearLayout>(android.R.id.content).getChildAt(0) as? LinearLayout
        outer?.let {
            for (i in 0 until it.childCount) {
                val child = it.getChildAt(i)
                if (child is LinearLayout && child.tag == "voiceBar") {
                    child.visibility = if (listening) View.VISIBLE else View.GONE
                }
            }
        }
        if (listening) {
            voiceButton.text = "🔴"
            voiceButton.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(voiceRed)) }
        } else {
            voiceButton.text = "🎙️"
            voiceButton.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }
    }

    private fun stopVoiceUI() {
        isListening = false
        updateVoiceUI(false)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST_CODE && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.get(0) ?: ""
            if (spokenText.isNotBlank()) {
                voiceStatusText.text = "Suna: $spokenText"
                processVoiceCommand(spokenText)
            }
            stopVoiceUI()
        } else {
            stopVoiceUI()
        }
    }

    private fun processVoiceCommand(command: String) {
        val cmd = command.lowercase(Locale.getDefault()).trim()
        Toast.makeText(this, "🎙️ $command", Toast.LENGTH_SHORT).show()

        when {
            // SAVE PURCHASE
            cmd.contains("save") && (cmd.contains("purchase") || cmd.contains("bill") || cmd.contains("محفوظ")) -> {
                savePurchase()
            }
            // SUPPLIER
            cmd.contains("supplier") || cmd.contains("party") || cmd.contains("سپلائر") -> {
                val supplierName = cmd.replace("supplier", "").replace("party", "").replace("select", "").replace("set", "").trim()
                if (supplierName.length > 2) {
                    findAndSetSupplier(supplierName)
                } else {
                    openSupplierPicker()
                }
            }
            // PAID AMOUNT
            cmd.contains("paid") || cmd.contains("payment") || cmd.contains("جمع") || cmd.contains("ادائیگی") -> {
                val amount = extractNumber(cmd)
                if (amount > 0) {
                    paidAmountField.setText(amount.toInt().toString())
                    Toast.makeText(this, "✅ Paid set: Rs $amount", Toast.LENGTH_SHORT).show()
                }
            }
            // ADD ITEM - Main command: "Add 2 carton sugar", "Sugar 2 carton", "2 ctn shampoo add karo"
            cmd.contains("add") || cmd.contains("ایڈ") || Regex("\\d+.*(carton|ctn|bag|pcs|outer|lari|dabbi|کاٹن|بوری)").containsMatchIn(cmd) -> {
                parseAndAddItemVoice(cmd)
            }
            // TOTAL
            cmd.contains("total") || cmd.contains("subtotal") || cmd.contains("کتنا") -> {
                val subtotal = billItems.sumOf { it.totalAmount }
                Toast.makeText(this, "💵 Total: Rs %.2f".format(subtotal), Toast.LENGTH_LONG).show()
            }
            // CLEAR BILL
            cmd.contains("clear") || cmd.contains("new") || cmd.contains("نیا") -> {
                billItems.clear()
                refreshItemsUI()
                Toast.makeText(this, "🗑️ Bill cleared", Toast.LENGTH_SHORT).show()
            }
            else -> {
                // Try to parse as item anyway - if user just says "Sugar 2 carton"
                parseAndAddItemVoice(cmd)
            }
        }
    }

    private fun extractNumber(text: String): Double {
        val regex = Regex("(\\d+(?:\\.\\d+)?)")
        val match = regex.find(text)
        return match?.value?.toDoubleOrNull() ?: 0.0
    }

    private fun parseAndAddItemVoice(command: String) {
        lifecycleScope.launch {
            if (allProductsCache.isEmpty()) {
                allProductsCache = PosDatabase.get(this@PurchaseActivity).productDao().all().first()
            }

            var qty = extractNumber(command)
            if (qty == 0.0) qty = 1.0

            // Detect unit
            var unit = "pcs"
            when {
                command.contains("carton") || command.contains("ctn") || command.contains("کارٹن") || command.contains("کاٹن") -> unit = "ctn"
                command.contains("bag") || command.contains("bori") || command.contains("بوری") -> unit = "bag"
                command.contains("outer") || command.contains("آؤٹر") -> unit = "outer"
                command.contains("lari") || command.contains("لڑی") -> unit = "lari"
                command.contains("dabbi") || command.contains("ڈبی") || command.contains("dabi") -> unit = "dabbi"
                command.contains("kg") || command.contains("kilo") || command.contains("کلو") -> unit = "kg"
                command.contains("pcs") || command.contains("piece") || command.contains("عدد") -> unit = "pcs"
            }

            // Find product - remove numbers and units from command to get product name
            var productName = command
                .replace(Regex("\\d+(?:\\.\\d+)?"), "")
                .replace("add", "", true)
                .replace("karo", "", true)
                .replace("kar", "", true)
                .replace("carton", "", true)
                .replace("ctn", "", true)
                .replace("bag", "", true)
                .replace("outer", "", true)
                .replace("lari", "", true)
                .replace("dabbi", "", true)
                .replace("pcs", "", true)
                .replace("kg", "", true)
                .trim()

            if (productName.length < 2) {
                Toast.makeText(this@PurchaseActivity, "❓ Product name nahi mila: $command", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Search product
            val matched = allProductsCache.filter { it.name.contains(productName, true) }
            val product = matched.firstOrNull()

            if (product == null) {
                // Ask to create new item
                AlertDialog.Builder(this@PurchaseActivity)
                    .setTitle("❓ Item nahi mila: $productName")
                    .setMessage("Kya naya item banana hai? ' $productName ' - $qty $unit")
                    .setPositiveButton("✅ Banao") { _, _ ->
                        showSaveNewItemDialogVoice(productName, unit, qty)
                    }
                    .setNegativeButton("❌ Cancel", null)
                    .show()
                return@launch
            }

            // Calculate factor - 3 tier logic: Ctn 50 Outer 10 Dabbi, Shampoo 48 Lari 16 Pcs
            val factor = when (unit) {
                product.unit -> if (product.secondaryUnitQty > 0 && product.tertiaryUnitQty > 0) product.secondaryUnitQty * product.tertiaryUnitQty else if (product.secondaryUnitQty > 0) product.secondaryUnitQty else 1.0
                product.secondaryUnit -> if (product.tertiaryUnitQty > 0) product.tertiaryUnitQty else 1.0
                else -> 1.0
            }
            val smallestQty = qty * factor
            val rate = product.cost
            val total = qty * rate

            billItems.add(BillItem(product, qty, unit, factor, rate, total, smallestQty))
            refreshItemsUI()
            Toast.makeText(this@PurchaseActivity, "✅ Added: ${product.name} - $qty $unit = Rs %.2f (Voice)".format(total), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSaveNewItemDialogVoice(name: String, unit: String, qty: Double) {
        lifecycleScope.launch {
            val product = Product(barcode = "P${System.currentTimeMillis()}", name = name.trim().replaceFirstChar { it.uppercase() }, unit = unit, tertiaryUnit = unit, stock = 0, cost = 0.0, salePrice = 0.0)
            PosDatabase.get(this@PurchaseActivity).productDao().upsert(product)
            allProductsCache = allProductsCache + product
            // Auto add to bill
            billItems.add(BillItem(product, qty, unit, 1.0, 0.0, 0.0, qty))
            refreshItemsUI()
            Toast.makeText(this@PurchaseActivity, "✅ New item created & added: $name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findAndSetSupplier(name: String) {
        lifecycleScope.launch {
            val suppliers = PosDatabase.get(this@PurchaseActivity).supplierDao().all().first()
            val matched = suppliers.filter { it.name.contains(name, true) }.firstOrNull()
            if (matched != null) {
                selectedSupplier = matched
                partyNameField.text = "✅ " + matched.name + " (Voice)"
                partyNameField.setTextColor(Color.parseColor(textDark))
                partyNameField.setTypeface(partyNameField.typeface, Typeface.BOLD)
                val closing = matched.openingBalance + matched.balance
                partyBalanceText.text = "Rs %.2f".format(closing)
                Toast.makeText(this@PurchaseActivity, "✅ Supplier: ${matched.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@PurchaseActivity, "❓ Supplier nahi mila: $name", Toast.LENGTH_SHORT).show()
                openSupplierPicker()
            }
        }
    }

    private fun openSupplierPicker() {
        lifecycleScope.launch {
            val suppliers = PosDatabase.get(this@PurchaseActivity).supplierDao().all().first()
            if (suppliers.isEmpty()) {
                Toast.makeText(this@PurchaseActivity, "👥 No suppliers - bolo 'Add supplier Ahmed'", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = suppliers.map { "🏭 " + it.name }.toTypedArray()
            AlertDialog.Builder(this@PurchaseActivity)
                .setTitle("👥 Select Supplier - Bolo naam")
                .setItems(names) { _, which ->
                    selectedSupplier = suppliers[which]
                    partyNameField.text = "✅ " + selectedSupplier!!.name
                    partyNameField.setTextColor(Color.parseColor(textDark))
                    partyNameField.setTypeface(partyNameField.typeface, Typeface.BOLD)
                    val closing = selectedSupplier!!.openingBalance + selectedSupplier!!.balance
                    partyBalanceText.text = "Rs %.2f".format(closing)
                }.show()
        }
    }

    private fun showAddItemDialog() {
        // Manual dialog still available as fallback
        var allProducts: List<Product> = emptyList()
        lifecycleScope.launch {
            allProducts = PosDatabase.get(this@PurchaseActivity).productDao().all().first()
            val dialogLayout = LinearLayout(this@PurchaseActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(20,16,20,16); setBackgroundColor(Color.WHITE) }
            dialogLayout.addView(TextView(this@PurchaseActivity).apply { text = "📦 Add Item - Voice se bolo ya type karo"; textSize = 15f; setTextColor(Color.parseColor(darkBlue)); setTypeface(typeface, Typeface.BOLD); setPadding(0,0,0,12) })
            val itemNameBox = LinearLayout(this@PurchaseActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(14,6,8,6); background = GradientDrawable().apply { setColor(Color.parseColor("#F9FAFB")); cornerRadius = 12f; setStroke(1, Color.parseColor(border)) } }
            val itemNameField = EditText(this@PurchaseActivity).apply { hint = "🔍 Type to search..."; background = null; textSize = 14f; layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
            itemNameBox.addView(itemNameField)
            dialogLayout.addView(itemNameBox)

            val qtyField = EditText(this@PurchaseActivity).apply { hint = "Qty"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
            dialogLayout.addView(qtyField)
            val rateField = EditText(this@PurchaseActivity).apply { hint = "Rate"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
            dialogLayout.addView(rateField)

            val suggestionsBox = LinearLayout(this@PurchaseActivity).apply { orientation = LinearLayout.VERTICAL }
            dialogLayout.addView(suggestionsBox)

            val dialog = AlertDialog.Builder(this@PurchaseActivity).setView(dialogLayout).setPositiveButton("Add to Bill") { _, _ ->
                val qty = qtyField.text.toString().toDoubleOrNull() ?: 1.0
                val name = itemNameField.text.toString()
                val product = allProducts.filter { it.name.contains(name, true) }.firstOrNull()
                if (product != null) {
                    val rate = rateField.text.toString().toDoubleOrNull() ?: product.cost
                    billItems.add(BillItem(product, qty, product.unit, 1.0, rate, qty*rate, qty))
                    refreshItemsUI()
                }
            }.setNegativeButton("Cancel", null).create()
            dialog.show()

            itemNameField.addTextChangedListener(object: TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun afterTextChanged(s:Editable?){
                    suggestionsBox.removeAllViews()
                    val filter = s?.toString() ?: ""
                    if (filter.length < 1) return
                    val filtered = allProducts.filter { it.name.contains(filter, true) }.take(5)
                    for (p in filtered) {
                        suggestionsBox.addView(TextView(this@PurchaseActivity).apply {
                            text = p.name; setPadding(12,8,12,8)
                            setOnClickListener { itemNameField.setText(p.name); rateField.setText(p.cost.toString()) }
                        })
                    }
                }
            })
        }
    }

    private fun refreshItemsUI() {
        itemsContainer.removeAllViews()
        var totalQty = 0.0
        var subtotal = 0.0
        for ((index, item) in billItems.withIndex()) {
            totalQty += item.displayQty
            subtotal += item.totalAmount
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(14,12,14,12)
                background = GradientDrawable().apply { setColor(Color.parseColor(cardWhite)); cornerRadius = 10f; setStroke(1, Color.parseColor(border)) }
                layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,6) }
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-2,1f)
                addView(TextView(this@PurchaseActivity).apply { text = "📦 ${item.product.name}"; textSize = 13.5f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(this@PurchaseActivity).apply { text = "${item.displayQty} ${item.displayUnit} × Rs %.2f = Rs %.2f".format(item.ratePerDisplayUnit, item.totalAmount); textSize = 11f; setTextColor(Color.parseColor(textGray)) })
            })
            row.addView(TextView(this).apply { text = "🗑️"; setPadding(10,6,10,6); setOnClickListener { billItems.removeAt(index); refreshItemsUI() } })
            itemsContainer.addView(row)
        }
        totalQtyText.text = "📦 Total Qty:%.1f".format(totalQty)
        subtotalText.text = "💵 Subtotal: %.2f".format(subtotal)
        updateDue()
    }

    private fun updateDue() {
        val subtotal = billItems.sumOf { it.totalAmount }
        val paid = paidAmountField.text.toString().toDoubleOrNull() ?: 0.0
        dueAmountText.text = "⏳ Rs %.2f".format(subtotal - paid)
    }

    private fun savePurchase() {
        if (selectedSupplier == null) { Toast.makeText(this, "👥 Select supplier - Bolo 'Supplier Ahmed'", Toast.LENGTH_SHORT).show(); return }
        if (billItems.isEmpty()) { Toast.makeText(this, "📦 Add items - Bolo 'Add 2 carton sugar'", Toast.LENGTH_SHORT).show(); return }
        val subtotal = billItems.sumOf { it.totalAmount }
        val paid = paidAmountField.text.toString().toDoubleOrNull() ?: 0.0
        val billNo = editingBillNo ?: "PUR-${System.currentTimeMillis()}"
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)
            val purchase = Purchase(billNo = billNo, supplierId = selectedSupplier!!.id, supplierName = selectedSupplier!!.name, total = subtotal, paid = paid, status = "completed", createdAt = System.currentTimeMillis())
            db.purchaseDao().insertPurchase(purchase)
            val items = billItems.map { PurchaseItem(billNo = billNo, barcode = it.product.barcode, qty = it.smallestQty, unit = it.displayUnit, unitCost = it.ratePerDisplayUnit, amount = it.totalAmount) }
            db.purchaseDao().insertItems(items)
            for (item in billItems) { db.productDao().increaseStock(item.product.barcode, item.smallestQty.toInt()) }
            val due = subtotal - paid
            if (due > 0) { db.supplierDao().addBalance(selectedSupplier!!.id, due) }
            Toast.makeText(this@PurchaseActivity, "✅ Purchase saved (Voice): $billNo", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun loadBillForEdit(billNo: String) {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@PurchaseActivity)
            val purchase = db.purchaseDao().findPurchase(billNo) ?: return@launch
            val items = db.purchaseDao().itemsForBill(billNo)
            selectedSupplier = purchase.supplierId?.let { db.supplierDao().find(it) }
            partyNameField.text = "✅ " + purchase.supplierName
            paidAmountField.setText(purchase.paid.toString())
            billItems.clear()
            for (it in items) {
                val product = db.productDao().find(it.barcode) ?: Product(barcode = it.barcode, name = it.barcode)
                billItems.add(BillItem(product, it.qty, it.unit, 1.0, it.unitCost, it.amount, it.qty))
            }
            refreshItemsUI()
            saveButton.text = "✏️ UPDATE PURCHASE"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
        }
    }
}
