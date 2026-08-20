package com.grocerypos.v11

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val teal = "#14B8A6"
    private val dark = "#0F172A"
    private val bg = "#F0FDFA"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Outer
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
        }

        // Header Ultra
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 36)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(teal), Color.parseColor("#0F766E"))
            ).apply {
                cornerRadii = floatArrayOf(0f,0f,0f,0f,0f,0f,40f,40f)
            }
        }
        header.addView(TextView(this).apply {
            text = "🕌 IBTISAAM"
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "Kiryana Store - v13 Ultra Premium"
            textSize = 13f
            setTextColor(Color.parseColor("#CCFBF1"))
            setPadding(0,6,0,0)
        })
        outer.addView(header)

        // Body
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20,24,20,24)
            gravity = Gravity.CENTER
        }

        // Success Card
        val card = LinearLayout(this).apply {
            setPadding(4,4,4,4)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(teal), Color.parseColor("#8B5CF6"))
            ).apply { cornerRadius = 26f }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28,28,28,28)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 22f
            }
            gravity = Gravity.CENTER
        }

        inner.addView(TextView(this).apply {
            text = "✅ BUILD PASS!"
            textSize = 22f
            setTextColor(Color.parseColor(dark))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        inner.addView(TextView(this).apply {
            text = "\nUstad g zalalat khatam!\n\nAPK ban gayi hai v13\n\nProduct ✅\nUser ✅\nDatabase ✅\n\nAb Purchase / Sale ka ultra design add karenge!"
            textSize = 14f
            setTextColor(Color.parseColor("#475569"))
            gravity = Gravity.CENTER
            setLineSpacing(8f, 1
