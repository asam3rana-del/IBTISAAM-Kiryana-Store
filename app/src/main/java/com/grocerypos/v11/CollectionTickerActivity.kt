package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.AutopilotDay
import com.grocerypos.v11.AutopilotItem
import com.grocerypos.v11.CollectionRepository
import com.grocerypos.v11.TouchReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NEW (Flutter parity, batch 6): Collection Ticker — port of Flutter's collection_ticker_screen.dart.
 * LCD / LED board: today's to-do parties run across the bottom in one line, one party at a time
 * is shown big in the middle (every 5 s). Refreshes itself every minute (a party reminded today
 * drops off, a payment lowers the amount). Keep the shop tablet standing; screen stays on.
 */
class CollectionTickerActivity : AppCompatActivity() {

    companion object {
        const val AMBER = "#FFB300"
        const val GREEN = "#39FF14"
        const val RED = "#FF5252"
        const val DIM = "#8AFFFFFF"

        fun reasonLabel(it: AutopilotItem): String = when (it.reason) {
            TouchReason.BROKEN_PROMISE -> "WADA TOOTA"
            TouchReason.PROMISE_TODAY -> "AAJ KA WADA"
            TouchReason.QIST_DUE -> "QIST DUE"
            TouchReason.PAY_DAY -> "AAJ PAYMENT DIN"
            TouchReason.FOLLOW_UP_DUE -> "FOLLOW-UP AAJ"
            TouchReason.LATE -> if (it.oldestDays > 0) "LATE ${it.oldestDays} DIN" else "LATE"
            TouchReason.DUE_TODAY -> "AAJ DUE"
        }

        fun money(v: Double): String = String.format(Locale.US, "%,d", Math.round(v))

        fun tickerText(todo: List<AutopilotItem>): String =
            if (todo.isEmpty()) "AAJ KI WASOOLI MUKAMMAL"
            else todo.joinToString("     ◆     ") { "${it.party.partyName.uppercase()}  Rs ${money(it.total)}" }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var day: AutopilotDay? = null
    private var spot = 0
    private var lastTicker = ""

    private lateinit var clock: TextView
    private lateinit var stats: TextView
    private lateinit var reason: TextView
    private lateinit var name: TextView
    private lateinit var amount: TextView
    private lateinit var counter: TextView
    private lateinit var marquee: TextView

    private val clockFmt = SimpleDateFormat("hh:mm:ss a", Locale.ENGLISH)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun led(size: Float, hex: String, bold: Boolean = true) = TextView(this).apply {
        textSize = size
        setTextColor(Color.parseColor(hex))
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        letterSpacing = 0.08f
        setShadowLayer(14f, 0f, 0f, Color.parseColor(hex))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(led(20f, AMBER).apply { text = "AAJ KI WASOOLI"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        clock = led(18f, GREEN)
        top.addView(clock)
        top.addView(TextView(this).apply {
            text = "  ✕"; textSize = 22f; setTextColor(Color.parseColor(DIM)); setPadding(dp(12), 0, dp(4), 0)
            setOnClickListener { finish() }
        })
        root.addView(top)
        stats = led(15f, AMBER).apply { setPadding(0, dp(4), 0, 0) }
        root.addView(stats)

        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        reason = led(18f, RED).apply { gravity = Gravity.CENTER }
        name = led(54f, AMBER).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        amount = led(48f, GREEN).apply { gravity = Gravity.CENTER }
        counter = led(14f, DIM, bold = false).apply { gravity = Gravity.CENTER }
        for (v in listOf(reason, name, amount, counter)) { centre.addView(v); (v.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) }
        root.addView(centre)

        val bar = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setStroke(dp(2), Color.parseColor("#99FFB300")); setColor(Color.BLACK)
            }
            setPadding(dp(8), 0, dp(8), 0)
        }
        marquee = led(28f, AMBER).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isHorizontalFadingEdgeEnabled = false
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isSelected = true   // marquee only runs while selected
        }
        bar.addView(marquee)
        root.addView(bar)
        setContentView(root)
        reason.text = ""; name.text = "…"; amount.text = ""; counter.text = ""
    }

    private val tickClock = object : Runnable {
        override fun run() { clock.text = clockFmt.format(Date()); handler.postDelayed(this, 1000) }
    }
    private val tickSpot = object : Runnable {
        override fun run() { spot++; renderCentre(); handler.postDelayed(this, 5000) }
    }
    private val tickLoad = object : Runnable {
        override fun run() { load(); handler.postDelayed(this, 60_000) }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tickClock); handler.postDelayed(tickSpot, 5000); handler.post(tickLoad)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun load(): Job = lifecycleScope.launch {
        try { day = CollectionRepository.autopilotToday(this@CollectionTickerActivity) } catch (e: Exception) { }
        render()
    }

    private fun render() {
        val d = day ?: return
        val q = d.queue
        stats.text = "BAQI ${q.todo.size}  Rs ${money(q.todoAmount)}     HO CHUKA ${q.done.size}     AAJ WASOOL Rs ${money(d.collectedToday)}"
        val t = tickerText(q.todo)
        // Reset the marquee only when the text really changed (else it jumps back every minute).
        if (t != lastTicker) {
            lastTicker = t
            // Pad so a short line still scrolls (marquee needs text wider than the bar).
            marquee.text = t + "          ◆          " + t
            marquee.isSelected = true
        }
        renderCentre()
    }

    private fun renderCentre() {
        val d = day ?: return
        val todo = d.queue.todo
        if (todo.isEmpty()) {
            reason.text = ""; name.text = "SAB KAAM HO GAYA"; name.setTextColor(Color.parseColor(GREEN))
            name.setShadowLayer(14f, 0f, 0f, Color.parseColor(GREEN)); amount.text = ""; counter.text = ""
            return
        }
        val i = spot % todo.size
        val it = todo[i]
        name.setTextColor(Color.parseColor(AMBER)); name.setShadowLayer(14f, 0f, 0f, Color.parseColor(AMBER))
        reason.text = reasonLabel(it)
        name.text = it.party.partyName.uppercase()
        amount.text = "Rs ${money(it.total)}"
        counter.text = "${i + 1} / ${todo.size}"
        name.alpha = 0f; amount.alpha = 0f
        name.animate().alpha(1f).setDuration(500).start()
        amount.animate().alpha(1f).setDuration(500).start()
    }
}
