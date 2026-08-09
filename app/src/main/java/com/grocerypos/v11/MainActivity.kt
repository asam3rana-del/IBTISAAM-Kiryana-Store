package com.grocerypos.v11

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CartLine(val p:Product,val qty:Int,val rate:Double)

class MainActivity:AppCompatActivity(){
    internal lateinit var db:PosDatabase
    private val cart=mutableListOf<CartLine>()
    private var totalView:TextView?=null
    private var listAdapter:ArrayAdapter<String>?=null
    internal var posPickedProduct:Product?=null
    private var posDateStr:String=SimpleDateFormat("dd-MM-yyyy",Locale.getDefault()).format(Date())
    private var posPaymentType:String="Cash"
    private var posCustomer:Customer?=null

    internal data class PurchaseCartLine(val p:Product,val qty:Int,val unit:String,val rate:Double)
    internal val purchaseCart=mutableListOf<PurchaseCartLine>()
    internal var purSupplier:Supplier?=null
    internal var purDateStr:String=SimpleDateFormat("dd-MM-yyyy",Locale.getDefault()).format(Date())

    internal val COLOR_GREEN=Color.parseColor("#0F5C39")
    internal val COLOR_GREEN_DARK=Color.parseColor("#0B3A26")
    internal val COLOR_GOLD=Color.parseColor("#C9972F")
    internal val COLOR_CREAM=Color.parseColor("#F6F4EE")
    internal val COLOR_INK=Color.parseColor("#16241D")
    internal val COLOR_INK_SOFT=Color.parseColor("#5B6B62")
    internal val COLOR_CARD=Color.parseColor("#FFFFFF")
    internal val COLOR_RED=Color.parseColor("#C23B2F")
    internal val COLOR_BLUE=Color.parseColor("#2B5F8A")

    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        db=PosDatabase.get(this)
        showDashboard()
    }

    internal fun roundedBg(color:Int,radius:Float=24f):GradientDrawable{
        return GradientDrawable().apply{ setColor(color); cornerRadius=radius }
    }

    internal fun styledButton(text:String,bg:Int=COLOR_GREEN,textColor:Int=Color.WHITE):Button{
        return Button(this).apply{
            this.text=text
            setTextColor(textColor)
            textSize=15f
            background=roundedBg(bg)
            setPadding(28,28,28,28)
            isAllCaps=false
            val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0,10,0,10)
            layoutParams=lp
            elevation=3f
        }
    }

    internal fun styledEditText(hintText:String):EditText{
        return EditText(this).apply{
            hint=hintText
            setPadding(28,24,28,24)
            background=roundedBg(Color.parseColor("#EFEDE4"),16f)
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_INK_SOFT)
            val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0,8,0,8)
            layoutParams=lp
        }
    }

    internal fun base(title:String):LinearLayout{
        val scroll=ScrollView(this)
        val outer=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(COLOR_CREAM)
        }
        scroll.addView(outer)
        val header=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(COLOR_GREEN_DARK)
            setPadding(30,60,30,36)
        }
        header.addView(TextView(this).apply{
            text=title; textSize=22f; setTextColor(Color.WHITE)
        })
        outer.addView(header)
        val body=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(24,24,24,24)
        }
        outer.addView(body)
        setContentView(scroll)
        return body
    }

    internal fun statCard(label:String,value:String,bg:Int):LinearLayout{
        return LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            background=roundedBg(bg,20f)
            setPadding(26,26,26,26)
            val lp=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            lp.setMargins(8,8,8,8)
            layoutParams=lp
            elevation=3f
            addView(TextView(this@MainActivity).apply{
                text=label;textSize=12f;setTextColor(Color.parseColor("#E7F2EC"))
            })
            addView(TextView(this@MainActivity).apply{
                text=value;textSize=16f;setTextColor(Color.WHITE)
                setPadding(0,10,0,0)
                setTypeface(typeface,android.graphics.Typeface.BOLD)
            })
        }
    }

    internal fun menuCard(icon:String,label:String,bg:Int,textColor:Int=Color.WHITE,onClick:()->Unit):LinearLayout{
        return LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            background=roundedBg(bg,22f)
            setPadding(20,36,20,28)
            val lp=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            lp.setMargins(8,8,8,8)
            layoutParams=lp
            elevation=3f
            isClickable=true
            isFocusable=true
            addView(TextView(this@MainActivity).apply{
                text=icon;textSize=28f;gravity=Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply{
                text=label;textSize=12.5f;setTextColor(textColor);gravity=Gravity.CENTER
                setPadding(0,14,0,0)
                setTypeface(typeface,android.graphics.Typeface.BOLD)
            })
            setOnClickListener{onClick()}
        }
    }

    internal fun row(vararg views:android.view.View):LinearLayout{
        return LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutParams=lp
            views.forEach{addView(it)}
        }
    }

    internal fun showDashboard(){
        val root=base("🏪  IBTISAAM TRADERS POS")

        val statsRow1=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0,0,0,4)
            layoutParams=lp
        }
        val statsRow2=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0,0,0,20)
            layoutParams=lp
        }
        val cardSales=statCard("TOTAL SALES","...",COLOR_GREEN)
        val cardExpense=statCard("EXPENSES","...",COLOR_BLUE)
        val cardProducts=statCard("PRODUCTS","...",COLOR_GOLD)
        val cardLow=statCard("LOW STOCK","...",COLOR_RED)
        statsRow1.addView(cardSales);statsRow1.addView(cardExpense)
        statsRow2.addView(cardProducts);statsRow2.addView(cardLow)
        root.addView(statsRow1)
        root.addView(statsRow2)

        lifecycleScope.launch{
            val totalSales=db.saleDao().totalSales()
            val totalExpenses=db.expenseDao().total()
            val products=db.productDao().all().first()
            val lowStock=products.count{it.stock<=it.reorderLevel}
            (cardSales.getChildAt(1) as TextView).text="${totalSales.toInt()} PKR"
            (cardExpense.getChildAt(1) as TextView).text="${totalExpenses.toInt()} PKR"
            (cardProducts.getChildAt(1) as TextView).text="${products.size}"
            (cardLow.getChildAt(1) as TextView).text="$lowStock"
        }

        root.addView(TextView(this).apply{
            text="MENU";textSize=12f;setTextColor(COLOR_INK_SOFT)
            setTypeface(typeface,android.graphics.Typeface.BOLD)
            setPadding(4,0,0,10)
        })

        val posCard=menuCard("🛒","POS / BILL",COLOR_GREEN){showPos()}
        val productsCard=menuCard("📦","PRODUCTS",COLOR_GOLD,COLOR_INK){showProducts()}
        val customersCard=menuCard("👤","CUSTOMERS",COLOR_BLUE){showCustomers()}
        val suppliersCard=menuCard("🏢","SUPPLIERS",Color.parseColor("#8A6D3B")){showSuppliers()}
        val reportsCard=menuCard("📊","REPORTS",COLOR_GREEN_DARK){showReports()}
        val expenseCard=menuCard("💵","EXPENSE",COLOR_GOLD,COLOR_INK){showExpense()}
        val purchaseCard=menuCard("🛍️","PURCHASE",COLOR_GREEN){showPurchase()}
        val paymentsCard=menuCard("💳","PAYMENTS",COLOR_BLUE){showPayments()}
        val returnsCard=menuCard("↩️","RETURNS",COLOR_RED){showReturns()}
        val settingsCard=menuCard("⚙️","SETTINGS",Color.parseColor("#555555")){toast("Coming soon")}

        root.addView(row(posCard,productsCard))
        root.addView(row(customersCard,suppliersCard))
        root.addView(row(reportsCard,expenseCard))
        root.addView(row(purchaseCard,paymentsCard))
        root.addView(row(returnsCard,settingsCard))
    }

    internal fun showProductPicker(title:String,onPick:(Product)->Unit,onCancel:()->Unit){
        val root=base(title)
        val listBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(listBox)
        val back=styledButton("CANCEL",COLOR_RED)
        root.addView(back)
        back.setOnClickListener{onCancel()}
        lifecycleScope.launch{
            val products=db.productDao().all().first()
            listBox.removeAllViews()
            if(products.isEmpty()){
                listBox.addView(TextView(this@MainActivity).apply{
                    text="No products yet — add one first";setTextColor(COLOR_INK_SOFT)
                })
            }
            products.forEach{p->
                listBox.addView(LinearLayout(this@MainActivity).apply{
                    orientation=LinearLayout.VERTICAL
                    background=roundedBg(COLOR_CARD,14f)
                    setPadding(24,20,24,20)
                    val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(0,0,0,8);layoutParams=lp;elevation=1f
                    isClickable=true;isFocusable=true
                    addView(TextView(this@MainActivity).apply{
                        text=p.name;textSize=15f;setTextColor(COLOR_INK)
                        setTypeface(typeface,android.graphics.Typeface.BOLD)
                    })
                    addView(TextView(this@MainActivity).apply{
                        text="${p.salePrice.toInt()} PKR  •  Stock: ${p.stock} ${p.unit}"
                        textSize=12.5f;setTextColor(COLOR_INK_SOFT)
                    })
                    setOnClickListener{onPick(p)}
                })
            }
        }
    }

    internal fun showSupplierPicker(onPick:(Supplier)->Unit,onCancel:()->Unit){
        val root=base("Select Supplier")
        val listBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(listBox)
        val back=styledButton("CANCEL",COLOR_RED)
        root.addView(back)
        back.setOnClickListener{onCancel()}
        lifecycleScope.launch{
            val suppliers=db.supplierDao().all().first()
            listBox.removeAllViews()
            if(suppliers.isEmpty()){
                listBox.addView(TextView(this@MainActivity).apply{
                    text="No suppliers yet — add one from Suppliers screen";setTextColor(COLOR_INK_SOFT)
                })
            }
            suppliers.forEach{s->
                listBox.addView(LinearLayout(this@MainActivity).apply{
                    orientation=LinearLayout.VERTICAL
                    background=roundedBg(COLOR_CARD,14f)
                    setPadding(24,20,24,20)
                    val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(0,0,0,8);layoutParams=lp;elevation=1f
                    isClickable=true;isFocusable=true
                    addView(TextView(this@MainActivity).apply{
                        text=s.name;textSize=15f;setTextColor(COLOR_INK)
                        setTypeface(typeface,android.graphics.Typeface.BOLD)
                    })
                    addView(TextView(this@MainActivity).apply{
                        text=s.phone;textSize=12.5f;setTextColor(COLOR_INK_SOFT)
                    })
                    setOnClickListener{onPick(s)}
                })
            }
        }
    }

    internal fun showCustomerPicker(onPick:(Customer)->Unit,onCancel:()->Unit){
        val root=base("Select Customer")
        val listBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(listBox)
        val back=styledButton("CANCEL",COLOR_RED)
        root.addView(back)
        back.setOnClickListener{onCancel()}
        lifecycleScope.launch{
            val customers=db.customerDao().all().first()
            listBox.removeAllViews()
            if(customers.isEmpty()){
                listBox.addView(TextView(this@MainActivity).apply{
                    text="No customers yet — add one from Customers screen";setTextColor(COLOR_INK_SOFT)
                })
            }
            customers.forEach{c->
                listBox.addView(LinearLayout(this@MainActivity).apply{
                    orientation=LinearLayout.VERTICAL
                    background=roundedBg(COLOR_CARD,14f)
                    setPadding(24,20,24,20)
                    val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.setMargins(0,0,0,8);layoutParams=lp;elevation=1f
                    isClickable=true;isFocusable=true
                    addView(TextView(this@MainActivity).apply{
                        text=c.name;textSize=15f;setTextColor(COLOR_INK)
                        setTypeface(typeface,android.graphics.Typeface.BOLD)
                    })
                    addView(TextView(this@MainActivity).apply{
                        text="${c.phone}  •  Balance: ${c.balance.toInt()} PKR"
                        textSize=12.5f;setTextColor(COLOR_INK_SOFT)
                    })
                    setOnClickListener{onPick(c)}
                })
            }
        }
    }

    internal fun pickDate(currentStr:String,onPicked:(String)->Unit){
        val cal=Calendar.getInstance()
        try{
            val parts=currentStr.split("-")
            if(parts.size==3){
                cal.set(parts[2].toInt(),parts[1].toInt()-1,parts[0].toInt())
            }
        }catch(e:Exception){}
        DatePickerDialog(this,{_,year,month,day->
            val picked=String.format("%02d-%02d-%04d",day,month+1,year)
            onPicked(picked)
        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    internal val defaultUnits=listOf("pcs","kg","gram","litre","dozen","bag","peti")

    internal fun buildUnitSpinner(initial:String=""):Spinner{
        val items=(defaultUnits+listOf("+ Add New Unit")).toMutableList()
        val spinner=Spinner(this).apply{
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,items)
            if(initial.isNotBlank() && items.contains(initial)) setSelection(items.indexOf(initial))
        }
        lifecycleScope.launch{
            val custom=db.unitDao().all().first().map{it.name}
            if(custom.isNotEmpty()){
                val merged=(defaultUnits+custom).distinct()+listOf("+ Add New Unit")
                spinner.adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,merged)
                if(initial.isNotBlank() && merged.contains(initial)) spinner.setSelection(merged.indexOf(initial))
            }
        }
        spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:AdapterView<*>?,view:android.view.View?,position:Int,id:Long){
                val selected=spinner.selectedItem?.toString()?:""
                if(selected=="+ Add New Unit"){
                    val input=EditText(this@MainActivity).apply{hint="Unit name e.g. carton"}
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Add New Unit")
                        .setView(input)
                        .setPositiveButton("Add"){_,_->
                            val newUnit=input.text.toString().trim()
                            if(newUnit.isNotBlank()){
                                lifecycleScope.launch{
                                    db.unitDao().insert(UnitType(newUnit))
                                    toast("Unit added: $newUnit — reopen this screen to use it")
                                }
                            }
                        }
                        .setNegativeButton("Cancel",null)
                        .show()
                }
            }
            override fun onNothingSelected(parent:AdapterView<*>?){}
        }
        return spinner
    }

    private fun showPos(){
        val root=base("🛒 POS / NEW BILL")

        val dateField=styledEditText("Date").apply{
            setText(posDateStr);isFocusable=false;isClickable=true
        }
        root.addView(dateField)
        dateField.setOnClickListener{
            pickDate(posDateStr){picked->posDateStr=picked;showPos()}
        }

        val payRow=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0,8,0,8);layoutParams=lp
        }
        fun payChip(label:String):TextView=TextView(this).apply{
            text=label;textSize=13f;setTextColor(Color.WHITE)
            background=roundedBg(if(posPaymentType==label) COLOR_GREEN else COLOR_INK_SOFT,30f)
            setPadding(30,20,30,20)
            gravity=Gravity.CENTER
            val lp=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            lp.setMargins(4,0,4,0);layoutParams=lp
        }
        val cashChip=payChip("Cash")
        val creditChip=payChip("Credit")
        payRow.addView(cashChip);payRow.addView(creditChip)
        root.addView(payRow)
        cashChip.setOnClickListener{posPaymentType="Cash";posCustomer=null;showPos()}
        creditChip.setOnClickListener{posPaymentType="Credit";showPos()}

        if(posPaymentType=="Credit"){
            val custBtn=styledButton(
                if(posCustomer!=null) "👤 ${posCustomer!!.name}" else "📋 SELECT CUSTOMER",
                if(posCustomer!=null) COLOR_GREEN else COLOR_GOLD,
                if(posCustomer!=null) Color.WHITE else COLOR_INK
            )
            root.addView(custBtn)
            custBtn.setOnClickListener{
                showCustomerPicker({c->posCustomer=c;showPos()},{showPos()})
            }
        }

        val pickBtn=styledButton(
            if(posPickedProduct!=null) "✅ ${posPickedProduct!!.name}" else "📋 SELECT PRODUCT",
            if(posPickedProduct!=null) COLOR_GREEN else COLOR_GOLD,
            if(posPickedProduct!=null) Color.WHITE else COLOR_INK
        )
        root.addView(pickBtn)
        pickBtn.setOnClickListener{
            showProductPicker("Select Product",{p->posPickedProduct=p;showPos()},{showPos()})
        }

        val qty=styledEditText("Quantity").apply{setText("1");inputType=2}
        val rate=styledEditText("Rate (editable)").apply{
            inputType=8194
            if(posPickedProduct!=null) setText(posPickedProduct!!.salePrice.toInt().toSt
