package com.grocerypos.v11.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.grocerypos.v11.PosDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var tabRow: LinearLayout
    private lateinit var listContainer: LinearLayout
    private var showingSales = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }

        root.addView(TextView(this).apply { text = "History"; textSize = 22f; setPadding(0,0,0,16) })

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabRow)

        root.addView(divider())

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        setContentView(ScrollView(this).apply { addView(root) })

        buildTabs()
        showSales()
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        tabRow.addView(Button(this).apply {
            text = "SALES"
            setTextColor(Color.WHITE)
            background = roundedBackground(if (showingSales) "#2E7D32" else "#90A4AE", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0,0,8,0) }
            setOnClickListener { showSales() }
        })
        tabRow.addView(Button(this).apply {
            text = "PURCHASES"
            setTextColor(Color.WHITE)
            background = roundedBackground(if (!showingSales) "#EF6C00" else "#90A4AE", 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8,0,0,0) }
            setOnClickListener { showPurchases() }
        })
    }

    private fun showSales() {
        showingSales = true
        buildTabs()
        loadSales()
    }

    private fun showPurchases() {
        showingSales = false
        buildTabs()
        loadPurchases()
    }

    private fun loadSales() {
        lifecycleScope.launch {
            val list = PosDatabase.get(this@HistoryActivity).saleDao().allSales()
            listContainer.removeAllViews()
            if (list.isEmpty()) {
                listContainer.addView(emptyText("Koi sale nahi hui abhi tak"))
                return@launch
            }
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            for (s in list) {
                listContainer.addView(row(
                    title = s.invoice,
                    subtitle = "${s.customerName}  •  ${s.paymentMethod}",
                    amount = s.total,
                    date = fmt.format(Date(s.createdAt)),
                    color = "#2E7D32"
                ))
            }
        }
    }

    private fun loadPurchases() {
        lifecycleScope.launch {
            val list = PosDatabase.get(this@HistoryActivity).purchaseDao().allPurchases()
            listContainer.removeAllViews()
            if (list.isEmpty()) {
                listContainer.addView(emptyText("Koi purchase nahi hui abhi tak"))
                return@launch
            }
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            for (p in list) {
                listContainer.addView(row(
                    title = p.billNo,
                    subtitle = p.supplierName,
                    amount = p.total,
                    date = fmt.format(Date(p.createdAt)),
                    color = "#EF6C00"
                ))
            }
        }
    }

    private fun row(title: String, subtitle: String, amount: Double, date: String, color: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)

            val topRow = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.HORIZONTAL }
            topRow.addView(TextView(this@HistoryActivity).apply {
                text = title
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            topRow.addView(TextView(this@HistoryActivity).apply {
                text = "Rs %.2f".format(amount)
                setTextColor(Color.parseColor(color))
                textSize = 15f
            })
            addView(topRow)

            addView(TextView(this@HistoryActivity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
            addView(TextView(this@HistoryActivity).apply {
                text = date
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            addView(divider())
        }
    }

    private fun emptyText(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.GRAY)
        setPadding(8, 8, 8, 8)
    }

    private fun roundedBackground(colorHex: String, cornerRadius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(colorHex))
        this.cornerRadius = cornerRadius.toFloat()
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFFEEEEEE.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
    }
}
