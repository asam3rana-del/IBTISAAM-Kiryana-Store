package com.grocerypos.v11.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.BuildConfig
import com.grocerypos.v11.LicenseManager
import com.grocerypos.v11.LicenseState
import com.grocerypos.v11.LicenseStatus
import com.grocerypos.v11.MainActivity
import com.grocerypos.v11.util.Loc
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NEW: License screen (Hanna Solutions). Opened from Settings → License, and by MainActivity
 * whenever the license does not allow use (then [EXTRA_BLOCKED] = true: Back closes the app,
 * only "Check again", "Enter key", "Backup" and "Contact" are offered — the shop's data is
 * never touched or deleted).
 */
class LicenseActivity : AppCompatActivity() {

    companion object { const val EXTRA_BLOCKED = "blocked" }

    private lateinit var c: CuColors
    private lateinit var content: LinearLayout
    private var blocked = false
    private var checking = false
    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)

    private fun t(en: String, ur: String) = Loc.t(this, en, ur)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        c = CuColors(this)
        blocked = intent.getBooleanExtra(EXTRA_BLOCKED, false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cuDp(18), cuDp(36), cuDp(18), cuDp(24))
            setBackgroundColor(Color.parseColor(c.bg))
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor(c.bg)); addView(root) })
        render(LicenseManager.state(this))
        checkOnline(silent = true)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (blocked && !LicenseManager.state(this@LicenseActivity).canUse) finishAffinity() else finish()
            }
        })
    }

    private fun statusText(s: LicenseState): Pair<String, String> = when (s.status) {
        LicenseStatus.ACTIVE -> t("License active", "لائسنس فعال ہے") to c.teal
        LicenseStatus.TRIAL -> t("Trial", "ٹرائل") to c.amber
        LicenseStatus.EXPIRED -> t("License expired", "لائسنس ختم ہو گیا") to c.red
        LicenseStatus.DISABLED -> t("License switched off", "لائسنس بند کر دیا گیا") to c.red
        LicenseStatus.CLOCK -> t("Phone date / time is wrong", "فون کی تاریخ / وقت غلط ہے") to c.red
    }

    private fun render(s: LicenseState) {
        content.removeAllViews()
        content.addView(cuText(t("License", "لائسنس"), 22f, c.text, true))
        content.addView(cuText("Hanna Solutions", 12f, c.muted))
        content.addView(cuGap(12))

        val card = cuCard(c)
        val (label, color) = statusText(s)
        card.addView(cuText(label, 18f, color, true))
        when (s.status) {
            LicenseStatus.ACTIVE, LicenseStatus.TRIAL -> card.addView(cuText(
                t("Valid till ", "تک درست: ") + dateFmt.format(Date(s.validUntil)) + "  •  " +
                    t("${s.daysLeft} days left", "${s.daysLeft} دن باقی"), 13f, c.text))
            LicenseStatus.EXPIRED -> card.addView(cuText(t("Ended on ", "ختم ہوا: ") + dateFmt.format(Date(s.validUntil)) +
                ". " + t("Please renew to continue.", "جاری رکھنے کے لیے تجدید کروائیں۔"), 13f, c.text))
            LicenseStatus.DISABLED -> card.addView(cuText(t("Contact Hanna Solutions.", "ہانا سولوشنز سے رابطہ کریں۔"), 13f, c.text))
            LicenseStatus.CLOCK -> card.addView(cuText(t("Set the correct date & time (automatic), connect internet and tap Check again.",
                "درست تاریخ / وقت (آٹومیٹک) لگائیں، انٹرنیٹ چلائیں اور دوبارہ چیک کریں۔"), 13f, c.text))
        }
        if (s.status == LicenseStatus.TRIAL) card.addView(cuText(t("Ask Hanna Solutions to activate this device before the trial ends.",
            "ٹرائل ختم ہونے سے پہلے ہانا سولوشنز سے یہ ڈیوائس ایکٹیو کروائیں۔"), 12f, c.muted))
        val shop = LicenseManager.shopName(this)
        if (shop.isNotBlank()) card.addView(cuText(shop, 12.5f, c.muted, true))
        val last = LicenseManager.lastOnlineCheck(this)
        card.addView(cuText(t("Last online check: ", "آخری آن لائن چیک: ") + (if (last > 0) dateFmt.format(Date(last)) else t("never", "کبھی نہیں")), 11.5f, c.muted))
        content.addView(card)

        // Device code — what the shop sends to Hanna.
        val code = LicenseManager.deviceCode(this)
        val codeCard = cuCard(c)
        codeCard.addView(cuText(t("This device's code", "اس ڈیوائس کا کوڈ"), 12f, c.muted))
        codeCard.addView(cuText(code, 26f, c.text, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.1f; setTextIsSelectable(true) })
        codeCard.addView(cuText(t("Send this code to Hanna Solutions to activate / renew.", "ایکٹیو / تجدید کے لیے یہ کوڈ ہانا سولوشنز کو بھیجیں۔"), 11.5f, c.muted))
        codeCard.addView(cuGap(6))
        codeCard.addView(cuButton(t("Copy code", "کوڈ کاپی کریں"), c.navy) {
            try {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("device code", code))
                cuToast(t("Copied", "کاپی ہو گیا"))
            } catch (e: Exception) { }
        })
        content.addView(codeCard)
        content.addView(cuGap(6))

        content.addView(cuButton(if (checking) t("Checking…", "چیک ہو رہا ہے…") else t("Check again (internet)", "دوبارہ چیک کریں (انٹرنیٹ)"), c.teal) {
            if (!checking) checkOnline(silent = false)
        })
        content.addView(cuGap(6))
        if (LicenseManager.offlineKeysEnabled()) {
            content.addView(cuButton(t("Enter license key", "لائسنس کی درج کریں"), c.navy) { askKey() })
            content.addView(cuGap(6))
        }
        val support = BuildConfig.LICENSE_SUPPORT_WHATSAPP
        if (support.isNotBlank()) {
            content.addView(cuButton(t("WhatsApp Hanna Solutions", "ہانا سولوشنز کو واٹس ایپ"), "#25D366") {
                val msg = "Assalam o Alaikum, license ke liye.\nDevice code: $code\nStatus: ${s.status}" +
                    (if (shop.isNotBlank()) "\nShop: $shop" else "")
                if (!cuOpenWhatsApp(support, msg)) cuToast(t("WhatsApp not found", "واٹس ایپ نہیں ملا"))
            })
            content.addView(cuGap(6))
        }
        if (!s.canUse) {
            // Data stays safe: let the shop take a backup even while blocked.
            content.addView(cuButton(t("Backup my data", "اپنے ڈیٹا کا بیک اپ"), c.purple) {
                startActivity(Intent(this, BackupExportActivity::class.java))
            })
            content.addView(cuGap(6))
        }
        if (blocked && s.canUse) {
            content.addView(cuButton(t("Continue", "جاری رکھیں"), c.teal) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            })
        }
    }

    private fun checkOnline(silent: Boolean) {
        checking = true
        render(LicenseManager.state(this))
        lifecycleScope.launch {
            val r = LicenseManager.refreshOnline(this@LicenseActivity)
            checking = false
            val s = LicenseManager.state(this@LicenseActivity)
            render(s)
            if (!silent) cuToast(when (r) {
                LicenseManager.Online.OK -> t("Updated from server", "سرور سے اپڈیٹ ہو گیا")
                LicenseManager.Online.NOT_FOUND -> t("No license on server for this device yet", "سرور پر اس ڈیوائس کا لائسنس ابھی نہیں")
                LicenseManager.Online.NO_ACCESS -> t("Server did not allow the check", "سرور نے چیک کی اجازت نہیں دی")
                LicenseManager.Online.OFFLINE -> t("No internet — try again", "انٹرنیٹ نہیں — دوبارہ کوشش کریں")
            })
            if (blocked && s.canUse && !silent) cuToast(t("License OK — tap Continue", "لائسنس ٹھیک ہے — جاری رکھیں دبائیں"))
        }
    }

    private fun askKey() {
        val (scroll, body) = cuDialogBody()
        body.addView(cuText(t("Key looks like IBT-20271231-AB12CD34EF", "کی کچھ یوں ہوتی ہے: IBT-20271231-AB12CD34EF"), 12f, c.muted))
        val f = cuField("IBT-YYYYMMDD-XXXXXXXXXX", c)
        body.addView(f)
        android.app.AlertDialog.Builder(this)
            .setTitle(t("License key", "لائسنس کی"))
            .setView(scroll)
            .setNegativeButton(t("Cancel", "منسوخ"), null)
            .setPositiveButton(t("Activate", "ایکٹیو کریں")) { _, _ ->
                val s = LicenseManager.applyKey(this, f.text.toString())
                if (s == null) cuToast(t("This key is not valid for this device", "یہ کی اس ڈیوائس کے لیے درست نہیں"))
                else {
                    render(s)
                    cuToast(if (s.canUse) t("Activated till ", "ایکٹیو: ") + dateFmt.format(Date(s.validUntil))
                        else t("Key accepted but its date has passed", "کی درست ہے مگر اس کی تاریخ گزر چکی"))
                }
            }.show()
    }
}
