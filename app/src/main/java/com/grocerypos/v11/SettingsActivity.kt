package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0FDFA"))
            setPadding(20,20,20,20)
            gravity = Gravity.CENTER
        }
        root.addView(TextView(this).apply {
            text = "⚙️ Settings\n\n✅ User Management Fixed\n✅ Product Fixed\n✅ Purchase/Sale Fixed\n\nVersion 13 - Ultra Premium"
            textSize = 18f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        setContentView(root)
    }
}
