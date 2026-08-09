package com.grocerypos.v11

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import kotlinx.coroutines.launch

data class CartLine(val p:Product,val qty:Int)

class MainActivity:AppCompatActivity(){
    private lateinit var db:PosDatabase
    private val cart=mutableListOf<CartLine>()
    private var totalView:TextView?=null
    private var listAdapter:ArrayAdapter<String>?=null

    private val COLOR_GREEN=Color.parseColor("#0F5C39")
    private val COLOR_GOLD=Color.parseColor("#C9972F")
    private val COLOR_CREAM=Color.parseColor("#F6F4EE")
    private val COLOR_INK=Color.parseColor("#16241D")
    private val COLOR_CARD=Color.parseColor("#FFFFFF")
    private val COLOR_RED=Color.parseColor("#C23B2F")

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
            elevation=2f
        }
    }

    private fun styledEditText(hintText:String):EditText{
        return EditText(this).apply{
            hint=hintText
            setPadding(28,24,28,24)
            background=roundedBg(Color.parseColor("#EFEDE4"),16f)
            setTextColor(COLOR_INK)
            val lp=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0,8,0,8)
            layoutParams=lp
        }
    }

    private fun base(title:String):LinearLayout{
        val outer=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(COLOR_CREAM)
        }
        val header=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(COLOR_GREEN)
            setPadding(30,50,30,30)
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
        return body
    }

    private fun showDashboard(){
        val root=base("🏪 GROCERY POS")
        val pos=styledButton("🛒  POS / NEW BILL",COLOR_GREEN)
        val products=styledButton("📦  PRODUCTS & STOCK",COLOR_GOLD,COLOR_INK)
        val customers=styledButton("👤  CUSTOMERS / UDHAR",COLOR_GREEN)
        val suppliers=styledButton("🏢  SUPPLIERS",COLOR_GOLD,COLOR_INK)
        val reports=styledButton("📊  REPORTS",COLOR_GREEN)
        val expense=styledButton("💵  EXPENSE",COLOR_GOLD,COLOR_INK)
        val purchase=styledButton("🛍️  PURCHASE",COLOR_GREEN)
        val payments=styledButton("💳  PAYMENTS",COLOR_GOLD,COLOR_INK)
        val returns=styledButton("↩️  RETURNS",COLOR_RED)
        val settings=styledButton("⚙️  SETTINGS",Color.parseColor("#555555"))
        listOf(pos,products,customers,suppliers,reports,expense,purchase,payments,returns,settings).forEach(root::addView)
        setContentView(root)
        pos.setOnClickListener{showPos()}
        products.setOnClickListener{showProducts()}
        customers.setOnClickListener{showCustomers()}
        suppliers.setOnClickListener{showSuppliers()}
        reports.setOnClickListener{showReports()}
        expense.setOnClickListener{showExpense()}
        purchase.setOnClickListener{showPurchase()}
        payments.setOnClickListener{showPayments()}
        returns.setOnClickListener{showReturns()}
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
        val list=ListView(this)
        listAdapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,mutableListOf())
        list.adapter=listAdapter
        totalView=TextView(this).apply{text="Total: 0 PKR";textSize=20f;setTextColor(COLOR_INK);setPadding(0,20,0,20)}
        root.addView(code);root.addView(qty);root.addView(add)
        root.addView(hold);root.addView(recall);root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(totalView);root.addView(save);root.addView(back)
        setContentView(root)
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
                db.heldDao().all().collect{listItems->
                    if(listItems.isNotEmpty()){
                        val h=listItems.first()
                        cart.clear()
                        h.payload.split(";").forEach{part->
                            val x=part.split(","); if(x.size==2){
                                val p=db.productDao().find(x[0]); val q=x[1].toIntOrNull()?:1
                                if(p!=null) cart.add(CartLine(p,q))
                            }
                        }
                        db.heldDao().delete(h);refreshCart()
                    }
                    return@collect
                }
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
        val save=styledButton("SAVE PRODUCT",COLOR_GREEN)
        val back=styledButton("DASHBOARD",COLOR_RED)
        listOf(bc,name,cat,cost,price,stock,reorder,expiry,save,back).forEach(root::addView)
        setContentView(root)
        save.setOnClickListener{
            lifecycleScope.launch{
                db.productDao().upsert(Product(bc.text.toString(),name.text.toString(),cat.text.toString(),
                    cost.text.toString().toDoubleOrNull()?:0.0,price.text.toString().toDoubleOrNull()?:0.0,
                    stock.text.toString().toIntOrNull()?:0,reorder.text.toString().toIntOrNull()?:0,expiry.text.toString()))
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
        setContentView(root)
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
        setContentView(root)
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
        listOf(cat,desc,amt,save,back).forEach(root::addView);setContentView(root)
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
        val save=styledButton("RECEIVE STOCK",COLOR_GREEN)
        val back=styledButton("BACK",COLOR_RED)
        listOf(barcode,qty,cost,save,back).forEach(root::addView);setContentView(root)
        save.setOnClickListener{lifecycleScope.launch{
            val code=barcode.text.toString(); val q=qty.text.toString().toIntOrNull()?:0
            val p=db.productDao().find(code)
            if(p==null){toast("Product not found");return@launch}
            db.productDao().increase(code,q)
            db.auditDao().insert(Audit(username="local",action="PURCHASE_STOCK_IN",reference=code,details="Qty=$q Cost=${cost.text}"))
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
        listOf(ref,party,amount,method,save,back).forEach(root::addView);setContentView(root)
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
        listOf(type,ref,barcode,qty,amount,save,back).forEach(root::addView);setContentView(root)
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
            root.addView(back);setContentView(root);back.setOnClickListener{showDashboard()}
        }
    }

    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
