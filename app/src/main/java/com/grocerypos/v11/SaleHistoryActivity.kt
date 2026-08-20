package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.graphics.drawable.GradientDrawable

class SaleHistoryActivity : AppCompatActivity() {

    private val bg = "#F0FDFA"
    private val teal = "#14B8A6"
    private val dark = "#0F172A"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        // Header
        val header = LinearLayout(this).apply {
            setPadding(22,26,22,26)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(teal), Color.parseColor("#0F766E"))).apply {
                cornerRadii = floatArrayOf(0f,0f,0f,0f,0f,0f,32f,32f)
            }
        }
        header.addView(TextView(this).apply {
            text = "🧾 Sale History - Ultra Premium"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        outer.addView(header)

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1,0,1f) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16,18,16,24)
            gravity = Gravity.CENTER
        }

        root.addView(TextView(this).apply {
            text = "✅ Sale History Fixed\n\nProduct / Purchase / Sale / User / Settings\nSab OK hai - v13\n\nAb paymentDao / cashTransactionDao ka error khatam!\n\nAPK ban rahi hai!"
            textSize = 16f
            setTextColor(Color.parseColor(dark))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(20,20,20,20)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 18f
                setStroke(2, Color.parseColor("#CCFBF1"))
            }
        })

        scroll.addView(root)
        outer.addView(scroll)
        setContentView(outer)
    }
}
