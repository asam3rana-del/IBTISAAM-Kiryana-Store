package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import com.grocerypos.v11.UnitType
import kotlinx.coroutines.launch

/**
 * One-time bulk Urdu -> English rename tool for Category and Unit values.
 * Shows every distinct Urdu value ONCE (not per-product) with a blank English
 * field next to it. Save applies the new name to the master table AND every
 * product row that used the old value — category, unit, secondaryUnit,
 * tertiaryUnit — in one shot.
 */
class BulkTranslateActivity : AppCompatActivity() {

    private val bg = "#F3F2FA"
    private val cardBg = "#FFFFFF"
    private val primary = "#4A3AFF"
    private val textDark = "#1A1A2E"
    private val textGray = "#8A8A9E"
    private val border = "#E7E5F3"

    // old value -> input field
    private val categoryFields = LinkedHashMap<String, EditText>()
    private val unitFields = LinkedHashMap<String, EditText>()

    private lateinit var catContainer: LinearLayout
    private lateinit var unitContainer: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 140)
            setBackgroundColor(Color.parseColor(bg))
        }

        root.addView(TextView(this).apply {
            text = "Bulk Translate: Category & Unit"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(textDark))
        })
        root.addView(TextView(this).apply {
            text = "Type the English name once for each value below. Leave blank to skip."
            textSize = 12.5f
            setTextColor(Color.parseColor(textGray))
            setPadding(0, 6, 0, 24)
        })

        root.addView(sectionHeader("Categories"))
        catContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(catContainer)
        root.addView(spacer(24))

        root.addView(sectionHeader("Units"))
        unitContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(unitContainer)
        root.addView(spacer(24))

        val saveBtn = TextView(this).apply {
            text = "SAVE TRANSLATIONS"
            textSize = 14.5f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(primary)); cornerRadius = 16f
            }
            setPadding(0, 30, 0, 30)
            setOnClickListener { saveAll() }
        }
        root.addView(saveBtn)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(bg))
            addView(root)
        })

        loadValues()
    }

    private fun loadValues() {
        lifecycleScope.launch {
            val db = PosDatabase.get(this@BulkTranslateActivity)

            val categories = (
                db.categoryDao().all().let { flow ->
                    var list = emptyList<String>()
                    flow.collect { list = it.map { c -> c.name }; return@collect }
                    list
                }
            )
            // one-shot reads are simpler for a bulk tool than collecting flows forever
            val cats = mutableSetOf<String>()
            db.productDao().distinctCategories().forEach { cats.add(it) }
            categories.forEach { cats.add(it) }

            val units = mutableSetOf<String>()
            db.unitDao().all().let { flow ->
                flow.collect { list -> list.forEach { units.add(it.name) }; return@collect }
            }
            db.productDao().distinctPrimaryUnits().forEach { units.add(it) }
            db.productDao().distinctSecondaryUnits().forEach { units.add(it) }
            db.productDao().distinctTertiaryUnits().forEach { units.add(it) }

            renderRows(catContainer, cats.filter { looksUrdu(it) }.sorted(), categoryFields)
            renderRows(unitContainer, units.filter { looksUrdu(it) }.sorted(), unitFields)

            if (categoryFields.isEmpty() && unitFields.isEmpty()) {
                catContainer.addView(TextView(this@BulkTranslateActivity).apply {
                    text = "Nothing left to translate 🎉"
                    setTextColor(Color.parseColor(textGray))
                })
            }
        }
    }

    // Simple Urdu/Arabic-script detector so English values already saved aren't shown again.
    private fun looksUrdu(s: String): Boolean =
        s.any { it.code in 0x0600..0x06FF }

    private fun renderRows(container: LinearLayout, values: List<String>, map: LinkedHashMap<String, EditText>) {
        for (v in values) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18, 14, 18, 14)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(cardBg))
                    setStroke(2, Color.parseColor(border))
                    cornerRadius = 14f
                }
            }
            row.addView(TextView(this).apply {
                text = v
                textSize = 14f
                setTextColor(Color.parseColor(textDark))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            val input = EditText(this).apply {
                hint = "English name"
                textSize = 14f
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
            val db = PosDatabase.get(this@BulkTranslateActivity)
            var count = 0

            for ((oldVal, field) in categoryFields) {
                val newVal = field.text.toString().trim()
                if (newVal.isEmpty() || newVal == oldVal) continue
                db.categoryDao().insert(Category(newVal))
                db.categoryDao().deleteByName(oldVal)
                db.productDao().renameCategoryInProducts(oldVal, newVal)
                count++
            }

            for ((oldVal, field) in unitFields) {
                val newVal = field.text.toString().trim()
                if (newVal.isEmpty() || newVal == oldVal) continue
                db.unitDao().insert(UnitType(newVal))
                db.unitDao().deleteByName(oldVal)
                db.productDao().renamePrimaryUnitInProducts(oldVal, newVal)
                db.productDao().renameSecondaryUnitInProducts(oldVal, newVal)
                db.productDao().renameTertiaryUnitInProducts(oldVal, newVal)
                count++
            }

            Toast.makeText(
                this@BulkTranslateActivity,
                "$count value(s) translated across all products",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun sectionHeader(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 12.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor(primary))
        letterSpacing = 0.03f
        setPadding(0, 0, 0, 10)
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, (h * resources.displayMetrics.density).toInt())
    }
}
