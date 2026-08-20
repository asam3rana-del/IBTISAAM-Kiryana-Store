
package com.grocerypos.v11.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private lateinit var voiceStatusBar: LinearLayout
    private lateinit var voiceStatusText: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        editingBillNo = intent.getStringExtra(EXTRA_BILL_NO)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        // HEADER WITH SAFE VOICE BUTTON
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 24, 18, 22)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(tealStart), Color.parseColor(tealEnd))).apply { cornerRadii = floatArrayOf(0f,0f,0f,0f,28f,28f,0f,0f) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 8f; outlineProvider = ViewOutlineProvider.BACKGROUND }
        }
        val headerLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        headerLeft.addView(TextView(this).apply { text = "🛒 خریداری - Purchase"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        headerLeft.addView(TextView(this).apply { text = "🎙️ اردو وائس"; textSize = 11f; setTextColor(Color.parseColor("#C6FFF7")) })
        header.addView(headerLeft)

        voiceButton = TextView(this).apply {
            text = "🎙️"; textSize = 22f; gravity = Gravity.CENTER
            setPadding(16,16,16,16)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { elevation = 6f; outlineProvider = ViewOutlineProvider.BACKGROUND }
            // SAFE CLICK - NO CRASH
            setOnClickListener {
                try {
                    safeVoiceClick()
                } catch (e: Exception) {
                    Toast.makeText(this@PurchaseActivity, "❌ Voice error: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        header.addView(voiceButton)
        outer.addView(header)

        // VOICE STATUS BAR - HIDDEN BY DEFAULT
        voiceStatusBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14,10,14,10)
            setBackgroundColor(Color.parseColor("#FEF2F2"))
            visibility = View.GONE
        }
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusRow.addView(TextView(this).apply { text = "🔴"; textSize = 12f; setPadding(0,0,8,0) })
        voiceStatusText = TextView(this).apply { text = "سن رہا ہوں... بولو"; textSize = 13f; setTextColor(Color.parseColor(voiceRed)); setTypeface(typeface, Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        statusRow.addView(voiceStatusText)
        statusRow.addView(TextView(this).apply { text = "❌"; setPadding(10,4,10,4); setOnClickListener { hideVoiceBar() } })
        voiceStatusBar.addView(statusRow)
        voiceStatusBar.addView(TextView(this).apply { text = "💡 بولو: 'چینی دو کارٹن', 'سپلائر احمد', 'جمع 5000', 'محفوظ کرو'"; textSize = 10.5f; setTextColor(Color.parseColor(textGray)); setPadding(0,6,0,0) })
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

        val partyCard = premiumCard()
        partyCard.addView(TextView(this).apply { text = "👤 پارٹی / سپلائر"; textSize = 10.5f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); setPadding(0,0,0,10) })
        val partyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        partyNameField = TextView(this).apply {
            text = "🏭 پارٹی نام * - بولو 'سپلائر احمد'"; textSize = 13f; setTextColor(Color.parseColor("#9AA6B8")); layoutParams = LinearLayout.LayoutParams(0,-2,1f)
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
            setOnClickListener { openSupplierPicker() }
        })
        partyCard.addView(partyRow)
        root.addView(partyCard)
        root.addView(spacer(12))

        val addItemsCard = premiumCard().apply { setPadding(16,14,16,14) }
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addRow.addView(LinearLayout(this@PurchaseActivity).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-2,1f)
            addView(TextView(this@PurchaseActivity).apply { text = "🎙️ اردو وائس ایڈ"; textSize = 14f; setTextColor(Color.parseColor(textDark)); setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(this@PurchaseActivity).apply { text = "بولو 'چینی دو کارٹن'"; textSize = 11f; setTextColor(Color.parseColor(textGray)) })
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
            setOnClickListener {
                try { safeVoiceClick() } catch (e: Exception) { Toast.makeText(this@PurchaseActivity, "Voice error", Toast.LENGTH_SHORT).show() }
            }
        })
        addItemsCard.addView(addRow)
        addItemsCard.addView(TextView(this).apply { text = "💡 اردو: 'چینی دو کارٹن', 'سپلائر علی', 'جمع 5000', 'محفوظ کرو'"; textSize = 10f; setTextColor(Color.parseColor(textGray)); setPadding(4,8,0,0) })
        root.addView(addItemsCard)
        root.addView(spacer(10))

        itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(itemsContainer)
        root.addView(spacer(10))

        val totalsCard = premiumCard().apply { setPadding(16,12,16,12) }
        totalQtyText = TextView(this).apply { text = "📦 کل مقدار:0.0"; textSize = 11.5f; setTextColor(Color.parseColor(textGray)); layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        subtotalText = TextView(this).apply { text = "💵 ٹوٹل: 0.00"; textSize = 11.5f; setTextColor(Color.parseColor(textGray)); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
        totalsCard.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(totalQtyText); addView(subtotalText) })
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
            try {
                allProductsCache = PosDatabase.get(this@PurchaseActivity).productDao().all().first()
            } catch (e: Exception) {}
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

    // ===== FIXED VOICE - NO CRASH =====
    private fun safeVoiceClick() {
        // 1. Check permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "🎙️ مائک اجازت چاہیے", Toast.LENGTH_SHORT).show()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
            return
        }

        // 2. Check if Google Voice is available
        if (!isGoogleVoiceAvailable()) {
            AlertDialog.Builder(this)
                .setTitle("🎙️ Google Voice نہیں ملا")
                .setMessage("Google app install کریں یا manual add استعمال کریں")
                .setPositiveButton("Manual Add") { _, _ -> showAddItemDialog() }
                .setNegativeButton("OK", null)
                .show()
            return
        }

        // 3. Start voice safely
        startVoiceUrduSafe()
    }

    private fun isGoogleVoiceAvailable(): Boolean {
        return try {
            val pm = packageManager
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            val activities = pm.queryIntentActivities(intent, 0)
            activities.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun startVoiceUrduSafe() {
        try {
            showVoiceBar()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK")
                putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayOf("ur-PK", "en-US"))
                putExtra(RecognizerIntent.EXTRA_PROMPT, "بولو: چینی دو کارٹن")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            }
            startActivityForResult(intent, VOICE_REQUEST_CODE)
        } catch (e: ActivityNotFoundException) {
            hideVoiceBar()
            Toast.makeText(this, "❌ Voice service نہیں ملا - Google app install کریں", Toast.LENGTH_LONG).show()
            // Fallback to manual
            showAddItemDialog()
        } catch (e: Exception) {
            hideVoiceBar()
            Toast.makeText(this, "❌ Voice error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showVoiceBar() {
        voiceStatusBar.visibility = View.VISIBLE
        voiceStatusText.text = "🔴 سن رہا ہوں... بولو - 'چینی دو کارٹن'"
        voiceButton.text = "🔴"
        voiceButton.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(voiceRed)) }
    }

    private fun hideVoiceBar() {
        voiceStatusBar.visibility = View.GONE
        voiceButton.text = "🎙️"
        voiceButton.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            hideVoiceBar()
            if (requestCode == VOICE_REQUEST_CODE) {
                if (resultCode == RESULT_OK && data != null) {
                    val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    if (!results.isNullOrEmpty()) {
                        val spoken = results[0]
                        if (spoken.isNotBlank()) {
                            voiceStatusText.text = "سنا: $spoken"
                            // Show briefly then hide
                            voiceStatusBar.visibility = View.VISIBLE
                            voiceStatusBar.postDelayed({ hideVoiceBar() }, 2000)
                            processVoiceUrdu(spoken)
                        }
                    }
                } else {
                    // User cancelled or no speech
                    Toast.makeText(this, "🎙️ کوئی آواز نہیں سنی", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            hideVoiceBar()
            Toast.makeText(this, "❌ Voice result error", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            if (requestCode == PERMISSION_REQUEST_CODE) {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "✅ مائک اجازت مل گئی - اب 🎙️ دباؤ", Toast.LENGTH_SHORT).show()
                    startVoiceUrduSafe()
                } else {
                    Toast.makeText(this, "❌ مائک اجازت ضروری ہے", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {}
    }

    private fun processVoiceUrdu(command: String) {
        try {
            val cmd = command.lowercase(Locale.getDefault()).trim()
            Toast.makeText(this, "🎙️ $command", Toast.LENGTH_SHORT).show()

            when {
                cmd.contains("محفوظ") || cmd.contains("سیو") || (cmd.contains("save") && cmd.length < 20) -> savePurchase()
                cmd.contains("سپلائر") || cmd.contains("سپلائیر") || cmd.contains("پارٹی") || cmd.contains("supplier") || cmd.contains("party") -> {
                    val name = cmd.replace("سپلائر", "").replace("سپلائیر", "").replace("پارٹی", "").replace("supplier", "").replace("party", "").replace("select", "").trim()
                    if (name.length > 2) findAndSetSupplier(name) else openSupplierPicker()
                }
                cmd.contains("جمع") || cmd.contains("ادا") || cmd.contains("paid") -> {
                    val amount = extractNumberUrdu(cmd)
                    if (amount > 0) {
                        paidAmountField.setText(amount.toInt().toString())
                        Toast.makeText(this, "✅ جمع: Rs $amount", Toast.LENGTH_SHORT).show()
                    }
                }
                cmd.contains("ٹوٹل") || cmd.contains("کل") || cmd.contains("کتنا") || cmd.contains("total") -> {
                    val subtotal = billItems.sumOf { it.totalAmount }
                    Toast.makeText(this, "💵 ٹوٹل: Rs %.2f".format(subtotal), Toast.LENGTH_LONG).show()
                }
                cmd.contains("نیا") || cmd.contains("صاف") || cmd.contains("clear") || cmd.contains("new") -> {
                    billItems.clear()
                    refreshItemsUI()
                    Toast.makeText(this, "🗑️ نیا بل", Toast.LENGTH_SHORT).show()
                }
                else -> parseAndAddItemUrdu(cmd)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Command error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractNumberUrdu(text: String): Double {
        try {
            var normalized = text
                .replace("۰", "0").replace("۱", "1").replace("۲", "2").replace("۳", "3").replace("۴", "4")
                .replace("۵", "5").replace("۶", "6").replace("۷", "7").replace("۸", "8").replace("۹", "9")
            val urduNumbers = mapOf("ایک" to 1, "دو" to 2, "تین" to 3, "چار" to 4, "پانچ" to 5, "چھ" to 6, "سات" to 7, "آٹھ" to 8, "نو" to 9, "دس" to 10, "بیس" to 20, "تیس" to 30, "سو" to 100, "ہزار" to 1000)
            for ((word, num) in urduNumbers) {
                if (normalized.contains(word)) return num.toDouble()
            }
            val regex = Regex("(\\d+(?:\\.\\d+)?)")
            return regex.find(normalized)?.value?.toDoubleOrNull() ?: 0.0
        } catch (e: Exception) { return 0.0 }
    }

    private fun parseAndAddItemUrdu(command: String) {
        lifecycleScope.launch {
            try {
                if (allProductsCache.isEmpty()) {
                    allProductsCache = PosDatabase.get(this@PurchaseActivity).productDao().all().first()
                }
                var qty = extractNumberUrdu(command)
                if (qty == 0.0) qty = 1.0

                var unit = "pcs"
                when {
                    command.contains("کارٹن") || command.contains("کاٹن") || command.contains("carton") || command.contains("ctn") -> unit = "ctn"
                    command.contains("بوری") || command.contains("bag") -> unit = "bag"
                    command.contains("آؤٹر") || command.contains("outer") -> unit = "outer"
                    command.contains("لڑی") || command.contains("lari") -> unit = "lari"
                    command.contains("ڈبی") || command.contains("dabbi") -> unit = "dabbi"
                    command.contains("کلو") || command.contains("kg") -> unit = "kg"
                }

                var productName = command
                    .replace(Regex("\\d+(?:\\.\\d+)?"), "")
                    .replace("ایڈ", "", true).replace("ڈالو", "", true).replace("لاؤ", "", true).replace("کرو", "", true)
                    .replace("add", "", true).replace("karo", "", true)
                    .replace("کارٹن", "", true).replace("کاٹن", "", true).replace("carton", "", true).replace("ctn", "", true)
                    .replace("بوری", "", true).replace("bag", "", true)
                    .replace("ڈبی", "", true).replace("کلو", "", true).replace("pcs", "", true)
                    .replace("دو", "", true).replace("ایک", "", true).replace("تین", "", true)
                    .trim()

                if (productName.length < 2) {
                    Toast.makeText(this@PurchaseActivity, "❓ پروڈکٹ: $command", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val product = allProductsCache.filter { it.name.contains(productName, true) }.firstOrNull()
                    ?: allProductsCache.firstOrNull { it.name.contains(productName.split(" ").first(), true) }

                if (product == null) {
                    AlertDialog.Builder(this@PurchaseActivity)
                        .setTitle("❓ نہیں ملا: $productName")
                        .setMessage("نیا بنانا ہے؟ '$productName' - $qty $unit")
                        .setPositiveButton("✅ بناؤ") { _, _ ->
                            lifecycleScope.launch {
                                val newProduct = Product(barcode = "P${System.currentTimeMillis()}", name = productName.replaceFirstChar { it.uppercase() }, unit = unit, tertiaryUnit = unit, stock = 0, cost = 0.0, salePrice = 0.0)
                                PosDatabase.get(this@PurchaseActivity).productDao().upsert(newProduct)
                                allProductsCache = allProductsCache + newProduct
                                billItems.add(BillItem(newProduct, qty, unit, 1.0, 0.0, 0.0, qty))
                                refreshItemsUI()
                            }
                        }
                        .setNegativeButton("❌ نہیں", null)
                        .show()
                    return@launch
                }

                val factor = when (unit) {
                    product.unit -> if (product.secondaryUnitQty > 0 && product.tertiaryUnitQty > 0) product.secondaryUnitQty * product.tertiaryUnitQty else if (product.secondaryUnitQty > 0) product.secondaryUnitQty else 1.0
                    product.secondaryUnit -> if (product.tertiaryUnitQty > 0) product.tertiaryUnitQty else 1.0
                    else -> 1.0
                }
                val smallestQty = qty * factor
                val total = qty * product.cost

                billItems.add(BillItem(product, qty, unit, factor, product.cost, total, smallestQty))
                refreshItemsUI()
                Toast.makeText(this@PurchaseActivity, "✅ ${product.name} - $qty $unit", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@PurchaseActivity, "❌ Add error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun findAndSetSupplier(name: String) {
        lifecycleScope.launch {
            try {
                val suppliers = PosDatabase.get(this@PurchaseActivity).supplierDao().all().first()
                val matched = suppliers.filter { it.name.contains(name, true) }.firstOrNull()
                if (matched != null) {
                    selectedSupplier = matched
                    partyNameField.text = "✅ " + matched.name + " (آواز)"
                    partyNameField.setTextColor(Color.parseColor(textDark))
                    partyNameField.setTypeface(partyNameField.typeface, Typeface.BOLD)
                    partyBalanceText.text = "Rs %.2f".format(matched.openingBalance + matched.balance)
                } else {
                    Toast.makeText(this@PurchaseActivity, "❓ سپلائر نہیں: $name", Toast.LENGTH_SHORT).show()
                    openSupplierPicker()
                }
            } catch (e: Exception) {}
        }
    }

    private fun openSupplierPicker() {
        lifecycleScope.launch {
            try {
                val suppliers = PosDatabase.get(this@PurchaseActivity).supplierDao().all().first()
                if (suppliers.isEmpty()) {
                    Toast.makeText(this@PurchaseActivity, "👥 کوئی سپلائر نہیں", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val names = suppliers.map { "🏭 " + it.name }.toTypedArray()
                AlertDialog.Builder(this@PurchaseActivity)
                    .setTitle("👥 سپلائر - بولو نام")
                    .setItems(names) { _, which ->
                        selectedSupplier = suppliers[which]
                        partyNameField.text = "✅ " + selectedSupplier!!.name
                        partyNameField.setTextColor(Color.parseColor(textDark))
                        partyNameField.setTypeface(partyNameField.typeface, Typeface.BOLD)
                        partyBalanceText.text = "Rs %.2f".format(selectedSupplier!!.openingBalance + selectedSupplier!!.balance)
                    }.show()
            } catch (e: Exception) {}
        }
    }

    private fun showAddItemDialog() {
        var allProducts: List<Product> = emptyList()
        lifecycleScope.launch {
            try {
                allProducts = PosDatabase.get(this@PurchaseActivity).productDao().all().first()
                val dialogLayout = LinearLayout(this@PurchaseActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(20,16,20,16); setBackgroundColor(Color.WHITE) }
                dialogLayout.addView(TextView(this@PurchaseActivity).apply { text = "📦 آئٹم ایڈ"; textSize = 15f; setTextColor(Color.parseColor(darkBlue)); setTypeface(typeface, Typeface.BOLD); setPadding(0,0,0,12) })
                val itemNameField = EditText(this@PurchaseActivity).apply { hint = "🔍 چینی، شیمپو"; textSize = 14f }
                dialogLayout.addView(itemNameField)
                val qtyField = EditText(this@PurchaseActivity).apply { hint = "مقدار"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
                dialogLayout.addView(qtyField)
                val rateField = EditText(this@PurchaseActivity).apply { hint = "ریٹ"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
                dialogLayout.addView(rateField)
                val suggestionsBox = LinearLayout(this@PurchaseActivity).apply { orientation = LinearLayout.VERTICAL }
                dialogLayout.addView(suggestionsBox)
                val dialog = AlertDialog.Builder(this@PurchaseActivity).setView(dialogLayout).setPositiveButton("بل میں ڈالیں") { _, _ ->
                    try {
                        val qty = qtyField.text.toString().toDoubleOrNull() ?: 1.0
                        val name = itemNameField.text.toString()
                        val product = allProducts.filter { it.name.contains(name, true) }.firstOrNull()
                        if (product != null) {
                            val rate = rateField.text.toString().toDoubleOrNull() ?: product.cost
                            billItems.add(BillItem(product, qty, product.unit, 1.0, rate, qty*rate, qty))
                            refreshItemsUI()
                        }
                    } catch (e: Exception) {}
                }.setNegativeButton("منسوخ", null).create()
                dialog.show()
                itemNameField.addTextChangedListener(object: TextWatcher{
                    override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                    override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                    override fun afterTextChanged(s:Editable?){
                        try {
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
                        } catch (e: Exception) {}
                    }
                })
            } catch (e: Exception) {}
        }
    }

    private fun refreshItemsUI() {
        try {
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
        } catch (e: Exception) {}
    }

    private fun updateDue() {
        try {
            val subtotal = billItems.sumOf { it.totalAmount }
            val paid = paidAmountField.text.toString().toDoubleOrNull() ?: 0.0
            dueAmountText.text = "⏳ Rs %.2f".format(subtotal - paid)
        } catch (e: Exception) {}
    }

    private fun savePurchase() {
        try {
            if (selectedSupplier == null) { Toast.makeText(this, "👥 سپلائر منتخب کریں", Toast.LENGTH_SHORT).show(); return }
            if (billItems.isEmpty()) { Toast.makeText(this, "📦 آئٹم ایڈ کریں", Toast.LENGTH_SHORT).show(); return }
            val subtotal = billItems.sumOf { it.totalAmount }
            val paid = paidAmountField.text.toString().toDoubleOrNull() ?: 0.0
            val billNo = editingBillNo ?: "PUR-${System.currentTimeMillis()}"
            lifecycleScope.launch {
                try {
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
                } catch (e: Exception) {
                    Toast.makeText(this@PurchaseActivity, "❌ Save error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Save error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadBillForEdit(billNo: String) {
        lifecycleScope.launch {
            try {
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
            } catch (e: Exception) {}
        }
    }
}
