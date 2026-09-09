package com.grocerypos.v11.ui

import com.grocerypos.v11.R

/*
 * Product screen — "Add Item Unit" dialog: setting Primary/Secondary/Tertiary
 * units and how they convert into each other. Split out of ProductActivity.kt
 * as part of the "Oversized Activity files" cleanup (see IMPROVEMENT-PLAN.md),
 * following the same approach used for SaleActivity's SaleQuickSale.kt /
 * SaleHoldRecall.kt / SaleCart.kt. Declared as extension functions on
 * ProductActivity so they still reach the screen's theme colors, unit list,
 * and helper builders directly. No behavior change.
 */

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.grocerypos.v11.*
import com.grocerypos.v11.ui.components.*
import com.grocerypos.v11.util.Loc

internal fun ProductActivity.badgedSectionLabel(iconRes: Int, label: String, accentHex: String) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, 0, 0, 14)
    addView(ImageView(this@badgedSectionLabel).apply {
        setImageDrawable(tintedDrawable(iconRes, "#FFFFFF", 16))
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(accentHex))
        }
        val px = 30.dp()
        layoutParams = android.view.ViewGroup.LayoutParams(px, px)
    })
    addView(View(this@badgedSectionLabel).apply {
        layoutParams = LinearLayout.LayoutParams(10.dp(), 1)
    })
    addView(TextView(this@badgedSectionLabel).apply {
        text = label.uppercase()
        textSize = 12f
        setTextColor(Color.parseColor(navy))
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.02f
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    })
}

internal fun ProductActivity.openUnitDialog() {
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = strokedBg(cardWhite, cardWhite, 24)
        clipToOutline = true
    }

    // ---- Gradient header with rounded top corners + soft ruler icon badge, matching the
    // premium navy header treatment used across Purchase/Sale/Product. ----
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(28, 28, 28, 26)
        background = gradientBg(navy, navyLight, cornerTop = 24, cornerBottom = 0)
    }

    val headerTop = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    headerTop.addView(ImageView(this).apply {
        setImageDrawable(tintedDrawable(R.drawable.ic_ruler, "#FFFFFF", 21))
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(headerBadgeOverlay))
        }
        val px = 46.dp()
        layoutParams = android.view.ViewGroup.LayoutParams(px, px)
    })
    headerTop.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(14.dp(), 1)
    })

    val headerTextCol = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }
    headerTextCol.addView(TextView(this).apply {
        text = Loc.t(this@openUnitDialog, "Add Item Unit", "آئٹم یونٹ شامل کریں")
        textSize = 18.5f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
    })
    headerTextCol.addView(TextView(this).apply {
        text = Loc.t(
            this@openUnitDialog,
            "Set how this product's units convert into each other",
            "یہ پروڈکٹ کے یونٹس ایک دوسرے میں کیسے تبدیل ہوں گے، ترتیب دیں"
        )
        textSize = 11.5f
        setTextColor(Color.parseColor(headerSubtitleColor))
        setPadding(0, 5, 0, 0)
    })
    headerTop.addView(headerTextCol)
    header.addView(headerTop)
    content.addView(header)

    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 22, 20, 6)
        setBackgroundColor(Color.parseColor(bg))
    }

    val scroll = ScrollView(this)
    scroll.addView(body)

    // Primary — teal accent badge
    val primaryCard = premiumUnitCard()
    primaryCard.addView(badgedSectionLabel(R.drawable.ic_ruler, Loc.t(this, "Primary Unit", "بنیادی یونٹ"), teal))
    val primaryField = unitAutoCompleteField(
        Loc.t(this, "Type or pick unit, e.g. pcs, kg, box", "یونٹ لکھیں یا منتخب کریں، مثلاً pcs, kg, box")
    )
    primaryCard.addView(premiumFieldBox(primaryField, R.drawable.ic_text, teal))
    body.addView(primaryCard)
    body.addView(spacer(16))

    // Secondary — blue accent badge
    val secondaryCard = premiumUnitCard()
    secondaryCard.addView(
        badgedSectionLabel(
            R.drawable.ic_ruler,
            Loc.t(this, "Secondary Unit (smaller quantity, optional)", "ثانوی یونٹ (چھوٹی مقدار، اختیاری)"),
            blue
        )
    )
    val secondaryField = unitAutoCompleteField(
        Loc.t(this, "Leave blank if not needed", "اگر ضرورت نہیں تو خالی چھوڑ دیں")
    )
    secondaryCard.addView(premiumFieldBox(secondaryField, R.drawable.ic_text, blue))
    secondaryCard.addView(spacer(12))

    val secondaryQtyField = numberDialogField(
        Loc.t(
            this,
            "1 Primary = how many Secondary? e.g. 1 box = 12 pcs",
            "1 بنیادی یونٹ = کتنے ثانوی؟ مثلاً 1 box = 12 pcs"
        ),
        selectedSecondaryQty,
        EditorInfo.IME_ACTION_NEXT
    )
    secondaryCard.addView(premiumFieldBox(secondaryQtyField, R.drawable.ic_repeat, blue))
    body.addView(secondaryCard)
    body.addView(spacer(16))

    // Tertiary — orange accent badge
    val tertiaryCard = premiumUnitCard()
    tertiaryCard.addView(
        badgedSectionLabel(
            R.drawable.ic_ruler,
            Loc.t(this, "Tertiary Unit (smallest quantity, optional)", "تیسرا یونٹ (سب سے چھوٹی مقدار، اختیاری)"),
            orange
        )
    )
    val tertiaryField = unitAutoCompleteField(
        Loc.t(this, "Leave blank if not needed", "اگر ضرورت نہیں تو خالی چھوڑ دیں")
    )
    tertiaryCard.addView(premiumFieldBox(tertiaryField, R.drawable.ic_text, orange))
    tertiaryCard.addView(spacer(12))

    val tertiaryQtyField = numberDialogField(
        Loc.t(
            this,
            "1 Secondary = how many Tertiary?",
            "1 ثانوی یونٹ = کتنے تیسرے یونٹس؟"
        ),
        selectedTertiaryQty,
        EditorInfo.IME_ACTION_DONE
    )
    tertiaryCard.addView(premiumFieldBox(tertiaryQtyField, R.drawable.ic_repeat, orange))
    body.addView(tertiaryCard)
    body.addView(spacer(6))

    content.addView(
        scroll,
        LinearLayout.LayoutParams(-1, 0, 1f)
    )

    // ---- Footer sits on its own elevated white strip with a soft top divider, gradient
    // Save button, and a bit more breathing room than the old flat footer. ----
    val footer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(24, 18, 24, 24)
        setBackgroundColor(Color.parseColor(cardWhite))
        applyElevation(this, 6f)
    }
    content.addView(footer)

    val dialog = AlertDialog.Builder(this).setView(content).create()
    dialog.window?.setBackgroundDrawable(
        GradientDrawable().apply {
            setColor(Color.parseColor(cardWhite))
            cornerRadius = 24 * resources.displayMetrics.density
        }
    )

    val unitSuggestions = units.distinct()
    primaryField.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, unitSuggestions))
    secondaryField.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, unitSuggestions))
    tertiaryField.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, unitSuggestions))

    primaryField.setText(selectedPrimaryUnit)
    secondaryField.setText(if (selectedSecondaryUnit == "None") "" else selectedSecondaryUnit)
    tertiaryField.setText(if (selectedTertiaryUnit == "None") "" else selectedTertiaryUnit)

    fun autoSecondary() {
        val p = primaryField.text.toString().trim()
        val s = secondaryField.text.toString().trim()
        if (p.isBlank() || s.isBlank()) return
        val standard = standardUnitQty(p, s) ?: return
        if (secondaryQtyField.text.toString().isBlank()) {
            secondaryQtyField.setText(trimNum(standard))
        }
    }

    fun autoTertiary() {
        val s = secondaryField.text.toString().trim()
        val t = tertiaryField.text.toString().trim()
        if (s.isBlank() || t.isBlank()) return
        val standard = standardUnitQty(s, t) ?: return
        if (tertiaryQtyField.text.toString().isBlank()) {
            tertiaryQtyField.setText(trimNum(standard))
        }
    }

    fun trySaveUnitSelection() {
        val p = primaryField.text.toString().trim()
        var s = secondaryField.text.toString().trim().ifBlank { "None" }
        var t = tertiaryField.text.toString().trim().ifBlank { "None" }
        val sq = secondaryQtyField.text.toString().toDoubleOrNull() ?: 0.0
        val tq = tertiaryQtyField.text.toString().toDoubleOrNull() ?: 0.0

        if (p.isBlank()) {
            Toast.makeText(
                this@openUnitDialog,
                Loc.t(this@openUnitDialog, "Select Primary Unit", "بنیادی یونٹ منتخب کریں"),
                Toast.LENGTH_SHORT
            ).show()
            primaryField.requestFocus()
            return
        }

        if (s != "None" && s.equals(p, ignoreCase = true)) {
            Toast.makeText(
                this@openUnitDialog,
                Loc.t(this@openUnitDialog, "Secondary must be different", "ثانوی یونٹ مختلف ہونا چاہیے"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (s != "None" && sq <= 0) {
            secondaryQtyField.error =
                Loc.t(this@openUnitDialog, "Enter quantity", "مقدار درج کریں")
            secondaryQtyField.requestFocus()
            return
        }

        if (t != "None" && s == "None") {
            Toast.makeText(
                this@openUnitDialog,
                Loc.t(this@openUnitDialog, "Select Secondary first", "پہلے ثانوی یونٹ منتخب کریں"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (t != "None" && t.equals(s, ignoreCase = true)) {
            Toast.makeText(
                this@openUnitDialog,
                Loc.t(this@openUnitDialog, "Tertiary must be different", "تیسرا یونٹ مختلف ہونا چاہیے"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (t != "None" && tq <= 0) {
            tertiaryQtyField.error =
                Loc.t(this@openUnitDialog, "Enter quantity", "مقدار درج کریں")
            tertiaryQtyField.requestFocus()
            return
        }

        if (s == "None") {
            t = "None"
        }

        selectedPrimaryUnit = p
        selectedSecondaryUnit = s
        selectedSecondaryQty = if (s == "None") 0.0 else sq
        selectedTertiaryUnit = t
        selectedTertiaryQty = if (t == "None") 0.0 else tq

        ensureUnitSaved(p)
        if (s != "None") ensureUnitSaved(s)
        if (t != "None") ensureUnitSaved(t)

        if (
            selectedOpeningStockUnit.isBlank() ||
            selectedOpeningStockUnit == selectedPrimaryUnit
        ) {
            selectedOpeningStockUnit = selectedPrimaryUnit
        }

        selectUnitBtn.text = buildString {
            append(selectedPrimaryUnit)
            if (selectedSecondaryUnit != "None") {
                append(" / $selectedSecondaryUnit")
            }
            if (selectedTertiaryUnit != "None") {
                append(" / $selectedTertiaryUnit")
            }
        }

        refreshStockUnitAdapter()
        updateOpeningStockPreview()

        val conversion = buildString {
            if (selectedSecondaryUnit != "None") {
                append("1 $selectedPrimaryUnit = ${trimNum(selectedSecondaryQty)} $selectedSecondaryUnit")
            }
            if (selectedTertiaryUnit != "None") {
                if (isNotEmpty()) append("   •   ")
                append("1 $selectedSecondaryUnit = ${trimNum(selectedTertiaryQty)} $selectedTertiaryUnit")
            }
        }

        if (conversion.isNotEmpty()) {
            Toast.makeText(
                this@openUnitDialog,
                conversion,
                Toast.LENGTH_SHORT
            ).show()
        }

        hideKeyboard()
        dialog.dismiss()
    }

    primaryField.addTextChangedListener(simpleWatcher { autoSecondary() })
    secondaryField.addTextChangedListener(simpleWatcher { autoSecondary(); autoTertiary() })
    tertiaryField.addTextChangedListener(simpleWatcher { autoTertiary() })

    primaryField.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) safeShowDropDown(primaryField) }
    secondaryField.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) safeShowDropDown(secondaryField) }
    tertiaryField.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) safeShowDropDown(tertiaryField) }

    primaryField.setOnItemClickListener { _, _, _, _ -> secondaryField.requestFocus() }
    primaryField.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_NEXT) { secondaryField.requestFocus(); true } else false
    }
    secondaryField.setOnItemClickListener { _, _, _, _ -> secondaryQtyField.requestFocus() }
    secondaryField.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_NEXT) { secondaryQtyField.requestFocus(); true } else false
    }
    secondaryQtyField.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_NEXT) { tertiaryField.requestFocus(); true } else false
    }
    tertiaryField.setOnItemClickListener { _, _, _, _ -> tertiaryQtyField.requestFocus() }
    tertiaryField.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_NEXT) { tertiaryQtyField.requestFocus(); true } else false
    }
    tertiaryQtyField.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) { trySaveUnitSelection(); true } else false
    }

    autoSecondary()
    autoTertiary()

    footer.addView(TextView(this).apply {
        text = Loc.t(this@openUnitDialog, "Cancel", "منسوخ کریں")
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(Color.parseColor(textMuted))
        setTypeface(typeface, Typeface.BOLD)
        background = strokedBg(border, fieldFill, 14)
        setPadding(0, 24, 0, 24)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(0, 0, 8, 0)
        }
        setOnClickListener { dialog.dismiss() }
    })

    footer.addView(TextView(this).apply {
        text = Loc.t(this@openUnitDialog, "Save", "محفوظ کریں")
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
<<<<<<< HEAD
        background = gradientBg(teal, tealDark, cornerTop = 14, cornerBottom = 14)
=======
        background = gradientBg(teal, "#0C8F8A", cornerTop = 14, cornerBottom = 14)
>>>>>>> cc8b3ed1c3be113f6b2a67aab0b9727c246553fa
        setPadding(0, 24, 0, 24)
        applyElevation(this, 3f)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(8, 0, 0, 0)
        }
        setLeadingIcon(R.drawable.ic_check, "#FFFFFF", 15, 6)
        setOnClickListener { trySaveUnitSelection() }
    })

    dialog.show()
}

internal fun ProductActivity.premiumUnitCard() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(20, 18, 20, 18)
    background = strokedBg(border, cardWhite, 18)
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(0, 0, 0, 0)
    }
    applyElevation(this, 1.5f)
}

internal fun ProductActivity.premiumFieldBox(field: EditText, iconRes: Int, accentHex: String) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = strokedBg(border, fieldFill, 12)
    setPadding(10, 8, 16, 8)
    addView(ImageView(this@premiumFieldBox).apply {
        setImageDrawable(tintedDrawable(iconRes, "#FFFFFF", 16))
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(accentHex))
            alpha = 210
        }
        val px = 30.dp()
        layoutParams = android.view.ViewGroup.LayoutParams(px, px)
    })
    addView(View(this@premiumFieldBox).apply {
        layoutParams = LinearLayout.LayoutParams(10.dp(), 1)
    })
    (field.parent as? ViewGroup)?.removeView(field)
    field.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    addView(field)
}

internal fun ProductActivity.unitAutoCompleteField(hintText: String) = AutoCompleteTextView(this).apply {
    hint = hintText
    setHintTextColor(Color.parseColor(textMuted))
    setTextColor(Color.parseColor(textDark))
    setTypeface(typeface, Typeface.BOLD)
    background = null
    textSize = 15f
    threshold = 1
    imeOptions = EditorInfo.IME_ACTION_NEXT
}

internal fun ProductActivity.numberDialogField(hintText: String, oldValue: Double, imeAction: Int = EditorInfo.IME_ACTION_NEXT) =
    EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.parseColor(textMuted))
        setTextColor(Color.parseColor(textDark))
        background = null
        textSize = 15f
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        imeOptions = imeAction
        if (oldValue > 0) setText(trimNum(oldValue))
    }
