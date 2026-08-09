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
    private lateinit var db:PosDatabase
    private val cart=mutableListOf<CartLine>()
    private var totalView:TextView?=null
    private var listAdapter:ArrayAdapter<String>?=null
    private var posPickedProduct:Product?=null
    private var posDateStr:String=SimpleDateFormat("dd-MM-yyyy",Locale.getDefault()).format(Date())
    private var posPaymentType:String="Cash"
    private var posCustomer:Customer?=null

    private data class PurchaseCartLine(val p:Product,val qty:Int,val unit:String,val rate:Double)
    private val purchaseCart=mutableListOf<PurchaseCartLine>()
    private var purSupplier:Supplier?=null
    private var purDateStr:String=SimpleDateFormat("dd-MM-yyyy",Locale.getDefault()).format(Date())

    private val COLOR_GREEN=Color.parseColor("#0F5C39")
    private val COLOR_GREEN_DARK=Color.parseColor("#0B3A26")
    private val COLOR_GOLD=Color.parseColor("#C9972F")
    private val COLOR_CREAM=Color.parseColor("#F6F4EE")
    private val COLOR_INK=Color.parseColor("#16241D")
    private val COLOR_INK_SOFT=Color.parseColor("#5B6B62")
    private val COLOR_CARD=Color.parseColor("#FFFFFF")
    private val COLOR_RED=Color.parseColor("#C23B2F")
    private val COLOR_BLUE=Color.parseColor("#2B5F8A")

    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        db=PosDatabase.get(this)
        showDashboard()
    }

    private fun roundedBg(color:Int,radius:Float=24f):GradientDrawable{
        return GradientDrawable().apply{ setColor(color); cornerRadius=radius }
    }

    private fun styledButton(text:String,bg:Int=COLOR_GREEN,textColor:Int=Color.WHITE):Button{
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

    private fun styledEditText(hintText:String):EditText{
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

    private fun base(title:String):LinearLayout{
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

    private fun statCard(label:String,value:String,bg:Int):LinearLayout{
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

    private fun menuCard(icon:String,label:String,bg:Int,textColor:Int=Color.WHITE,onClick:()->Unit):LinearLayout{
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

    private fun row(vararg views:android.view.View):LinearLayout{
        return LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutParams=lp
            views.forEach{addView(it)}
        }
    }

    private fun showDashboard(){
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

    // ---------- Pickers ----------
    private fun showProductPicker(title:String,onPick:(Product)->Unit,onCancel:()->Unit){
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

    private fun showSupplierPicker(onPick:(Supplier)->Unit,onCancel:()->Unit){
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

    private fun showCustomerPicker(onPick:(Customer)->Unit,onCancel:()->Unit){
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

    private fun pickDate(currentStr:String,onPicked:(String)->Unit){
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

    // ---------- POS ----------
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
            if(posPickedProduct!=null) setText(posPickedProduct!!.salePrice.toInt().toString())
        }
        root.addView(qty)
        root.addView(rate)

        val add=styledButton("ADD TO CART",COLOR_GOLD,COLOR_INK)
        val hold=styledButton("HOLD BILL",Color.parseColor("#555555"))
        val recall=styledButton("RECALL BILL",Color.parseColor("#555555"))
        val save=styledButton("SAVE BILL",COLOR_GREEN)
        val back=styledButton("DASHBOARD",COLOR_RED)
        val list=ListView(this).apply{
            val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,600)
            layoutParams=lp
        }
        listAdapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,mutableListOf())
        list.adapter=listAdapter
        totalView=TextView(this).apply{text="Total: 0 PKR";textSize=20f;setTextColor(COLOR_INK);setPadding(0,20,0,20)}
        root.addView(add)
        root.addView(hold);root.addView(recall);root.addView(list)
        root.addView(totalView);root.addView(save);root.addView(back)
        refreshCart()

        add.setOnClickListener{
            val p=posPickedProduct
            val q=qty.text.toString().toIntOrNull()?:1
            val r=rate.text.toString().toDoubleOrNull()?:(p?.salePrice?:0.0)
            if(p==null){toast("Select a product first");return@setOnClickListener}
            if(q<=0||q>p.stock){toast("Insufficient stock");return@setOnClickListener}
            cart.add(CartLine(p,q,r));refreshCart()
            posPickedProduct=null
            showPos()
        }
        hold.setOnClickListener{
            lifecycleScope.launch{
                if(cart.isEmpty()) return@launch
                val payload=cart.joinToString(";"){"${it.p.barcode},${it.qty},${it.rate}"}
                db.heldDao().hold(HeldBill("HOLD-${System.currentTimeMillis()}",payload))
                cart.clear();refreshCart();toast("Bill held")
            }
        }
        recall.setOnClickListener{
            lifecycleScope.launch{
                val heldList=db.heldDao().all().first()
                if(heldList.isNotEmpty()){
                    val h=heldList.first()
                    cart.clear()
                    h.payload.split(";").forEach{part->
                        val x=part.split(","); if(x.size>=2){
                            val p=db.productDao().find(x[0]); val q=x[1].toIntOrNull()?:1
                            val r=if(x.size>=3) x[2].toDoubleOrNull()?:(p?.salePrice?:0.0) else (p?.salePrice?:0.0)
                            if(p!=null) cart.add(CartLine(p,q,r))
                        }
                    }
                    db.heldDao().delete(h);refreshCart()
                } else toast("No held bills")
            }
        }
        save.setOnClickListener{
            lifecycleScope.launch{
                if(cart.isEmpty()){toast("Cart empty");return@launch}
                if(posPaymentType=="Credit" && posCustomer==null){toast("Select a customer for credit sale");return@launch}
                val invoice="INV-${System.currentTimeMillis()}"
                val subtotal=cart.sumOf{it.qty*it.rate}
                val items=cart.map{SaleItem(invoice=invoice,barcode=it.p.barcode,product=it.p.name,qty=it.qty,unitPrice=it.rate,cost=it.p.cost,amount=it.qty*it.rate)}
                db.withTransaction{
                    cart.forEach{line->
                        val changed=db.productDao().decrease(line.p.barcode,line.qty)
                        if(changed==0) throw IllegalStateException("Stock changed; bill not saved")
                    }
                    db.saleDao().sale(Sale(invoice=invoice,customerId=posCustomer?.id,subtotal=subtotal,discount=0.0,tax=0.0,total=subtotal,paid=if(posPaymentType=="Cash") subtotal else 0.0,paymentMethod=posPaymentType))
                    db.saleDao().items(items)
                }
                if(posPaymentType=="Credit" && posCustomer!=null){
                    db.customerDao().addBalance(posCustomer!!.id,subtotal)
                }
                cart.clear();refreshCart();toast("Saved $invoice")
                posCustomer=null;posPaymentType="Cash"
                showDashboard()
            }
        }
        back.setOnClickListener{posPickedProduct=null;posCustomer=null;posPaymentType="Cash";showDashboard()}
    }

    private fun refreshCart(){
        listAdapter?.clear()
        cart.forEach{listAdapter?.add("${it.p.name} × ${it.qty} @ ${it.rate.toInt()} = ${it.qty*it.rate} PKR")}
        listAdapter?.notifyDataSetChanged()
        totalView?.text="Total: ${cart.sumOf{it.qty*it.rate}} PKR"
    }

    // ---------- Products ----------
    private fun showProducts(){
        val root=base("📦 PRODUCTS & STOCK")
        val name=styledEditText("Product name")
        val cat=styledEditText("Category")
        val cost=styledEditText("Purchase cost").apply{inputType=2}
        val price=styledEditText("Sale price").apply{inputType=2}
        val stock=styledEditText("Opening stock").apply{inputType=2}
        val reorder=styledEditText("Reorder level").apply{inputType=2}
        val expiry=styledEditText("Expiry YYYY-MM-DD")
        val unitLabel=TextView(this).apply{text="Unit";setTextColor(COLOR_INK);setPadding(4,16,0,4)}
        val units=arrayOf("pcs","kg","gram","litre","dozen","bag","peti")
        val unitSpinner=Spinner(this).apply{
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,units)
        }
        val unitSize=styledEditText("Pieces per unit (e.g. Dozen=12)").apply{inputType=2;setText("1")}
        val unitNote=styledEditText("Unit contains (e.g. 1 bag = 50 kg)")
        val save=styledButton("SAVE PRODUCT",COLOR_GREEN)
        val back=styledButton("DASHBOARD",COLOR_RED)
        listOf(name,cat,cost,price,stock,reorder,expiry,unitLabel,unitSpinner,unitSize,unitNote,save,back).forEach(root::addView)
        save.setOnClickListener{
            lifecycleScope.launch{
                if(name.text.toString().isBlank()){toast("Enter product name");return@launch}
                db.productDao().upsert(Product(
                    barcode="P${System.currentTimeMillis()}",
                    name=name.text.toString(),
                    category=cat.text.toString(),
                    cost=cost.text.toString().toDoubleOrNull()?:0.0,
                    salePrice=price.text.toString().toDoubleOrNull()?:0.0,
                    stock=stock.text.toString().toIntOrNull()?:0,
                    reorderLevel=reorder.text.toString().toIntOrNull()?:0,
                    expiry=expiry.text.toString(),
                    unit=unitSpinner.selectedItem.toString(),
                    unitSize=unitSize.text.toString().toIntOrNull()?:1,
                    unitNote=unitNote.text.toString()
                ))
                toast("Product saved")
            }
        }
        back.setOnClickListener{showDashboard()}
    }

    private fun showCustomers(){
        val root=base("👤 CUSTOMERS / UDHAR")
        val name=styledEditText("Customer name")
        val phone=styledEditText("Phone")
        val limit=styledEditText("Credit limit").apply{inputType=2}
        val save=styledButton("SAVE CUSTOMER",COLOR_GREEN)
        val back=styledButton("BACK",COLOR_RED)
        listOf(name,phone,limit,save,back).forEach(root::addView)
        save.setOnClickListener{lifecycleScope.launch{
            db.customerDao().insert(Customer(name=name.text.toString(),phone=phone.text.toString(),creditLimit=limit.text.toString().toDoubleOrNull()?:0.0))
            toast("Customer saved")
        }}
        back.setOnClickListener{showDashboard()}
    }

    private fun showSuppliers(){
        val root=base("🏢 SUPPLIERS / PAYABLES")
        val name=styledEditText("Supplier name")
        val phone=styledEditText("Phone")
        val save=styledButton("SAVE SUPPLIER",COLOR_GREEN)
        val back=styledButton("BACK",COLOR_RED)
        listOf(name,phone,save,back).forEach(root::addView)
        save.setOnClickListener{lifecycleScope.launch{
            db.supplierDao().insert(Supplier(name=name.text.toString(),phone=phone.text.toString()))
            toast("Supplier saved")
        }}
        back.setOnClickListener{showDashboard()}
    }

    private fun showExpense(){
        val root=base("💵 EXPENSE")
        val cat=styledEditText("Category")
        val desc=styledEditText("Description")
        val amt=styledEditText("Amount").apply{inputType=2}
        val save=styledButton("SAVE EXPENSE",COLOR_GREEN)
        val back=styledButton("BACK",COLOR_RED)
        listOf(cat,desc,amt,save,back).forEach(root::addView)
        save.setOnClickListener{lifecycleScope.launch{
            db.expenseDao().insert(Expense(category=cat.text.toString(),description=desc.text.toString(),amount=amt.text.toString().toDoubleOrNull()?:0.0))
            toast("Expense saved")
        }}
        back.setOnClickListener{showDashboard()}
    }

    // ---------- Purchase ----------
    private fun showPurchaseAddItem(){
        val root=base("Add Item")
        val pickBtn=styledButton(
            if(posPickedProduct!=null) "✅ ${posPickedProduct!!.name}" else "📋 SELECT PRODUCT",
            if(posPickedProduct!=null) COLOR_GREEN else COLOR_GOLD,
            if(posPickedProduct!=null) Color.WHITE else COLOR_INK
        )
        val qty=styledEditText("Quantity").apply{inputType=2}
        val unitLabel=TextView(this).apply{text="Unit";setTextColor(COLOR_INK);setPadding(4,16,0,4)}
        val units=arrayOf("pcs","kg","gram","litre","dozen","bag","peti")
        val unitSpinner=Spinner(this).apply{
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,units)
        }
        val rate=styledEditText("Rate (per unit)").apply{inputType=2}
        val addBtn=styledButton("ADD TO LIST",COLOR_GREEN)
        val back=styledButton("CANCEL",COLOR_RED)
        listOf(pickBtn,qty,unitLabel,unitSpinner,rate,addBtn,back).forEach(root::addView)
        pickBtn.setOnClickListener{
            showProductPicker("Select Product",{p->posPickedProduct=p;showPurchaseAddItem()},{showPurchaseAddItem()})
        }
        addBtn.setOnClickListener{
            val p=posPickedProduct
            if(p==null){toast("Select a product first");return@setOnClickListener}
            val q=qty.text.toString().toIntOrNull()?:0
            val r=rate.text.toString().toDoubleOrNull()?:0.0
            if(q<=0){toast("Enter valid quantity");return@setOnClickListener}
            purchaseCart.add(PurchaseCartLine(p,q,unitSpinner.selectedItem.toString(),r))
            posPickedProduct=null
            showPurchase()
        }
        back.setOnClickListener{posPickedProduct=null;showPurchase()}
    }

    private fun showPurchase(){
        val root=base("🛍️ PURCHASE / STOCK IN")

        val dateField=styledEditText("Date").apply{setText(purDateStr);isFocusable=false;isClickable=true}
        dateField.setOnClickListener{
            pickDate(purDateStr){picked->purDateStr=picked;showPurchase()}
        }
        val supplierBtn=styledButton(
            if(purSupplier!=null) "🏢 ${purSupplier!!.name}" else "📋 SELECT SUPPLIER",
            if(purSupplier!=null) COLOR_GREEN else COLOR_GOLD,
            if(purSupplier!=null) Color.WHITE else COLOR_INK
        )
        val addItemBtn=styledButton("➕ ADD ITEM",COLOR_BLUE)
        root.addView(dateField)
        root.addView(supplierBtn)
        root.addView(addItemBtn)

        val listBox=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0,20,0,0);layoutParams=lp
        }
        root.addView(listBox)

        val totalTextView=TextView(this).apply{
            text="Total: ${purchaseCart.sumOf{it.qty*it.rate}.toInt()} PKR"
            textSize=18f;setTextColor(COLOR_INK);setPadding(0,20,0,20)
            setTypeface(typeface,android.graphics.Typeface.BOLD)
        }
        root.addView(totalTextView)

        val save=styledButton("SAVE PURCHASE",COLOR_GREEN)
        val back=styledButton("DASHBOARD",COLOR_RED)
        root.addView(save);root.addView(back)

        if(purchaseCart.isEmpty()){
            listBox.addView(TextView(this).apply{
                text="No items added yet";setTextColor(COLOR_INK_SOFT);setPadding(6,10,6,10)
            })
        }
        purchaseCart.forEachIndexed{idx,line->
            listBox.addView(LinearLayout(this).apply{
                orientation=LinearLayout.HORIZONTAL
                background=roundedBg(COLOR_CARD,14f)
                setPadding(24,20,24,20)
                val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0,0,0,8);layoutParams=lp;elevation=1f
                addView(LinearLayout(this@MainActivity).apply{
                    orientation=LinearLayout.VERTICAL
                    layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
                    addView(TextView(this@MainActivity).apply{
                        text=line.p.name;textSize=14.5f;setTextColor(COLOR_INK)
                        setTypeface(typeface,android.graphics.Typeface.BOLD)
                    })
                    addView(TextView(this@MainActivity).apply{
                        text="${line.qty} ${line.unit} × ${line.rate.toInt()} = ${(line.qty*line.rate).toInt()} PKR"
                        textSize=12.5f;setTextColor(COLOR_INK_SOFT)
                    })
                })
                addView(TextView(this@MainActivity).apply{
                    text="✕";textSize=18f;setTextColor(COLOR_RED)
                    setPadding(20,0,4,0)
                    isClickable=true
                    setOnClickListener{purchaseCart.removeAt(idx);showPurchase()}
                })
            })
        }

        supplierBtn.setOnClickListener{
            showSupplierPicker({s->purSupplier=s;showPurchase()},{showPurchase()})
        }
        addItemBtn.setOnClickListener{
            showPurchaseAddItem()
        }
        save.setOnClickListener{
            lifecycleScope.launch{
                if(purchaseCart.isEmpty()){toast("Add at least one item");return@launch}
                val billNo="PUR-${System.currentTimeMillis()}"
                val total=purchaseCart.sumOf{it.qty*it.rate}
                db.purchaseDao().purchase(Purchase(billNo=billNo,supplierId=purSupplier?.id,total=total,paid=0.0))
                db.purchaseDao().items(purchaseCart.map{
                    PurchaseItem(billNo=billNo,barcode=it.p.barcode,qty=it.qty,unitCost=it.rate,amount=it.qty*it.rate)
                })
                purchaseCart.forEach{
                    db.productDao().increase(it.p.barcode,it.qty)
                    db.productDao().updateUnit(it.p.barcode,it.unit)
                }
                db.auditDao().insert(Audit(username="local",action="PURCHASE_SAVED",reference=billNo,details="Items=${purchaseCart.size} Total=$total"))
                toast("Purchase saved: $billNo")
                purchaseCart.clear();purSupplier=null
                showDashboard()
            }
        }
        back.setOnClickListener{purchaseCart.clear();purSupplier=null;showDashboard()}
    }

    private fun showPayments(){
        val root=base("💳 PAYMENTS")
        val ref=styledEditText("Reference")
        val party=styledEditText("Party type: Customer/Supplier")
        val amount=styledEditText("Amount").apply{inputType=2}
        val method=styledEditText("Cash/Card/JazzCash/Easypaisa")
        val save=styledButton("SAVE PAYMENT",COLOR_GREEN)
        val back=styledButton("BACK",COLOR_RED)
        listOf(ref,party,amount,method,save,back).forEach(root::addView)
        save.setOnClickListener{lifecycleScope.launch{
            db.paymentDao().insert(Payment(reference=ref.text.toString(),partyType=party.text.toString(),
                partyId=null,amount=amount.text.toString().toDoubleOrNull()?:0.0,method=method.text.toString()))
            toast("Payment saved")
        }}
        back.setOnClickListener{showDashboard()}
    }

    private fun showReturns(){
        val root=base("↩️ RETURNS")
        val type=styledEditText("Sale Return / Purchase Return")
        val ref=styledEditText("Invoice / Bill reference")
        val qty=styledEditText("Quantity").apply{inputType=2}
        val amount=styledEditText("Amount").apply{inputType=2}
        val pickBtn=styledButton(
            if(posPickedProduct!=null) "✅ ${posPickedProduct!!.name}" else "📋 SELECT PRODUCT",
            if(posPickedProduct!=null) COLOR_GREEN else COLOR_GOLD,
            if(posPickedProduct!=null) Color.WHITE else COLOR_INK
        )
        val save=styledButton("SAVE RETURN",COLOR_GREEN)
        val back=styledButton("BACK",COLOR_RED)
        listOf(type,ref,pickBtn,qty,amount,save,back).forEach(root::addView)
        pickBtn.setOnClickListener{
            showProductPicker("Select Product",{p->posPickedProduct=p;showReturns()},{showReturns()})
        }
        save.setOnClickListener{lifecycleScope.launch{
            val p=posPickedProduct
            if(p==null){toast("Select a product first");return@launch}
            db.returnDao().insert(ReturnLine(reference=ref.text.toString(),type=type.text.toString(),
                barcode=p.barcode,qty=qty.text.toString().toIntOrNull()?:0,
                amount=amount.text.toString().toDoubleOrNull()?:0.0))
            toast("Return recorded")
            posPickedProduct=null
            showDashboard()
        }}
        back.setOnClickListener{posPickedProduct=null;showDashboard()}
    }

    // ---------- Reports ----------
    private fun startOfDay(daysAgo:Int):Long{
        val cal=Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR,-daysAgo)
        cal.set(Calendar.HOUR_OF_DAY,0);cal.set(Calendar.MINUTE,0)
        cal.set(Calendar.SECOND,0);cal.set(Calendar.MILLISECOND,0)
        return cal.timeInMillis
    }

    private fun showReports(){
        val root=base("📊 REPORTS")
        val chipsRow=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0,0,0,16)
            layoutParams=lp
        }
        fun chip(label:String):TextView=TextView(this).apply{
            text=label;textSize=12.5f;setTextColor(Color.WHITE)
            background=roundedBg(COLOR_INK_SOFT,30f)
            setPadding(30,18,30,18)
            val lp=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            lp.setMargins(4,0,4,0)
            layoutParams=lp
            gravity=Gravity.CENTER
        }
        val chipToday=chip("Today")
        val chipWeek=chip("7 Days")
        val chipMonth=chip("30 Days")
        val chipAll=chip("All Time")
        listOf(chipToday,chipWeek,chipMonth,chipAll).forEach{chipsRow.addView(it)}
        root.addView(chipsRow)

        val statsBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(statsBox)

        val chartTitle=TextView(this).apply{
            text="Last 7 Days Sales";textSize=13f;setTextColor(COLOR_INK_SOFT)
            setTypeface(typeface,android.graphics.Typeface.BOLD)
            setPadding(4,24,0,10)
        }
        root.addView(chartTitle)
        val chartBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(chartBox)

        val topTitle=TextView(this).apply{
            text="Best Selling Products";textSize=13f;setTextColor(COLOR_INK_SOFT)
            setTypeface(typeface,android.graphics.Typeface.BOLD)
            setPadding(4,24,0,10)
        }
        root.addView(topTitle)
        val topBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(topBox)

        val back=styledButton("BACK",COLOR_RED)
        root.addView(back)
        back.setOnClickListener{showDashboard()}

        fun loadRange(start:Long,end:Long){
            lifecycleScope.launch{
                val totalSales=db.saleDao().totalSalesBetween(start,end)
                val totalExpenses=db.expenseDao().totalBetween(start,end)
                val count=db.saleDao().countBetween(start,end)
                statsBox.removeAllViews()
                statsBox.addView(statCard("SALES","${totalSales.toInt()} PKR",COLOR_GREEN))
                statsBox.addView(statCard("EXPENSES","${totalExpenses.toInt()} PKR",COLOR_BLUE))
                statsBox.addView(statCard("NET PROFIT","${(totalSales-totalExpenses).toInt()} PKR",COLOR_GOLD))
                statsBox.addView(statCard("TRANSACTIONS","$count",COLOR_GREEN_DARK))

                val top=db.saleDao().topProducts(start,end)
                topBox.removeAllViews()
                if(top.isEmpty()){
                    topBox.addView(TextView(this@MainActivity).apply{text="No sales in this range";setTextColor(COLOR_INK_SOFT)})
                } else {
                    top.forEach{tp->
                        topBox.addView(LinearLayout(this@MainActivity).apply{
                            orientation=LinearLayout.HORIZONTAL
                            background=roundedBg(COLOR_CARD,14f)
                            setPadding(24,20,24,20)
                            val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
                            lp.setMargins(0,0,0,8);layoutParams=lp;elevation=1f
                            addView(TextView(this@MainActivity).apply{
                                text=tp.product;setTextColor(COLOR_INK);textSize=14f
                                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
                            })
                            addView(TextView(this@MainActivity).apply{
                                text="${tp.totalQty} sold";setTextColor(COLOR_GREEN);textSize=14f
                                setTypeface(typeface,android.graphics.Typeface.BOLD)
                            })
                        })
                    }
                }
            }
        }

        fun setActiveChip(active:TextView){
            listOf(chipToday,chipWeek,chipMonth,chipAll).forEach{
                it.background=roundedBg(if(it==active) COLOR_GREEN else COLOR_INK_SOFT,30f)
            }
        }

        chipToday.setOnClickListener{setActiveChip(chipToday);loadRange(startOfDay(0),System.currentTimeMillis())}
        chipWeek.setOnClickListener{setActiveChip(chipWeek);loadRange(startOfDay(7),System.currentTimeMillis())}
        chipMonth.setOnClickListener{setActiveChip(chipMonth);loadRange(startOfDay(30),System.currentTimeMillis())}
        chipAll.setOnClickListener{setActiveChip(chipAll);loadRange(0L,System.currentTimeMillis())}

        setActiveChip(chipWeek)
        loadRange(startOfDay(7),System.currentTimeMillis())

        lifecycleScope.launch{
            val daily=db.saleDao().dailySales(startOfDay(6),System.currentTimeMillis())
            val maxVal=daily.maxOfOrNull{it.total}?:1.0
            chartBox.removeAllViews()
            if(daily.isEmpty()){
                chartBox.addView(TextView(this@MainActivity).apply{text="No data yet";setTextColor(COLOR_INK_SOFT)})
            } else {
                daily.forEach{d->
                    val pct=if(maxVal>0) (d.total/maxVal) else 0.0
                    chartBox.addView(LinearLayout(this@MainActivity).apply{
                        orientation=LinearLayout.VERTICAL
                        setPadding(0,6,0,6)
                        addView(TextView(this@MainActivity).apply{
                            text="${d.day}  —  ${d.total.toInt()} PKR"
                            textSize=11.5f;setTextColor(COLOR_INK_SOFT)
                        })
                        addView(android.view.View(this@MainActivity).apply{
                            background=roundedBg(COLOR_GOLD,8f)
                            val widthPx=(280*pct).toInt().coerceAtLeast(6)
                            layoutParams=LinearLayout.LayoutParams(widthPx,20)
                        })
                    })
                }
            }
        }
    }

    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
