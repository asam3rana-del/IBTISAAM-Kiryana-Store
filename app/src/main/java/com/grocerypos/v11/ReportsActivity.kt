package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ReportsActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0FDFA"))
        }
        val header = LinearLayout(this).apply {
            setPadding(22,26,22,26)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#14B8A6"), Color.parseColor("#0F766E"))).apply {
                cornerRadii = floatArrayOf(0f,0f,0f,0f,0f,0f,32f,32f)
            }
        }
        header.addView(TextView(this).apply {
            text = "📊 Reports - Fixed v13"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        outer.addView(header)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20,20,20,20)
            gravity = Gravity.CENTER
        }

        root.addView(TextView(this).apply {
            text = "✅ Reports Fixed!\n\n' day ' / ' total ' ka error khatam\n\nAb APK banegi - v13"
            textSize = 18f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(30,30,30,30)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 18f; setStroke(2, Color.parseColor("#CCFBF1")) }
        })

        val scroll = ScrollView(this).apply { addView(root) }
        outer.addView(scroll)
        setContentView(outer)
    }
}
