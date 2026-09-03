package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.Category
import com.grocerypos.v11.PosDatabase
import com.grocerypos.v11.SyncQueueHelper
import com.grocerypos.v11.UnitType
import com.grocerypos.v11.util.DuplicateUnitFix
import kotlinx.coroutines.launch

/**
 * One-time bulk Urdu -> English rename tool for Category and Unit values.
 * Shows every distinct Urdu value ONCE (not per-product) with a blank English
 * field next to it. Save applies the new name to the master table AND every
 * product row that used the old value — category, unit, secondaryUnit,
 * tertiaryUnit — in one shot. No per-product editing needed.
 *
 * FIX: originally read categories/units via `dao.all().first()` (collecting a
 * Room Flow once). That path failed silently on some devices/DB states,
 * leaving the screen with zero rows and no error shown — which looked like
 * "nothing happens, keyboard won't open" since there were no EditTexts to tap.
 * Now uses plain one-shot suspend queries (allOnce()) instead of a Flow, and
 * wraps the whole load in try/catch so any real failure shows as a Toast
 * instead of silently producing an empty screen.
 *
 * ADDED: a "Fix Duplicate Unit Names" maintenance button (separate from the
 * Urdu->English translation flow above — this screen only surfaces
 * Urdu-script values via looksUrdu(), so an already-English but accidentally
 * duplicated value like "Box\nBox" would never show up as a row here and
 * could never be fixed through the translate-and-save flow). The button
 * calls DuplicateUnitFix.run(db), which scans Primary/Secondary/Tertiary
 * unit values across every product for an exact-duplicated word (e.g.
 * "Box\nBox", "Box Box") and collapses it to the single clean word, using
 * the same rename* functions this screen already uses above.
 */
class BulkTranslateActivity : AppCompatActivity() {

    // ================= PREMIUM COLOR PALETTE (matches rest of app) =================
    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val primaryDark = "#3527D6"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"
    private val teal = "#0F9B8E"
    private val tealDark = "#0C7C71"

    companion object {
        private const val TAG = "BulkTranslate"
    }

    // old value -> input field, keeps insertion order for a stable UI
    private val categoryFields = LinkedHashMap<String, EditText>()
    private val unitFields = LinkedHashMap<String, EditText>()

    private lateinit var catContainer: LinearLayout
    private lateinit var unitContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var saveBtn: TextView
    private lateinit var loadingText: TextView
    private lateinit var fixDuplicatesBtn: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 40)
            setBackgroundColor(Color.parseColor(bg))
        }

        // ================= HEADER =================
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 22, 26, 22)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 22f }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
        }
        header.addView(TextView(this).apply {
            text = "🌐 Bulk Translate"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "Type the English name once for each Urdu value — it applies to every product using it."
            textSize = 12f
            setTextColor(Color.parseColor("#DAD5FF"))
            setPadding(0, 6, 0, 0)
        })
        root.addView(header)

        // ================= FIX DUPLICATE UNIT NAMES (maintenance action) =================
        // Separate from the Urdu translate flow below: this fixes values like "Box\nBox"
        // (an already-English word accidentally typed twice into one unit field), which
        // never shows up in the Urdu rows above since looksUrdu() would exclude it.
        val fixCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(cardBg))
                setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(teal))
                cornerRadius = 16f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
        }
        fixCard.addView(TextView(this).apply {
            text = "🔧 Fix Duplicate Unit Names"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(textDark))
        })
        fixCard.addView(TextView(this).apply {
            text = "Cleans up unit fields that got typed twice by accident (e.g. \"Box\\nBox\" -> \"Box\") across every product, in every category."
            textSize = 12f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 6, 0, 14)
        })
        fixDuplicatesBtn = TextView(this).apply {
            text = "RUN FIX"
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(teal), Color.parseColor(tealDark))
            ).apply { cornerRadius = 14f }
            setPadding(0, 24, 0, 24)
            setOnClickListener { runDuplicateUnitFix() }
        }
        fixCard.addView(fixDuplicatesBtn)
        root.addView(fixCard)

        loadingText = TextView(this).apply {
            text = "Loading…"
            textSize = 13.5f
            setTextColor(Color.parseColor(textGray))
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40)
        }
        root.addView(loadingText)

        emptyText = TextView(this).apply {
            text = "Nothing left to translate 🎉"
            textSize = 14f
            setTextColor(Color.parseColor(textGray))
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 60)
            visibility = View.GONE
        }
        root.addView(emptyText)

        root.addView(sectionHeader("Categories"))
        catContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(catContainer)
        root.addView(spacer(24))

        root.addView(sectionHeader("Units"))
        unitContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(unitContainer)
        root.addView(spacer(30))

        saveBtn = TextView(this).apply {
            text = "💾  SAVE TRANSLATIONS"
            textSize = 14.5f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(primary), Color.parseColor(primaryDark))
            ).apply { cornerRadius = 16f }
            setPadding(0, 30, 0, 30)
            visibility = View.GONE
            setOnClickListener { saveAll() }
        }
        root.addView(saveBtn)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadValues()
    }

    // ---- Runs the one-time duplicate-unit cleanup (see DuplicateUnitFix.kt) and
    // reloads the Urdu translate rows afterward, since a fixed unit's distinct
    // value set may have changed. ----
    private fun runDuplicateUnitFix() {
        fixDuplicatesBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                val db = PosDatabase.get(this@BulkTranslateActivity)
                val count = DuplicateUnitFix.run(db)
                if (count > 0) SyncQueueHelper.trigger(this@BulkTranslateActivity)
                Toast.makeText(
                    this@BulkTranslateActivity,
                    if (count > 0) "$count unit name(s) fixed across all products"
                    else "No duplicated unit names found",
                    Toast.LENGTH_LONG
                ).show()
                loadValues()
            } catch (e: Exception) {
                Log.e(TAG, "runDuplicateUnitFix failed", e)
                Toast.makeText(
                    this@BulkTranslateActivity,
                    "Could not fix duplicates: ${e.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                fixDuplicatesBtn.isEnabled = true
            }
        }
    }

    private fun loadValues() {
        lifecycleScope.launch {
            try {
                val db = PosDatabase.get(this@BulkTranslateActivity)

                // ---- Categories: master table + any stray text saved directly on products ----
                val cats = mutableSetOf<String>()
                db.categoryDao().allOnce().forEach { cats.add(it.name) }
                db.productDao().distinctCategories().forEach { cats.add(it) }

                // ---- Units: master table + primary/secondary/tertiary unit text on products ----
                val units = mutableSetOf<String>()
                db.unitDao().allOnce().forEach { units.add(it.name) }
                db.productDao().distinctPrimaryUnits().forEach { units.add(it) }
                db.productDao().distinctSecondaryUnits().forEach { units.add(it) }
                db.productDao().distinctTertiaryUnits().forEach { units.add(it) }

                val urduCats = cats.filter { looksUrdu(it) }.sorted()
                val urduUnits = units.filter { looksUrdu(it) }.sorted()

                loadingText.visibility = View.GONE

                // Clear any rows from a previous load (runDuplicateUnitFix calls this
                // again after fixing, so the container must not just keep appending).
                catContainer.removeAllViews()
                unitContainer.removeAllViews()
                categoryFields.clear()
                unitFields.clear()

                renderRows(catContainer, urduCats, categoryFields)
                renderRows(unitContainer, urduUnits, unitFields)

                if (urduCats.isEmpty() && urduUnits.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    saveBtn.visibility = View.GONE
                } else {
                    emptyText.visibility = View.GONE
                    saveBtn.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadValues failed", e)
                loadingText.visibility = View.GONE
                Toast.makeText(
                    this@BulkTranslateActivity,
                    "Could not load values: ${e.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Detects Urdu/Arabic-script text so already-English values never show up here again.
    private fun looksUrdu(s: String): Boolean =
        s.any { it.code in 0x0600..0x06FF }

    private fun renderRows(
        container: LinearLayout,
        values: List<String>,
        map: LinkedHashMap<String, EditText>
    ) {
        for (v in values) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18, 14, 18, 14)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(cardBg))
                    setStroke((1.4 * resources.displayMetrics.density).toInt(), Color.parseColor(border))
                    cornerRadius = 14f
                }
            }
            row.addView(TextView(this).apply {
                text = v
                textSize = 14.5f
                setTextColor(Color.parseColor(textDark))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(this).apply {
                text = "→"
                textSize = 15f
                setTextColor(Color.parseColor(textGray))
                setPadding(14, 0, 14, 0)
            })
            val input = EditText(this).apply {
                hint = "English name"
                setHintTextColor(Color.parseColor(textGray))
                setTextColor(Color.parseColor(textDark))
                textSize = 14.5f
                maxLines = 1
                isFocusableInTouchMode = true
                isFocusable = true
                isEnabled = true
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            row.addView(input)
            map[v] = input
            container.addView(row)
            container.addView(spacer(10))
        }
    }

    private fun saveAll() {
        lifecycleScope.launch {
            try {
                val db = PosDatabase.get(this@BulkTranslateActivity)
                var count = 0
                // FIX (#12 — category/unit master sync incomplete): every barcode
                // touched by ANY rename this save, collected up front — one product
                // can carry an old value in more than one slot (e.g. unit AND
                // secondaryUnit both being renamed this run), so this is a set, not
                // a per-loop list, to avoid enqueueing (and pushing) the same
                // product twice.
                val touchedBarcodes = mutableSetOf<String>()

                // ---- Categories: rename master row (insert new + delete old, so it never
                // crashes if the English name already exists) and cascade into every product. ----
                for ((oldVal, field) in categoryFields) {
                    val newVal = field.text.toString().trim()
                    if (newVal.isEmpty() || newVal == oldVal) continue
                    db.productDao().findByCategory(oldVal).forEach { touchedBarcodes.add(it.barcode) }
                    db.categoryDao().insert(Category(newVal))
                    db.categoryDao().deleteByName(oldVal)
                    db.productDao().renameCategoryInProducts(oldVal, newVal)
                    count++
                }

                // ---- Units: same pattern, but cascades into primary/secondary/tertiary unit
                // columns since one Urdu unit name can appear in any of the three slots. ----
                for ((oldVal, field) in unitFields) {
                    val newVal = field.text.toString().trim()
                    if (newVal.isEmpty() || newVal == oldVal) continue
                    db.productDao().findByPrimaryUnit(oldVal).forEach { touchedBarcodes.add(it.barcode) }
                    db.productDao().findBySecondaryUnit(oldVal).forEach { touchedBarcodes.add(it.barcode) }
                    db.productDao().findByTertiaryUnit(oldVal).forEach { touchedBarcodes.add(it.barcode) }
                    db.unitDao().insert(UnitType(newVal))
                    db.unitDao().deleteByName(oldVal)
                    db.productDao().renamePrimaryUnitInProducts(oldVal, newVal)
                    db.productDao().renameSecondaryUnitInProducts(oldVal, newVal)
                    db.productDao().renameTertiaryUnitInProducts(oldVal, newVal)
                    count++
                }

                // FIX (#12 — category/unit master sync incomplete): the renames above
                // ran as raw SQL UPDATEs, which silently bypassed sync — this app's
                // only sync unit is Product (categories/units master tables have no
                // Firestore collection of their own; see firestore.rules). Without
                // this, a rename stayed local to the device that made it forever —
                // other devices kept the old category/unit value indefinitely. Now
                // every touched product is re-read (post-rename) and enqueued so the
                // new value actually reaches other devices/branches.
                touchedBarcodes.forEach { barcode ->
                    db.productDao().find(barcode)?.let { p -> SyncQueueHelper.enqueueProduct(db, p) }
                }
                if (touchedBarcodes.isNotEmpty()) {
                    SyncQueueHelper.trigger(this@BulkTranslateActivity)
                }

                Toast.makeText(
                    this@BulkTranslateActivity,
                    if (count > 0) "$count value(s) translated across all products" else "Nothing entered to translate",
                    Toast.LENGTH_LONG
                ).show()

                if (count > 0) finish()
            } catch (e: Exception) {
                Log.e(TAG, "saveAll failed", e)
                Toast.makeText(
                    this@BulkTranslateActivity,
                    "Could not save: ${e.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor(primary))
        letterSpacing = 0.03f
        setPadding(4, 0, 0, 10)
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, (heightDp * resources.displayMetrics.density).toInt())
    }
}
