package com.grocerypos.v11

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

data class CartLine(val p:Product,val qty:Int)

class MainActivity:AppCompatActivity(){
    private lateinit var db:PosDatabase
    private val cart=mutableListOf<CartLine>()
    private var totalView:TextView?=null
    private var listAdapter:ArrayAdapter<String>?=null

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

    // ---------- Dashboard card grid ----------
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
                text=value;textSize=18f;setTextColor(Color.WHITE)
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
        val root=base("🏪  GROCERY POS")

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

    private fun showPos(){
        val root=base("🛒 POS / NEW BILL")
        val code=styledEditText("Scan / enter barcode").apply{isSingleLine=true}
        val qty=styledEditText("Quantity").apply{setText("1");inputType=2}
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
        root.addView(code);root.addView(qty);root.addView(add)
        root.addView(hold);root.addView(recall);root.addView(list)
        root.addView(totalView);root.addView(save);root.addView(back)
        add.setOnClickListener{
            lifecycleScope.launch{
                val p=db.productDao().find(code.text.toString().trim())
                val q=qty.text.toString().toIntOrNull()?:1
                if(p==null){toast("Product not found");return@launch}
                if(q<=0||q>p.stock){toast("Insufficient stock");return@launch}
                cart.add(CartLine(p,q));refreshCart();code.text.clear()
            }
        }
        hold.setOnClickListener{
            lifecycleScope.launch{
                if(cart.isEmpty()) return@launch
                val payload=cart.joinToString(";"){"${it.p.barcode},${it.qty}"}
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
                        val x=part.split(","); if(x.size==2){
                            val p=db.productDao().find(x[0]); val q=x[1].toIntOrNull()?:1
                            if(p!=null) cart.add(CartLine(p,q))
                        }
                    }
                    db.heldDao().delete(h);refreshCart()
                } else toast("No held bills")
            }
        }
        save.setOnClickListener{
            lifecycleScope.launch{
                if(cart.isEmpty()){toast("Cart empty");return@launch}
                val invoice="INV-${System.currentTimeMillis()}"
                val subtotal=cart.sumOf{it.qty*it.p.salePrice}
                val items=cart.map{SaleItem(invoice=invoice,barcode=it.p.barcode,product=it.p.name,qty=it.qty,unitPrice=it.p.salePrice,cost=it.p.cost,amount=it.qty*it.p.salePrice)}
                db.withTransaction{
                    cart.forEach{line->
                        val changed=db.productDao().decrease(line.p.barcode,line.qty)
                        if(changed==0) throw IllegalStateException("Stock changed; bill not saved")
                    }
                    db.saleDao().sale(Sale(invoice=invoice,subtotal=subtotal,discount=0.0,tax=0.0,total=subtotal,paid=subtotal,paymentMethod="Cash"))
                    db.saleDao().items(items)
                }
                cart.clear();refreshCart();toast("Saved $invoice");showDashboard()
            }
        }
        back.setOnClickListener{showDashboard()}
    }

    private fun refreshCart(){
        listAdapter?.clear()
        cart.forEach{listAdapter?.add("${it.p.name} × ${it.qty} = ${it.qty*it.p.salePrice} PKR")}
        listAdapter?.notifyDataSetChanged()
        totalView?.text="Total: ${cart.sumOf{it.qty*it.p.salePrice}} PKR"
    }

    private fun showProducts(){
        val root=base("📦 PRODUCTS & STOCK")
        val bc=styledEditText("Barcode")
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
        val save=styledButton("SAVE PRODUCT",COLOR_GREEN)
        val back=styledButton("DASHBOARD",COLOR_RED)
        listOf(bc,name,cat,cost,price,stock,reorder,expiry,unitLabel,unitSpinner,unitSize,save,back).forEach(root::addView)
        save.setOnClickListener{
            lifecycleScope.launch{
                db.productDao().upsert(Product(
                    barcode=bc.text.toString(),
                    name=name.text.toString(),
                    category=cat.text.toString(),
                    cost=cost.text.toString().toDoubleOrNull()?:0.0,
                    salePrice=price.text.toString().toDoubleOrNull()?:0.0,
                    stock=stock.text.toString().toIntOrNull()?:0,
                    reorderLevel=reorder.text.toString().toIntOrNull()?:0,
                    expiry=expiry.text.toString(),
                    unit=unitSpinner.selectedItem.toString(),
                    unitSize=unitSize.text.toString().toIntOrNull()?:1
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

    private fun showPurchase(){
        val root=base("🛍️ PURCHASE / STOCK IN")
        val barcode=styledEditText("Barcode")
        val qty=styledEditText("Quantity").apply{inputType=2}
        val cost=styledEditText("Unit cost").apply{inputType=2}
        val unitLabel=TextView(this).apply{text="Unit";setTextColor(COLOR_INK);setPadding(4,16,0,4)}
        val units=arrayOf("pcs","kg","gram","litre","dozen")
        val unitSpinner=Spinner(this).apply{
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,units)
        }
        val save=styledButton("RECEIVE STOCK",COLOR_GREEN)
        val back=styledButton("BACK",COLOR_RED)
        listOf(barcode,qty,cost,unitLabel,unitSpinner,save,back).forEach(root::addView)
        save.setOnClickListener{lifecycleScope.launch{
            val code=barcode.text.toString(); val q=qty.text.toString().toIntOrNull()?:0
            val p=db.productDao().find(code)
            if(p==null){toast("Product not found");return@launch}
            db.productDao().increase(code,q)
            db.productDao().updateUnit(code,unitSpinner.selectedItem.toString())
            db.auditDao().insert(Audit(username="local",action="PURCHASE_STOCK_IN",reference=code,details="Qty=$q Unit=${unitSpinner.selectedItem} Cost=${cost.text}"))
            toast("Stock received");showDashboard()
        }}
        back.setOnClickListener{showDashboard()}
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
        val barcode=styledEditText("Barcode")
        val qty=styledEditText("Quantity").apply{inputType=2}
        val amount=styledEditText("Amount").apply{inputType=2}
        val save=styledButton("SAVE RETURN",COLOR_GREEN)
        val back=styledButton("BACK",COLOR_RED)
        listOf(type,ref,barcode,qty,amount,save,back).forEach(root::addView)
        save.setOnClickListener{lifecycleScope.launch{
            db.returnDao().insert(ReturnLine(reference=ref.text.toString(),type=type.text.toString(),
                barcode=barcode.text.toString(),qty=qty.text.toString().toIntOrNull()?:0,
                amount=amount.text.toString().toDoubleOrNull()?:0.0))
            toast("Return recorded")
        }}
        back.setOnClickListener{showDashboard()}
    }

    private fun showReports(){
        lifecycleScope.launch{
            val sales=db.saleDao().totalSales()
            val expenses=db.expenseDao().total()
            val root=base("📊 REPORTS")
            root.addView(TextView(this@MainActivity).apply{
                text="Total Sales: $sales PKR\nExpenses: $expenses PKR\nGross sales available for P&L: $sales PKR"
                textSize=17f; setTextColor(COLOR_INK); setPadding(0,20,0,20)
            })
            val back=styledButton("BACK",COLOR_RED)
            root.addView(back);back.setOnClickListener{showDashboard()}
        }
    }

    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
