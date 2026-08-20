
package com.grocerypos.v11.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

// URDU ZYADA + HALKA SHOR (HORN, VEHICLE, GATHERING) OPTIMIZED VOICE
class PurchaseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BILL_NO = "billNo"
        const val VOICE_REQUEST_CODE = 1001
        const val PERMISSION_REQUEST_CODE = 1002
    }

    private val teal = "#0FA89A"
    private val tealStart = "#14B8A6"
    private val tealEnd = "#0D9488"
    private val darkBlue = "#0B2D4D"
    private val darkBlueStart = "#123A62"
    private val darkBlueEnd = "#081F36"
    private val bg = "#F4F5F7"
    private val cardWhite = "#FFFFFF"
    private val textDark = "#111827"
    private val textGray = "#6B7280"
    private val border = "#E5E7EB"
    private val lightTeal = "#E6F7F5"
    private val voiceRed = "#EF4444"

    private var selectedSupplier: Supplier? = null
    private var billItems = mutableListOf<BillItem>()
    private var editingBillNo: String? = null
    private var allProductsCache: List<Product> = emptyList()
    private var speechRecognizer: SpeechRecognizer? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

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
    private lateinit var voiceHintText: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        editingBillNo = intent.getStringExtra(EXTRA_BILL_NO)
        setupAudioEnhancers()

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        // HEADER - URDU VOICE FOCUS
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 24, 18, 22)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(tealStart), Color.parseColor(tealEnd))).apply { cornerRadii = floatArrayOf(0f,0f,0f,0f,28f,28f,0f,0f) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 8f; outlineProvider = ViewOutlineProvider.BACKGROUND }
        }
        val headerLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        headerLeft.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@PurchaseActivity).apply { text = "🛒"; textSize = 20f; setPadding(0,0,8,0) })
            addView(TextView(this@PurchaseActivity).apply { text = "خریداری - Purchase"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        })
        headerLeft.addView(TextView(this).apply { text = "🎙️ اردو وائس - ہارن/رش میں بھی"; textSize = 11f; setTextColor(Color.parseColor("#C6FFF7")); setPadding(0,3,0,0) })
        header.addView(headerLeft)

        voiceButton = TextView(this).apply {
            text = "🎙️"; textSize = 22f; gravity = Gravity.CENTER
            setPadding(16,16,16,16)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 6f; outlineProvider = ViewOutlineProvider.BACKGROUND }
            setOnClickListener { toggleVoiceUrdu() }
        }
        header.addView(voiceButton)
        outer.addView(header)

        // VOICE STATUS - URDU
        val voiceStatusBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14,10,14,10)
            setBackgroundColor(Color.parseColor("#FEF2F2"))
            visibility = View.GONE
            tag = "voiceBar"
        }
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusRow.addView(TextView(this).apply { text = "🔴"; textSize = 12f; setPadding(0,0,8,0) })
        voiceStatusText = TextView(this).apply { text = "سن رہا ہوں... بولو"; textSize = 13f; setTextColor(Color.parseColor(voiceRed)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        statusRow.addView(voiceStatusText)
        statusRow.addView(TextView(this).apply { text = "❌"; textSize = 12f; setPadding(10,4,10,4); setOnClickListener { stopVoiceUI() } })
        voiceStatusBar.addView(statusRow)
        voiceHintText = TextView(this).apply {
            text = "💡 بولو: 'چینی دو کارٹن', 'سپلائر احمد', 'جمع پانچ ہزار', 'محفوظ کرو'"
            textSize = 10.5f; setTextColor(Color.parseColor(textGray)); setPadding(0,6,0,0)
        }
        voiceStatusBar.addView(voiceHintText)
        outer.addView(voiceStatusBar)

        val scrollRoot = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 24) }

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
        firmCard.addView(TextView(this).apply { text = "🏢 فرم کا نام"; textSize = 10.5f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD) })
        firmNameText = TextView(this).apply { text = "✨ ابـتسام ٹریڈرز"; textSize = 16f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END; setPadding(0,8,0,12) }
        firmCard.addView(firmNameText)
        firmCard.addView(View(this).apply { setBackgroundColor(Color.parseColor("#F3F4F6")); layoutParams = LinearLayout.LayoutParams(-1,1) })
        firmCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0,12,0,0)
            addView(TextView(this@PurchaseActivity).apply { text = "💰 بیلنس"; textSize = 11f; setTextColor(Color.parseColor(textGray)); layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
            partyBalanceText = TextView(this@PurchaseActivity).apply { text = "Rs 0.00"; textSize = 15f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) }
            addView(partyBalanceText)
        })
        root.addView(firmCard)
        root.addView(spacer(12))

        // PARTY CARD - URDU VOICE HINT
        val partyCard = premiumCard()
        partyCard.addView(TextView(this).apply { text = "👤 پارٹی / سپلائر"; textSize = 10.5f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); setPadding(0,0,0,10) })
        val partyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        partyNameField = TextView(this).apply {
            text = "🏭 پارٹی نام * - بولو 'سپلائر احمد ٹریڈرز'"; textSize = 13f; setTextColor(Color.parseColor("#9AA6B8")); layoutParams = LinearLayout.LayoutParams(0,-2,1f)
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

        // ADD ITEMS - URDU VOICE ONLY, NO SCAN
        val addItemsCard = premiumCard().apply { setPadding(16,14,16,14) }
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addRow.addView(LinearLayout(this@PurchaseActivity).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-2,1f)
            addView(TextView(this@PurchaseActivity).apply { text = "🎙️ اردو وائس ایڈ"; textSize = 14f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(this@PurchaseActivity).apply { text = "بولو 'چینی دو کارٹن'"; textSize = 11f; setTextColor(Color.parseColor(textGray)); setPadding(0,2,0,0) })
        })
        addRow.addView(TextView(this).apply {
            text = "➕ ایڈ"; textSize = 12f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setPadding(16,10,16,10)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(darkBlueStart), Color.parseColor(darkBlueEnd))).apply { cornerRadius = 20f }
            setOnClickListener { showAddItemDialog() }
        })
        addRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(8,1) })
        addRow.addView(TextView(this).apply {
            text = "🎙️"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(14,10,14,10)
            background = GradientDrawable().apply { setColor(Color.parseColor(voiceRed)); cornerRadius = 20f }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 4f; outlineProvider = ViewOutlineProvider.BACKGROUND }
            setOnClickListener { toggleVoiceUrdu() }
        })
        addItemsCard.addView(addRow)
        addItemsCard.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1,8) })
        addItemsCard.addView(TextView(this).apply {
            text = "💡 اردو: 'چینی دو کارٹن', 'شیمپو ایک کارٹن', 'سپلائر علی', 'جمع 5000', 'محفوظ کرو'\n💡 English: 'Sugar 2 carton', 'Supplier Ahmed', 'Paid 5000', 'Save purchase'"
            textSize = 10f; setTextColor(Color.parseColor(textGray)); setPadding(4,0,0,0); setLineSpacing(4f, 1f)
        })
        root.addView(addItemsCard)
        root.addView(spacer(10))

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)
        root.addView(spacer(10))

        val totalsCard = premiumCard().apply { setPadding(16,12,16,12) }
        totalQtyText = TextView(this).apply { text = "📦 کل مقدار:0.0"; textSize = 11.5f; setTextColor(Color.parseColor(textGray)); layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        subtotalText = TextView(this).apply { text = "💵 ٹوٹل: 0.00"; textSize = 11.5f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        val totalsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(totalQtyText); addView(subtotalText) }
        totalsCard.addView(totalsRow)
        root.addView(totalsCard)
        root.addView(spacer(12))

        val paidCard = premiumCard()
        val paidRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        paidRow.addView(TextView(this).apply { text = "💳 جمع - بولو 'جمع 5000'"; textSize = 11f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
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
            background = GradientDrawable().apply { setColor(Color.parseColor(lightTeal)); cornerRadius = 12f }
        }
        dueCard.addView(TextView(this).apply { text = "⏳ بقایا"; textSize = 11f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        dueAmountText = TextView(this).apply { text = "Rs 0"; textSize = 18f; setTextColor(Color.parseColor(teal)); setTypeface(typeface, Typeface.BOLD) }
        dueCard.addView(dueAmountText)
        root.addView(dueCard)
        root.addView(spacer(18))

        saveButton = TextView(this).apply {
            text = "🎙️ بولو 'محفوظ کرو' | ✅ محفوظ کریں"; textSize = 13.5f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0,18,0,18)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor(tealStart), Color.parseColor(tealEnd))).apply { cornerRadius = 14f }
            setOnClickListener { savePurchase() }
        }
        root.addView(saveButton)

        scrollRoot.addView(root)
        outer.addView(scrollRoot)
        setContentView(outer)

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

    private fun setupAudioEnhancers() {
        // For horn/vehicle/gathering noise suppression
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                // These will be applied to audio session
                // Actual suppressors will be created when audio session is available
            }
        } catch (e: Exception) { }
    }

    // ===== URDU VOICE - HORN/VEHICLE/GATHERING FILTER =====
    private fun toggleVoiceUrdu() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
            return
        }
        startVoiceUrdu()
    }

    private fun startVoiceUrdu() {
        updateVoiceUI(true)
        // Primary: Urdu Pakistan, Fallback: English
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayOf("ur-PK", "en-US", "en-PK"))
            putExtra(RecognizerIntent.EXTRA_PROMPT, "بولو: چینی دو کارٹن، سپلائر، جمع، محفوظ کرو")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000)
            // Horn/vehicle noise handling - longer listening
            putExtra("android.speech.extra.DICTATION_MODE", true)
            putExtra("android.speech.extra.PREFER_OFFLINE", false)
        }
        try {
            startActivityForResult(intent, 1001)
        } catch (e: Exception) {
            // Fallback to English if Urdu not supported
            val fallbackIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo: Sugar 2 carton, Supplier, Paid, Save")
            }
            startActivityForResult(fallbackIntent, 1001)
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
        voiceButton.text = if (listening) "🔴" else "🎙️"
    }

    private fun stopVoiceUI() { updateVoiceUI(false) }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            // Try all results to handle horn/gathering noise - pick best match
            var processed = false
            results?.forEach { result ->
                if (!processed && result.isNotBlank() && isValidVoiceCommand(result)) {
                    voiceStatusText.text = "سنا: $result"
                    processVoiceUrdu(result)
                    processed = true
                }
            }
            if (!processed && !results.isNullOrEmpty()) {
                voiceStatusText.text = "سنا: ${results[0]}"
                processVoiceUrdu(results[0])
            }
            stopVoiceUI()
        } else {
            stopVoiceUI()
        }
    }

    // Filter horn/vehicle/gathering noise - check if result contains valid keywords
    private fun isValidVoiceCommand(text: String): Boolean {
        val lower = text.lowercase()
        val validKeywords = listOf(
            "چینی", "شیمپو", "صابن", "تیل", "آٹا", "چاول", "دال", "sugar", "shampoo", "soap", "oil",
            "کارٹن", "بوری", "ڈبی", "عدد", "کلو", "carton", "ctn", "bag", "pcs", "kg",
            "سپلائر", "پارٹی", "supplier", "party",
            "جمع", "ادا", "paid", "payment",
            "محفوظ", "سیو", "save",
            "ایڈ", "add", "ڈالو"
        )
        return validKeywords.any { lower.contains(it) } || Regex("\\d+").containsMatchIn(lower)
    }

    private fun processVoiceUrdu(command: String) {
        val cmd = command.lowercase(Locale.getDefault()).trim()
        Toast.makeText(this, "🎙️ $command", Toast.LENGTH_SHORT).show()

        when {
            // SAVE - URDU: محفوظ کرو، سیو کرو
            cmd.contains("محفوظ") || cmd.contains("سیو") || (cmd.contains("save") && (cmd.contains("purchase") || cmd.contains("خریداری") || cmd.length < 15)) -> {
                savePurchase()
            }
            // SUPPLIER - URDU: سپلائر احمد، پارٹی علی
            cmd.contains("سپلائر") || cmd.contains("سپلائیر") || cmd.contains("پارٹی") || cmd.contains("supplier") || cmd.contains("party") -> {
                val name = cmd.replace("سپلائر", "").replace("سپلائیر", "").replace("پارٹی", "").replace("supplier", "").replace("party", "").replace("select", "").replace("سیلیکٹ", "").replace("سلیکٹ", "").trim()
                if (name.length > 2) findAndSetSupplier(name) else openSupplierPicker()
            }
            // PAID - URDU: جمع پانچ ہزار، ادا پانچ ہزار
            cmd.contains("جمع") || cmd.contains("ادا") || cmd.contains("ادائیگی") || cmd.contains("paid") || cmd.contains("payment") -> {
                val amount = extractNumberUrdu(cmd)
                if (amount > 0) {
                    paidAmountField.setText(amount.toInt().toString())
                    Toast.makeText(this, "✅ جمع: Rs $amount", Toast.LENGTH_SHORT).show()
                }
            }
            // ADD ITEM - URDU MAIN
            cmd.contains("ایڈ") || cmd.contains("ڈالو") || cmd.contains("لاؤ") || cmd.contains("add") || Regex("\\d+.*(کارٹن|بوری|ڈبی|عدد|کلو|carton|ctn|bag|pcs|kg)").containsMatchIn(cmd) || isValidVoiceCommand(cmd) -> {
                parseAndAddItemUrdu(cmd)
            }
            // TOTAL
            cmd.contains("ٹوٹل") || cmd.contains("کل") || cmd.contains("کتنا") || cmd.contains("total") -> {
                val subtotal = billItems.sumOf { it.totalAmount }
                Toast.makeText(this, "💵 ٹوٹل: Rs %.2f - کل: %.1f".format(subtotal, billItems.sumOf { it.displayQty }), Toast.LENGTH_LONG).show()
            }
            // CLEAR
            cmd.contains("نیا") || cmd.contains("صاف") || cmd.contains("clear") || cmd.contains("new") -> {
                billItems.clear()
                refreshItemsUI()
                Toast.makeText(this, "🗑️ نیا بل", Toast.LENGTH_SHORT).show()
            }
            else -> {
                parseAndAddItemUrdu(cmd)
            }
        }
    }

    private fun extractNumberUrdu(text: String): Double {
        // Urdu numbers: ۱۲۳ and English 123
        var normalized = text
            .replace("۰", "0").replace("۱", "1").replace("۲", "2").replace("۳", "3").replace("۴", "4")
            .replace("۵", "5").replace("۶", "6").replace("۷", "7").replace("۸", "8").replace("۹", "9")
        
        // Urdu words for numbers
        val urduNumbers = mapOf(
            "ایک" to 1, "دو" to 2, "تین" to 3, "چار" to 4, "پانچ" to 5,
            "چھ" to 6, "سات" to 7, "آٹھ" to 8, "نو" to 9, "دس" to 10,
            "بیس" to 20, "تیس" to 30, "چالیس" to 40, "پچاس" to 50,
            "سو" to 100, "ہزار" to 1000
        )
        
        for ((word, num) in urduNumbers) {
            if (normalized.contains(word)) {
                return num.toDouble()
            }
        }
        
        val regex = Regex("(\\d+(?:\\.\\d+)?)")
        return regex.find(normalized)?.value?.toDoubleOrNull() ?: 0.0
    }

    private fun parseAndAddItemUrdu(command: String) {
        lifecycleScope.launch {
            if (allProductsCache.isEmpty()) {
                allProductsCache = PosDatabase.get(this@PurchaseActivity).productDao().all().first()
            }

            var qty = extractNumberUrdu(command)
            if (qty == 0.0) qty = 1.0

            // URDU UNITS + ENGLISH
            var unit = "pcs"
            when {
                command.contains("کارٹن") || command.contains("کاٹن") || command.contains("carton") || command.contains("ctn") || command.contains("کارٹون") -> unit = "ctn"
                command.contains("بوری") || command.contains("بوریاں") || command.contains("bag") || command.contains("بوری") -> unit = "bag"
                command.contains("آؤٹر") || command.contains("outer") || command.contains("اوٹر") -> unit = "outer"
                command.contains("لڑی") || command.contains("لری") || command.contains("lari") -> unit = "lari"
                command.contains("ڈبی") || command.contains("ڈبہ") || command.contains("dabbi") || command.contains("dabi") -> unit = "dabbi"
                command.contains("کلو") || command.contains("کلوگرام") || command.contains("kg") || command.contains("kilo") -> unit = "kg"
                command.contains("عدد") || command.contains("پیس") || command.contains("pcs") || command.contains("piece") -> unit = "pcs"
            }

            // Product name - URDU + ENGLISH cleaning
            var productName = command
                .replace(Regex("\\d+(?:\\.\\d+)?"), "")
                .replace("ایڈ", "", true).replace("ڈالو", "", true).replace("لاؤ", "", true).replace("کرو", "", true)
                .replace("add", "", true).replace("karo", "", true).replace("kar", "", true)
                .replace("کارٹن", "", true).replace("کاٹن", "", true).replace("carton", "", true).replace("ctn", "", true)
                .replace("بوری", "", true).replace("bag", "", true)
                .replace("آؤٹر", "", true).replace("outer", "", true)
                .replace("لڑی", "", true).replace("lari", "", true)
                .replace("ڈبی", "", true).replace("dabbi", "", true)
                .replace("کلو", "", true).replace("kg", "", true)
                .replace("عدد", "", true).replace("pcs", "", true)
                .replace("دو", "", true).replace("ایک", "", true).replace("تین", "", true)
                .trim()

            if (productName.length < 2) {
                Toast.makeText(this@PurchaseActivity, "❓ پروڈکٹ نام نہیں ملا: $command", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // URDU + ENGLISH fuzzy search
            val matched = allProductsCache.filter { 
                it.name.contains(productName, true) || 
                transliterateUrdu(it.name).contains(productName, true) ||
                productName.contains(it.name, true)
            }
            val product = matched.firstOrNull() ?: allProductsCache.firstOrNull { it.name.contains(productName.split(" ").first(), true) }

            if (product == null) {
                AlertDialog.Builder(this@PurchaseActivity)
                    .setTitle("❓ آئٹم نہیں ملا: $productName")
                    .setMessage("کیا نیا آئٹم بنانا ہے؟ '$productName' - $qty $unit\n\nبولو 'ہاں' یا 'نہیں'")
                    .setPositiveButton("✅ ہاں بناؤ") { _, _ -> showSaveNewItemUrdu(productName, unit, qty) }
                    .setNegativeButton("❌ نہیں", null)
                    .show()
                return@launch
            }

            // 3-tier: Ctn 50 Outer 10 Dabbi, Shampoo 48 Lari 16 Pcs
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
            Toast.makeText(this@PurchaseActivity, "✅ ${product.name} - $qty $unit = Rs %.2f".format(total), Toast.LENGTH_SHORT).show()
        }
    }

    private fun transliterateUrdu(text: String): String {
        // Simple Urdu to Roman for matching
        return text.lowercase()
            .replace("چینی", "cheeni sugar")
            .replace("شیمپو", "shampoo")
            .replace("صابن", "sabon soap")
            .replace("تیل", "tail oil")
    }

    private fun showSaveNewItemUrdu(name: String, unit: String, qty: Double) {
        lifecycleScope.launch {
            val product = Product(barcode = "P${System.currentTimeMillis()}", name = name.trim().replaceFirstChar { it.uppercase() }, unit = unit, tertiaryUnit = unit, stock = 0, cost = 0.0, salePrice = 0.0)
            PosDatabase.get(this@PurchaseActivity).productDao().upsert(product)
            allProductsCache = allProductsCache + product
            billItems.add(BillItem(product, qty, unit, 1.0, 0.0, 0.0, qty))
            refreshItemsUI()
            Toast.makeText(this@PurchaseActivity, "✅ نیا آئٹم: $name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findAndSetSupplier(name: String) {
        lifecycleScope.launch {
            val suppliers = PosDatabase.get(this@PurchaseActivity).supplierDao().all().first()
            val matched = suppliers.filter { it.name.contains(name, true) || transliterateUrdu(it.name).contains(name, true) }.firstOrNull()
            if (matched != null) {
                selectedSupplier = matched
                partyNameField.text = "✅ " + matched.name + " (آواز)"
                partyNameField.setTextColor(Color.parseColor(textDark))
                partyNameField.setTypeface(partyNameField.typeface, Typeface.BOLD)
                val closing = matched.openingBalance + matched.balance
                partyBalanceText.text = "Rs %.2f".format(closing)
                Toast.makeText(this@PurchaseActivity, "✅ سپلائر: ${matched.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@PurchaseActivity, "❓ سپلائر نہیں ملا: $name", Toast.LENGTH_SHORT).show()
                openSupplierPicker()
            }
        }
    }

    private fun openSupplierPicker() {
        lifecycleScope.launch {
            val suppliers = PosDatabase.get(this@PurchaseActivity).supplierDao().all().first()
            if (suppliers.isEmpty()) {
                Toast.makeText(this@PurchaseActivity, "👥 کوئی سپلائر نہیں", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = suppliers.map { "🏭 " + it.name }.toTypedArray()
            AlertDialog.Builder(this@PurchaseActivity)
                .setTitle("👥 سپلائر منتخب کریں - بولو نام")
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
        var allProducts: List<Product> = emptyList()
        lifecycleScope.launch {
            allProducts = PosDatabase.get(this@PurchaseActivity).productDao().all().first()
            val dialogLayout = LinearLayout(this@PurchaseActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(20,16,20,16); setBackgroundColor(Color.WHITE) }
            dialogLayout.addView(TextView(this@PurchaseActivity).apply { text = "📦 آئٹم ایڈ کریں - اردو وائس یا ٹائپ"; textSize = 15f; setTextColor(Color.parseColor(darkBlue)); setTypeface(typeface, Typeface.BOLD); setPadding(0,0,0,12) })
            val itemNameBox = LinearLayout(this@PurchaseActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(14,6,8,6); background = GradientDrawable().apply { setColor(Color.parseColor("#F9FAFB")); cornerRadius = 12f; setStroke(1, Color.parseColor(border)) } }
            val itemNameField = EditText(this@PurchaseActivity).apply { hint = "🔍 تلاش کریں... چینی، شیمپو"; background = null; textSize = 14f; layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
            itemNameBox.addView(itemNameField)
            dialogLayout.addView(itemNameBox)
            val qtyField = EditText(this@PurchaseActivity).apply { hint = "مقدار Qty"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
            dialogLayout.addView(qtyField)
            val rateField = EditText(this@PurchaseActivity).apply { hint = "ریٹ Rate"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
            dialogLayout.addView(rateField)
            val suggestionsBox = LinearLayout(this@PurchaseActivity).apply { orientation = LinearLayout.VERTICAL }
            dialogLayout.addView(suggestionsBox)
            val dialog = AlertDialog.Builder(this@PurchaseActivity).setView(dialogLayout).setPositiveButton("بل میں ڈالیں") { _, _ ->
                val qty = qtyField.text.toString().toDoubleOrNull() ?: 1.0
                val name = itemNameField.text.toString()
                val product = allProducts.filter { it.name.contains(name, true) }.firstOrNull()
                if (product != null) {
                    val rate = rateField.text.toString().toDoubleOrNull() ?: product.cost
                    billItems.add(BillItem(product, qty, product.unit, 1.0, rate, qty*rate, qty))
                    refreshItemsUI()
                }
            }.setNegativeButton("منسوخ", null).create()
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
        totalQtyText.text = "📦 کل مقدار:%.1f".format(totalQty)
        subtotalText.text = "💵 ٹوٹل: %.2f".format(subtotal)
        updateDue()
    }

    private fun updateDue() {
        val subtotal = billItems.sumOf { it.totalAmount }
        val paid = paidAmountField.text.toString().toDoubleOrNull() ?: 0.0
        dueAmountText.text = "⏳ Rs %.2f".format(subtotal - paid)
    }

    private fun savePurchase() {
        if (selectedSupplier == null) { Toast.makeText(this, "👥 سپلائر منتخب کریں - بولو 'سپلائر احمد'", Toast.LENGTH_SHORT).show(); return }
        if (billItems.isEmpty()) { Toast.makeText(this, "📦 آئٹم ایڈ کریں - بولو 'چینی دو کارٹن'", Toast.LENGTH_SHORT).show(); return }
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
            Toast.makeText(this@PurchaseActivity, "✅ خریداری محفوظ: $billNo", Toast.LENGTH_LONG).show()
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
            saveButton.text = "✏️ اپڈیٹ کریں"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceUrdu()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            noiseSuppressor?.release()
            echoCanceler?.release()
            gainControl?.release()
            speechRecognizer?.destroy()
        } catch (e: Exception) {}
    }
}
