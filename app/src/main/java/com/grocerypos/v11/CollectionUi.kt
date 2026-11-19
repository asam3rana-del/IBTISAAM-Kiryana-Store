package com.grocerypos.v11.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import com.grocerypos.v11.DayUtil
import com.grocerypos.v11.Dues
import com.grocerypos.v11.util.Loc
import com.grocerypos.v11.util.ThemeManager
import java.util.Calendar

// NEW (Flutter parity, batch 1): small view helpers shared ONLY by AmanatLoanActivity and
// CollectionActivity. Prefixed "cu" so they never clash with the app-wide helpers in
// ui/components (strokedBg / iconBadge / tintedDrawable ...).

internal class CuColors(ctx: Context) {
    private val p = ThemeManager.palette(ctx)
    val bg = p.bg
    val card = p.cardWhite
    val text = p.textDark
    val muted = p.textMuted
    val border = p.border
    val red = p.red
    val navy = p.navy
    val teal = p.flatTealFg
    val tealBg = p.flatTealBg
    val amber = p.flatAmberFg
    val amberBg = p.flatAmberBg
    val purple = p.flatPurpleFg
    val purpleBg = p.flatPurpleBg
    val field = p.fieldFill
    val whatsapp = "#25D366"
    val whatsappBg = "#DDF6E8"
}

internal fun Context.cuDp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

internal fun Context.cuCardBg(c: CuColors, radius: Int = 18): GradientDrawable = GradientDrawable().apply {
    setColor(Color.parseColor(c.card))
    setStroke(cuDp(1), Color.parseColor(c.border))
    cornerRadius = cuDp(radius).toFloat()
}

internal fun Context.cuFill(hex: String, radius: Int = 14): GradientDrawable = GradientDrawable().apply {
    setColor(Color.parseColor(hex))
    cornerRadius = cuDp(radius).toFloat()
}

internal fun Context.cuCard(c: CuColors): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = cuCardBg(c)
    setPadding(cuDp(14), cuDp(12), cuDp(14), cuDp(12))
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { setMargins(0, 0, 0, cuDp(10)) }
}

internal fun Context.cuText(s: String, sizeSp: Float, colorHex: String, bold: Boolean = false): TextView = TextView(this).apply {
    text = s
    textSize = sizeSp
    setTextColor(Color.parseColor(colorHex))
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

internal fun Context.cuGap(dp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(cuDp(dp), cuDp(dp))
}

/** Round icon button (WhatsApp / call / check ...). */
internal fun Context.cuRound(iconRes: Int, fgHex: String, bgHex: String, onClick: () -> Unit): ImageView =
    ImageView(this).apply {
        val d = ContextCompat.getDrawable(this@cuRound, iconRes)?.mutate()
        d?.setTint(Color.parseColor(fgHex))
        setImageDrawable(d)
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(bgHex)) }
        val px = cuDp(38)
        layoutParams = LinearLayout.LayoutParams(px, px).apply { setMargins(cuDp(6), 0, 0, 0) }
        setOnClickListener { onClick() }
    }

/** Full-width filled button. */
internal fun Context.cuButton(label: String, bgHex: String, fgHex: String = "#FFFFFF", onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        textSize = 14.5f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor(fgHex))
        setTypeface(typeface, Typeface.BOLD)
        background = cuFill(bgHex, 14)
        setPadding(cuDp(12), cuDp(14), cuDp(12), cuDp(14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, 0, 0, cuDp(10)) }
        setOnClickListener { onClick() }
    }

/** Small chip / pill. */
internal fun Context.cuChip(label: String, selected: Boolean, c: CuColors, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(if (selected) Color.WHITE else Color.parseColor(c.muted))
        background = if (selected) cuFill(c.purple, 16) else cuCardBg(c, 16)
        setPadding(cuDp(12), cuDp(8), cuDp(12), cuDp(8))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, 0, cuDp(8), cuDp(8)) }
        setOnClickListener { onClick() }
    }

/** Horizontally scrolling chip row. */
internal fun Context.cuChipRow(): Pair<HorizontalScrollView, LinearLayout> {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val scroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    return Pair(scroll, row)
}

internal fun Context.cuField(hint: String, c: CuColors, numeric: Boolean = false, initial: String = ""): EditText =
    EditText(this).apply {
        this.hint = hint
        setText(initial)
        textSize = 14.5f
        setTextColor(Color.parseColor(c.text))
        setHintTextColor(Color.parseColor(c.muted))
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
        background = cuFill(c.field, 12)
        setPadding(cuDp(12), cuDp(10), cuDp(12), cuDp(10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, 0, 0, cuDp(10)) }
    }

internal fun Context.cuDialogBody(): Pair<ScrollView, LinearLayout> {
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(cuDp(20), cuDp(8), cuDp(20), cuDp(4))
    }
    return Pair(ScrollView(this).apply { addView(body) }, body)
}

/** Date picker (calendar mode, same as the rest of the app). [onPick] gets local-midnight millis. */
internal fun Activity.cuPickDate(initial: Long, onPick: (Long) -> Unit) {
    val cal = Calendar.getInstance()
    if (initial > 0L) cal.timeInMillis = initial
    DatePickerDialog(this, { _, y, m, d ->
        val picked = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        onPick(picked)
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
        datePicker.calendarViewShown = true
        datePicker.spinnersShown = false
    }.show()
}

/** Date "button" that shows the picked date and re-opens the picker. value 0 = not set. */
internal class CuDateField(private val act: Activity, private val c: CuColors, private val label: String, initial: Long, private val clearable: Boolean = true) {
    var value: Long = initial
        private set
    val view: TextView = act.cuText("", 14f, c.text).apply {
        background = act.cuFill(c.field, 12)
        setPadding(act.cuDp(12), act.cuDp(12), act.cuDp(12), act.cuDp(12))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, 0, 0, act.cuDp(10)) }
        setOnClickListener { act.cuPickDate(this@CuDateField.value) { update(it) } }
        setOnLongClickListener { if (clearable) update(0L); true }
    }
    init { refresh() }
    fun update(v: Long) { value = v; refresh() }
    private fun refresh() {
        view.text = if (value > 0L) "$label: ${DayUtil.fmt(value)}" else "$label: " + Loc.t(act, "tap to pick", "تاریخ چنیں")
    }
}

internal fun Activity.cuToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

/** wa.me link. false = WhatsApp nahi khula. */
internal fun Activity.cuOpenWhatsApp(phone: String, message: String): Boolean {
    val digits = Dues.whatsAppDigits(phone)
    return try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")))
        true
    } catch (e: Exception) {
        cuToast(Loc.t(this, "Could not open WhatsApp", "واٹس ایپ نہیں کھل سکا"))
        false
    }
}

internal fun Activity.cuDial(phone: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.trim()}")))
    true
} catch (e: Exception) {
    cuToast(Loc.t(this, "Could not open the dialer", "ڈائلر نہیں کھل سکا"))
    false
}

internal fun cuParseAmount(s: String): Double? = s.trim().replace(",", "").toDoubleOrNull()?.takeIf { it > 0 && !it.isInfinite() }
