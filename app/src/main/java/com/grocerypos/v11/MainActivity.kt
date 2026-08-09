package com.grocerypos.v11

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

    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        db=PosDatabase.get(this)
        showDashboard()
    }

    private fun base(title:String):LinearLayout{
        return LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(20,20,20,20)
            addView(TextView(this@MainActivity).apply{
                text=title; textSize=24f; setPadding(0,0,0,18)
            })
        }
    }

    private fun showDashboard(){
        val root=base("GROCERY POS V11")
        val pos=Button(this).apply{text="🛒 POS / NEW BILL"}
        val products=Button(this).apply{text="📦 PRODUCTS & STOCK"}
        val customers=Button(this).apply{text="👤 CUSTOMERS / UDHAR"}
        val suppliers=Button(this).apply{text="🏢 SUPPLIERS"}
        val reports=Button(this).apply{text="📊 REPORTS"}
        val expense=Button(this).apply{text="💵 EXPENSE"}
        val purchase=Button(this).apply{text="🛒 PURCHASE"}
        val payments=Button(this).apply{text="💳 PAYMENTS"}
        val returns=Button(this).apply{text="↩️ RETURNS"}
        val settings=Button(this).apply{text="⚙️ SETTINGS"}
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
        val root=base("POS / NEW BILL")
        val code=EditText(this).apply{hint="Scan / enter barcode";singleLine=true}
        val qty=EditText(this).apply{hint="Quantity";setText("1");inputType=2}
        val add=Button(this).apply{text="ADD"}
        val hold=Button(this).apply{text="HOLD BILL"}
        val recall=Button(this).apply{text="RECALL BILL"}
        val save=Button(this).apply{text="SAVE BILL"}
        val back=Button(this).apply{text="DASHBOARD"}
        val list=ListView(this)
        listAdapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,mutableListOf())
        list.adapter=listAdapter
        totalView=TextView(this).apply{text="Total: 0 PKR";textSize=20f}
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
        val root=base("PRODUCTS & STOCK")
        val bc=EditText(this).apply{hint="Barcode"}
        val name=EditText(this).apply{hint="Product name"}
        val cat=EditText(this).apply{hint="Category"}
        val cost=EditText(this).apply{hint="Purchase cost";inputType=2}
        val price=EditText(this).apply{hint="Sale price";inputType=2}
        val stock=EditText(this).apply{hint="Opening stock";inputType=2}
        val reorder=EditText(this).apply{hint="Reorder level";inputType=2}
        val expiry=EditText(this).apply{hint="Expiry YYYY-MM-DD"}
        val save=Button(this).apply{text="SAVE PRODUCT"}
        val back=Button(this).apply{text="DASHBOARD"}
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
        val root=base("CUSTOMERS / UDHAR")
        val name=EditText(this).apply{hint="Customer name"}
        val phone=EditText(this).apply{hint="Phone"}
        val limit=EditText(this).apply{hint="Credit limit";inputType=2}
        val save=Button(this).apply{text="SAVE CUSTOMER"}
        val back=Button(this).apply{text="BACK"}
        listOf(name,phone,limit,save,back).forEach(root::addView)
        setContentView(root)
        save.setOnClickListener{lifecycleScope.launch{
            db.customerDao().insert(Customer(name=name.text.toString(),phone=phone.text.toString(),creditLimit=limit.text.toString().toDoubleOrNull()?:0.0))
            toast("Customer saved")
        }}
        back.setOnClickListener{showDashboard()}
    }

    private fun showSuppliers(){
        val root=base("SUPPLIERS / PAYABLES")
        val name=EditText(this).apply{hint="Supplier name"}
        val phone=EditText(this).apply{hint="Phone"}
        val save=Button(this).apply{text="SAVE SUPPLIER"}
        val back=Button(this).apply{text="BACK"}
        listOf(name,phone,save,back).forEach(root::addView)
        setContentView(root)
        save.setOnClickListener{lifecycleScope.launch{
            db.supplierDao().insert(Supplier(name=name.text.toString(),phone=phone.text.toString()))
            toast("Supplier saved")
        }}
        back.setOnClickListener{showDashboard()}
    }

    private fun showExpense(){
        val root=base("EXPENSE")
        val cat=EditText(this).apply{hint="Category"}
        val desc=EditText(this).apply{hint="Description"}
        val amt=EditText(this).apply{hint="Amount";inputType=2}
        val save=Button(this).apply{text="SAVE EXPENSE"}
        val back=Button(this).apply{text="BACK"}
        listOf(cat,desc,amt,save,back).forEach(root::addView);setContentView(root)
        save.setOnClickListener{lifecycleScope.launch{
            db.expenseDao().insert(Expense(category=cat.text.toString(),description=desc.text.toString(),amount=amt.text.toString().toDoubleOrNull()?:0.0))
            toast("Expense saved")
        }}
        back.setOnClickListener{showDashboard()}
    }


    private fun showPurchase(){
        val root=base("PURCHASE / STOCK IN")
        val barcode=EditText(this).apply{hint="Barcode"}
        val qty=EditText(this).apply{hint="Quantity";inputType=2}
        val cost=EditText(this).apply{hint="Unit cost";inputType=2}
        val save=Button(this).apply{text="RECEIVE STOCK"}
        val back=Button(this).apply{text="BACK"}
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
        val root=base("PAYMENTS")
        val ref=EditText(this).apply{hint="Reference"}
        val party=EditText(this).apply{hint="Party type: Customer/Supplier"}
        val amount=EditText(this).apply{hint="Amount";inputType=2}
        val method=EditText(this).apply{hint="Cash/Card/JazzCash/Easypaisa"}
        val save=Button(this).apply{text="SAVE PAYMENT"}
        val back=Button(this).apply{text="BACK"}
        listOf(ref,party,amount,method,save,back).forEach(root::addView);setContentView(root)
        save.setOnClickListener{lifecycleScope.launch{
            db.paymentDao().insert(Payment(reference=ref.text.toString(),partyType=party.text.toString(),
                partyId=null,amount=amount.text.toString().toDoubleOrNull()?:0.0,method=method.text.toString()))
            toast("Payment saved")
        }}
        back.setOnClickListener{showDashboard()}
    }

    private fun showReturns(){
        val root=base("RETURNS")
        val type=EditText(this).apply{hint="Sale Return / Purchase Return"}
        val ref=EditText(this).apply{hint="Invoice / Bill reference"}
        val barcode=EditText(this).apply{hint="Barcode"}
        val qty=EditText(this).apply{hint="Quantity";inputType=2}
        val amount=EditText(this).apply{hint="Amount";inputType=2}
        val save=Button(this).apply{text="SAVE RETURN"}
        val back=Button(this).apply{text="BACK"}
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
            val root=base("REPORTS")
            root.addView(TextView(this@MainActivity).apply{
                text="Total Sales: $sales PKR\nExpenses: $expenses PKR\nGross sales available for P&L: $sales PKR"
                textSize=19f
            })
            val back=Button(this@MainActivity).apply{text="BACK"}
            root.addView(back);setContentView(root);back.setOnClickListener{showDashboard()}
        }
    }

    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
